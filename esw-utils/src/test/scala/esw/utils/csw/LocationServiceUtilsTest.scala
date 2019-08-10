package esw.utils.csw

import java.net.URI

import akka.Done
import akka.actor.CoordinatedShutdown
import akka.actor.CoordinatedShutdown.UnknownReason
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter.TypedActorSystemOps
import csw.location.api.exceptions.OtherLocationIsRegistered
import csw.location.api.scaladsl.{LocationService, RegistrationResult}
import csw.location.models.ComponentType.Sequencer
import csw.location.models.Connection.AkkaConnection
import csw.location.models.{AkkaLocation, AkkaRegistration, ComponentId, ComponentType}
import csw.params.core.models.{Prefix, Subsystem}
import esw.ocs.api.BaseTestSuite
import esw.ocs.api.models.messages.RegistrationError
import org.mockito.Mockito.{verify, when}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class LocationServiceUtilsTest extends ScalaTestWithActorTestKit with BaseTestSuite {

  private val locationService = mock[LocationService]

  private val prefix         = Prefix("tcs.home.datum")
  private val uri            = new URI("uri")
  private val akkaConnection = AkkaConnection(ComponentId("ocs", Sequencer))
  private val registration   = AkkaRegistration(akkaConnection, prefix, uri)
  private val akkaLocation   = AkkaLocation(akkaConnection, prefix, uri)

  "register" must {
    "return successful RegistrationResult" in {
      val system: ActorSystem[_] = ActorSystem(Behaviors.empty, "test")
      val coordinatedShutdown    = CoordinatedShutdown(system.toUntyped)
      val registrationResult     = mock[RegistrationResult]
      when(registrationResult.location).thenReturn(akkaLocation)
      when(registrationResult.unregister()).thenReturn(Future.successful(Done))
      when(locationService.register(registration)).thenReturn(Future(registrationResult))

      val locationServiceUtils = new LocationServiceUtils(locationService)

      locationServiceUtils.register(registration)(system).rightValue should ===(akkaLocation)
      coordinatedShutdown.run(UnknownReason).futureValue
      verify(registrationResult).unregister()
    }

    "map location service registration failure to RegistrationError" in {
      val system: ActorSystem[_] = ActorSystem(Behaviors.empty, "test")
      val errorMsg               = "error message"
      when(locationService.register(registration)).thenReturn(Future.failed(OtherLocationIsRegistered(errorMsg)))

      val locationServiceUtils = new LocationServiceUtils(locationService)

      locationServiceUtils.register(registration)(system).leftValue should ===(
        RegistrationError(errorMsg)
      )
      system.terminate()
    }
  }

  "listBySubsystem" must {
    "list all locations which match given componentType and subsystem | ESW-144" in {
      val testUri = new URI("test-uri")
      val tcsLocations = List(
        AkkaLocation(AkkaConnection(ComponentId("TCS_1", ComponentType.SequenceComponent)), Prefix("tcs.test.filter1"), testUri),
        AkkaLocation(AkkaConnection(ComponentId("TCS_2", ComponentType.SequenceComponent)), Prefix("tcs.test.filter2"), testUri),
        AkkaLocation(AkkaConnection(ComponentId("TCS_3", ComponentType.SequenceComponent)), Prefix("tcs.test.filter3"), testUri)
      )
      val sequenceComponentLocations = tcsLocations ++ List(
        AkkaLocation(AkkaConnection(ComponentId("OSS_1", ComponentType.SequenceComponent)), Prefix("oss.test.filter1"), testUri),
        AkkaLocation(AkkaConnection(ComponentId("IRIS_1", ComponentType.SequenceComponent)), Prefix("iris.test.filter1"), testUri)
      )

      when(locationService.list(ComponentType.SequenceComponent)).thenReturn(Future.successful(sequenceComponentLocations))
      val locationServiceUtils = new LocationServiceUtils(locationService)

      val actualLocations = locationServiceUtils.listBy(Subsystem.TCS, ComponentType.SequenceComponent).futureValue

      actualLocations shouldEqual tcsLocations
    }

    "return empty list if no matching component type and subsystem is found | ESW-144" in {
      val testUri = new URI("test-uri")
      val sequenceComponentLocations = List(
        AkkaLocation(AkkaConnection(ComponentId("TCS_1", ComponentType.SequenceComponent)), Prefix("tcs.test.filter1"), testUri),
        AkkaLocation(AkkaConnection(ComponentId("TCS_2", ComponentType.SequenceComponent)), Prefix("tcs.test.filter2"), testUri),
        AkkaLocation(AkkaConnection(ComponentId("IRIS_1", ComponentType.SequenceComponent)), Prefix("iris.test.filter1"), testUri)
      )

      when(locationService.list(ComponentType.SequenceComponent)).thenReturn(Future.successful(sequenceComponentLocations))
      val locationServiceUtils = new LocationServiceUtils(locationService)

      val actualLocations = locationServiceUtils.listBy(Subsystem.NFIRAOS, ComponentType.SequenceComponent).futureValue

      actualLocations shouldEqual List.empty
    }
  }
}