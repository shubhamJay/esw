package esw.ocs.core

import java.net.URI

import akka.Done
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.scaladsl.Behaviors
import csw.location.models.Connection.AkkaConnection
import csw.location.models.{AkkaLocation, ComponentId, ComponentType}
import csw.params.core.models.Prefix
import esw.ocs.BaseTestSuite
import esw.ocs.api.models.messages.SequenceComponentMsg
import esw.ocs.api.models.messages.SequenceComponentMsg.{GetStatus, LoadScript, UnloadScript}
import esw.ocs.api.models.messages.SequenceComponentResponses.{GetStatusResponse, LoadScriptResponse}

class SequenceComponentClientTest extends ScalaTestWithActorTestKit with BaseTestSuite {
  private val location =
    AkkaLocation(AkkaConnection(ComponentId("test", ComponentType.Sequencer)), Prefix("test"), new URI("uri"))
  private val loadScriptResponse = LoadScriptResponse(Right(location))
  private val getStatusResponse  = GetStatusResponse(Some(location))

  private val mockedBehavior: Behaviors.Receive[SequenceComponentMsg] = Behaviors.receiveMessage[SequenceComponentMsg] { msg =>
    msg match {
      case LoadScript(_, _, replyTo) => replyTo ! loadScriptResponse
      case GetStatus(replyTo)        => replyTo ! getStatusResponse
      case UnloadScript(replyTo)     => replyTo ! Done
    }
    Behaviors.same
  }

  private val sequenceComponent       = spawn(mockedBehavior)
  private val sequenceComponentClient = new SequenceComponentClient(sequenceComponent)

  "LoadScript | ESW-103" in {
    sequenceComponentClient.loadScript("sequencerId", "observingMode").futureValue should ===(loadScriptResponse)
  }

  "GetStatus | ESW-103" in {
    sequenceComponentClient.getStatus.futureValue should ===(getStatusResponse)
  }

  "UnloadScript | ESW-103" in {
    sequenceComponentClient.unloadScript().futureValue should ===(Done)
  }
}
