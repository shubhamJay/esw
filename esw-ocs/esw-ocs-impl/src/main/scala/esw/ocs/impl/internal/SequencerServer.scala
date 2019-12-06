package esw.ocs.impl.internal

import akka.Done
import akka.actor.typed.ActorRef
import csw.location.models.AkkaLocation
import csw.params.core.models.Subsystem
import esw.ocs.api.protocol.ScriptError
import esw.ocs.impl.messages.SequenceComponentMsg

// Note: The APIs in this service are blocking. SequenceComponentBehavior consumes this api and since
// these operations are not performed very often they could be blocked. Blocking here
// significantly simplifies the design of SequenceComponentBehavior.
trait SequencerServer {
  def start(): Either[ScriptError, AkkaLocation]
  def shutDown(): Done
}

trait SequencerServerFactory {
  def make(subsystem: Subsystem, observingMode: String, sequenceComponent: ActorRef[SequenceComponentMsg]): SequencerServer
}
