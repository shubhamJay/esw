package esw.ocs.dsl.script

import java.util.concurrent.Executors

import reactor.blockhound.BlockHound

import scala.concurrent.ExecutionContext

object Main extends App {

  private val ec: ExecutionContext = ExecutionContext.fromExecutorService(Executors.newSingleThreadScheduledExecutor)

  private val runnable: Runnable = new Runnable {
    override def run(): Unit = {
      Thread.currentThread.setName("thread-to-monitor")
      println(s"hello ${Thread.currentThread().getName}");
    }
  }
  ec.execute(runnable)

  BlockHound.install(builder => {
    builder.nonBlockingThreadPredicate(p => {
      p.or(it => it.getName().contains("thread-to-monitor"))
    })
    builder.blockingMethodCallback(m => {
      new Exception(m.toString).printStackTrace()
    })
    builder.allowBlockingCallsInside(
      "scala.Console$",
      "println"
    )
  })

  private val blockingRunnable: Runnable = new Runnable {
    override def run(): Unit = {
      println(s"running blocking code ${Thread.currentThread().getName}")
      Thread.sleep(2000)
    }
  }

  private val nonBlockingRunnable: Runnable = new Runnable {
    override def run(): Unit = {
      println(s"running non-blocking code ${Thread.currentThread().getName}")
    }
  }

  ec.execute(blockingRunnable)
  ec.execute(nonBlockingRunnable)
  ec.execute(nonBlockingRunnable)
  ec.execute(nonBlockingRunnable)
  ec.execute(nonBlockingRunnable)

}
