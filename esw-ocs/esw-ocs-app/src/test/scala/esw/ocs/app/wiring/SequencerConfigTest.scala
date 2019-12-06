package esw.ocs.app.wiring

import com.typesafe.config.{Config, ConfigFactory}
import csw.params.core.models.Subsystem.ESW
import csw.params.core.models.{Prefix, Subsystem}
import esw.http.core.BaseTestSuite
import esw.ocs.dsl.script.exceptions.ScriptLoadingException.ScriptConfigurationMissingException
import esw.ocs.dsl.script.{CswServices, ScriptDsl, StrandEc}

class SequencerConfigTest extends BaseTestSuite {
  private val config: Config = ConfigFactory.load()

  "from" must {
    "create SequencerConfig based on subsystem and observingMode | ESW-103" in {
      val subsystem        = Subsystem.ESW
      val observingMode    = "darknight"
      val sequencerConfigs = SequencerConfig.from(config, subsystem, observingMode)

      sequencerConfigs.prefix.componentName should ===("darknight")
      sequencerConfigs.prefix should ===(Prefix(ESW, "darknight"))
      sequencerConfigs.scriptClass should ===(classOf[ValidTestScript].getCanonicalName)
    }

    "throw ScriptConfigurationMissingException if script config is not provided for given subsystem and observingMode | ESW-103" in {
      val subsystem     = Subsystem.CSW
      val observingMode = "invalidObservingMode"

      val exception = intercept[ScriptConfigurationMissingException] {
        SequencerConfig.from(config, subsystem, observingMode)
      }
      exception.getMessage should ===(s"Script configuration missing for [${subsystem.name}] with [$observingMode]")
    }
  }
}

class ValidTestScript(csw: CswServices) extends ScriptDsl(csw, StrandEc())
