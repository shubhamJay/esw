package esw.gateway.server.routes

import akka.http.scaladsl.marshalling.sse.EventStreamMarshalling._
import akka.http.scaladsl.server.Directives.{entity, _}
import akka.http.scaladsl.server.Route
import csw.event.api.scaladsl.SubscriptionModes.RateLimiterMode
import csw.event.api.scaladsl.{EventPublisher, EventSubscriber}
import csw.params.core.formats.JsonSupport
import csw.params.core.models.Subsystem
import csw.params.events.{Event, EventKey}
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport
import esw.gateway.server.routes.RichSourceExt.RichSource
import esw.template.http.server.csw.utils.CswContext

import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.language.postfixOps

class EventRoutes(cswCtx: CswContext) extends JsonSupport with PlayJsonSupport {
  import cswCtx._

  lazy val subscriber: EventSubscriber = eventService.defaultSubscriber
  lazy val publisher: EventPublisher   = eventService.defaultPublisher

  val route: Route = {

    pathPrefix("event") {
      pathEnd {
        post {
          entity(as[Event]) { event =>
            complete(publisher.publish(event))
          }
        } ~
        get {
          parameter("key".as[String].*) { keys =>
            validate(keys.nonEmpty, "Request is missing query parameter key") {
              val eventualEvents = subscriber.get(keys.toEventKeys)
              complete(eventualEvents)
            }
          }
        }
      } ~
      pathPrefix("subscribe") {
        get {
          pathEnd {
            parameters(("key".as[String].*, "max-frequency".as[Int])) { (keys, frequency) =>
              validate(keys.nonEmpty, "Request is missing query parameter key") {
                complete(
                  subscriber
                    .subscribe(keys.toEventKeys, maxFrequencyToDuration(frequency), RateLimiterMode)
                    .toSSE(settings.sseHeartbeatDuration)
                )
              }
            }
          } ~
          path(Segment) { subsystem =>
            val sub = Subsystem.withNameInsensitive(subsystem)
            parameters(("max-frequency".as[Int], "pattern" ?)) { (frequency, pattern) =>
              val events = pattern match {
                case Some(p) => subscriber.pSubscribe(sub, p)
                case None    => subscriber.pSubscribe(sub, "*")
              }

              complete(
                events
                  .via(eventSubscriberUtil.subscriptionModeStage(maxFrequencyToDuration(frequency), RateLimiterMode))
                  .toSSE(settings.sseHeartbeatDuration)
              )
            }
          }
        }
      }
    }
  }

  private def maxFrequencyToDuration(frequency: Int): FiniteDuration = (1000 / frequency).millis

  implicit class RichEventKeys(keys: Iterable[String]) {
    def toEventKeys: Set[EventKey] = keys.map(EventKey(_)).toSet
  }
}
