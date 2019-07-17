package esw.template.http.server.commons

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.{Directives, ExceptionHandler}
import csw.commons.http.{JsonRejectionHandler, JsonSupport}
import csw.logging.api.scaladsl.Logger

import scala.concurrent.TimeoutException
import scala.util.control.NonFatal

class RouteHandlers(log: Logger) extends Directives with JsonRejectionHandler {

  val commonExceptionHandlers: ExceptionHandler = ExceptionHandler {
    case ex: TimeoutException =>
      log.error(ex.getMessage, ex = ex)
      complete(JsonSupport.asJsonResponse(StatusCodes.GatewayTimeout, ex.getMessage))
    case NonFatal(ex) =>
      log.error(ex.getMessage, ex = ex)
      complete(JsonSupport.asJsonResponse(StatusCodes.InternalServerError, ex.getMessage))
  }
}
