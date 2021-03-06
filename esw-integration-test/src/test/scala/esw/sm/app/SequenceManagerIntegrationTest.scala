package esw.sm.app

import java.io.File
import java.nio.file.{Files, Path}

import csw.config.api.scaladsl.ConfigService
import csw.config.api.{ConfigData, TokenFactory}
import csw.config.client.scaladsl.ConfigClientFactory
import csw.location.api.models.ComponentType.{Machine, SequenceComponent, Sequencer, Service}
import csw.location.api.models.Connection.AkkaConnection
import csw.location.api.models.{AkkaLocation, ComponentId, ComponentType, Metadata}
import csw.prefix.models.Prefix
import csw.prefix.models.Subsystem._
import csw.testkit.ConfigTestKit
import esw.agent.akka.app.AgentSettings
import esw.agent.akka.app.process.cs.Coursier
import esw.agent.service.api.AgentServiceApi
import esw.agent.service.api.client.AgentServiceClientFactory
import esw.agent.service.api.models.Spawned
import esw.agent.service.app.AgentServiceWiring
import esw.ocs.api.actor.client.{SequenceComponentImpl, SequencerImpl}
import esw.ocs.api.models.ObsMode
import esw.ocs.api.protocol.SequenceComponentResponse.GetStatusResponse
import esw.ocs.testkit.EswTestKit
import esw.ocs.testkit.Service.AAS
import esw.sm.api.models.{AgentStatus, SequenceComponentStatus}
import esw.sm.api.models.ProvisionConfig
import esw.sm.api.protocol.CommonFailure.{ConfigurationMissing, LocationServiceError}
import esw.sm.api.protocol.ConfigureResponse.ConflictingResourcesWithRunningObsMode
import esw.sm.api.protocol.StartSequencerResponse.{LoadScriptError, SequenceComponentNotAvailable}
import esw.sm.api.protocol._
import esw.sm.app.TestSetup.obsModeConfigPath
import esw.{BinaryFetcherUtil, GitUtil}
import msocket.impl.HttpError

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class SequenceManagerIntegrationTest extends EswTestKit(AAS) {
  private val WFOS_CAL                     = ObsMode("WFOS_Cal")
  private val IRIS_CAL                     = ObsMode("IRIS_Cal")
  private val IRIS_DARKNIGHT               = ObsMode("IRIS_Darknight")
  private val sequenceManagerPrefix        = Prefix(ESW, "sequence_manager")
  private val configTestKit: ConfigTestKit = frameworkTestKit.configTestKit
  private val ocsAppVersion                = GitUtil.latestCommitSHA("esw")
  private val testCsChannel: String        = BinaryFetcherUtil.eswChannel(ocsAppVersion)

  private var agentService: AgentServiceApi          = _
  private var agentServiceWiring: AgentServiceWiring = _
  private val ocsVersionOpt                          = Some(ocsAppVersion)

  override def beforeAll(): Unit = {
    super.beforeAll()
    BinaryFetcherUtil.fetchBinaryFor(testCsChannel, Coursier.ocsApp(ocsVersionOpt))
    agentServiceWiring = new AgentServiceWiring(Some(4449))
    agentServiceWiring.start().futureValue
    val httpLocation = resolveHTTPLocation(agentServiceWiring.prefix, ComponentType.Service)
    agentService = AgentServiceClientFactory(httpLocation, () => tokenWithEswUserRole())
  }

  override protected def beforeEach(): Unit = {
    locationService.unregisterAll().futureValue
    registerKeycloak()
  }

  override protected def afterEach(): Unit = {
    TestSetup.cleanup()
    super.afterEach()
  }

  override def afterAll(): Unit = {
    agentServiceWiring.stop().futureValue
    super.afterAll()
  }

  override implicit def patienceConfig: PatienceConfig = PatienceConfig(1.minute, 100.millis)

  "start sequence manager and register akka + http locations| ESW-171, ESW-172, ESW-173, ESW-366, ESW-332" in {
    val agentPrefix      = Prefix(ESW, "agent1")
    val expectedMetadata = Metadata().withAgentPrefix(agentPrefix).withPid(ProcessHandle.current().pid())

    // resolving sequence manager fails for Akka and Http
    intercept[Exception](resolveAkkaLocation(sequenceManagerPrefix, Service))
    intercept[Exception](resolveHTTPLocation(sequenceManagerPrefix, Service))

    // ESW-173 Start sequence manager using command line arguments without any other ESW dependency
    SequenceManagerApp.main(Array("start", "-o", obsModeConfigPath.toString, "--local", "-a", agentPrefix.toString()))

    // verify sequence manager is started and AkkaLocation & HttpLocation are registered with location service
    val smAkkaLocation = resolveAkkaLocation(sequenceManagerPrefix, Service)
    smAkkaLocation.prefix shouldBe sequenceManagerPrefix

    // ESW-366 verify agent prefix and pid metadata is present in Sequence manager akka location
    smAkkaLocation.metadata should ===(expectedMetadata)

    val smHttpLocation = resolveHTTPLocation(sequenceManagerPrefix, Service)
    smHttpLocation.prefix shouldBe sequenceManagerPrefix

    // ESW-366 verify agent prefix and pid metadata is present in Sequence manager http location
    smHttpLocation.metadata should ===(expectedMetadata)

  }

  "configure SH, send sequence to master sequencer and cleanup for provided observation mode | ESW-162, ESW-164, ESW-166, ESW-171, ESW-178, ESW-351, ESW-366, ESW-332" in {
    val eswSeqCompPrefix   = Prefix(ESW, "primary")
    val irisSeqCompPrefix  = Prefix(IRIS, "primary")
    val aoeswSeqCompPrefix = Prefix(AOESW, "primary")

    TestSetup.startSequenceComponents(eswSeqCompPrefix, irisSeqCompPrefix, aoeswSeqCompPrefix)

    // ESW-171, ESW-332: Starts SM and returns SM Http client which had ESW-user role.
    val sequenceManagerClient = TestSetup.startSequenceManagerAuthEnabled(sequenceManagerPrefix, tokenWithEswUserRole)

    // ESW-366 verify SM Location metadata contains pid
    val smAkkaLocation = resolveAkkaLocation(sequenceManagerPrefix, Service)
    smAkkaLocation.metadata shouldBe Metadata().withPid(ProcessHandle.current().pid())

    val eswIrisCalPrefix   = Prefix(ESW, IRIS_CAL.name)
    val irisCalPrefix      = Prefix(IRIS, IRIS_CAL.name)
    val aoeswIrisCalPrefix = Prefix(AOESW, IRIS_CAL.name)

    // ************ Configure for observing mode ************************
    val configureResponse = sequenceManagerClient.configure(IRIS_CAL).futureValue

    // assert for Successful Configuration
    configureResponse shouldBe a[ConfigureResponse.Success]

    // ESW-162 verify configure response returns master sequencer ComponentId
    val masterSequencerLocation = resolveHTTPLocation(eswIrisCalPrefix, Sequencer)
    configureResponse should ===(ConfigureResponse.Success(masterSequencerLocation.connection.componentId))

    // ESW-162 (verify all appropriate Sequencers are started based on observing mode)
    resolveSequencerLocation(eswIrisCalPrefix).connection should ===(sequencerConnection(eswIrisCalPrefix))
    resolveSequencerLocation(irisCalPrefix).connection should ===(sequencerConnection(irisCalPrefix))
    resolveSequencerLocation(aoeswIrisCalPrefix).connection should ===(sequencerConnection(aoeswIrisCalPrefix))

    // ESW-164 assert that sequence components have loaded sequencer scripts
    assertThatSeqCompIsLoadedWithScript(eswSeqCompPrefix)
    assertThatSeqCompIsLoadedWithScript(irisSeqCompPrefix)
    assertThatSeqCompIsLoadedWithScript(aoeswSeqCompPrefix)

    // *************** Cleanup for observing mode - ESW-351 ********************
    val response = sequenceManagerClient.shutdownObsModeSequencers(IRIS_CAL).futureValue

    // assert for Successful Cleanup
    response should ===(ShutdownSequencersResponse.Success)

    // ESW-166 verify all sequencers are stopped for the observing mode and seq comps are available
    assertThatSeqCompIsAvailable(eswSeqCompPrefix)
    assertThatSeqCompIsAvailable(irisSeqCompPrefix)
    assertThatSeqCompIsAvailable(aoeswSeqCompPrefix)
  }

  "configure should run multiple obs modes in parallel if resources are not conflicting | ESW-168, ESW-169, ESW-170, ESW-171, ESW-179, ESW-178, ESW-332" in {
    TestSetup.startSequenceComponents(
      Prefix(ESW, "primary"),
      Prefix(ESW, "secondary"),
      Prefix(IRIS, "primary"),
      Prefix(AOESW, "primary"),
      Prefix(WFOS, "primary"),
      Prefix(TCS, "primary")
    )
    val sequenceManagerClient =
      TestSetup.startSequenceManagerAuthEnabled(sequenceManagerPrefix, tokenWithEswUserRole)

    // Configure for "IRIS_Cal" observing mode should be successful as the resources are available
    sequenceManagerClient.configure(IRIS_CAL).futureValue shouldBe a[ConfigureResponse.Success]

    // *************** Avoid conflicting sequence execution | ESW-169, ESW-179 ********************
    // Configure for "IRIS_Darknight" observing mode should return error because resource IRIS and NFIRAOS are busy
    sequenceManagerClient.configure(IRIS_DARKNIGHT).futureValue should ===(ConflictingResourcesWithRunningObsMode(Set(IRIS_CAL)))

    // *************** Should run observation concurrently if no conflict in resources | ESW-168, ESW-170 ********************
    // Configure for "WFOS_Cal" observing mode should be successful as the resources are available
    sequenceManagerClient.configure(WFOS_CAL).futureValue shouldBe a[ConfigureResponse.Success]

    // Test cleanup
    sequenceManagerClient.shutdownObsModeSequencers(IRIS_CAL).futureValue
    sequenceManagerClient.shutdownObsModeSequencers(WFOS_CAL).futureValue
  }

  "configure should read config file from remote config server | ESW-357, ESW-332" in {
    // start config server
    configTestKit.startConfigServer()

    val obsMode = ObsMode("APS_Cal")
    val factory = mock[TokenFactory]
    when(factory.getToken).thenReturn("valid")
    val adminApi: ConfigService = ConfigClientFactory.adminApi(configTestKit.actorSystem, locationService, factory)
    configTestKit.initSvnRepo()
    val configFilePath = Path.of("/tmt/test/smConfig.conf")
    val obsModeConfig: String =
      """
        esw-sm {
        |  obsModes: {
        |    APS_Cal: {
        |      resources: [ESW, TCS]
        |      sequencers: [ESW]
        |    }
        |  }
        |}
        |""".stripMargin
    // create obsMode config file on config server
    adminApi.create(configFilePath, ConfigData.fromString(obsModeConfig), annex = false, "First commit").futureValue

    TestSetup.startSequenceComponents(Prefix(ESW, "primary"))
    // Read SM config from remote config server
    val sequenceManagerClient =
      TestSetup.startSequenceManagerAuthEnabled(
        sequenceManagerPrefix,
        tokenWithEswUserRole,
        obsModeConfigPath = configFilePath,
        isConfigLocal = false
      )

    sequenceManagerClient.configure(obsMode).futureValue shouldBe a[ConfigureResponse.Success]

    // Test cleanup
    sequenceManagerClient.shutdownObsModeSequencers(obsMode).futureValue
    configTestKit.deleteServerFiles()
    configTestKit.terminateServer()
  }

  "Use ESW sequence components as fallback for other subsystems and give error if enough components are not available| ESW-164, ESW-171, ESW-340, ESW-332" in {
    val sequenceManagerClient = TestSetup.startSequenceManagerAuthEnabled(sequenceManagerPrefix, tokenWithEswUserRole)

    // Start only 2 sequence components
    TestSetup.startSequenceComponents(Prefix(ESW, "primary"), Prefix(IRIS, "primary"))

    // ESW-340: Configuring without sufficient number of sequence components
    sequenceManagerClient.configure(IRIS_CAL).futureValue shouldBe a[SequenceComponentNotAvailable]

    // Start one more ESW component for AOESW sequencer
    TestSetup.startSequenceComponents(Prefix(ESW, "secondary"))

    // ************ Configure for observing mode: sequencers required: [IRIS, ESW, AOESW] ************************
    sequenceManagerClient.configure(IRIS_CAL).futureValue shouldBe a[ConfigureResponse.Success]

    val aoeswSequencer          = resolveSequencer(AOESW, IRIS_CAL)
    val seqCompRunningSequencer = new SequencerImpl(aoeswSequencer).getSequenceComponent.futureValue

    // ESW-164 verify AOESW.IRIS_CAL sequencer is running on fallback ESW sequence component as AOESW sequence component
    // is not available
    seqCompRunningSequencer.prefix.subsystem shouldBe ESW

    //test cleanup
    sequenceManagerClient.shutdownObsModeSequencers(IRIS_CAL).futureValue
  }

  "throw exception if obs mode config file is missing | ESW-162, ESW-160, ESW-171, ESW-366, ESW-332" in {
    // ESW-366 verify that agentPrefix argument is parsed successfully
    val exception = intercept[RuntimeException](
      SequenceManagerApp.main(
        Array("start", "-o", "sm-config.conf", "--local", "-a", "ESW.agent1")
      )
    )
    exception.getMessage shouldBe "File does not exist on local disk at path sm-config.conf"
  }

  "start and shutdown sequencer for given subsystem and observation mode | ESW-176, ESW-326, ESW-171, ESW-167, ESW-351, ESW-332" in {
    TestSetup.startSequenceComponents(Prefix(ESW, "primary"), Prefix(ESW, "secondary"), Prefix(AOESW, "primary"))

    val sequenceManagerClient = TestSetup.startSequenceManagerAuthEnabled(sequenceManagerPrefix, tokenWithEswUserRole)

    // verify that sequencer is not present
    intercept[Exception](resolveHTTPLocation(Prefix(ESW, IRIS_DARKNIGHT.name), Sequencer))

    val response  = sequenceManagerClient.startSequencer(ESW, IRIS_DARKNIGHT).futureValue
    val response2 = sequenceManagerClient.startSequencer(IRIS, IRIS_CAL).futureValue
    val response3 = sequenceManagerClient.startSequencer(AOESW, IRIS_CAL).futureValue

    // ESW-176 Verify that start sequencer return Started response with component id for master sequencer
    response should ===(StartSequencerResponse.Started(ComponentId(Prefix(ESW, IRIS_DARKNIGHT.name), Sequencer)))
    response2 should ===(StartSequencerResponse.Started(ComponentId(Prefix(IRIS, IRIS_CAL.name), Sequencer)))
    response3 should ===(StartSequencerResponse.Started(ComponentId(Prefix(AOESW, IRIS_CAL.name), Sequencer)))

    // verify that sequencer is started
    resolveHTTPLocation(Prefix(ESW, IRIS_DARKNIGHT.name), Sequencer)
    resolveHTTPLocation(Prefix(IRIS, IRIS_CAL.name), Sequencer)
    resolveHTTPLocation(Prefix(AOESW, IRIS_CAL.name), Sequencer)

    // ESW-326, ESW-167 Verify that shutdown sequencer returns Success
    val shutdownResponse = sequenceManagerClient.shutdownSequencer(ESW, IRIS_DARKNIGHT).futureValue
    shutdownResponse should ===(ShutdownSequencersResponse.Success)

    // verify that sequencer is shut down
    intercept[Exception](resolveHTTPLocation(Prefix(ESW, IRIS_DARKNIGHT.name), Sequencer))
    resolveHTTPLocation(Prefix(IRIS, IRIS_CAL.name), Sequencer)
    resolveHTTPLocation(Prefix(AOESW, IRIS_CAL.name), Sequencer)

    // ESW-167: verify that sequence component is up
    resolveSequenceComponentLocation(Prefix(ESW, "primary"))
    resolveSequenceComponentLocation(Prefix(ESW, "secondary"))
    resolveSequenceComponentLocation(Prefix(AOESW, "primary"))

    // ESW-351
    val shutdownResponse2 = sequenceManagerClient.shutdownSequencer(AOESW, IRIS_CAL).futureValue
    shutdownResponse2 should ===(ShutdownSequencersResponse.Success)

    // verify that sequencer is shut down
    resolveHTTPLocation(Prefix(IRIS, IRIS_CAL.name), Sequencer)
    intercept[Exception](resolveHTTPLocation(Prefix(AOESW, IRIS_CAL.name), Sequencer))

    // ESW-167: verify that sequence component is up
    resolveSequenceComponentLocation(Prefix(ESW, "primary"))
    resolveSequenceComponentLocation(Prefix(ESW, "secondary"))
    resolveSequenceComponentLocation(Prefix(AOESW, "primary"))
  }

  "restart a running sequencer for given subsystem and obsMode | ESW-327, ESW-171, ESW-332" in {
    TestSetup.startSequenceComponents(Prefix(ESW, "primary"))
    val componentId = ComponentId(Prefix(ESW, IRIS_DARKNIGHT.name), Sequencer)

    val sequenceManagerClient = TestSetup.startSequenceManagerAuthEnabled(sequenceManagerPrefix, tokenWithEswUserRole)

    sequenceManagerClient.startSequencer(ESW, IRIS_DARKNIGHT)

    // verify that sequencer is started
    val initialLocation = resolveHTTPLocation(Prefix(ESW, IRIS_DARKNIGHT.name), Sequencer)

    // restart sequencer that is already running
    val secondRestartResponse = sequenceManagerClient.restartSequencer(ESW, IRIS_DARKNIGHT).futureValue
    // verify that restart sequencer return Success response with component id
    secondRestartResponse should ===(RestartSequencerResponse.Success(componentId))

    val restartedLocation = resolveHTTPLocation(Prefix(ESW, IRIS_DARKNIGHT.name), Sequencer)

    // restarted sequencer runs on a different port
    initialLocation should not equal restartedLocation
  }

  "restart a non-running sequencer for given subsystem and obsMode should return error | ESW-327, ESW-332" in {
    val componentId = ComponentId(Prefix(ESW, IRIS_DARKNIGHT.name), Sequencer)
    val connection  = AkkaConnection(componentId)

    val sequenceManagerClient = TestSetup.startSequenceManagerAuthEnabled(sequenceManagerPrefix, tokenWithEswUserRole)

    // restart sequencer that is not already running
    val secondRestartResponse = sequenceManagerClient.restartSequencer(ESW, IRIS_DARKNIGHT).futureValue
    // verify that restart sequencer return Error response with connection
    secondRestartResponse should ===(LocationServiceError(s"Could not find location matching connection: $connection"))
  }

  "shutdown running sequencers for given subsystem | ESW-345, ESW-351, ESW-332" in {
    val irisDarkNightPrefix = Prefix(ESW, IRIS_DARKNIGHT.name)
    val wfosCalPrefix       = Prefix(WFOS, WFOS_CAL.name)

    val darkNightSequencerL = spawnSequencer(ESW, IRIS_DARKNIGHT)
    val calSequencerL       = spawnSequencer(WFOS, WFOS_CAL)

    // verify all sequencers are started
    resolveAkkaLocation(irisDarkNightPrefix, Sequencer) should ===(darkNightSequencerL)
    resolveAkkaLocation(wfosCalPrefix, Sequencer) should ===(calSequencerL)

    //ESW-351 - shutdown all ESW sequencers that are running
    val sequenceManagerClient = TestSetup.startSequenceManagerAuthEnabled(sequenceManagerPrefix, tokenWithEswUserRole)
    sequenceManagerClient.shutdownSubsystemSequencers(ESW).futureValue should ===(ShutdownSequencersResponse.Success)

    // verify ESW sequencer has stopped
    intercept[Exception](resolveAkkaLocation(irisDarkNightPrefix, Sequencer))

    // verify WFOS sequencer is still running
    resolveAkkaLocation(wfosCalPrefix, Sequencer) should ===(calSequencerL)
  }

  "shutdown all the running sequencers | ESW-324, ESW-171, ESW-351, ESW-332" in {
    val irisDarkNightPrefix = Prefix(ESW, IRIS_DARKNIGHT.name)
    val irisCalPrefix       = Prefix(ESW, IRIS_CAL.name)

    val darkNightSequencerL = spawnSequencer(ESW, IRIS_DARKNIGHT)
    val calSequencerL       = spawnSequencer(ESW, IRIS_CAL)

    // verify Sequencers are started
    resolveAkkaLocation(irisDarkNightPrefix, Sequencer) should ===(darkNightSequencerL)
    resolveAkkaLocation(irisCalPrefix, Sequencer) should ===(calSequencerL)

    //ESW-351 - shutdown all the sequencers that are running
    val sequenceManagerClient = TestSetup.startSequenceManagerAuthEnabled(sequenceManagerPrefix, tokenWithEswUserRole)
    sequenceManagerClient.shutdownAllSequencers().futureValue should ===(ShutdownSequencersResponse.Success)

    // verify all sequencers has stopped
    intercept[Exception](resolveAkkaLocation(irisDarkNightPrefix, Sequencer))
    intercept[Exception](resolveAkkaLocation(irisCalPrefix, Sequencer))
  }

  "return loadScript error if configuration is missing for subsystem observation mode | ESW-176, ESW-171" in {
    TestSetup.startSequenceComponents(Prefix(ESW, "primary"))

    val sequenceManagerClient = TestSetup.startSequenceManager(sequenceManagerPrefix)

    // verify that sequencer is not present
    intercept[Exception](resolveHTTPLocation(Prefix(ESW, "invalid_obs_mode"), Sequencer))

    val response: StartSequencerResponse = sequenceManagerClient.startSequencer(ESW, ObsMode("invalid_obs_mode")).futureValue

    response shouldBe a[LoadScriptError]
    val loadScriptError: LoadScriptError = response.asInstanceOf[LoadScriptError]
    loadScriptError.msg should ===("Script configuration missing for [ESW] with [invalid_obs_mode]")
  }

  "support all observation modes in configuration file | ESW-160, ESW-332" in {
    val tmpPath = File.createTempFile("temp-config", ".conf").toPath
    File.createTempFile("temp-config", ".conf").deleteOnExit()
    Files.write(tmpPath, "esw-sm {\n  obsModes: {}}".getBytes)

    TestSetup.startSequenceComponents(Prefix(ESW, "primary"))
    val obsMode = ObsMode("APS_Cal")

    // try to configure obsMode which is not present in script
    val sequenceManagerClient = TestSetup.startSequenceManagerAuthEnabled(sequenceManagerPrefix, tokenWithEswUserRole, tmpPath)
    sequenceManagerClient.configure(obsMode).futureValue shouldBe ConfigurationMissing(obsMode)

    // Add obs mode in config file
    Files.write(
      tmpPath,
      "esw-sm {\n  obsModes: {\n    APS_Cal: {\n      resources: [ESW, APS]\n      sequencers: [ESW]\n    } } }".getBytes
    )

    // unregister SM and start SM so configuration for obsMode can be picked up
    TestSetup.unregisterSequenceManager(sequenceManagerPrefix)
    val restartedSequenceManager    = TestSetup.startSequenceManagerAuthEnabled(sequenceManagerPrefix, tokenWithEswUserRole, tmpPath)
    val response: ConfigureResponse = restartedSequenceManager.configure(obsMode).futureValue

    // verify that configuration is successful
    response should ===(ConfigureResponse.Success(ComponentId(Prefix(ESW, obsMode.name), Sequencer)))
  }

  "spawn sequence component on given machine with given name and shutdown for given prefix | ESW-337, ESW-338, ESW-351, ESW-332" in {
    val seqCompName   = "seq_comp"
    val seqCompPrefix = Prefix(ESW, seqCompName)

    //spawn ESW agent
    val agentPrefix = getRandomAgentPrefix(ESW)
    spawnAgent(AgentSettings(agentPrefix, 1.minute, testCsChannel))

    //verify that agent is available
    resolveAkkaLocation(agentPrefix, Machine)

    //ESW-337 verify that sequence component is not running
    intercept[Exception](resolveSequenceComponentLocation(seqCompPrefix))

    val sequenceManagerClient = TestSetup.startSequenceManagerAuthEnabled(sequenceManagerPrefix, tokenWithEswUserRole)

    val response = Await.result(agentService.spawnSequenceComponent(agentPrefix, seqCompName, ocsVersionOpt), 1.minute)
    response should ===(Spawned)

    //ESW-337 verify that sequence component is now spawned
    resolveSequenceComponentLocation(seqCompPrefix)

    //ESW-351, ESW-338 shutdown sequence component
    sequenceManagerClient.shutdownSequenceComponent(seqCompPrefix).futureValue should ===(
      ShutdownSequenceComponentResponse.Success
    )

    //ESW-351, ESW-338 verify that sequence component is shutdown
    intercept[Exception](resolveSequenceComponentLocation(seqCompPrefix))
  }

  "shutdown all running sequence components | ESW-346, ESW-332" in {
    val eswSeqCompPrefix   = Prefix(ESW, "primary")
    val irisSeqCompPrefix  = Prefix(IRIS, "primary")
    val aoeswSeqCompPrefix = Prefix(AOESW, "primary")
    TestSetup.startSequenceComponents(eswSeqCompPrefix, irisSeqCompPrefix, aoeswSeqCompPrefix)

    // verify sequence components are started
    resolveSequenceComponentLocation(eswSeqCompPrefix) shouldBe a[AkkaLocation]
    resolveSequenceComponentLocation(irisSeqCompPrefix) shouldBe a[AkkaLocation]
    resolveSequenceComponentLocation(aoeswSeqCompPrefix) shouldBe a[AkkaLocation]

    val sequenceManagerApi = TestSetup.startSequenceManagerAuthEnabled(sequenceManagerPrefix, tokenWithEswUserRole)
    sequenceManagerApi.shutdownAllSequenceComponents().futureValue should ===(ShutdownSequenceComponentResponse.Success)

    // verify all started sequence components are stopped
    intercept[Exception](resolveSequenceComponentLocation(eswSeqCompPrefix))
    intercept[Exception](resolveSequenceComponentLocation(irisSeqCompPrefix))
    intercept[Exception](resolveSequenceComponentLocation(aoeswSeqCompPrefix))

    // verify there are no sequence components in the system
    locationService.list(SequenceComponent).futureValue should ===(List.empty)
  }

  "provision should shutdown all running seq comps and start new as given in provision config | ESW-347, ESW-358, ESW-332" in {
    val eswAgentPrefix  = getRandomAgentPrefix(ESW)
    val irisAgentPrefix = getRandomAgentPrefix(IRIS)
    // start required agents to provision and verify they are running
    spawnAgent(AgentSettings(eswAgentPrefix, 1.minute, testCsChannel))
    spawnAgent(AgentSettings(irisAgentPrefix, 1.minute, testCsChannel))

    val eswRunningSeqComp = Prefix(ESW, "ESW_10")
    TestSetup.startSequenceComponents(eswRunningSeqComp)

    val provisionConfig = ProvisionConfig(eswAgentPrefix -> 1, irisAgentPrefix -> 1)
    val sequenceManager = TestSetup.startSequenceManagerAuthEnabled(sequenceManagerPrefix, tokenWithEswUserRole)
    sequenceManager.provision(provisionConfig).futureValue should ===(ProvisionResponse.Success)

    val eswNewSeqCompPrefix = Prefix(ESW, "ESW_1")
    val irisNewSeqComp      = Prefix(IRIS, "IRIS_1")
    //verify seq comps are started as per the config
    val sequenceCompLocations = locationService.list(SequenceComponent).futureValue
    sequenceCompLocations.map(_.prefix) should not contain eswRunningSeqComp // ESW-358 verify the old seqComps are removed
    sequenceCompLocations.size shouldBe 2
    sequenceCompLocations.map(_.prefix) should contain allElementsOf List(eswNewSeqCompPrefix, irisNewSeqComp)

    //clean up the provisioned sequence components
    sequenceManager.shutdownAllSequenceComponents().futureValue should ===(ShutdownSequenceComponentResponse.Success)
  }

  "getAgentStatus should return status for running sequence components and loaded scripts | ESW-349, ESW-332, ESW-367" in {

    val eswAgentPrefix  = getRandomAgentPrefix(ESW)
    val irisAgentPrefix = getRandomAgentPrefix(IRIS)
    // start required agents
    spawnAgent(AgentSettings(eswAgentPrefix, 1.minute, testCsChannel))
    spawnAgent(AgentSettings(irisAgentPrefix, 1.minute, testCsChannel))

    val sequenceManager = TestSetup.startSequenceManagerAuthEnabled(sequenceManagerPrefix, tokenWithEswUserRole)

    agentService.spawnSequenceComponent(eswAgentPrefix, "primary", ocsVersionOpt).futureValue
    agentService.spawnSequenceComponent(irisAgentPrefix, "primary", ocsVersionOpt).futureValue

    sequenceManager.startSequencer(IRIS, IRIS_DARKNIGHT).futureValue

    val sequencerLocation = resolveSequencerLocation(IRIS, IRIS_DARKNIGHT)

    val expectedStatus = List(
      AgentStatus(
        ComponentId(irisAgentPrefix, Machine),
        List(
          SequenceComponentStatus(ComponentId(Prefix(IRIS, "primary"), SequenceComponent), Some(sequencerLocation))
        )
      ),
      AgentStatus(
        ComponentId(eswAgentPrefix, Machine),
        List(
          SequenceComponentStatus(ComponentId(Prefix(ESW, "primary"), SequenceComponent), None)
        )
      )
    )

    val actualResponse = sequenceManager.getAgentStatus.futureValue.asInstanceOf[AgentStatusResponse.Success]
    actualResponse.agentStatus.toSet should ===(expectedStatus.toSet)
    actualResponse.seqCompsWithoutAgent should ===(List.empty)

    sequenceManager.shutdownAllSequenceComponents().futureValue
  }

  "Give 403 response if request does not have ESW-user role (Unauthorised)| ESW-332" in {
    val sequenceManagerApi = TestSetup.startSequenceManagerAuthEnabled(sequenceManagerPrefix, tokenWithIrisUserRole)

    val httpError = intercept[HttpError](Await.result(sequenceManagerApi.configure(IRIS_CAL), defaultTimeout))
    httpError.statusCode shouldBe 403
  }

  "Give 401 response if request have token (unauthenticated) | ESW-332" in {
    val sequenceManagerApi = TestSetup.startSequenceManagerAuthEnabled(sequenceManagerPrefix, () => None) // empty token

    val httpError = intercept[HttpError](Await.result(sequenceManagerApi.configure(IRIS_CAL), defaultTimeout))
    httpError.statusCode shouldBe 401
  }

  private def sequencerConnection(prefix: Prefix) = AkkaConnection(ComponentId(prefix, Sequencer))

  private def assertThatSeqCompIsAvailable(prefix: Prefix): Unit = assertSeqCompAvailability(isSeqCompAvailable = true, prefix)
  private def assertThatSeqCompIsLoadedWithScript(prefix: Prefix): Unit =
    assertSeqCompAvailability(isSeqCompAvailable = false, prefix)

  private def assertSeqCompAvailability(isSeqCompAvailable: Boolean, prefix: Prefix): Unit = {
    val seqCompStatus = new SequenceComponentImpl(resolveSequenceComponentLocation(prefix)).status.futureValue
    seqCompStatus shouldBe a[GetStatusResponse]
    val getStatusResponse = seqCompStatus.asInstanceOf[GetStatusResponse]
    if (isSeqCompAvailable) getStatusResponse.response shouldBe None // assert sequence component is available
    else getStatusResponse.response.isDefined shouldBe true          // assert sequence components is busy
  }
}
