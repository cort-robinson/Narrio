package app.narrio.data

import android.content.Context
import android.util.AtomicFile
import app.narrio.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** TEMPORARY: replace with A's SharedPositionStore. Private atomic files, no Room schema/migration. */
class TemporaryFileSharedPositions(context: Context) : GuardedSharedPositionStore {
    private val root = File(context.filesDir, "temporary-reader-positions")
    private val mutex = Mutex()
    private val changes = MutableStateFlow(0L)
    private fun file(bookId: String) = File(root, BookTextParser.fingerprint(bookId.toByteArray()) + ".json")
    private suspend fun read(bookId: String): SharedPosition? = withContext(Dispatchers.IO) {
        val atomic = AtomicFile(file(bookId))
        if (!atomic.baseFile.exists()) null else NarrioJson.decodeFromString<SharedPosition>(atomic.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
    }
    override fun observe(bookId: String): Flow<SharedPosition?> = changes.map { current(bookId) }.distinctUntilChanged()
    override suspend fun current(bookId: String): SharedPosition? = mutex.withLock { read(bookId) }
    override suspend fun commit(update: PositionUpdate): SharedPosition? = commitWhen(update) { true }
    override suspend fun commitWhen(update: PositionUpdate, allowed: () -> Boolean): SharedPosition? = mutex.withLock {
        val previous = read(update.bookId)
        if ((previous?.sequence ?: 0L) != update.basedOnSequence) return@withLock null
        val value = SharedPosition(update.bookId, update.origin, update.text, update.audio, update.textConfidence,
            update.audioConfidence, update.basedOnSequence + 1, System.currentTimeMillis())
        val saved = withContext(Dispatchers.IO) {
            if (!allowed()) false else {
                writeSyncFile(file(update.bookId), NarrioJson.encodeToString(value)); true
            }
        }
        if (!saved) return@withLock null
        changes.value++
        value
    }
}

/** TEMPORARY: A owns durable per-(edition, recording, part) alignment progress in Room. */
class TemporaryFileAlignmentJobs(context: Context) : AlignmentJobRepository {
    private val file = File(context.filesDir, "temporary-reader-alignment.json")
    private val mutex = Mutex()
    private suspend fun read(): List<AlignmentProgress> = withContext(Dispatchers.IO) {
        val atomic = AtomicFile(file)
        if (!atomic.baseFile.exists()) emptyList() else NarrioJson.decodeFromString<List<AlignmentProgress>>(
            atomic.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
    }
    override suspend fun progress(key: AlignmentKey) = mutex.withLock { read().firstOrNull { it.key == key } ?: AlignmentProgress(key) }
    override suspend fun save(progress: AlignmentProgress) = mutex.withLock {
        // Aggregate counts/cursor only, no transcripts, PCM, or growing per-window logs.
        val values = (read().filterNot { it.key == progress.key } + progress).takeLast(512)
        withContext(Dispatchers.IO) { writeSyncFile(file, NarrioJson.encodeToString(values)) }
    }
}

private fun writeSyncFile(file: File, json: String) {
    file.parentFile?.mkdirs()
    val atomic = AtomicFile(file)
    val stream = atomic.startWrite()
    try { stream.write(json.toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
    catch (error: Exception) { atomic.failWrite(stream); throw error }
}

/** TEMPORARY single-active-edition adapter. Replace reads with A's multi-edition repository. */
class TemporaryLegacyMappingRepository(private val library: LibraryDao, private val text: FollowAlongStore) : PositionMappingRepository {
    private val sources = ConcurrentHashMap<Pair<String, String>, AudioSource>()
    private val durations = ConcurrentHashMap<String, Long>()
    fun register(bookId: String, source: AudioSource) { sources[bookId to source.id] = source }
    fun duration(partId: String, durationMs: Long) { if (durationMs > 0) durations[partId] = durationMs }
    override suspend fun snapshot(bookId: String, sourceId: String): MappingSnapshot? {
        val entry = library.bookText(bookId) ?: return null
        val document = try { text.load(entry) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return null }
        if (library.bookText(bookId)?.documentId != document.id) return null
        val shelf = library.find(bookId)
        val source = sources[bookId to sourceId] ?: library.position(bookId, sourceId)?.let { NarrioJson.decodeFromString<AudioSource>(it.sourceJson) }
            ?: shelf?.source()?.takeIf { it.id == sourceId } ?: shelf?.book()?.sources?.firstOrNull { it.id == sourceId } ?: return null
        val effective = source.copy(parts = source.parts.map { part -> part.copy(durationMs = durations[part.id] ?: part.durationMs.takeIf { it > 0 }
            ?: shelf?.book()?.durationMs?.takeIf { source.parts.size == 1 } ?: 0) })
        return MappingSnapshot(document, effective, library.observeTextBindings(bookId).first().map { it.binding() })
    }
}

fun alignmentKey(bookId: String, snapshot: MappingSnapshot, partId: String): AlignmentKey = AlignmentKey(bookId,
    snapshot.document.id, snapshot.source.id, partId, snapshot.document.normalizationVersion,
    BookTextParser.fingerprint(NarrioJson.encodeToString(snapshot.source.copy(parts = snapshot.source.parts.map {
        // Discovered duration is a cache, not recording identity; links are never persisted here.
        it.copy(durationMs = 0, archiveUrl = "")
    }, textFiles = emptyList())).toByteArray()))
