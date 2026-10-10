package app.narrio.data

import android.content.SharedPreferences
import androidx.room.withTransaction
import app.narrio.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.util.UUID

/** Shelf `state` values for a book TorBox is getting ready. */
object PreparationStates {
    const val PREPARING = "preparing"
    const val READY = "ready"
    /** TorBox can't fetch the release; another recording is the way forward. */
    const val FAILED = "failed"
    /** Narrio stopped checking (TorBox unreachable, rejected the key, or disconnected); checking again resumes. */
    const val PAUSED = "paused"
    val TRACKED = setOf(PREPARING, READY, FAILED, PAUSED)
}

/** The shelf row's hold on one TorBox item: its id ([ShelfEntry.preparationId]) and the format asked for. */
data class PreparationKey(val torrentId: Long, val format: String)
val ShelfEntry.preparationKey get() = PreparationKey(preparationId, pendingFormat)

/** What earlier checks of one preparation saw, so a lasting problem can be told from a blip. */
@Serializable
data class PreparationWatch(val missingChecks: Int = 0, val missingSinceMs: Long = 0, val stalledSinceMs: Long = 0, val errors: Int = 0)

/**
 * One requested preparation, kept apart from the shelf row's book (which can be a different, played recording):
 * [recording] from TorBox item [torrentId] in [format]. [generation] is new whenever preparation starts again, so
 * earlier checks and notifications can't apply to it; a notification carries it to prove which preparation it means.
 */
@Serializable
data class PreparationRecord(
    val bookId: String, val generation: String, val recording: Audiobook, val torrentId: Long, val format: String,
    val outcome: String = PreparationStates.PREPARING, val problem: String = "", val readySources: List<AudioSource> = emptyList(),
    val watch: PreparationWatch = PreparationWatch(),
    /** False between saving a ready/failed outcome and announcing it, so a stopped process announces it later. */
    val announced: Boolean = true,
) {
    val key get() = PreparationKey(torrentId, format)
    /** The recording with its prepared TorBox audio, once ready. */
    val ready: Audiobook? get() = if (outcome != PreparationStates.READY || readySources.isEmpty()) null
        else recording.copy(cacheState = "cached", cachedFormats = readySources.map { it.format }.distinct(), sources = readySources)
    internal fun change() = PreparationChange(ready ?: recording, outcome == PreparationStates.READY, problem, generation)
}

/** A preparation became ready, or failed; reported once, after it's saved. */
data class PreparationChange(val book: Audiobook, val ready: Boolean, val problem: String = "", val generation: String = "")

/** One check: the latest status, the recording with its TorBox audio once ready, and the change it caused, if any. */
data class PreparationUpdate(val preparation: Preparation, val book: Audiobook? = null, val change: PreparationChange? = null)

/** Background round [check] (counting from 0) after [errorRuns] rounds in a row that couldn't reach TorBox. */
data class PreparationRound(val check: Int = 0, val errorRuns: Int = 0) {
    val delayMinutes: Long? get() = PreparationPolicy.delayMinutes(check, errorRuns)
}

object PreparationPolicy {
    /** Cached releases usually finish within minutes, so early checks come quickly; then every half hour. */
    val rampMinutes = listOf(2L, 5, 10, 15)
    const val STEADY_MINUTES = 30L
    /** Rounds that can't reach TorBox back off from half an hour to this, and stop after [MAX_ERROR_RUNS] in a row. */
    const val MAX_ERROR_MINUTES = 240L
    const val MAX_ERROR_RUNS = 6
    /** A book whose status can't be read this many rounds in a row stops being checked, without holding up the others. */
    const val MAX_BOOK_ERRORS = 6
    /** A TorBox item missing this many checks, over at least [MISSING_MS], was removed from the account. */
    const val MISSING_CHECKS = 3
    const val MISSING_MS = 10 * 60_000L
    /** How long a release may wait without peers, or a queued request without appearing, before it's called failed. */
    const val GIVE_UP_MS = 24 * 60 * 60_000L
    const val FAILED_STATE = "Couldn't get it ready"
    const val PAUSED_STATE = "Stopped checking TorBox"
    const val UNKNOWN_PROBLEM = "TorBox couldn't get this recording."
    const val DISCONNECTED = "TorBox is disconnected. Connect it in Settings to keep checking."
    const val KEY_REJECTED = "TorBox didn't accept your key. Reconnect TorBox in Settings to keep checking."
    const val UNREACHABLE = "Narrio couldn't reach TorBox for a while. Check again to keep going."
    const val UNREADABLE = "Narrio couldn't read this book's status from TorBox. Check again to keep going."

    /** Minutes before round [check]; null stops checking. */
    fun delayMinutes(check: Int, errorRuns: Int): Long? = when {
        errorRuns >= MAX_ERROR_RUNS -> null
        errorRuns > 0 -> minOf(STEADY_MINUTES shl (errorRuns - 1).coerceAtMost(8), MAX_ERROR_MINUTES)
        else -> rampMinutes.getOrElse(check) { STEADY_MINUTES }
    }

    /** Folds a fresh TorBox status into what earlier checks saw; a returned [Preparation.problem] means it failed. */
    fun judge(prep: Preparation, watch: PreparationWatch, now: Long): Pair<Preparation, PreparationWatch> {
        if (prep.ready || prep.failed) return prep to PreparationWatch()
        val missing = if (prep.missing) watch.missingChecks + 1 else 0
        val missingSince = if (!prep.missing) 0 else watch.missingSinceMs.takeIf { it > 0 } ?: now
        val stalledSince = if (!prep.stalled) 0 else watch.stalledSinceMs.takeIf { it > 0 } ?: now
        val problem = when {
            // A known item that keeps not appearing was removed; a queued request (no id yet) gets a day to appear.
            prep.missing && prep.torrentId > 0 && missing >= MISSING_CHECKS && now - missingSince >= MISSING_MS ->
                "This recording is no longer in your TorBox account."
            prep.missing && now - missingSince >= GIVE_UP_MS -> "TorBox never started getting this recording."
            prep.stalled && now - stalledSince >= GIVE_UP_MS -> "No one has shared this recording for a day."
            else -> ""
        }
        val next = PreparationWatch(missing, missingSince, stalledSince)
        return (if (problem.isEmpty()) prep else prep.copy(state = FAILED_STATE, problem = problem)) to next
    }
}

/** TorBox's account, read once per round: each preparation's status and, once ready, its audio. */
interface PreparationAccount {
    fun preparation(book: Audiobook, torrentId: Long): Preparation
    fun sources(book: Audiobook, torrentId: Long): List<AudioSource>
}

/** The shelf rows preparation reads, and the only writes it makes to them. */
interface PreparationShelf {
    suspend fun find(id: String): ShelfEntry?
    suspend fun tracked(): List<ShelfEntry>
    /** Adds [book] if it isn't on the shelf (an existing row keeps its own recording) and holds [torrentId] in [format]. */
    suspend fun begin(book: Audiobook, torrentId: Long, format: String)
    /**
     * Moves [id] to [state] only while it's still in [from] holding [key], in one transaction; [torrentId] replaces a
     * queued request's id. [ready] adds prepared audio, but only to the same recording. False when the row changed or is gone.
     */
    suspend fun commit(id: String, key: PreparationKey, from: String, state: String, torrentId: Long = key.torrentId, ready: Audiobook? = null): Boolean
    /** Ends preparation (the prepared recording is being played) only while the row still holds [key]. */
    suspend fun finish(id: String, key: PreparationKey): Boolean
}

class RoomPreparationShelf(private val database: LibraryDatabase) : PreparationShelf {
    private val dao = database.library()
    override suspend fun find(id: String) = dao.find(id)
    override suspend fun tracked() = dao.observeShelf().first().filter { it.state in PreparationStates.TRACKED }
    override suspend fun begin(book: Audiobook, torrentId: Long, format: String) = database.withTransaction {
        if (dao.find(book.id) == null) dao.save(book)
        dao.preparing(book.id, torrentId, format)
    }
    override suspend fun commit(id: String, key: PreparationKey, from: String, state: String, torrentId: Long, ready: Audiobook?) = database.withTransaction {
        val row = dao.find(id)
        if (row == null || row.state != from || row.preparationKey != key) return@withTransaction false
        val book = row.book()
        if (ready != null && sameRecording(book, ready)) {
            val merged = book.copy(cacheState = "cached", cachedFormats = ready.cachedFormats, sources = (ready.sources + book.sources).distinctBy { it.id })
            dao.metadata(id, NarrioJson.encodeToString(Audiobook.serializer(), merged), merged.sources.any { it.parts.isNotEmpty() })
        }
        if (torrentId != key.torrentId) dao.preparing(id, torrentId, key.format)
        dao.state(id, state)
        true
    }
    override suspend fun finish(id: String, key: PreparationKey) = database.withTransaction {
        val row = dao.find(id)
        if (row == null || row.state !in PreparationStates.TRACKED || row.preparationKey != key) false else { dao.finishPreparation(id); true }
    }
    private fun sameRecording(a: Audiobook, b: Audiobook) = a.torrentHash.isNotBlank() && a.torrentHash.equals(b.torrentHash, true) ||
        a.recordingId.isNotBlank() && a.recordingId == b.recordingId
}

interface PreparationRecords {
    suspend fun get(bookId: String): PreparationRecord?
    suspend fun all(): List<PreparationRecord>
    suspend fun put(record: PreparationRecord)
    suspend fun remove(bookId: String)
}

/** One JSON value per book in its own preferences file, written synchronously; nothing here leaves the phone. */
class PreferencePreparationRecords(private val preferences: SharedPreferences) : PreparationRecords {
    override suspend fun get(bookId: String) = withContext(Dispatchers.IO) { read(bookId) }
    override suspend fun all() = withContext(Dispatchers.IO) { preferences.all.keys.mapNotNull(::read) }
    override suspend fun put(record: PreparationRecord) = withContext(Dispatchers.IO) {
        preferences.edit().putString(record.bookId, NarrioJson.encodeToString(PreparationRecord.serializer(), record)).commit(); Unit
    }
    override suspend fun remove(bookId: String) = withContext(Dispatchers.IO) { preferences.edit().remove(bookId).commit(); Unit }
    private fun read(bookId: String) = preferences.getString(bookId, null)
        ?.let { runCatching { NarrioJson.decodeFromString(PreparationRecord.serializer(), it) }.getOrNull() }
}

/**
 * The one place a book's TorBox preparation is started, checked, and settled, for the book page and the background
 * checks alike. Results are revalidated against the shelf row and saved in one transaction; a change is announced
 * after it's saved, exactly once, even if the check is cancelled or the process stops in between.
 *
 * Identity: a preparation is the TorBox item [ShelfEntry.preparationId] in [ShelfEntry.pendingFormat] (a [PreparationKey]),
 * with its [PreparationRecord] naming the recording and a [PreparationRecord.generation]. Every write rechecks, inside
 * one transaction, that the row still holds that key in the state it was read in; a removed, replaced, or played row
 * is never written. A preparation completes only when a source from that same TorBox item plays: one of its ready
 * sources, or a source whose torrent id is the preparation id (in its format, when one was asked for). A matching
 * format alone, or another recording, never completes it. Notifications carry the generation; [current] must still
 * return it before Listen plays anything.
 *
 * Book pages: [begin] when asking TorBox to get a book ready, [placeholder] then [check] to show status, [played] when a
 * recording starts, [current] to validate a notification, [forget] when the book is removed. Background: [checkAll] and
 * [unreachable]. Account: [pauseAll] on disconnect, [resumeAll] on connect. Never write a tracked row's `state`,
 * `preparationId`, or `pendingFormat` directly; go through these.
 */
class TorBoxPreparations(
    private val shelf: PreparationShelf,
    private val records: PreparationRecords,
    private val account: suspend () -> PreparationAccount,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newGeneration: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = Mutex()
    private val changed = MutableSharedFlow<PreparationChange>(extraBufferCapacity = 16)
    /** Every change, as it's announced; Narrio on screen shows these itself. */
    val changes = changed.asSharedFlow()
    /** Told about each change once it's saved (a notification while Narrio is away). Failures here don't undo it. */
    var announce: (PreparationChange) -> Unit = {}

    /**
     * Holds [book]'s shelf row for TorBox item [preparation] in [format]. Asking again for the same item while it's
     * getting ready or ready keeps its generation; anything else starts a new one with no earlier history.
     */
    suspend fun begin(book: Audiobook, preparation: Preparation, format: String): PreparationRecord = lock.withLock {
        withContext(NonCancellable) {
            val row = shelf.find(book.id)
            val key = PreparationKey(preparation.torrentId, format)
            val previous = records.get(book.id)?.takeIf { row != null && row.preparationKey == key && it.key == key &&
                row.state in setOf(PreparationStates.PREPARING, PreparationStates.READY) }
            val record = previous ?: PreparationRecord(book.id, newGeneration(), book, key.torrentId, format)
            records.put(record)
            if (previous == null || row?.state != PreparationStates.READY) shelf.begin(book, key.torrentId, format)
            record
        }
    }

    /** What the book page shows for [entry] until a check returns; null when nothing is being prepared. */
    suspend fun placeholder(entry: ShelfEntry): Preparation? {
        val problem = records.get(entry.bookId)?.takeIf { it.key == entry.preparationKey }?.problem.orEmpty()
        return when (entry.state) {
            PreparationStates.PREPARING -> Preparation(entry.preparationId, false, 0f, "Getting ready in TorBox")
            PreparationStates.READY -> Preparation(entry.preparationId, true, 0f, "Ready to listen")
            PreparationStates.FAILED -> Preparation(entry.preparationId, false, 0f, PreparationPolicy.FAILED_STATE,
                problem = problem.ifBlank { PreparationPolicy.UNKNOWN_PROBLEM })
            PreparationStates.PAUSED -> Preparation(entry.preparationId, false, 0f, PreparationPolicy.PAUSED_STATE,
                problem = problem.ifBlank { PreparationPolicy.UNREACHABLE }, paused = true)
            else -> null
        }
    }

    /** [bookId]'s preparation while its shelf row still holds it; a notification's generation must match this. */
    suspend fun current(bookId: String): PreparationRecord? {
        val row = shelf.find(bookId)?.takeIf { it.state in PreparationStates.TRACKED } ?: return null
        return records.get(bookId)?.takeIf { it.key == row.preparationKey }
    }

    /** Checks [bookId] now (the book page, including Check again); [shown] is the recording the page shows. Null when nothing is being prepared. */
    suspend fun check(bookId: String, shown: Audiobook? = null): PreparationUpdate? {
        if (shelf.find(bookId)?.state !in PreparationStates.TRACKED) return null
        val snapshot = account()
        return lock.withLock {
            val row = shelf.find(bookId)?.takeIf { it.state in PreparationStates.TRACKED } ?: return@withLock null
            examine(row, snapshot, shown)
        }
    }

    /**
     * One background round over every book getting ready, with a single account listing. Returns the next round, or
     * null when nothing is left to check; a disconnected account or rejected key pauses them, saying why.
     */
    suspend fun checkAll(connected: Boolean, round: PreparationRound): PreparationRound? {
        deliverPending()
        if (round.errorRuns >= PreparationPolicy.MAX_ERROR_RUNS) { pauseAll(PreparationPolicy.UNREACHABLE); return null }
        if (shelf.tracked().none { it.state == PreparationStates.PREPARING }) return null
        if (!connected) { pauseAll(PreparationPolicy.DISCONNECTED); return null }
        val snapshot = try { account() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: ProviderAuthorizationException) { pauseAll(PreparationPolicy.KEY_REJECTED); return null }
            catch (_: Exception) { return unreachable(round) }
        for (id in shelf.tracked().filter { it.state == PreparationStates.PREPARING }.map { it.bookId }) {
            try { lock.withLock { shelf.find(id)?.takeIf { it.state == PreparationStates.PREPARING }?.let { examine(it, snapshot, null) } } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { unreadable(id) }
        }
        return if (shelf.tracked().none { it.state == PreparationStates.PREPARING }) null else PreparationRound(round.check + 1)
    }

    /** A round that couldn't reach TorBox backs off; after too many in a row, checking stops and the shelf says so. */
    suspend fun unreachable(round: PreparationRound): PreparationRound? {
        val next = PreparationRound(round.check + 1, round.errorRuns + 1)
        if (next.delayMinutes != null) return next
        pauseAll(PreparationPolicy.UNREACHABLE); return null
    }

    /** Stops checking everything getting ready, keeping [problem] for the shelf and book page. */
    suspend fun pauseAll(problem: String) = lock.withLock {
        for (row in shelf.tracked().filter { it.state == PreparationStates.PREPARING })
            settle(row, recordFor(row, null).copy(outcome = PreparationStates.PAUSED, problem = problem), PreparationStates.PAUSED, null)
    }

    /** Checks paused preparations again, such as after reconnecting TorBox. */
    suspend fun resumeAll() = lock.withLock {
        for (row in shelf.tracked().filter { it.state == PreparationStates.PAUSED })
            settle(row, recordFor(row, null).let { it.copy(outcome = PreparationStates.PREPARING, problem = "", watch = it.watch.copy(errors = 0)) }, PreparationStates.PREPARING, null)
    }

    /** Ends the preparation when [source] is its recording; playing another recording leaves it pending. */
    suspend fun played(bookId: String, source: AudioSource): Boolean = lock.withLock {
        val row = shelf.find(bookId)?.takeIf { it.state in PreparationStates.TRACKED } ?: return@withLock false
        val record = records.get(bookId)?.takeIf { it.key == row.preparationKey }
        val ours = record?.readySources?.any { it.id == source.id } == true || row.preparationId > 0 && source.torrentId == row.preparationId &&
            (row.pendingFormat.isBlank() || row.pendingFormat == source.format)
        if (!ours) return@withLock false
        withContext(NonCancellable) { shelf.finish(bookId, row.preparationKey).also { if (it) records.remove(bookId) } }
    }

    /** Forgets [bookId]'s preparation when the book leaves the shelf. */
    suspend fun forget(bookId: String) = lock.withLock { withContext(NonCancellable) { records.remove(bookId) } }

    /** Announces outcomes saved before a stopped process could announce them; superseded ones are dropped. */
    suspend fun deliverPending() = lock.withLock {
        withContext(NonCancellable) {
            for (record in records.all().filter { !it.announced }) {
                val row = shelf.find(record.bookId)
                when {
                    row == null || row.state !in PreparationStates.TRACKED -> records.remove(record.bookId)
                    row.preparationKey != record.key || row.state != record.outcome -> records.put(record.copy(announced = true))
                    else -> { report(record.change()); records.put(record.copy(announced = true)) }
                }
            }
        }
    }

    /** The row's record when it still describes the same TorBox item, else a new generation for it. */
    private suspend fun recordFor(row: ShelfEntry, shown: Audiobook?): PreparationRecord =
        records.get(row.bookId)?.takeIf { it.key == row.preparationKey }
            ?: PreparationRecord(row.bookId, newGeneration(), shown?.takeIf { it.id == row.bookId } ?: row.book(), row.preparationId, row.pendingFormat, outcome = row.state)

    private suspend fun examine(row: ShelfEntry, account: PreparationAccount, shown: Audiobook?): PreparationUpdate {
        val record = recordFor(row, shown)
        val (prep, watch) = PreparationPolicy.judge(account.preparation(record.recording, record.torrentId), record.watch, clock())
        val torrentId = if (record.torrentId == 0L && prep.torrentId > 0) prep.torrentId else record.torrentId
        return when {
            prep.ready -> {
                val found = account.sources(record.recording, prep.torrentId)
                val sources = if (record.recording.provider == "archive") record.recording.sources else found
                val next = record.copy(torrentId = torrentId, outcome = PreparationStates.READY, problem = "", readySources = sources, watch = watch)
                val change = next.change().takeIf { row.state != PreparationStates.READY }
                if (!settle(row, next, PreparationStates.READY, change, next.ready)) PreparationUpdate(prep) else PreparationUpdate(prep, next.ready, change)
            }
            // A ready row stays ready; the page still shows TorBox's latest status.
            row.state == PreparationStates.READY -> PreparationUpdate(prep)
            prep.failed -> {
                val next = record.copy(torrentId = torrentId, outcome = PreparationStates.FAILED, problem = prep.problem, watch = watch)
                val change = next.change().takeIf { row.state != PreparationStates.FAILED }
                val saved = settle(row, next, PreparationStates.FAILED, change)
                PreparationUpdate(prep, null, change.takeIf { saved })
            }
            else -> {
                // Still getting ready: a failed or paused one TorBox has picked up again is back to preparing.
                settle(row, record.copy(torrentId = torrentId, outcome = PreparationStates.PREPARING, problem = "", watch = watch), PreparationStates.PREPARING, null)
                PreparationUpdate(prep)
            }
        }
    }

    /** A book whose status couldn't be read; after too many rounds it's paused so the others carry on. */
    private suspend fun unreadable(bookId: String) = lock.withLock {
        val row = shelf.find(bookId)?.takeIf { it.state == PreparationStates.PREPARING } ?: return@withLock
        val record = recordFor(row, null).let { it.copy(watch = it.watch.copy(errors = it.watch.errors + 1)) }
        if (record.watch.errors < PreparationPolicy.MAX_BOOK_ERRORS) withContext(NonCancellable) { records.put(record) }
        else settle(row, record.copy(outcome = PreparationStates.PAUSED, problem = PreparationPolicy.UNREADABLE), PreparationStates.PAUSED, null)
    }

    /**
     * Saves [next] and moves the row to [state] (only if it's unchanged since [row] was read), then announces
     * [change]. Runs to completion even if the check is cancelled. False when the result was stale and dropped.
     */
    private suspend fun settle(row: ShelfEntry, next: PreparationRecord, state: String, change: PreparationChange?, ready: Audiobook? = null): Boolean =
        withContext(NonCancellable) {
            // Nothing for the shelf to change: only what this check saw is kept.
            if (change == null && ready == null && row.state == state && next.torrentId == row.preparationId) { records.put(next); return@withContext true }
            records.put(next.copy(announced = change == null))
            if (!shelf.commit(row.bookId, row.preparationKey, row.state, state, next.torrentId, ready)) {
                // Removed, or a newer preparation or listening replaced it: nothing to save or announce.
                if (shelf.find(row.bookId)?.state !in PreparationStates.TRACKED) records.remove(row.bookId)
                else records.put(next.copy(announced = true))
                return@withContext false
            }
            if (change != null) { report(change); records.put(next.copy(announced = true)) }
            true
        }

    private fun report(change: PreparationChange) {
        runCatching { announce(change) }
        changed.tryEmit(change)
    }
}

/** How background checks follow the set of books getting ready (each "book:preparation"). */
enum class PreparationSchedule {
    /** Keep pending checks, or start them if there are none. */
    KEEP,
    /** A new preparation began: check soon again, from the first quick round. */
    RESTART,
    CANCEL, NONE;

    companion object {
        fun plan(previous: Set<String>?, current: Set<String>): PreparationSchedule = when {
            current.isEmpty() -> if (previous?.isEmpty() == true) NONE else CANCEL
            previous == null -> KEEP
            (current - previous).isNotEmpty() -> RESTART
            else -> NONE
        }
    }
}
