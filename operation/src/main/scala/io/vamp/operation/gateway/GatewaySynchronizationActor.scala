package io.vamp.operation.gateway

import akka.actor.Actor
import akka.pattern.ask
import akka.util.Timeout
import io.vamp.common.{ Config, ConfigMagnet, Namespace }
import io.vamp.common.akka._
import io.vamp.container_driver.ContainerDriverActor
import io.vamp.container_driver.ContainerDriverActor.DeployedGateways
import io.vamp.gateway_driver.GatewayDriverActor
import io.vamp.gateway_driver.GatewayDriverActor.{ Pull, Push }
import io.vamp.model.artifact._
import io.vamp.model.event.Event
import io.vamp.operation.gateway.GatewaySynchronizationActor.SynchronizeAll
import io.vamp.operation.notification._
import io.vamp.persistence.{ ArtifactPaginationSupport, ArtifactSupport, PersistenceActor }
import io.vamp.pulse.PulseActor
import io.vamp.pulse.PulseActor.Publish

import scala.util.{ Failure, Success }

class GatewaySynchronizationSchedulerActor extends SchedulerActor with OperationNotificationProvider {

  def tick(): Unit = IoC.actorFor[GatewaySynchronizationActor] ! SynchronizeAll
}

object GatewaySynchronizationActor {

  val timeout: ConfigMagnet[Timeout] = Config.timeout("vamp.operation.gateway.response-timeout")

  def portRangeLower()(implicit namespace: Namespace): Int = {
    val portRange = Config.string("vamp.operation.gateway.port-range")().split("-").map(_.toInt)
    portRange(0)
  }

  def portRangeUpper()(implicit namespace: Namespace): Int = {
    val portRange = Config.string("vamp.operation.gateway.port-range")().split("-").map(_.toInt)
    portRange(1)
  }

  sealed trait GatewayMessages

  object SynchronizeAll extends GatewayMessages

  case class Synchronize(gateways: List[Gateway], deployments: List[Deployment], marshalled: List[Gateway]) extends GatewayMessages

}

private case class GatewayPipeline(deployable: List[Gateway], nonDeployable: List[Gateway]) {
  val all: List[Gateway] = deployable ++ nonDeployable
}

class GatewaySynchronizationActor extends CommonSupportForActors with ArtifactSupport with ArtifactPaginationSupport with OperationNotificationProvider {

  import PersistenceActor._
  import GatewaySynchronizationActor._

  private var currentPort = portRangeLower - 1

  def receive: Actor.Receive = {
    case SynchronizeAll ⇒ synchronize()
    case s: Synchronize ⇒ synchronize(s.gateways, s.deployments, s.marshalled)
    case _              ⇒
  }

  private def synchronize(): Unit = {
    val sendTo = self
    implicit val timeout: Timeout = PersistenceActor.timeout()
    (for {
      gateways ← consume(allArtifacts[Gateway])
      deployments ← consume(allArtifacts[Deployment])
      marshalled ← checked[List[Gateway]](IoC.actorFor[GatewayDriverActor] ? Pull)
    } yield (gateways, deployments, marshalled)) onComplete {
      case Success((gateways, deployments, marshalled)) ⇒ sendTo ! Synchronize(gateways, deployments, marshalled)
      case Failure(error)                               ⇒ reportException(InternalServerError(error))
    }
  }

  private def synchronize(gateways: List[Gateway], deployments: List[Deployment], marshalled: List[Gateway]): Unit = {
    (portAssignment(deployments) andThen instanceUpdate(deployments) andThen select(marshalled) andThen flush)(gateways)
  }

  private def portAssignment(deployments: List[Deployment]): List[Gateway] ⇒ GatewayPipeline = { gateways ⇒
    val used = gateways.map(_.port.number).toSet

    def availablePort = {
      currentPort += 1
      while (used.contains(currentPort)) currentPort += 1
      if (currentPort > portRangeUpper)
        throwException(NoAvailablePortError(portRangeLower, portRangeUpper))
      currentPort
    }

    val (noPortGateways, otherGateways) = gateways.partition { gateway ⇒ !gateway.port.assigned }

    noPortGateways foreach { gateway ⇒
      if (gateway.deployed) IoC.actorFor[PersistenceActor] ! UpdateGatewayDeploymentStatus(gateway, deployed = false)
      IoC.actorFor[PersistenceActor] ! CreateGatewayPort(gateway, availablePort)
    }

    GatewayPipeline(otherGateways, noPortGateways)
  }

  private def instanceUpdate(deployments: List[Deployment]): GatewayPipeline ⇒ GatewayPipeline = { pipeline ⇒

    val (passThrough, withoutRoutes) = pipeline.deployable.map { gateway ⇒
      val routes = gateway.routes.map {
        case route: DefaultRoute ⇒
          val routeTargets = targets(pipeline.deployable, deployments, route)
          val targetMatch = routeTargets == route.targets
          if (!targetMatch) IoC.actorFor[PersistenceActor] ! UpdateGatewayRouteTargets(gateway, route, routeTargets)
          route.copy(targets = routeTargets)
        case route ⇒ route
      }

      gateway.copy(routes = routes)

    } partition { gateway ⇒
      gateway.routes.exists {
        case route: DefaultRoute if route.targets.nonEmpty ⇒ targets(pipeline.deployable, deployments, route) == route.targets
        case _ ⇒ false
      } || !gateway.internal
    }

    passThrough filter (!_.deployed) foreach { gateway ⇒ IoC.actorFor[PersistenceActor] ! UpdateGatewayDeploymentStatus(gateway, deployed = true) }

    withoutRoutes filter (_.deployed) foreach { gateway ⇒ IoC.actorFor[PersistenceActor] ! UpdateGatewayDeploymentStatus(gateway, deployed = false) }

    GatewayPipeline(passThrough, pipeline.nonDeployable ++ withoutRoutes)
  }

  private def targets(gateways: List[Gateway], deployments: List[Deployment], route: DefaultRoute): List[RouteTarget] = {
    route.path.external match {
      case Some(external) ⇒ ExternalRouteTarget(external, Map()) :: Nil
      case _ ⇒

        val targets = route.path.segments match {

          case reference :: Nil ⇒
            gateways.find {
              _.name == reference
            }.flatMap { gw ⇒
              Option {
                InternalRouteTarget(reference, gw.port.number)
              }
            } :: Nil

          case deployment :: _ :: Nil ⇒
            gateways.find { gateway ⇒
              gateway.name == route.path.normalized && GatewayPath(gateway.name).segments.head == deployment
            }.flatMap { gateway ⇒
              Option {
                InternalRouteTarget(route.path.normalized, gateway.port.number)
              }
            } :: Nil

          case deployment :: cluster :: port :: Nil ⇒
            deployments.find {
              _.name == deployment
            }.flatMap {
              _.clusters.find(_.name == cluster)
            }.flatMap {
              _.portBy(port)
            }.flatMap { port ⇒
              if (port != 0) Option(InternalRouteTarget(route.path.normalized, port)) else None
            } :: Nil

          case deployment :: cluster :: service :: port :: Nil ⇒
            deployments.find {
              _.name == deployment
            }.flatMap {
              _.clusters.find(_.name == cluster)
            }.flatMap {
              _.services.find(_.breed.name == service)
            }.map { service ⇒
              service.instances.map {
                instance ⇒
                  {
                    if (!instance.ports.contains(port))
                      log.error(s"$port does not exist in instance: ${instance.name} host: ${instance.host} ports: ${instance.ports}")
                    Option {
                      InternalRouteTarget(instance.name, Option(instance.host), instance.ports(port))
                    }
                  }
              }
            }.getOrElse(Nil)

          case _ ⇒ None :: Nil
        }

        if (targets.exists(_.isEmpty)) Nil else targets.flatten
    }
  }

  private def select(marshalled: List[Gateway]): GatewayPipeline ⇒ List[Gateway] = { pipeline ⇒

    val selected = pipeline.deployable

    val currentAsMap = marshalled.map(g ⇒ g.name → g).toMap
    val selectedAsMap = selected.map(g ⇒ g.name → g).toMap

    currentAsMap.keySet.diff(selectedAsMap.keySet).foreach(name ⇒ sendEvent(currentAsMap(name), "undeployed"))
    selectedAsMap.keySet.diff(currentAsMap.keySet).foreach(name ⇒ sendEvent(selectedAsMap(name), "deployed"))

    selected
  }

  private def flush: List[Gateway] ⇒ Unit = { gateways ⇒

    val sorted = gateways sortWith { (gateway1, gateway2) ⇒
      val len1 = GatewayPath(gateway1.name).segments.size
      val len2 = GatewayPath(gateway2.name).segments.size
      if (len1 == len2) gateway1.name.compareTo(gateway2.name) < 0
      else len1 < len2
    }

    IoC.actorFor[GatewayDriverActor] ! Push(sorted)
    IoC.actorFor[ContainerDriverActor] ! DeployedGateways(sorted)
  }

  private def sendEvent(gateway: Gateway, event: String): Unit = {
    log.info(s"Gateway event: ${gateway.name} - $event")
    val tags = Set(s"gateways${Event.tagDelimiter}${gateway.name}", event)
    IoC.actorFor[PulseActor] ! Publish(Event(tags, gateway))
  }
}
