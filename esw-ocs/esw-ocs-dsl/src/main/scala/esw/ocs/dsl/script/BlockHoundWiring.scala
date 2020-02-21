package esw.ocs.dsl.script

import reactor.blockhound.BlockHound

object BlockHoundWiring {
  def start(): Unit = {
    println("starting blockhound")
    BlockHound.install(builder => {
      builder.nonBlockingThreadPredicate(p => {
        p.or(it => it.getName.contains("thread-to-monitor"))
      })
      builder.blockingMethodCallback(m => {
        new Exception(m.toString).printStackTrace()
      })
      builder.allowBlockingCallsInside(
        "scala.Console$",
        "println"
      )
    })
  }
}
