package app.narrio.data

import androidx.room.withTransaction
import app.narrio.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import java.util.concurrent.ConcurrentHashMap

/** Multi-edition mapping reads persisted recordings and only the active edition's bindings. */
class RoomPositionMappingRepository(private val database: LibraryDatabase, private val text: FollowAlongStore) : PositionMappingRepository {
    private val library get() = database.library()
    private val durations = ConcurrentHashMap<Triple<String, String, String>, Long>()
    suspend fun register(bookId: String, source: AudioSource) {
        val book = library.find(bookId)?.book() ?: return
        val previous = book.sources.firstOrNull { it.id == source.id }
        val known = source.withKnownDurations(previous)
        if (previous != known)
            library.save(book.copy(sources = listOf(known) + book.sources.filterNot { it.id == source.id }))
    }
    fun duration(bookId: String, sourceId: String, partId: String, durationMs: Long): Boolean =
        durationMs > 0 && durations.put(Triple(bookId, sourceId, partId), durationMs) != durationMs
    suspend fun persistDuration(bookId: String, sourceId: String, partId: String, durationMs: Long) =
        library.recordingDuration(bookId, sourceId, partId, durationMs)
    override suspend fun snapshot(bookId: String, sourceId: String): MappingSnapshot? {
        val entry = library.bookText(bookId) ?: return null
        val document = try { text.load(entry) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return null }
        return database.withTransaction {
            if (library.bookText(bookId)?.documentId != document.id) return@withTransaction null
            val shelf = library.find(bookId) ?: return@withTransaction null
            val source = library.position(bookId, sourceId)?.let { NarrioJson.decodeFromString<AudioSource>(it.sourceJson) }
                ?: shelf.source()?.takeIf { it.id == sourceId } ?: shelf.book().sources.firstOrNull { it.id == sourceId }
                ?: return@withTransaction null
            val effective = source.copy(parts = source.parts.map { part -> part.copy(durationMs = durations[Triple(bookId, sourceId, part.id)]
                ?: part.durationMs.takeIf { it > 0 } ?: 0) })
            MappingSnapshot(document, effective, library.editionBindings(bookId, document.id).map { it.binding() })
        }
    }
}

/** Full-library resumability and pairing evidence; fingerprints/versions invalidate old attempts. */
class RoomAlignmentJobs(private val database: LibraryDatabase) : AlignmentJobRepository {
    private val library get() = database.library()
    override suspend fun progress(key: AlignmentKey): AlignmentProgress {
        val row = library.alignmentJob(key.bookId, key.editionId, key.sourceId, key.sourceId, key.partId)
            ?: return AlignmentProgress(key)
        if (row.textFingerprint != key.editionId || row.audioFingerprint != key.layoutFingerprint ||
            row.normalizationVersion != key.normalizationVersion || row.alignmentVersion != key.algorithmVersion)
            return AlignmentProgress(key)
        return AlignmentProgress(key, row.resumePositionMs, row.attempted, row.matched, row.firstAttemptMs,
            row.lastAttemptMs, row.status == "complete")
    }
    override suspend fun save(progress: AlignmentProgress) = database.withTransaction {
        val key = progress.key
        // Deleted editions must not be resurrected by work already in flight.
        if (library.edition(key.bookId, key.editionId) == null) return@withTransaction
        library.putAlignmentJob(AlignmentJobEntry(key.bookId, key.editionId, key.sourceId, key.sourceId, key.partId,
            status = if (progress.complete) "complete" else "running",
            progress = if (progress.complete) 1.0 else 0.0,
            textFingerprint = key.editionId, audioFingerprint = key.layoutFingerprint,
            normalizationVersion = key.normalizationVersion, alignmentVersion = key.algorithmVersion,
            resumePositionMs = progress.nextWindowMs, updatedAtMs = System.currentTimeMillis(),
            attempted = progress.attempted, matched = progress.matched,
            firstAttemptMs = progress.firstAttemptMs, lastAttemptMs = progress.lastAttemptMs))
    }
}

fun alignmentKey(bookId: String, snapshot: MappingSnapshot, partId: String): AlignmentKey = AlignmentKey(bookId,
    snapshot.document.id, snapshot.source.id, partId, snapshot.document.normalizationVersion,
    BookTextParser.fingerprint(NarrioJson.encodeToString(snapshot.source.copy(parts = snapshot.source.parts.map {
        // Discovered duration is a cache, not recording identity; links are never persisted here.
        it.copy(durationMs = 0, archiveUrl = "")
    }, textFiles = emptyList())).toByteArray()))
