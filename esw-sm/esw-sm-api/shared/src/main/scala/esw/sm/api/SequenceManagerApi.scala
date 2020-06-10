package esw.sm.api

import csw.prefix.models.Subsystem
import esw.sm.api.models._

import scala.concurrent.Future

trait SequenceManagerApi {
  def configure(observingMode: String): Future[ConfigureResponse]
  def cleanup(observingMode: String): Future[CleanupResponse]
  def getRunningObsModes: Future[GetRunningObsModesResponse]
  def startSequencer(subsystem: Subsystem, observingMode: String): Future[StartSequencerResponse]
  def shutdownSequencer(subsystem: Subsystem, observingMode: String): Future[ShutdownSequencerResponse]
  def restartSequencer(subsystem: Subsystem, observingMode: String): Future[RestartSequencerResponse]
  def shutdownAllSequencers(): Future[ShutdownAllSequencersResponse]
}
