package app.narrio.playback

import android.content.Context
import android.net.ConnectivityManager
import androidx.work.*
import app.narrio.NarrioApplication
import app.narrio.data.*
import app.narrio.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** Phone audio has no network/charging requirement; streaming requires both unmetered and charging. */
class BookAlignmentScheduler(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val work = WorkManager.getInstance(context)
    fun start() {
        val graph = (context.applicationContext as NarrioApplication).graph
        scope.launch {
            combine(graph.library.observeShelf(), graph.offline.books, graph.narrationSync.status) { shelf, downloads, _ -> shelf to downloads }
                .collect { (shelf, downloads) ->
                    if (!enabled()) return@collect
                    for (entry in shelf) {
                        val book = entry.book()
                        val sources = (book.sources + listOfNotNull(entry.source()) + downloads.filter { it.book.id == book.id }.map { it.source }).distinctBy { it.id }
                        for (source in sources) {
                            graph.mappingRepository.register(book.id, source)
                            val snapshot = try { graph.mappingRepository.snapshot(book.id, source.id) }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { null } ?: continue
                            if (snapshot.document.timedSourceId.isNotBlank()) continue
                            for (part in source.parts) {
                                val key = alignmentKey(book.id, snapshot, part.id)
                                if (!graph.alignmentJobs.progress(key).complete)
                                    enqueue(key, graph.offline.complete(part))
                            }
                        }
                    }
                }
        }
    }
    fun enabled() = context.getSharedPreferences("preferences", Context.MODE_PRIVATE).getBoolean(SETTING, true)
    fun setEnabled(enabled: Boolean) {
        context.getSharedPreferences("preferences", Context.MODE_PRIVATE).edit().putBoolean(SETTING, enabled).apply()
        if (!enabled) work.cancelAllWorkByTag(TAG)
        else scope.launch {
            val graph = (context.applicationContext as NarrioApplication).graph
            for (entry in graph.library.observeShelf().first()) {
                val sources = (entry.book().sources + listOfNotNull(entry.source()) + graph.offline.books.value.filter { it.book.id == entry.bookId }.map { it.source }).distinctBy { it.id }
                for (source in sources) {
                    graph.mappingRepository.register(entry.bookId, source)
                    val snapshot = graph.mappingRepository.snapshot(entry.bookId, source.id) ?: continue
                    for (part in source.parts) enqueue(alignmentKey(entry.bookId, snapshot, part.id), graph.offline.complete(part))
                }
            }
        }
    }
    fun enqueue(key: AlignmentKey, downloaded: Boolean, continuation: Boolean = false) {
        if (!enabled()) return
        val constraints = Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true)
            .setRequiredNetworkType(if (downloaded) NetworkType.NOT_REQUIRED else NetworkType.UNMETERED)
            .setRequiresCharging(!downloaded).build()
        val name = TAG + BookTextParser.fingerprint(NarrioJson.encodeToString(AlignmentKey.serializer(), key).toByteArray())
        // Separate local/stream names let a finished download upgrade an existing constrained request.
        val request = OneTimeWorkRequestBuilder<BookAlignmentWorker>().addTag(TAG).setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
            .setInitialDelay(if (continuation) 30 else 0, TimeUnit.SECONDS)
            .setInputData(workDataOf("key" to NarrioJson.encodeToString(AlignmentKey.serializer(), key), "downloaded" to downloaded)).build()
        work.enqueueUniqueWork(name + if (downloaded) "-local" else "-stream",
            if (continuation) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, request)
        if (downloaded) work.cancelUniqueWork(name + "-stream")
    }
    companion object {
        const val SETTING = "backgroundNarrationAlignment"
        const val TAG = "narrio-book-alignment-"
    }
}

class BookAlignmentWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as NarrioApplication).graph
        if (!graph.bookAlignment.enabled()) return Result.success()
        val key = inputData.getString("key")?.let { NarrioJson.decodeFromString<AlignmentKey>(it) } ?: return Result.failure()
        try {
            if (graph.playback.state.value.playing || graph.playback.state.value.buffering) return Result.retry()
            var snapshot = graph.mappingRepository.snapshot(key.bookId, key.sourceId) ?: return Result.success()
            if (alignmentKey(key.bookId, snapshot, key.partId) != key || snapshot.document.timedSourceId.isNotBlank()) return Result.success()
            val part = snapshot.source.parts.firstOrNull { it.id == key.partId } ?: return Result.success()
            // A deleted download must not silently turn into an unconstrained streaming request.
            val downloaded = graph.offline.complete(part)
            if (inputData.getBoolean("downloaded", false) && !downloaded) {
                graph.bookAlignment.enqueue(key, false)
                return Result.success()
            }
            val book = graph.library.find(key.bookId)?.book() ?: return Result.success()
            val model = graph.speechModels.modelFor(snapshot.document.language.ifBlank { book.language }) ?: return Result.success()
            if (graph.speechModels.installed(model) == null) {
                val unmetered = applicationContext.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == false
                if (!unmetered) return Result.retry()
                graph.speechModels.install(model) { }
            }
            val duration = part.durationMs.takeIf { it > 0 } ?: graph.narrationSync.duration(part, downloaded)
            if (duration <= 0) return Result.retry()
            graph.mappingRepository.duration(key.bookId, key.sourceId, part.id, duration)
            graph.mappingRepository.persistDuration(key.bookId, key.sourceId, part.id, duration)
            var progress = graph.alignmentJobs.progress(key)
            val deadline = android.os.SystemClock.elapsedRealtime() + 240_000
            repeat(AlignmentPolicy.WINDOWS_PER_RUN) {
                currentCoroutineContext().ensureActive()
                if (!graph.bookAlignment.enabled()) return Result.success()
                if (downloaded && !graph.offline.complete(part)) return Result.retry()
                if (graph.playback.state.value.playing || graph.playback.state.value.buffering) return Result.retry()
                if (android.os.SystemClock.elapsedRealtime() >= deadline) return@repeat
                val start = AlignmentPolicy.next(progress, duration) ?: return Result.success()
                snapshot = graph.mappingRepository.snapshot(key.bookId, key.sourceId) ?: return Result.success()
                if (alignmentKey(key.bookId, snapshot, key.partId) != key) return Result.success()
                val binding = snapshot.bindings.firstOrNull { it.documentId == key.editionId && it.sourceId == key.sourceId && it.partId == key.partId }
                    ?: TextBinding(key.editionId, key.sourceId, key.partId, WHOLE_BOOK)
                val target = SyncTarget(book, snapshot.source, part, start, duration, snapshot.document, binding)
                val result = if (downloaded) graph.narrationSync.offlineWindow(target, start) else graph.narrationSync.window(target, start)
                when (result.outcome) {
                    WindowOutcome.UNSUPPORTED -> return Result.success()
                    WindowOutcome.UNAVAILABLE -> return Result.retry()
                    else -> Unit
                }
                var covered = PairingEvidence.covered(binding, start)
                if (result.anchors.isNotEmpty()) {
                    // FollowAlongStore serializes this read/merge/write against manual adjustments.
                    val merged = graph.followAlong.mergeNarration(key.bookId, binding, result.anchors, duration)
                    covered = PairingEvidence.covered(merged, start)
                }
                progress = AlignmentPolicy.advance(progress, start, duration, covered)
                graph.alignmentJobs.save(progress)
                graph.mappingRepository.snapshot(key.bookId, key.sourceId)?.let { graph.readingSync.refreshPairing(key.bookId, it) }
                setProgress(workDataOf("nextWindowMs" to progress.nextWindowMs, "attempted" to progress.attempted, "matched" to progress.matched))
                delay(1_000)
            }
            if (!progress.complete) graph.bookAlignment.enqueue(key, downloaded, continuation = true)
            return Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return Result.retry() }
    }
}
