package esw.ocs.dsl.highlevel

import akka.actor.typed.ActorSystem
import csw.command.client.SequencerCommandServiceFactory
import csw.command.client.internal.SequencerCommandServiceImpl
import csw.location.models.AkkaLocation
import csw.params.commands.CommandResponse
import csw.params.commands.Sequence
import csw.params.core.models.Id
import csw.time.core.models.UTCTime
import esw.ocs.api.SequencerAdminApi
import esw.ocs.api.SequencerAdminFactoryApi
import esw.ocs.api.protocol.`Ok$`
import esw.ocs.dsl.sequence_manager.LocationServiceUtil
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import scala.concurrent.Future
import java.util.concurrent.CompletableFuture

class RichSequencerTest {

    private val hint = "test-hint"
    private val startTime: UTCTime = UTCTime.now()

    private val sequencerId: String = "tcs"
    private val observingMode: String = "darknight"
    private val sequence: Sequence = mockk()

    private val sequencerAdminFactory: SequencerAdminFactoryApi = mockk()
    private val locationServiceUtil: LocationServiceUtil = mockk()
    private val actorSystem: ActorSystem<*> = mockk()

    private val tcsSequencer = RichSequencer(sequencerId, observingMode, sequencerAdminFactory, locationServiceUtil, actorSystem)

    private val sequencerCommandService: SequencerCommandServiceImpl = mockk()
    private val sequencerAdmin: SequencerAdminApi = mockk()

    private val sequencerLocation:AkkaLocation = mockk()

    @Test
    fun `submitAndWait should resolve sequencerCommandService for given sequencer and call submitAndWait method on it | ESW-245 `() = runBlocking {

        mockkStatic(SequencerCommandServiceFactory::class)
        every { locationServiceUtil.resolveSequencer(sequencerId, observingMode, any()) }.answers { Future.successful(sequencerLocation) }
        every { SequencerCommandServiceFactory.make(sequencerLocation, actorSystem) }.answers { sequencerCommandService }
        every { sequencerCommandService.submitAndWait(sequence) }.answers { Future.successful(CommandResponse.Completed(Id.apply())) }

        tcsSequencer.submitAndWait(sequence)

        verify { sequencerCommandService.submitAndWait(sequence) }
    }

    @Test
    fun `diagnosticMode should resolve sequencerAdmin for given sequencer and call diagnosticMode method on it | ESW-143, ESW-245 `() = runBlocking {

        every { sequencerAdminFactory.jMake(sequencerId, observingMode) }.answers{CompletableFuture.completedFuture(sequencerAdmin)}
        every { sequencerAdmin.diagnosticMode(startTime, hint) }.answers{Future.successful(`Ok$`.`MODULE$`)}

        tcsSequencer.diagnosticMode(startTime, hint)
        verify { sequencerAdmin.diagnosticMode(startTime, hint) }
    }

    @Test
    fun `operationsMode should resolve sequencerAdmin for given sequencer and call operationsMode method on it | ESW-143, ESW-245 `() = runBlocking {

        every { sequencerAdminFactory.jMake(sequencerId, observingMode) }.answers{CompletableFuture.completedFuture(sequencerAdmin)}
        every { sequencerAdmin.operationsMode() }.answers{Future.successful(`Ok$`.`MODULE$`)}

        tcsSequencer.operationsMode()
        verify { sequencerAdmin.operationsMode() }
    }

    @Test
    fun `goOnline should resolve sequencerAdmin for given sequencer and call goOnline method on it | ESW-236, ESW-245 `() = runBlocking {

        every { sequencerAdminFactory.jMake(sequencerId, observingMode) }.answers{CompletableFuture.completedFuture(sequencerAdmin)}
        every { sequencerAdmin.goOnline() }.answers{Future.successful(`Ok$`.`MODULE$`)}

        tcsSequencer.goOnline()
        verify { sequencerAdmin.goOnline() }
    }

    @Test
    fun `goOffline should resolve sequencerAdmin for given sequencer and call goOffline method on it | ESW-236, ESW-245 `() = runBlocking {

        every { sequencerAdminFactory.jMake(sequencerId, observingMode) }.answers{CompletableFuture.completedFuture(sequencerAdmin)}
        every { sequencerAdmin.goOffline() }.answers{Future.successful(`Ok$`.`MODULE$`)}

        tcsSequencer.goOffline()
        verify { sequencerAdmin.goOffline() }
    }

    @Test
    fun `abortSequence should resolve sequencerAdmin for given sequencer and call abortSequence method on it | ESW-137, ESW-245 `() = runBlocking {

        every { sequencerAdminFactory.jMake(sequencerId, observingMode) }.answers{CompletableFuture.completedFuture(sequencerAdmin)}
        every { sequencerAdmin.abortSequence() }.answers{Future.successful(`Ok$`.`MODULE$`)}

        tcsSequencer.abortSequence()
        verify { sequencerAdmin.abortSequence() }
    }

    @Test
    fun `stop should resolve sequencerAdmin for given sequencer and call stop method on it | ESW-138, ESW-245 `() = runBlocking {

        every { sequencerAdminFactory.jMake(sequencerId, observingMode) }.answers{CompletableFuture.completedFuture(sequencerAdmin)}
        every { sequencerAdmin.stop() }.answers{Future.successful(`Ok$`.`MODULE$`)}

        tcsSequencer.stop()
        verify { sequencerAdmin.stop() }
    }
}