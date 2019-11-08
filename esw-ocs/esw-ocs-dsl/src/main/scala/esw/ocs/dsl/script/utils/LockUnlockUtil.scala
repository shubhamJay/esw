package esw.ocs.dsl.script.utils

import java.time.Duration
import java.util.concurrent.{CompletionStage, TimeUnit}

import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.{ActorRef, ActorSystem, Scheduler, SpawnProtocol}
import akka.stream.scaladsl.{Keep, Sink}
import akka.stream.typed.scaladsl.ActorSource
import akka.stream.{Materializer, OverflowStrategy}
import akka.util.Timeout
import csw.command.client.messages.SupervisorLockMessage.{Lock, Unlock}
import csw.command.client.models.framework.LockingResponse
import csw.command.client.models.framework.LockingResponse._
import csw.location.models.ComponentType
import csw.params.core.models.Prefix
import esw.ocs.dsl.sequence_manager.LocationServiceUtil

import scala.compat.java8.FutureConverters.FutureOps
import scala.concurrent.duration.{FiniteDuration, _}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.FutureConverters.CompletionStageOps
import scala.util.Success

class LockUnlockUtil(locationServiceUtil: LocationServiceUtil)(actorSystem: ActorSystem[SpawnProtocol.Command]) {
  implicit val timeout: Timeout             = 5.seconds
  private implicit val scheduler: Scheduler = actorSystem.scheduler
  private implicit val ec: ExecutionContext = actorSystem.executionContext
  private implicit val mat: Materializer    = Materializer(actorSystem)

  def lock(componentName: String, componentType: ComponentType, prefix: Prefix, leaseDuration: Duration)(
      onLockAboutToExpire: () => CompletionStage[Void],
      onLockExpired: () => CompletionStage[Void]
  ): CompletionStage[LockingResponse] = {
    val leaseFiniteDuration  = FiniteDuration(leaseDuration.toNanos, TimeUnit.NANOSECONDS)
    val eventualComponentRef = locationServiceUtil.resolveComponentRef(componentName, componentType)

    val firstLockResponse: Promise[LockingResponse] = Promise()

    eventualComponentRef.flatMap { compRef =>
      actorSource
        .mapMaterializedValue { lockResponseReplyTo =>
          compRef ! Lock(prefix, lockResponseReplyTo, leaseFiniteDuration)
          firstLockResponse.future
        }
        .mapAsync(1) { lockResponse =>
          firstLockResponse.tryComplete(Success(lockResponse))
          executeCallbacks(onLockAboutToExpire, onLockExpired)(lockResponse)
        }
        .takeWhile(isNotFinalLockResponse)
        .toMat(Sink.ignore)(Keep.left)
        .run()
    }.toJava
  }

  def unlock(componentName: String, componentType: ComponentType, prefix: Prefix): CompletionStage[LockingResponse] =
    locationServiceUtil
      .resolveComponentRef(componentName, componentType)
      .flatMap(_ ? (Unlock(prefix, _: ActorRef[LockingResponse])))
      .toJava

  private def actorSource =
    ActorSource
      .actorRef[LockingResponse](
        completionMatcher = PartialFunction.empty,
        failureMatcher = PartialFunction.empty,
        bufferSize = 256,
        overflowStrategy = OverflowStrategy.dropHead
      )

  private def isFinalLockResponse: LockingResponse => Boolean = {
    case LockAcquired        => false
    case LockExpiringShortly => false
    case _                   => true
  }

  private def isNotFinalLockResponse: LockingResponse => Boolean = !isFinalLockResponse(_)

  private def executeCallbacks(
      onLockAboutToExpire: () => CompletionStage[Void],
      onLockExpired: () => CompletionStage[Void]
  ): LockingResponse => Future[LockingResponse] = {
    case res @ LockExpired         => onLockExpired().asScala.map(_ => res)
    case res @ LockExpiringShortly => onLockAboutToExpire().asScala.map(_ => res)
    case res                       => Future.successful(res)
  }
}
