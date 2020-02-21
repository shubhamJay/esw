package esw.ocs.script

import csw.logging.client.scaladsl.LoggingSystemFactory
import csw.params.commands.{CommandName, Sequence, Setup}
import csw.prefix.models.Prefix
import csw.prefix.models.Subsystem.ESW
import esw.ocs.api.SequencerApi
import esw.ocs.impl.SequencerActorProxy
import esw.ocs.testkit.EswTestKit
import esw.ocs.testkit.Service._
import scala.concurrent.duration.DurationInt

import scala.concurrent.Await

class ScriptIntegrationTest extends EswTestKit(EventServer, AlarmServer, ConfigServer) {

  // TestScript.kt
  private val ocsSubsystem               = ESW
  private val ocsObservingMode           = "darknight"
  private var ocsSequencer: SequencerApi = _

  override def beforeAll(): Unit = {
    super.beforeAll()
  }

  override def beforeEach(): Unit = {
    val ocsSequencerRef = spawnSequencerRef(ocsSubsystem, ocsObservingMode)
    ocsSequencer = new SequencerActorProxy(ocsSequencerRef)
  }

  override def afterEach(): Unit = shutdownAllSequencers()

  "Sequencer Script" must {
    "be able to send sequence to other Sequencer by resolving location through TestScript | ESW-88, ESW-145, ESW-190, ESW-195, ESW-119, ESW-251, CSW-81" in {
      val command1 = Setup(Prefix("esw.test"), CommandName("non-blocking-command"), None)
      val command2 = Setup(Prefix("esw.test"), CommandName("blocking-command"), None)
      val sequence = Sequence(Seq(command1, command2))

      Await.result(ocsSequencer.submitAndWait(sequence), 10.seconds)
      println("sleeping")
      Thread.sleep(100000)
    }
  }
}
