package esw.ocs.dsl.script

import java.util.concurrent.{Executors, ScheduledExecutorService}

import akka.Done

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.DurationInt

class StrandEc private (private[esw] val executorService: ScheduledExecutorService) {
  val ec: ExecutionContext = ExecutionContext.fromExecutorService(executorService)
  def monitor(): Done = {
    val eventualUnit = Future {
      println(s"******* thread name ${Thread.currentThread().getName}")
      Thread.currentThread().setName("thread-to-monitor")
      Done
    }(ec)

    val done = Await.result(eventualUnit, 1.seconds)
    println("done changing name")
    BlockHoundWiring.start()
    done
  }
  def shutdown(): Unit = executorService.shutdownNow()
}

object StrandEc {
  def apply(): StrandEc = {
    val ec = new StrandEc(Executors.newSingleThreadScheduledExecutor())
    ec.monitor()
    ec
  }
}
