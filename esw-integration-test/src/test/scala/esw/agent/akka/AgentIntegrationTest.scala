package esw.agent.akka

import java.nio.file.Paths

import csw.location.api.codec.LocationServiceCodecs
import csw.location.api.models.ComponentType.{Machine, SequenceComponent, Service}
import csw.location.api.models.Connection.AkkaConnection
import csw.location.api.models.{AkkaLocation, ComponentId}
import csw.prefix.models.Prefix
import csw.prefix.models.Subsystem.{ESW, IRIS}
import esw.agent.akka.app.AgentSettings
import esw.agent.akka.app.process.cs.Coursier
import esw.agent.akka.client.AgentClient
import esw.agent.service.api.models.{Killed, Spawned}
import esw.commons.utils.location.LocationServiceUtil
import esw.ocs.api.actor.client.SequenceComponentImpl
import esw.ocs.api.models.ObsMode
import esw.ocs.api.protocol.SequenceComponentResponse.SequencerLocation
import esw.ocs.testkit.EswTestKit
import esw.ocs.testkit.Service.AAS
import esw.{BinaryFetcherUtil, GitUtil}

import scala.concurrent.duration.DurationLong

class AgentIntegrationTest extends EswTestKit(AAS) with LocationServiceCodecs {

  private val irisPrefix               = Prefix("esw.iris")
  private val irisSeqCompConnection    = AkkaConnection(ComponentId(irisPrefix, SequenceComponent))
  private val appVersion               = GitUtil.latestCommitSHA("esw")
  private val agentPrefix: Prefix      = Prefix(ESW, "machine_A1")
  private var agentClient: AgentClient = _
  private val locationServiceUtil      = new LocationServiceUtil(locationService)

  private val eswVersion: Some[String] = Some(appVersion)

  override def beforeAll(): Unit = {
    super.beforeAll()
    val channel: String = "file://" + getClass.getResource("/apps.json").getPath
    spawnAgent(AgentSettings(agentPrefix, 1.minute, channel))
    BinaryFetcherUtil.fetchBinaryFor(channel, Coursier.ocsApp(eswVersion), eswVersion)
    BinaryFetcherUtil.fetchBinaryFor(channel, Coursier.smApp(eswVersion), eswVersion)
    agentClient = AgentClient.make(agentPrefix, locationServiceUtil).rightValue
  }

  override implicit def patienceConfig: PatienceConfig = PatienceConfig(1.minute, 100.millis)

  //ESW-325: spawns sequence component via agent using coursier with provided sha
  private def spawnSequenceComponent(componentName: String) = agentClient.spawnSequenceComponent(componentName, eswVersion)

  "Agent" must {
    "start and register itself with location service | ESW-237" in {
      val agentLocation = locationService.resolve(AkkaConnection(ComponentId(agentPrefix, Machine)), 5.seconds).futureValue
      agentLocation should not be empty
    }

    "return Spawned on SpawnSequenceComponent and Killed on KillComponent message |  ESW-153, ESW-237, ESW-276, ESW-325, ESW-366, ESW-367" in {
      val darknight = ObsMode("darknight")
      spawnSequenceComponent(irisPrefix.componentName).futureValue should ===(Spawned)
      // Verify registration in location service
      val seqCompLoc = locationService.resolve(irisSeqCompConnection, 5.seconds).futureValue.value
      seqCompLoc.connection shouldBe irisSeqCompConnection

      // ESW-366 verify agent prefix and pid metadata is present in Sequence component akka location
      seqCompLoc.metadata.getAgentPrefix.value should ===(agentPrefix)
      seqCompLoc.metadata.value.contains("PID") shouldBe true

      // start sequencer i.e. load IRIS darknight script
      val seqCompApi         = new SequenceComponentImpl(seqCompLoc)
      val loadScriptResponse = seqCompApi.loadScript(IRIS, darknight).futureValue

      // verify sequencer location from load script and looked up from location service is the same
      loadScriptResponse shouldBe SequencerLocation(resolveSequencerLocation(IRIS, darknight))

      agentClient.killComponent(seqCompLoc).futureValue should ===(Killed)
      // Verify not registered in location service
      locationService.resolve(irisSeqCompConnection, 5.seconds).futureValue shouldEqual None
    }

    "return Spawned on SpawnSequenceManager | ESW-180, ESW-366, ESW-367" in {
      val obsModeConfigPath = Paths.get(ClassLoader.getSystemResource("smObsModeConfig.conf").toURI)
      // spawn sequence manager
      agentClient.spawnSequenceManager(obsModeConfigPath, isConfigLocal = true, eswVersion).futureValue should ===(Spawned)

      // Verify registration in location service
      val seqManagerConnection   = AkkaConnection(ComponentId(Prefix(ESW, "sequence_manager"), Service))
      val location: AkkaLocation = locationService.resolve(seqManagerConnection, 5.seconds).futureValue.value

      // ESW-366 verify agent prefix and pid metadata is present in Sequence component akka location
      location.metadata.getAgentPrefix.get should ===(agentPrefix)
//      location.metadata.value.contains("PID") shouldBe true

      agentClient.killComponent(location).futureValue
    }
  }

  override def afterAll(): Unit = {
    locationService.unregisterAll()
    super.afterAll()
  }
}
