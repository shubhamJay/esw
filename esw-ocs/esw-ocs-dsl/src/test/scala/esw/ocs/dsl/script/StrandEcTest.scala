package esw.ocs.dsl.script

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import reactor.blockhound.BlockHound

import scala.concurrent.Future

class StrandEcTest extends AnyWordSpec with Matchers {
  "shutdown" must {
    "stop executor service" in {

      val builder = BlockHound.builder()
      builder.install()
      builder.disallowBlockingCallsInside(
        "scala.Console$",
        "println"
      )
      builder.blockingMethodCallback(m => {
        new Exception(m.toString).printStackTrace()
      })
      builder.blockingMethodCallback(println)

      val strandEc = StrandEc()

      println(strandEc.ec)
//      val eventualUnit = Future {
//        Thread.sleep(1000)
//      }(strandEc.ec)
//
//      eventualUnit.onComplete(_ => println("************"))(strandEc.ec)
//      Thread.sleep(10000)
//      strandEc.shutdown()
//      strandEc.executorService.isShutdown shouldBe true
    }
  }
}
