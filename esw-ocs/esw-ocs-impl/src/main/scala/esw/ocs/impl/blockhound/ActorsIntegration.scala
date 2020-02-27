package esw.ocs.impl.blockhound

import akka.actor.typed.ActorSystem
import reactor.blockhound.BlockHound
import reactor.blockhound.integration.BlockHoundIntegration

class ActorsIntegration(actorSystem: ActorSystem[_]) extends BlockHoundIntegration {
  override def applyTo(builder: BlockHound.Builder): Unit = {
    builder
      .nonBlockingThreadPredicate(p => {
        p.or(it => it.getName.contains(s"${actorSystem.name}-akka.actor.default-dispatcher"))
      })
      //      .allowBlockingCallsInside("esw.http.core.wiring.HttpService", "bind")
      .blockingMethodCallback(method => new Exception(method.toString).printStackTrace())
  }

  override def toString: String = s"[ActorIntegration for ${actorSystem.name}]"
}
