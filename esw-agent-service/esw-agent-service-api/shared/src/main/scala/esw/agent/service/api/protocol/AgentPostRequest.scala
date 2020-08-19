package esw.agent.service.api.protocol

import java.nio.file.Path

import csw.location.api.models.Connection
import csw.prefix.models.Prefix

sealed trait AgentPostRequest

object AgentPostRequest {

  case class SpawnSequenceManager(agentPrefix: Prefix, obsModeConfigPath: Path, isConfigLocal: Boolean, version: Option[String])
      extends AgentPostRequest

  case class SpawnSequenceComponent(agentPrefix: Prefix, componentName: String, version: Option[String]) extends AgentPostRequest

  case class KillComponent(connection: Connection) extends AgentPostRequest
}
