package app.narrio.preparation

import android.content.Context
import androidx.work.*
import app.narrio.NarrioApplication
import app.narrio.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.TimeUnit

/** One background round, and scheduling the next; the worker adds WorkManager's attempts, a time limit, and nothing else. */
interface PreparationRounds {
    suspend fun run(round: PreparationRound): PreparationRound?
    /** The round couldn't finish or reach TorBox: the next round, or null once checking has stopped. */
    suspend fun unreachable(round: PreparationRound): PreparationRound?
    fun next(round: PreparationRound)
}

/**
 * Keeps checking TorBox while any shelf book is getting ready, whether or not Narrio is open: quickly at first, then
 * every half hour, only with a network connection. Checks stop when nothing is preparing; disconnecting pauses them.
 */
class PreparationChecks(
    private val context: Context, private val shelf: Flow<List<ShelfEntry>>,
    private val preparations: TorBoxPreparations, private val connected: () -> Boolean,
) : PreparationRounds {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val work by lazy { WorkManager.getInstance(context) }

    /** Follows the shelf, so every way a preparation starts, resumes, ends, or is removed schedules or cancels checks. */
    fun start() {
        scope.launch { preparations.deliverPending() }
        scope.launch {
            var known: Set<String>? = null
            shelf.map { entries -> entries.filter { it.state == PreparationStates.PREPARING }.map { "${it.bookId}:${it.preparationId}:${it.pendingFormat}" }.toSet() }
                .distinctUntilChanged().collect { current ->
                    when (PreparationSchedule.plan(known, current)) {
                        PreparationSchedule.KEEP -> enqueue(PreparationRound(), ExistingWorkPolicy.KEEP)
                        PreparationSchedule.RESTART -> enqueue(PreparationRound(), ExistingWorkPolicy.REPLACE)
                        // Saving and announcing an outcome can't be cancelled midway, so this never loses one.
                        PreparationSchedule.CANCEL -> cancel()
                        PreparationSchedule.NONE -> Unit
                    }
                    known = current
                }
        }
    }

    fun cancel() { work.cancelUniqueWork(NAME) }

    override suspend fun run(round: PreparationRound) = preparations.checkAll(connected(), round)
    override suspend fun unreachable(round: PreparationRound) = preparations.unreachable(round)
    // Appended work starts after this round finishes, its delay counted from then.
    override fun next(round: PreparationRound) = enqueue(round, ExistingWorkPolicy.APPEND_OR_REPLACE)

    private fun enqueue(round: PreparationRound, policy: ExistingWorkPolicy) {
        val delay = round.delayMinutes ?: return
        if (!connected()) return
        val request = OneTimeWorkRequestBuilder<PreparationCheckWorker>().addTag(NAME)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(delay, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, PreparationPolicy.STEADY_MINUTES, TimeUnit.MINUTES)
            .setInputData(workDataOf(CHECK to round.check, ERRORS to round.errorRuns)).build()
        work.enqueueUniqueWork(NAME, policy, request)
    }

    companion object {
        const val NAME = "narrio-torbox-preparation"
        const val CHECK = "check"
        const val ERRORS = "errors"
    }
}

/** One round of checks; it queues the next round after itself while something is still getting ready. */
class PreparationCheckWorker @JvmOverloads constructor(
    context: Context, params: WorkerParameters,
    private val rounds: PreparationRounds = (context.applicationContext as NarrioApplication).graph.preparationChecks,
    private val limitMs: Long = ROUND_LIMIT_MS,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // A round WorkManager had to stop and run again counts toward the same limit as one that couldn't reach TorBox.
        val round = PreparationRound(inputData.getInt(PreparationChecks.CHECK, 0), inputData.getInt(PreparationChecks.ERRORS, 0) + runAttemptCount)
        val next = try { withTimeout(limitMs) { rounds.run(round) } }
            catch (_: TimeoutCancellationException) { rounds.unreachable(round) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { rounds.unreachable(round) }
        next?.let(rounds::next)
        return Result.success()
    }

    companion object {
        /** Well inside WorkManager's ten minutes; a round is one account listing plus local work. */
        const val ROUND_LIMIT_MS = 4 * 60_000L
    }
}
