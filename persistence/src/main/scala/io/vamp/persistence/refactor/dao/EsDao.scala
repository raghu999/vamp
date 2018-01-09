package io.vamp.persistence.refactor.dao

import com.sksamuel.elastic4s.ElasticDsl._
import com.sksamuel.elastic4s.{ElasticsearchClientUri, IndexAndType, TcpClient}
import io.circe._
import io.circe.parser._
import io.circe.syntax._
import io.vamp.common.{Config, Id, Namespace}
import io.vamp.persistence.refactor.api._
import io.vamp.persistence.refactor.exceptions.{DuplicateObjectIdException, InvalidFormatException, InvalidObjectIdException, VampPersistenceModificationException}
import io.vamp.persistence.refactor.serialization.SerializationSpecifier
import org.elasticsearch.common.settings.Settings

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}

/**
 * Created by mihai on 11/10/17.
 */
class EsDao(val namespace: Namespace, elasticSearchHostAndPort: String, elasticSearchClusterName: String, testingContext: Boolean = false) extends SimpleArtifactPersistenceDao {
  implicit val ns: Namespace = namespace
  private[persistence] val indexName = s"vamp_${namespace.name}"

  lazy val esClient: TcpClient = {
    val esClientUri: ElasticsearchClientUri = ElasticsearchClientUri(elasticSearchHostAndPort)
    val settings: Settings = Settings.builder()
      .put("cluster.name", elasticSearchClusterName)
      .build()
    val client: TcpClient = TcpClient.transport(settings, esClientUri)
    Await.result(
      (for {
        indexExists ← client.execute(indexExists(indexName))
        _ ← if (indexExists.isExists) {
          if (testingContext) {
            (client.execute(deleteIndex(indexName))).flatMap(_ ⇒ client.execute(createIndex(indexName)))
          }
          else Future.successful(())
        }
        else {
          // Create the index
          client.execute(createIndex(indexName))
        }
      } yield ()), 10.second
    )
    client
  }

  override def create[T: SerializationSpecifier](obj: T): Future[Id[T]] = {
    val sSpecifier = implicitly[SerializationSpecifier[T]]
    val newObjectId = sSpecifier.idExtractor(obj)
    implicit val jsonEncoder = sSpecifier.encoder
    for {
      _ ← read(newObjectId).flatMap(_ ⇒ Future.failed(DuplicateObjectIdException(newObjectId))).recover {
        case e: InvalidObjectIdException[_] ⇒ ()
      }
      _ ← esClient.execute {
        (indexInto(indexName, sSpecifier.typeName) doc (obj.asJson.noSpaces) id (newObjectId)).copy(createOnly = Some(true))
      }
    } yield newObjectId
  }

  override def createOrUpdate[T: SerializationSpecifier](obj: T): Future[Id[T]] = {
    val sSpecifier = implicitly[SerializationSpecifier[T]]
    for {
      existing ← readIfAvailable(sSpecifier.idExtractor(obj))
      _ ← existing match {
        case None    ⇒ create(obj)
        case Some(_) ⇒ update(sSpecifier.idExtractor(obj), (_: T) ⇒ obj)
      }
    } yield sSpecifier.idExtractor(obj)
  }

  override def read[T: SerializationSpecifier](objectId: Id[T]): Future[T] = {
    val sSpecifier = implicitly[SerializationSpecifier[T]]
    for {
      getResponse ← esClient.execute {
        get(objectId.toString) from (indexName, sSpecifier.typeName)
      }
    } yield {
      if (!getResponse.exists || getResponse.isSourceEmpty) throw new InvalidObjectIdException[T](objectId)
      else interpretAsObject(getResponse.sourceAsString)
    }
  }

  private def readVersioned[T: SerializationSpecifier](objectId: Id[T]): Future[Versioned[T]] = {
    val sSpecifier = implicitly[SerializationSpecifier[T]]
    for {
      getResponse ← esClient.execute {
        get(objectId.toString) from (indexName, sSpecifier.typeName)
      }
    } yield {
      if (!getResponse.exists || getResponse.isSourceEmpty) throw new InvalidObjectIdException[T](objectId)
      else Versioned(interpretAsObject(getResponse.sourceAsString), getResponse.version)
    }
  }

  override def update[T: SerializationSpecifier](id: Id[T], updateFunction: T ⇒ T): Future[Unit] = {
    val sSpecifier = implicitly[SerializationSpecifier[T]]
    implicit val jsonEncoder = sSpecifier.encoder

    for {
      currentObject ← readVersioned(id)
      updatedObject = updateFunction(currentObject.obj)
      _ ← if (sSpecifier.idExtractor(updatedObject) != id) Future.failed(VampPersistenceModificationException(s"Changing id to ${sSpecifier.idExtractor(updatedObject)}", id))
      else Future.successful(())
      _ ← esClient.execute {
        (indexInto(indexName, sSpecifier.typeName) doc (updatedObject.asJson.noSpaces) id (sSpecifier.idExtractor(updatedObject)) version currentObject.version)
      }
    } yield ()
  }

  override def deleteObject[T: SerializationSpecifier](objectId: Id[T]): Future[Unit] = {
    val sSpecifier = implicitly[SerializationSpecifier[T]]
    for {
      _ ← read(objectId) // Ensure the object exists
      _ ← esClient.execute {
        delete(objectId.value) from (IndexAndType(indexName, sSpecifier.typeName))
      }
    } yield ()
  }

  def getAll[T: SerializationSpecifier](fromAndSize: Option[(Int, Int)] = None): Future[SearchResponse[T]] = {
    implicit val s = implicitly[SerializationSpecifier[T]]
    for {
      numberOfObjects ← esClient.execute(search(indexName) types (s.typeName) size 0)
      allObjects ← esClient.execute(search(indexName) types (s.typeName) from (
        fromAndSize match {
          case None         ⇒ 0
          case Some((f, _)) ⇒ f
        }) size (
          fromAndSize match {
            case None         ⇒ numberOfObjects.totalHits.toInt
            case Some((_, t)) ⇒ t
          }))
    } yield {
      val responseHits = allObjects.original.getHits().getHits()
      val responseList = responseHits.map(s ⇒ interpretAsObject(s.getSourceAsString)).toList
      SearchResponse(responseList, from = fromAndSize.map(_._1).getOrElse(0), size = fromAndSize.map(_._2).getOrElse(responseList.size), total = allObjects.totalHits.toInt)
    }
  }

  private def interpretAsObject[T](stringToRead: String)(implicit serializationSpecifier: SerializationSpecifier[T]): T = {
    implicit val decoder: Decoder[T] = serializationSpecifier.decoder
    decode[T](stringToRead) match {
      case Right(s) ⇒ s
      case Left(e)  ⇒ throw InvalidFormatException(objectAsString = stringToRead, originalException = e)
    }
  }

  override def info: Option[PersistenceInfo] = Try(esClient) match {
    case Success(_) => Some(PersistenceInfo(s"ElasticSearch: ${elasticSearchHostAndPort}", PersistenceDatabase(`type` = "elasticsearch", connection = elasticSearchHostAndPort)))
    case Failure(_) => None
  }

  private[persistence] def afterTestCleanup: Unit = Await.result(esClient.execute(deleteIndex(indexName)), 10.second)

  override def readIfAvailable[T: SerializationSpecifier](id: Id[T]): Future[Option[T]] = {
    read(id).map(x ⇒ Some(x)).recover {
      case e: InvalidObjectIdException[_] ⇒ None
    }
  }

  def init(): Future[Unit] = {
    for {
      _ <- Future.fromTry(Try(esClient))
      _ <- esClient.execute(indexExists("inexistent_index")).map(_ ⇒ ())
    } yield ()

  }

  case class Versioned[T](obj: T, version: Long)

  private def replaceSecialIdChars(id: String): String = id.replace('/', '_')
}

class EsDaoFactory extends SimpleArtifactPersistenceDaoFactory {
  def get(namespace: Namespace) = {
    implicit val ns: Namespace = namespace
    val elasticSearchHostAndPort = Config.string("vamp.persistence.database.elasticsearch.elasticsearch-url")()
    val elasticSearchClusterName = Config.string("vamp.persistence.database.elasticsearch.elasticsearch-cluster-name")()
    val testingContext = Config.boolean("vamp.persistence.database.elasticsearch.elasticsearch-test-cluster")()
    new EsDao(ns, elasticSearchHostAndPort, elasticSearchClusterName, testingContext)
  }
}