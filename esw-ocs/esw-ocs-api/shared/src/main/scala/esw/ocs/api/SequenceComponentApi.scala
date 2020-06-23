package esw.ocs.api

import csw.prefix.models.Subsystem
import esw.ocs.api.models.ObsMode
import esw.ocs.api.protocol.SequenceComponentResponse.{GetStatusResponse, OkOrUnhandled, ScriptResponseOrUnhandled}

import scala.concurrent.Future

trait SequenceComponentApi {
  def loadScript(subsystem: Subsystem, observingMode: ObsMode): Future[ScriptResponseOrUnhandled]
  def restartScript(): Future[ScriptResponseOrUnhandled]
  def unloadScript(): Future[OkOrUnhandled]
  def status: Future[GetStatusResponse]
  def shutdown(): Future[OkOrUnhandled]
}
