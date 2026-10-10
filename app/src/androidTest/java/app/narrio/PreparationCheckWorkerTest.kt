package app.narrio

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import app.narrio.data.PreparationRound
import app.narrio.preparation.PreparationCheckWorker
import app.narrio.preparation.PreparationChecks
import app.narrio.preparation.PreparationRounds
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/** The background preparation worker under WorkManager's own test harness, with rounds that never reach TorBox. */
@RunWith(AndroidJUnit4::class)
class PreparationCheckWorkerTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private class Rounds(private val result: suspend (PreparationRound) -> PreparationRound?) : PreparationRounds {
        val ran = mutableListOf<PreparationRound>(); val unreachable = mutableListOf<PreparationRound>(); val queued = mutableListOf<PreparationRound>()
        override suspend fun run(round: PreparationRound): PreparationRound? { ran += round; return result(round) }
        override suspend fun unreachable(round: PreparationRound) = PreparationRound(round.check + 1, round.errorRuns + 1).also { unreachable += round }
        override fun next(round: PreparationRound) { queued += round }
    }

    private fun run(rounds: Rounds, check: Int, errors: Int, attempts: Int = 0, limitMs: Long = 60_000): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<PreparationCheckWorker>(context)
            .setInputData(workDataOf(PreparationChecks.CHECK to check, PreparationChecks.ERRORS to errors)).setRunAttemptCount(attempts)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    PreparationCheckWorker(appContext, workerParameters, rounds, limitMs)
            }).build().doWork()
    }

    @Test fun aRoundQueuesTheNextAfterItselfAndNothingWhenDone() {
        val more = Rounds { PreparationRound(it.check + 1) }
        assertEquals(ListenableWorker.Result.success(), run(more, 2, 0))
        assertEquals(listOf(PreparationRound(2, 0)), more.ran)
        assertEquals(listOf(PreparationRound(3, 0)), more.queued)

        val done = Rounds { null }
        assertEquals(ListenableWorker.Result.success(), run(done, 5, 0))
        assertTrue(done.queued.isEmpty())
    }

    @Test fun roundsWorkManagerRestartedCountTowardTheSameLimit() {
        val rounds = Rounds { null }
        run(rounds, 2, 1, attempts = 2)
        assertEquals(listOf(PreparationRound(2, 3)), rounds.ran)
    }

    @Test fun anOverrunOrFailedRoundBacksOffInsteadOfRetryingAtOnce() {
        val slow = Rounds { awaitCancellation() }
        assertEquals(ListenableWorker.Result.success(), run(slow, 2, 0, limitMs = 200))
        assertEquals(listOf(PreparationRound(2, 0)), slow.unreachable)
        assertEquals(listOf(PreparationRound(3, 1)), slow.queued)

        val failing = Rounds { throw IOException("Connection reset") }
        assertEquals(ListenableWorker.Result.success(), run(failing, 4, 1))
        assertEquals(listOf(PreparationRound(5, 2)), failing.queued)
    }
}
