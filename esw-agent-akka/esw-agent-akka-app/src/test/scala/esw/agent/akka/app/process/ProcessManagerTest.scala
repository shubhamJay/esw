package esw.agent.akka.app.process

import java.net.URI

import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.{ActorSystem, SpawnProtocol}
import csw.location.api.models.ComponentType.SequenceComponent
import csw.location.api.models.Connection.AkkaConnection
import csw.location.api.models.{AkkaLocation, ComponentId, Metadata}
import csw.location.api.scaladsl.LocationService
import csw.logging.api.scaladsl.Logger
import csw.prefix.models.Prefix
import esw.agent.akka.app.AgentSettings
import esw.agent.akka.client.AgentCommand.SpawnCommand.SpawnSequenceComponent
import esw.agent.service.api.models.{Failed, SpawnResponse}
import esw.testcommons.BaseTestSuite

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

class ProcessManagerTest extends BaseTestSuite {

  private implicit val actorSystem: ActorSystem[SpawnProtocol.Command] = ActorSystem(SpawnProtocol(), "process-manager")
  private implicit val logger: Logger                                  = mock[Logger]

  private val compName   = randomString(10)
  private val compPrefix = Prefix(randomSubsystem, compName)
  private val compId     = ComponentId(compPrefix, SequenceComponent)
  private val connection = AkkaConnection(compId)
  private val uri        = new URI("some")

  private val processExecutor = mock[ProcessExecutor]
  private val locationService = mock[LocationService]
  private val agentSetting    = mock[AgentSettings]

  override protected def afterAll(): Unit = {
    actorSystem.terminate()
    actorSystem.whenTerminated.futureValue
  }

  "spawn" must {
    "return failed response when component is already registered | ESW-237, ESW-367" in {
      val probe                  = TestProbe[SpawnResponse]()
      val spawnSequenceComponent = SpawnSequenceComponent(probe.ref, Prefix("ESW.primary"), "darknight", None)
      val connection             = spawnSequenceComponent.connection
      val location               = AkkaLocation(connection, uri, Metadata.empty)

      when(locationService.resolve(connection, 0.seconds)).thenReturn(Future.successful(Some(location)))
      val manager = new ProcessManager(locationService, processExecutor, agentSetting)
      manager.spawn(spawnSequenceComponent).futureValue should ===(
        Left(s"Component ${connection.componentId.fullName} is already registered with location service at location $location")
      )
      verify(locationService).resolve(connection, 0.seconds)
    }
  }

  "kill" must {
    "return failed response when location does not contain pid | ESW-367" in {
      val location = AkkaLocation(connection, uri, Metadata.empty)
      val manager  = new ProcessManager(locationService, processExecutor, agentSetting)
      manager.kill(location).futureValue should ===(Failed(s"$location metadata does not contain Pid"))
    }

    "return failed response when pid does not exist on agent machine | ESW-276, ESW-367" in {
      val pid      = 12345
      val location = AkkaLocation(connection, uri, Metadata().withPid(pid))
      val manager = new ProcessManager(locationService, processExecutor, agentSetting) {
        override def processHandle(pid: Long): Option[ProcessHandle] = None
      }
      manager.kill(location).futureValue should ===(Failed(s"Pid:$pid process does not exist"))
    }

    "return failed response when creating processHandle from pid throws an exception | ESW-367" in {
      val pid      = 12345
      val location = AkkaLocation(connection, uri, Metadata().withPid(pid))
      val manager = new ProcessManager(locationService, processExecutor, agentSetting) {
        override def processHandle(pid: Long): Option[ProcessHandle] = throw new SecurityException("Permission denied")
      }
      manager.kill(location).futureValue should ===(Failed("Permission denied"))
    }

    "return failed response when process.kill throws an exception | ESW-367" in {
      val pid      = 12345
      val process  = mock[ProcessHandle]
      val location = AkkaLocation(connection, uri, Metadata().withPid(pid))

      val manager = new ProcessManager(locationService, processExecutor, agentSetting) {
        override def processHandle(pid: Long): Option[ProcessHandle] = Some(process)
      }

      when(process.descendants()).thenThrow(new SecurityException("Permission denied"))
      manager.kill(location).futureValue should ===(
        Failed("Failed to kill component process, reason: Permission denied")
      )
    }
  }

}
