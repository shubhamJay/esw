package esw.highlevel.dsl.javadsl

import java.time.Duration
import java.util.concurrent.CompletionStage

import csw.time.core.models.TMTTime
import csw.time.scheduler.api.Cancellable
import esw.highlevel.dsl.TimeServiceDsl

import scala.concurrent.ExecutionContext

case class Callback(cb: () => CompletionStage[Void])

trait JTimeServiceDsl { self: TimeServiceDsl =>

  // fixme : test does task maps to Kotlin's suspendable?
  def scheduleOnce(startTime: TMTTime, task: Callback, ec: ExecutionContext): Cancellable = {
    val block: Runnable = () => task.cb()
    timeServiceSchedulerFactory.make()(ec).scheduleOnce(startTime, block)
  }

  def schedulePeriodically(startTime: TMTTime, interval: Duration, task: Callback, ec: ExecutionContext): Cancellable = {
    val block: Runnable = () => task.cb()
    timeServiceSchedulerFactory.make()(ec).schedulePeriodically(startTime, interval, block)
  }

}
