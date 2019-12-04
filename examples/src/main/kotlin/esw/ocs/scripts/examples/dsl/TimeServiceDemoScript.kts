package esw.ocs.scripts.examples.dsl

import esw.ocs.dsl.core.script
import kotlin.time.seconds

// ESW-122 TimeServiceDsl usage in script
script {

    //Usage inside handlers - schedule tasks while handling setup/observe commands
    onSetup("schedule-periodically") {
        val period = 2.seconds

        schedulePeriodicallyStartingAt(utcTimeAfter(5.seconds), period) {
            publishEvent(SystemEvent("lgsf", "publish.success"))
        }
    }

    onObserve("schedule-once") {
        scheduleOnceAt(taiTimeNow()) {
            publishEvent(SystemEvent("lgsf", "publish.success"))
        }
    }

    //Usage at top level
    scheduleOnceAt(taiTimeNow()) {
        publishEvent(SystemEvent("lgsf", "publish.success"))
    }

    schedulePeriodicallyStartingAt(utcTimeAfter(2.seconds), 5.seconds) {
        publishEvent(SystemEvent("lgsf", "publish.success"))
    }
}
