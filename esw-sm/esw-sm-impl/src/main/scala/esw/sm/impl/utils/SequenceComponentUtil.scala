package esw.sm.impl.utils

import akka.actor.typed.ActorSystem
import csw.location.api.models.ComponentType.SequenceComponent
import csw.location.api.models.Connection.AkkaConnection
import csw.location.api.models.{AkkaLocation, ComponentId}
import csw.prefix.models.Subsystem.ESW
import csw.prefix.models.{Prefix, Subsystem}
import esw.agent.api.ComponentStatus
import esw.agent.client.AgentClient
import esw.commons.extensions.FutureEitherExt.FutureEitherOps
import esw.commons.utils.FutureUtils
import esw.commons.utils.location.EswLocationError.RegistrationListingFailed
import esw.commons.utils.location.{EswLocationError, LocationServiceUtil}
import esw.ocs.api.SequenceComponentApi
import esw.ocs.api.actor.client.SequenceComponentImpl
import esw.ocs.api.models.ObsMode
import esw.ocs.api.protocol.SequenceComponentResponse.{Ok, ScriptResponseOrUnhandled, SequencerLocation, Unhandled}
import esw.ocs.api.protocol.{ScriptError, SequenceComponentResponse}
import esw.sm.api.protocol.CommonFailure.LocationServiceError
import esw.sm.api.protocol.GetAllAgentStatusResponse.{GetAgentComponentStatus, SequenceComponentStatus}
import esw.sm.api.protocol.ShutdownSequenceComponentsPolicy.{AllSequenceComponents, SingleSequenceComponent}
import esw.sm.api.protocol.StartSequencerResponse.{LoadScriptError, SequenceComponentNotAvailable, Started}
import esw.sm.api.protocol._

import scala.async.Async._
import scala.concurrent.Future

class SequenceComponentUtil(locationServiceUtil: LocationServiceUtil, agentUtil: AgentUtil)(implicit
    actorSystem: ActorSystem[_]
) {

  import actorSystem.executionContext

  def spawnSequenceComponent(machine: Prefix, name: String): Future[SpawnSequenceComponentResponse] = {
    val seqCompPrefix = Prefix(machine.subsystem, name)
    agentUtil
      .spawnSequenceComponentFor(machine, name)
      .mapToAdt(
        _ => SpawnSequenceComponentResponse.Success(ComponentId(seqCompPrefix, SequenceComponent)),
        identity
      )
  }

  def idleSequenceComponentsFor(
      subsystems: List[Subsystem]
  ): Future[Either[RegistrationListingFailed, List[AkkaLocation]]] =
    locationServiceUtil
      .listAkkaLocationsBy(SequenceComponent, withFilter = location => subsystems.contains(location.prefix.subsystem))
      .flatMapRight(filterIdleSequenceComponents)

  def loadScript(
      subSystem: Subsystem,
      obsMode: ObsMode
  ): Future[Either[StartSequencerResponse.Failure, Started]] =
    getAvailableSequenceComponent(subSystem).flatMap {
      case Left(error)       => Future.successful(Left(error))
      case Right(seqCompLoc) => loadScript(subSystem, obsMode, seqCompLoc)
    }

  def loadScript(
      subSystem: Subsystem,
      obsMode: ObsMode,
      seqCompLoc: AkkaLocation
  ): Future[Either[StartSequencerResponse.Failure, Started]] = {
    val seqCompApi = createSequenceComponentImpl(seqCompLoc)
    seqCompApi
      .loadScript(subSystem, obsMode)
      .flatMap {
        case SequencerLocation(location)             => Future.successful(Right(Started(location.connection.componentId)))
        case error: ScriptError.LocationServiceError => Future.successful(Left(LocationServiceError(error.msg)))
        case error: ScriptError.LoadingScriptFailed  => Future.successful(Left(LoadScriptError(error.msg)))
        case error: Unhandled                        => Future.successful(Left(LoadScriptError(error.msg)))
      }
  }

  def unloadScript(loc: AkkaLocation): Future[Ok.type] = createSequenceComponentImpl(loc).unloadScript()

  def shutdown(policy: ShutdownSequenceComponentsPolicy): Future[ShutdownSequenceComponentResponse] =
    (policy match {
      case SingleSequenceComponent(prefix) => shutdown(prefix)
      case AllSequenceComponents           => shutdownAll().mapRight(_ => SequenceComponentResponse.Ok)
    }).mapToAdt(_ => ShutdownSequenceComponentResponse.Success, error => LocationServiceError(error.msg))

  def restartScript(loc: AkkaLocation): Future[ScriptResponseOrUnhandled] = createSequenceComponentImpl(loc).restartScript()

  private def getAvailableSequenceComponent(subsystem: Subsystem): Future[Either[SequenceComponentNotAvailable, AkkaLocation]] =
    getIdleSequenceComponentFor(subsystem)
      .flatMap {
        case location @ Some(_)       => Future.successful(location)
        case None if subsystem != ESW => getIdleSequenceComponentFor(ESW) // fallback
        case None                     => Future.successful(None)
      }
      .map {
        case Some(location) => Right(location)
        case None           => Left(SequenceComponentNotAvailable(s"No available sequence components for $subsystem or $ESW"))
      }

  private def shutdown(prefix: Prefix): Future[Either[EswLocationError.FindLocationError, SequenceComponentResponse.Ok.type]] =
    locationServiceUtil
      .find(AkkaConnection(ComponentId(prefix, SequenceComponent)))
      .flatMapRight(shutdown)

  private def shutdownAll(): Future[Either[EswLocationError.RegistrationListingFailed, List[SequenceComponentResponse.Ok.type]]] =
    locationServiceUtil
      .listAkkaLocationsBy(SequenceComponent)
      .flatMapRight(Future.traverse(_)(shutdown))

  private def shutdown(loc: AkkaLocation): Future[SequenceComponentResponse.Ok.type] = createSequenceComponentImpl(loc).shutdown()

  private def getIdleSequenceComponentFor(subsystem: Subsystem): Future[Option[AkkaLocation]] =
    locationServiceUtil
      .listAkkaLocationsBy(subsystem, SequenceComponent)
      .flatMapToAdt(raceForIdleSequenceComponents, _ => None)

  // intentionally ignoring Left as in this case domain won't decide action based on what is error hence converting it to optionality

  private def raceForIdleSequenceComponents(locations: List[AkkaLocation]): Future[Option[AkkaLocation]] =
    FutureUtils
      .firstCompletedOf(locations.map(idleSequenceComponent))(_.isDefined)
      .map(_.flatten)

  private def filterIdleSequenceComponents(locations: List[AkkaLocation]): Future[List[AkkaLocation]] = {
    Future
      .traverse(locations)(idleSequenceComponent)
      .map(_.collect { case Some(location) => location })
  }

  private[sm] def idleSequenceComponent(sequenceComponentLocation: AkkaLocation): Future[Option[AkkaLocation]] =
    async {
      val sequenceComponentApi = createSequenceComponentImpl(sequenceComponentLocation)
      val isIdle               = await(sequenceComponentApi.status).response.isEmpty
      if (isIdle) Some(sequenceComponentLocation) else None
    }

  private[sm] def createSequenceComponentImpl(sequenceComponentLocation: AkkaLocation): SequenceComponentApi =
    new SequenceComponentImpl(sequenceComponentLocation)

  // input  -> ['ESW.IRIS-Machine','ESW.WFOS-Machine']
  // output -> Future [
//                    { 'ESW.IRIS-Machine' -> [
//                                    ('ESW.IRIS-SequenceComponent' -> [sequencerLocation/sequencerCompId]),             // if script is loaded
//                                    ('ESW.OCS-SequenceComponent' -> [None])                          // if script is not loaded
//                                 ]
//                    },
//                    {'ESW.WFOS-Machine' -> [ ('ESW.WFOS-SequenceComponent' -> [None])] }            // if script is not loaded
  // ]
  private[sm] def getRunningAgentsStatus(
      agentLocations: List[AkkaLocation]
  ): Future[List[GetAgentComponentStatus]] = {
    async {
      val mapOfAgents = Future.traverse(agentLocations)(location =>
        agentUtil
          .getAgent(location.prefix)
          .collect({
            case Right(agentClient) => location.connection.componentId -> agentClient
          })
      )
      await(getAgentsStatus(await(mapOfAgents)))
    }
  }

  private def getAgentsStatus(mapOfAgents: List[(ComponentId, AgentClient)]): Future[List[GetAgentComponentStatus]] = {
    async {
      val listOfPromises = mapOfAgents.map(agent => agent._1 -> agent._2.getAgentStatus)

      val future = Future.traverse(listOfPromises)({ agent => agent._2.map(x => agent._1 -> x.componentStatus) })

      val agentToRunningComponentsMap = await(future).map(value =>
        value._1 -> value._2
          .filter(value => value._1.componentType == SequenceComponent && value._2 == ComponentStatus.Running)
          .keys
          .toList
      )
      val future1 = Future.traverse(agentToRunningComponentsMap)({ set =>
        getRunningSeqComponent(set._2)
          .map(runningSeqComps => {
            set._1 -> runningSeqComps
          })
      })
      await(future1).map(x => GetAgentComponentStatus(x._1, x._2))
    }
  }

  // input  -> ['ESW.IRIS-SequenceComponent','ESW.WFOS-SequenceComponent']
  // output -> Future [
  //            [ 'ESW.IRIS-SequenceComponent' -> [locationObject], // if script is loaded
  //            [ 'ESW.WFOS-SequenceComponent' -> [None],           // if script is not loaded
  // ]
  private def getRunningSeqComponent(
      seqComponentIds: List[ComponentId]
  ): Future[List[SequenceComponentStatus]] = {
    async {
      val seqComponentAkkaLocations: Future[List[AkkaLocation]] =
        Future.traverse(seqComponentIds)(seqCompId => {
          locationServiceUtil
            .findByComponentNameAndType(seqCompId.prefix.componentName, seqCompId.componentType)
            .map({
              case Right(value) => {
                value match { // Assuming only akka location for Sequence Component
                  case x: AkkaLocation => x
                }
              }
            })
        })
      val value: List[AkkaLocation] = await(seqComponentAkkaLocations)
      await(GetSeqComponentsStatus(value))
    }
  }

  private def GetSeqComponentsStatus(values: List[AkkaLocation]) = {
    async {
      val seqComImpls = values.map(x => (x, createSequenceComponentImpl(x).status))
      val ss: Future[List[(ComponentId, Option[AkkaLocation])]] =
        Future.traverse(seqComImpls)({ x =>
          x._2.map(dd => x._1.connection.componentId -> dd.response)
        })
      await(ss)
        .map(x => {
          SequenceComponentStatus(x._1, if (x._2.isEmpty) None else Some(x._2.get.connection.componentId))
        })
    }
  }
}
