package esw.ocs.dsl.highlevel

import csw.time.core.models.TAITime
import csw.time.core.models.TMTTime
import csw.time.core.models.UTCTime
import csw.time.scheduler.api.Cancellable
import csw.time.scheduler.api.TimeServiceScheduler
import esw.ocs.dsl.SuspendableCallback
import esw.ocs.dsl.jdk.SuspendToJavaConverter
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.nanoseconds
import kotlin.time.toJavaDuration

interface TimeServiceDsl : SuspendToJavaConverter {
    val timeServiceScheduler: TimeServiceScheduler

    // This allows setting an absolute startTime
    fun scheduleOnceAt(startTime: TMTTime, task: SuspendableCallback): Cancellable =
            timeServiceScheduler.scheduleOnce(startTime, Runnable { task.toJava() })

    // This allows offset from now
    fun scheduleOnceFromNow(fromNow: Duration, task: SuspendableCallback): Cancellable {
        println("From now: " + fromNow)
        return timeServiceScheduler.scheduleOnce(utcTimeAfter(fromNow), Runnable { task.toJava() })
    }

    // This allows a period task with a period starting at an absolute time
    fun schedulePeriodicallyStartingAt(startTime: TMTTime, period: Duration, task: SuspendableCallback): Cancellable =
            timeServiceScheduler.schedulePeriodically(
                    startTime,
                    period.toJavaDuration(),
                    Runnable { task.toJava() })

    // This allows a periodic task starting some duration from now with a specified period
    fun schedulePeriodicallyFromNow(fromNow: Duration, period: Duration, task: SuspendableCallback): Cancellable =
            timeServiceScheduler.schedulePeriodically(
                    utcTimeAfter(fromNow),
                    period.toJavaDuration(),
                    Runnable { task.toJava() })

    fun utcTimeNow(): UTCTime = UTCTime.now()

    fun taiTimeNow(): TAITime = TAITime.now()

    fun utcTimeAfter(duration: Duration): UTCTime =
            UTCTime.after(FiniteDuration(duration.toLongNanoseconds(), TimeUnit.NANOSECONDS))

    fun taiTimeAfter(duration: Duration): TAITime =
            TAITime.after(FiniteDuration(duration.toLongNanoseconds(), TimeUnit.NANOSECONDS))

    fun TMTTime.offsetFromNow(): Duration = durationFromNow().toNanos().nanoseconds

}
