package io.vamp.http_api

import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import io.vamp.common.Namespace
import io.vamp.common.akka._
import io.vamp.common.http.HttpApiDirectives
import io.vamp.operation.controller.SchedulerController

object SchedulerRoute {
  val path: String = "scheduler"
}

trait SchedulerRoute extends AbstractRoute with SchedulerController with ExecutionContextProvider {
  this: HttpApiDirectives ⇒

  def schedulerRoutes(implicit namespace: Namespace, timeout: Timeout): Route = get {
    pathPrefix(SchedulerRoute.path) {
      path("nodes") {
        pageAndPerPage() { (page, perPage) ⇒
          onSuccess(nodes(page, perPage)) {
            result ⇒ respondWith(OK, result)
          }
        }
      } ~ path("routing") {
        pageAndPerPage() { (page, perPage) ⇒
          parameters('selector.?) { selector ⇒
            onSuccess(routing(selector)(page, perPage)) {
              result ⇒ respondWith(OK, result)
            }
          }
        }
      }
    }
  }
}
