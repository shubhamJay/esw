package esw.ocs.app.wiring

import akka.Done
import akka.actor.typed.SpawnProtocol.Spawn
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.{ActorRef, ActorSystem, Props, SpawnProtocol}
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import com.typesafe.config.Config
import csw.aas.http.SecurityDirectives
import csw.alarm.api.javadsl.IAlarmService
import csw.command.client.messages.sequencer.SequencerMsg
import csw.event.client.internal.commons.javawrappers.JEventService
import csw.location.api.AkkaRegistrationFactory
import csw.location.api.javadsl.ILocationService
import csw.location.api.models.Connection.AkkaConnection
import csw.location.api.models._
import csw.location.client.ActorSystemFactory
import csw.location.client.javadsl.JHttpLocationServiceFactory
import csw.logging.api.javadsl.ILogger
import csw.logging.api.scaladsl.Logger
import csw.logging.client.scaladsl.LoggerFactory
import csw.network.utils.SocketUtils
import csw.prefix.models.{Prefix, Subsystem}
import esw.commons.Timeouts
import esw.commons.extensions.FutureEitherExt.{FutureEitherJavaOps, FutureEitherOps}
import esw.commons.utils.location.LocationServiceUtil
import esw.http.core.wiring.{ActorRuntime, CswWiring, HttpService, Settings}
import esw.ocs.api.actor.client.{SequencerApiFactory, SequencerImpl}
import esw.ocs.api.actor.messages.SequencerMessages.Shutdown
import esw.ocs.api.codecs.SequencerServiceCodecs
import esw.ocs.api.models.ObsMode
import esw.ocs.api.protocol.ScriptError
import esw.ocs.api.protocol.ScriptError.{LoadingScriptFailed, LocationServiceError}
import esw.ocs.handler.{SequencerPostHandler, SequencerWebsocketHandler}
import esw.ocs.impl.blockhound.BlockHoundWiring
import esw.ocs.impl.core._
import esw.ocs.impl.internal._
import esw.ocs.impl.script.{ScriptApi, ScriptContext, ScriptLoader}
import msocket.impl.RouteFactory
import msocket.impl.post.PostRouteFactory
import msocket.impl.ws.WebsocketRouteFactory

import scala.async.Async.{async, await}
import scala.concurrent.{Await, Future}
import scala.util.control.NonFatal

// $COVERAGE-OFF$
private[ocs] class SequencerWiring(
    val subsystem: Subsystem,
    val obsMode: ObsMode,
    sequenceComponentPrefix: Prefix
) extends SequencerServiceCodecs {
  lazy val actorSystem: ActorSystem[SpawnProtocol.Command] = ActorSystemFactory.remote(SpawnProtocol(), "sequencer-system")

  private[ocs] lazy val config: Config  = actorSystem.settings.config
  private[ocs] lazy val sequencerConfig = SequencerConfig.from(config, subsystem, obsMode)
  import sequencerConfig._

  implicit lazy val timeout: Timeout = Timeouts.DefaultTimeout
  lazy val cswWiring: CswWiring      = new CswWiring(actorSystem)
  import cswWiring._
  import cswWiring.actorRuntime._

  implicit lazy val actorRuntime: ActorRuntime = cswWiring.actorRuntime

  lazy val sequencerRef: ActorRef[SequencerMsg] = Await.result(
    actorSystem ? { x: ActorRef[ActorRef[SequencerMsg]] =>
      Spawn(sequencerBehavior.setup, prefix.toString, Props.empty, x)
    },
    Timeouts.DefaultTimeout
  )

  //Pass lambda to break circular dependency shown below.
  //SequencerRef -> Script -> cswServices -> SequencerOperator -> SequencerRef
  private lazy val sequenceOperatorFactory = () => new SequenceOperator(sequencerRef)
  private lazy val componentId             = ComponentId(prefix, ComponentType.Sequencer)
  private[ocs] lazy val script: ScriptApi  = ScriptLoader.loadKotlinScript(scriptClass, scriptContext)

  private lazy val locationServiceUtil        = new LocationServiceUtil(locationService)
  lazy val jLocationService: ILocationService = JHttpLocationServiceFactory.makeLocalClient(actorSystem)

  lazy val jEventService: JEventService         = new JEventService(eventService)
  private lazy val jAlarmService: IAlarmService = alarmServiceFactory.jMakeClientApi(jLocationService, actorSystem)

  private lazy val loggerFactory    = new LoggerFactory(prefix)
  private lazy val logger: Logger   = loggerFactory.getLogger
  private lazy val jLoggerFactory   = loggerFactory.asJava
  private lazy val jLogger: ILogger = ScriptLoader.withScript(scriptClass)(jLoggerFactory.getLogger)

  private lazy val sequencerImplFactory = (_subsystem: Subsystem, _obsMode: ObsMode) => //todo: revisit timeout value
    locationServiceUtil
      .resolveSequencer(_subsystem, _obsMode.name, Timeouts.DefaultTimeout)
      .mapRight(SequencerApiFactory.make)
      .toJava

  lazy val scriptContext = new ScriptContext(
    heartbeatInterval,
    prefix,
    jLogger,
    sequenceOperatorFactory,
    actorSystem,
    jEventService,
    jAlarmService,
    sequencerImplFactory,
    config
  )

  private lazy val sequencerApi            = new SequencerImpl(sequencerRef)
  private lazy val securityDirectives      = SecurityDirectives.authDisabled(config)
  private lazy val postHandler             = new SequencerPostHandler(sequencerApi, securityDirectives)
  private lazy val websocketHandlerFactory = new SequencerWebsocketHandler(sequencerApi)

  lazy val routes: Route = RouteFactory.combine(metricsEnabled = false)(
    new PostRouteFactory("post-endpoint", postHandler),
    new WebsocketRouteFactory("websocket-endpoint", websocketHandlerFactory)
  )

  private lazy val settings          = new Settings(Some(SocketUtils.getFreePort), Some(prefix), config, ComponentType.Sequencer)
  private lazy val httpService       = new HttpService(logger, locationService, routes, settings, actorRuntime)
  private lazy val httpServerBinding = httpService.startAndRegisterServer()

  private val shutdownHttpService: () => Future[Done] = () =>
    async {
      logger.debug("Shutting down Sequencer http service")
      val (serverBinding, registrationResult) = await(httpServerBinding)
      val eventualTerminated                  = serverBinding.terminate(Timeouts.DefaultTimeout)
      val eventualDone                        = registrationResult.unregister()
      await(eventualTerminated.flatMap(_ => eventualDone))
    }

  lazy val sequencerBehavior =
    new SequencerBehavior(componentId, script, locationService, sequenceComponentPrefix, logger, shutdownHttpService)(
      actorSystem
    )

  lazy val sequencerServer: SequencerServer = new SequencerServer {
    override def start(): Either[ScriptError, AkkaLocation] = {
      try {
        logger.info(s"Starting sequencer for subsystem: $subsystem with observing mode: ${obsMode.name}")
        new Engine(script).start(sequenceOperatorFactory())

        Await.result(httpServerBinding, Timeouts.DefaultTimeout)

        val registration = AkkaRegistrationFactory.make(AkkaConnection(componentId), sequencerRef)
        val loc = Await.result(
          locationServiceUtil.register(registration).mapLeft(e => LocationServiceError(e.msg)),
          Timeouts.DefaultTimeout
        )

        logger.info(s"Successfully started Sequencer for subsystem: $subsystem with observing mode: ${obsMode.name}")
        if (enableThreadMonitoring) {
          logger.info(s"Thread Monitoring enabled for ${BlockHoundWiring.integrations}")
          BlockHoundWiring.install()
        }
        loc
      }
      catch {
        // This error will be logged in SequenceComponent.Do not log it here,
        // because exception caused while initialising will fail the instance creation of logger.
        case NonFatal(e) => Left(LoadingScriptFailed(e.getMessage))
      }
    }

    override def shutDown(): Done = {
      Await.result(sequencerRef ? Shutdown, Timeouts.DefaultTimeout)
      actorSystem.terminate()
      Await.result(actorSystem.whenTerminated, Timeouts.DefaultTimeout)
    }
  }
}
// $COVERAGE-ON$
