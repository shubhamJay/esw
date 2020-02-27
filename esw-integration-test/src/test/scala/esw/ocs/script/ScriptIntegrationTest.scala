package esw.ocs.script

import csw.prefix.models.Subsystem.ESW
import esw.ocs.api.SequencerApi
import esw.ocs.impl.SequencerActorProxy
import esw.ocs.testkit.EswTestKit

class ScriptIntegrationTest extends EswTestKit() {

  // TestScript.kt
  private val ocsSubsystem               = ESW
  private val ocsObservingMode           = "darknight"
  private var ocsSequencer: SequencerApi = _

  override def beforeEach(): Unit = {
    val ocsSequencerRef = spawnSequencerRef(ocsSubsystem, ocsObservingMode)
    ocsSequencer = new SequencerActorProxy(ocsSequencerRef)
  }

  override def afterEach(): Unit = shutdownAllSequencers()

  "Sequencer Script" must {
    "be able to send sequence to other Sequencer by resolving location through TestScript | ESW-88, ESW-145, ESW-190, ESW-195, ESW-119, ESW-251, CSW-81" in {

      ocsSequencer.getSequence

      Thread.sleep(10000)

      // response received by irisSequencer
    }
  }
}
