package esw.gateway.server

import akka.Done
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import csw.alarm.api.exceptions.KeyNotFoundException
import csw.alarm.models.AlarmSeverity
import csw.alarm.models.Key.AlarmKey
import csw.command.api.messages.CommandServiceHttpMessage.{Oneway, Submit, Validate}
import csw.event.api.exceptions.{EventServerNotAvailable, PublishFailure}
import csw.location.models.ComponentId
import csw.location.models.ComponentType.{Assembly, Sequencer}
import csw.logging.macros.SourceFactory
import csw.logging.models.{AnyId, Level}
import csw.params.commands.CommandIssue.IdNotAvailableIssue
import csw.params.commands.CommandResponse.{Accepted, Invalid, Started}
import csw.params.commands.{CommandName, CommandResponse, Sequence, Setup}
import csw.params.core.models.{Id, ObsId, Prefix, Subsystem}
import csw.params.events.{Event, EventKey, EventName, SystemEvent}
import esw.gateway.api.codecs.GatewayCodecs
import esw.gateway.api.protocol.PostRequest._
import esw.gateway.api.protocol._
import esw.gateway.api.{AlarmApi, EventApi, LoggingApi}
import esw.gateway.impl._
import esw.gateway.server.handlers.PostHandlerImpl
import esw.http.core.BaseTestSuite
import esw.ocs.api.protocol.{Ok, OkOrUnhandledResponse, SequencerPostRequest}
import msocket.api.models.ServiceError
import msocket.impl.Encoding
import msocket.impl.Encoding.JsonText
import msocket.impl.post.{ClientHttpCodecs, PostRouteFactory}
import org.mockito.ArgumentMatchers.{any, eq => argsEq}
import org.mockito.Mockito.when
import org.mockito.MockitoSugar._

import scala.concurrent.Future

class PostRouteTest extends BaseTestSuite with ScalatestRouteTest with GatewayCodecs with ClientHttpCodecs {

  override def encoding: Encoding[_] = JsonText

  private val cswCtxMocks = new CswWiringMocks()
  import cswCtxMocks._

  private val alarmApi: AlarmApi     = new AlarmImpl(alarmService)
  private val eventApi: EventApi     = new EventImpl(eventService, eventSubscriberUtil)
  private val loggingApi: LoggingApi = new LoggingImpl(loggerCache)
  private val postHandlerImpl        = new PostHandlerImpl(alarmApi, resolver, eventApi, loggingApi)
  private val route                  = new PostRouteFactory("post-endpoint", postHandlerImpl).make()
  private val source                 = Prefix("esw.test")
  private val destination            = Prefix("tcs.test")

  "Submit Command" must {
    "handle submit command and return started command response | ESW-91, ESW-216" in {
      val runId         = Id("123")
      val componentType = Assembly
      val command       = Setup(source, CommandName("c1"), Some(ObsId("obsId")))
      val componentId   = ComponentId(destination, componentType)
      val submitRequest = ComponentCommand(componentId, Submit(command))

      when(resolver.resolveComponent(componentId)).thenReturn(Future.successful(commandService))
      when(commandService.submit(command)).thenReturn(Future.successful(Started(runId)))

      Post("/post-endpoint", submitRequest) ~> route ~> check {
        responseAs[CommandResponse] shouldEqual Started(runId)
      }
    }

    "handle validate command and return accepted command response | ESW-91, ESW-216" in {
      val runId           = Id("123")
      val componentType   = Assembly
      val command         = Setup(source, CommandName("c1"), Some(ObsId("obsId")))
      val componentId     = ComponentId(destination, componentType)
      val validateRequest = ComponentCommand(componentId, Validate(command))

      when(resolver.resolveComponent(componentId)).thenReturn(Future.successful(commandService))
      when(commandService.validate(command)).thenReturn(Future.successful(Accepted(runId)))

      Post("/post-endpoint", validateRequest) ~> route ~> check {
        responseAs[CommandResponse] shouldEqual Accepted(runId)
      }
    }

    "handle oneway command and return accepted command response | ESW-91, ESW-216" in {
      val runId         = Id("123")
      val componentType = Assembly
      val command       = Setup(source, CommandName("c1"), Some(ObsId("obsId")))
      val componentId   = ComponentId(destination, componentType)
      val onewayRequest = ComponentCommand(componentId, Oneway(command))

      when(resolver.resolveComponent(componentId)).thenReturn(Future.successful(commandService))
      when(commandService.oneway(command)).thenReturn(Future.successful(Accepted(runId)))

      Post("/post-endpoint", onewayRequest) ~> route ~> check {
        responseAs[CommandResponse] shouldEqual Accepted(runId)
      }
    }

    "return InvalidComponent response for invalid component id | ESW-91, ESW-216" in {
      val componentType = Assembly
      val command       = Setup(source, CommandName("c1"), Some(ObsId("obsId")))
      val componentId   = ComponentId(destination, componentType)
      val submitRequest = ComponentCommand(componentId, Submit(command))

      val message = "component does not exist"
      when(resolver.resolveComponent(componentId)).thenReturn(Future.failed(InvalidComponent(message)))

      Post("/post-endpoint", submitRequest) ~> route ~> check {
        status shouldEqual StatusCodes.InternalServerError
        responseAs[InvalidComponent] shouldEqual InvalidComponent(message)
      }
    }
  }

  "SequencerRoutes" must {
    "handle submit command and return started command response | ESW-250" in {
      val sequence       = Sequence(Setup(source, CommandName("c1"), Some(ObsId("obsId"))))
      val componentId    = ComponentId(destination, Sequencer)
      val submitRequest  = SequencerCommand(componentId, SequencerPostRequest.Submit(sequence))
      val submitResponse = Started(Id("123"))

      when(resolver.resolveSequencer(componentId)).thenReturn(Future.successful(sequencer))
      when(sequencer.submit(sequence)).thenReturn(Future.successful(submitResponse))

      Post("/post-endpoint", submitRequest) ~> route ~> check {
        responseAs[CommandResponse] shouldEqual submitResponse
      }
    }

    "handle query command and return query response | ESW-250" in {
      val runId         = Id("runId")
      val componentId   = ComponentId(destination, Sequencer)
      val queryRequest  = SequencerCommand(componentId, SequencerPostRequest.Query(runId))
      val queryResponse = Invalid(runId, IdNotAvailableIssue(s"Sequencer is not running any sequence with runId $runId"))

      when(resolver.resolveSequencer(componentId)).thenReturn(Future.successful(sequencer))
      when(sequencer.query(runId)).thenReturn(Future.successful(queryResponse))

      Post("/post-endpoint", queryRequest) ~> route ~> check {
        responseAs[CommandResponse] shouldEqual queryResponse
      }
    }

    "handle go online command and return Ok response | ESW-250" in {
      val componentId     = ComponentId(destination, Sequencer)
      val goOnlineRequest = SequencerCommand(componentId, SequencerPostRequest.GoOnline)

      when(resolver.resolveSequencer(componentId)).thenReturn(Future.successful(sequencer))
      when(sequencer.goOnline()).thenReturn(Future.successful(Ok))

      Post("/post-endpoint", goOnlineRequest) ~> route ~> check {
        responseAs[OkOrUnhandledResponse] shouldEqual Ok
      }
    }
  }

  "Publish Event" must {
    "return Done on successful publish | ESW-92, ESW-216" in {
      val prefix       = Prefix("tcs.test.gateway")
      val name         = EventName("event1")
      val event        = SystemEvent(prefix, name, Set.empty)
      val publishEvent = PublishEvent(event)

      when(eventPublisher.publish(event)).thenReturn(Future.successful(Done))

      Post("/post-endpoint", publishEvent) ~> route ~> check {
        responseAs[Done] shouldEqual Done
      }
    }

    "return EventServerUnavailable error when EventServer is down | ESW-92, ESW-216" in {
      val prefix       = Prefix("tcs.test.gateway")
      val name         = EventName("event1")
      val event        = SystemEvent(prefix, name, Set.empty)
      val publishEvent = PublishEvent(event)

      when(eventPublisher.publish(event))
        .thenReturn(Future.failed(PublishFailure(event, new RuntimeException("Event server is down"))))

      Post("/post-endpoint", publishEvent) ~> route ~> check {
        status shouldEqual StatusCodes.InternalServerError
        responseAs[EventServerUnavailable] shouldEqual EventServerUnavailable()
      }
    }
  }

  "Get Event" must {
    "return an event successfully | ESW-94, ESW-216" in {
      val prefix   = Prefix("tcs.test.gateway")
      val name     = EventName("event1")
      val event    = SystemEvent(prefix, name, Set.empty)
      val eventKey = EventKey(prefix, name)
      val getEvent = GetEvent(Set(eventKey))

      when(eventSubscriber.get(Set(eventKey))).thenReturn(Future.successful(Set(event)))

      Post("/post-endpoint", getEvent) ~> route ~> check {
        responseAs[Set[Event]] shouldEqual Set(event)
      }
    }

    "return EmptyEventKeys error on sending no event keys in request | ESW-94, ESW-216" in {
      Post("/post-endpoint", GetEvent(Set())) ~> route ~> check {
        status shouldEqual StatusCodes.InternalServerError
        responseAs[EmptyEventKeys] shouldEqual EmptyEventKeys()
      }
    }

    "return EventServerUnavailable error when EventServer is down | ESW-94, ESW-216" in {
      val prefix   = Prefix("tcs.test.gateway")
      val name     = EventName("event1")
      val eventKey = EventKey(prefix, name)
      val getEvent = GetEvent(Set(eventKey))

      when(eventSubscriber.get(Set(eventKey)))
        .thenReturn(Future.failed(EventServerNotAvailable(new RuntimeException("Redis server is not available"))))

      Post("/post-endpoint", getEvent) ~> route ~> check {
        status shouldEqual StatusCodes.InternalServerError
        responseAs[EventServerUnavailable] shouldEqual EventServerUnavailable()
      }
    }

    "handle exceptions if get event fails for some unwanted reason | ESW-94, ESW-216" in {
      when(eventSubscriber.get(any[Set[EventKey]])).thenReturn(Future.failed(new RuntimeException("failed")))

      val eventKey = EventKey(Prefix("tcs.test.gateway"), EventName("event1"))

      Post("/post-endpoint", GetEvent(Set(eventKey))) ~> route ~> check {
        status shouldEqual StatusCodes.InternalServerError
        responseAs[ServiceError] shouldEqual ServiceError.fromThrowable(new RuntimeException("failed"))
      }
    }
  }

  "Set Alarm Severity" must {
    "returns Done on success | ESW-193, ESW-216, ESW-233" in {
      val componentName    = "testComponent"
      val alarmName        = "testAlarmName"
      val subsystemName    = Subsystem.IRIS
      val majorSeverity    = AlarmSeverity.Major
      val alarmKey         = AlarmKey(subsystemName, componentName, alarmName)
      val setAlarmSeverity = SetAlarmSeverity(alarmKey, majorSeverity)

      when(alarmService.setSeverity(alarmKey, majorSeverity)).thenReturn(Future.successful(Done))

      Post("/post-endpoint", setAlarmSeverity) ~> route ~> check {
        responseAs[Done] shouldEqual Done
      }
    }

    "returns SetAlarmSeverityFailure on key not found or invalid key | ESW-193, ESW-216, ESW-233" in {
      val componentName    = "testComponent"
      val alarmName        = "testAlarmName"
      val subsystemName    = Subsystem.IRIS
      val majorSeverity    = AlarmSeverity.Major
      val alarmKey         = AlarmKey(subsystemName, componentName, alarmName)
      val setAlarmSeverity = SetAlarmSeverity(alarmKey, majorSeverity)

      when(alarmService.setSeverity(alarmKey, majorSeverity)).thenReturn(Future.failed(new KeyNotFoundException("")))

      Post("/post-endpoint", setAlarmSeverity) ~> route ~> check {
        status shouldEqual StatusCodes.InternalServerError
        responseAs[SetAlarmSeverityFailure] shouldEqual SetAlarmSeverityFailure("")
      }
    }
  }

  "Log" must {
    "log the message, metadata and return Done | ESW-200" in {
      val log = Log(
        "esw-test",
        Level.FATAL,
        "test-message",
        Map(
          "additional-info" -> 45,
          "city"            -> "LA"
        )
      )

      Post("/post-endpoint", log) ~> route ~> check {
        responseAs[Done] shouldEqual Done
        val expectedMetadata = Map(
          "additional-info" -> 45,
          "city"            -> "LA"
        )
        verify(logger).fatal(argsEq("test-message"), argsEq(expectedMetadata), any[Throwable], any[AnyId])(
          any[SourceFactory]
        )
      }
    }

    "log the message and return Done | ESW-200" in {
      val log = Log(
        "esw-test",
        Level.FATAL,
        "test-message"
      )

      Post("/post-endpoint", log) ~> route ~> check {
        responseAs[Done] shouldEqual Done
        verify(logger).fatal(argsEq("test-message"), argsEq(Map.empty), any[Throwable], any[AnyId])(
          any[SourceFactory]
        )
      }
    }
  }

}
