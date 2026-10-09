package app.narrio.preparation

import android.content.Context
import androidx.work.*
import app.narrio.NarrioApplication
import app.narrio.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.TimeUnit

/**
 * Keeps checking TorBox while any shelf book is getting ready, whether or not Narrio is open: quickly at first, then
 * every half hour, only with a network connection. Checks stop when nothing is preparing or TorBox is disconnected.
 */
class PreparationChecks(private val context: Context, private val shelf: Flow<List<ShelfEntry>>, private val connected: () -> Boolean) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val work by lazy { WorkManager.getInstance(context) }

    /** Follows the shelf, so every way a preparation starts, ends, or is removed schedules or cancels checks. */
    fun start() {
        scope.launch {
            var known: Set<String>? = null
            shelf.map { entries -> entries.filter { it.state == "preparing" }.map { "${it.bookId}:${it.preparationId}" }.toSet() }
                .distinctUntilChanged().collect { current ->
                    when (PreparationSchedule.plan(known, current)) {
                        PreparationSchedule.KEEP -> enqueue(PreparationRound(), ExistingWorkPolicy.KEEP)
                        PreparationSchedule.RESTART -> enqueue(PreparationRound(), ExistingWorkPolicy.REPLACE)
                        PreparationSchedule.CANCEL -> cancel()
                        PreparationSchedule.NONE -> Unit
                    }
                    known = current
                }
        }
    }

    /** After reconnecting TorBox, resumes checks for anything still preparing. */
    fun resume() = scope.launch { if (shelf.first().any { it.state == "preparing" }) enqueue(PreparationRound(), ExistingWorkPolicy.KEEP) }

    fun cancel() { work.cancelUniqueWork(NAME) }

    internal fun enqueue(round: PreparationRound, policy: ExistingWorkPolicy) {
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
        internal const val CHECK = "check"
        internal const val ERRORS = "errors"
    }
}

/** One round of checks; it queues the next round after itself while something is still getting ready. */
class PreparationCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as NarrioApplication).graph
        val round = PreparationRound(inputData.getInt(PreparationChecks.CHECK, 0), inputData.getInt(PreparationChecks.ERRORS, 0))
        val next = try { graph.preparations.backgroundCheck(graph.credentials.read() != null, round) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { PreparationRound(round.check + 1, round.errorRuns + 1).takeIf { it.delayMinutes != null } }
        // Appended work starts after this one finishes, its delay counted from then.
        next?.let { graph.preparationChecks.enqueue(it, ExistingWorkPolicy.APPEND_OR_REPLACE) }
        return Result.success()
    }
}
