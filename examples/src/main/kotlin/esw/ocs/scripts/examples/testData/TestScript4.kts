package esw.ocs.scripts.examples.testData

import esw.ocs.dsl.core.script
import kotlinx.coroutines.delay
import kotlin.time.seconds

script {

    onSetup("command-lgsf") {
        // NOT update command response To avoid sequencer to
        // finish so that other commands gets time
        delay(1000)
    }

    onAbortSequence {
        //do some actions to abort sequence
        val successEvent = SystemEvent("tcs.test", "abort.success")
        publishEvent(successEvent)
    }

    onStop {
        //do some actions to stop
        val successEvent = SystemEvent("tcs.test", "stop.success")
        publishEvent(successEvent)
    }

    onSetup("time-service-dsl") {
        val period = 2.seconds

        schedulePeriodicallyFromNow(5.seconds, period) {
            publishEvent(SystemEvent("lgsf.test", "publish.success"))
        }

        scheduleOnceAt(taiTimeNow()) {
            publishEvent(SystemEvent("lgsf.test", "publish.success"))
        }
    }
}
