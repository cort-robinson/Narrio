package app.narrio.data

import android.content.SharedPreferences
import app.narrio.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** What earlier checks saw of one book while TorBox gets it ready, so a lasting problem can be told from a blip. */
@Serializable
data class PreparationWatch(val missingChecks: Int = 0, val missingSinceMs: Long = 0, val stalledSinceMs: Long = 0, val problem: String = "")

/** A book that was getting ready became ready, or couldn't; reported once, by whichever check saw it first. */
data class PreparationChange(val book: Audiobook, val ready: Boolean, val problem: String = "")

/** One check: the latest status, the book with its TorBox audio once ready, and the change it caused, if any. */
data class PreparationUpdate(val preparation: Preparation, val book: Audiobook? = null, val change: PreparationChange? = null)

/** Background check [check] (counting from 0) after [errorRuns] runs in a row that couldn't reach TorBox. */
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
    /** A TorBox item missing this many checks, over at least [MISSING_MS], was removed from the account. */
    const val MISSING_CHECKS = 3
    const val MISSING_MS = 10 * 60_000L
    /** How long a release may wait without peers, or a queued request without appearing, before it's called failed. */
    const val GIVE_UP_MS = 24 * 60 * 60_000L
    const val FAILED_STATE = "Couldn't get it ready"
    const val UNKNOWN_PROBLEM = "TorBox couldn't fetch this release."

    /** Minutes before [check]; null stops checking until preparation starts again or Narrio opens. */
    fun delayMinutes(check: Int, errorRuns: Int): Long? = when {
        errorRuns >= MAX_ERROR_RUNS -> null
        errorRuns > 0 -> minOf(STEADY_MINUTES shl (errorRuns - 1).coerceAtMost(8), MAX_ERROR_MINUTES)
        else -> rampMinutes.getOrElse(check) { STEADY_MINUTES }
    }

    /** Folds a fresh TorBox status into what earlier checks saw; a returned [Preparation.problem] means it failed. */
    fun judge(prep: Preparation, watch: PreparationWatch, now: Long): Pair<Preparation, PreparationWatch> {
        if (prep.ready) return prep to PreparationWatch()
        if (prep.failed) return prep to PreparationWatch(problem = prep.problem)
        val missing = if (prep.missing) watch.missingChecks + 1 else 0
        val missingSince = if (!prep.missing) 0 else watch.missingSinceMs.takeIf { it > 0 } ?: now
        val stalledSince = if (!prep.stalled) 0 else watch.stalledSinceMs.takeIf { it > 0 } ?: now
        val problem = when {
            // A known item that keeps not appearing was removed; a queued request (no id yet) gets a day to appear.
            prep.missing && prep.torrentId > 0 && missing >= MISSING_CHECKS && now - missingSince >= MISSING_MS ->
                "This release is no longer in your TorBox account."
            prep.missing && now - missingSince >= GIVE_UP_MS -> "TorBox never started this release."
            prep.stalled && now - stalledSince >= GIVE_UP_MS -> "No one has shared this release for a day."
            else -> ""
        }
        val next = PreparationWatch(missing, missingSince, stalledSince, problem)
        return (if (problem.isEmpty()) prep else prep.copy(state = FAILED_STATE, problem = problem)) to next
    }
}

/** The shelf rows preparation reads and writes. */
interface PreparationShelf {
    suspend fun find(id: String): ShelfEntry?
    suspend fun preparing(): List<ShelfEntry>
    suspend fun state(id: String, state: String)
    suspend fun save(book: Audiobook)
}

class RoomPreparationShelf(private val dao: LibraryDao) : PreparationShelf {
    override suspend fun find(id: String) = dao.find(id)
    override suspend fun preparing() = dao.observeShelf().first().filter { it.state == "preparing" }
    override suspend fun state(id: String, state: String) = dao.state(id, state)
    override suspend fun save(book: Audiobook) = dao.save(book)
}

interface PreparationHistory {
    fun get(id: String): PreparationWatch
    fun put(id: String, watch: PreparationWatch)
    fun remove(id: String)
}

/** One small JSON value per book in its own preferences file; nothing here leaves the phone. */
class PreferencePreparationHistory(private val preferences: SharedPreferences) : PreparationHistory {
    override fun get(id: String) = preferences.getString(id, null)
        ?.let { runCatching { NarrioJson.decodeFromString(PreparationWatch.serializer(), it) }.getOrNull() } ?: PreparationWatch()
    override fun put(id: String, watch: PreparationWatch) {
        if (watch == PreparationWatch()) remove(id) else preferences.edit().putString(id, NarrioJson.encodeToString(PreparationWatch.serializer(), watch)).apply()
    }
    override fun remove(id: String) { preferences.edit().remove(id).apply() }
}

/**
 * The one place a book's TorBox preparation is checked and its shelf state changed, for the book page and the
 * background checks alike. Checks run one at a time, so a change is saved and [changes] reports it exactly once.
 */
class TorBoxPreparations(
    private val shelf: PreparationShelf,
    private val history: PreparationHistory,
    private val refresh: suspend (Audiobook, Long) -> Preparation,
    private val sources: suspend (Audiobook, Long) -> List<AudioSource>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private val changed = MutableSharedFlow<PreparationChange>(extraBufferCapacity = 16)
    val changes = changed.asSharedFlow()
    /** Told about each change before it's saved, so a check cancelled afterwards can't lose it. */
    var announce: (PreparationChange) -> Unit = {}

    /** What the book page shows for [entry] until the first check returns; null when nothing is being prepared. */
    fun placeholder(entry: ShelfEntry): Preparation? = when (entry.state) {
        "preparing" -> Preparation(entry.preparationId, false, 0f, "Preparing in TorBox")
        "ready" -> Preparation(entry.preparationId, true, 0f, "Ready to listen")
        "failed" -> Preparation(entry.preparationId, false, 0f, PreparationPolicy.FAILED_STATE,
            problem = history.get(entry.bookId).problem.ifBlank { PreparationPolicy.UNKNOWN_PROBLEM })
        else -> null
    }

    suspend fun check(book: Audiobook, saved: ShelfEntry): PreparationUpdate = lock.withLock {
        val entry = shelf.find(book.id) ?: saved
        val (prep, watch) = PreparationPolicy.judge(refresh(book, entry.preparationId), history.get(book.id), clock())
        if (prep.ready) {
            val found = sources(book, prep.torrentId)
            val updated = book.copy(cacheState = "cached", cachedFormats = found.map { it.format }, sources = if (book.provider == "archive") book.sources else found)
            val change = PreparationChange(updated, true).takeIf { entry.state == "preparing" || entry.state == "failed" }
            change?.let(::report)
            shelf.state(book.id, "ready"); shelf.save(updated); history.remove(book.id)
            return@withLock PreparationUpdate(prep, updated, change)
        }
        val change = PreparationChange(book, false, prep.problem).takeIf { prep.failed && entry.state == "preparing" }
        change?.let(::report)
        history.put(book.id, watch)
        // A failed release TorBox has since picked up again (a retry in its dashboard) is getting ready once more.
        if (change != null) shelf.state(book.id, "failed") else if (!prep.failed && entry.state == "failed") shelf.state(book.id, "preparing")
        PreparationUpdate(prep, null, change)
    }

    /** Checks every book getting ready; [connected] is false when TorBox has been disconnected. Returns the next round, or null to stop. */
    suspend fun backgroundCheck(connected: Boolean, round: PreparationRound): PreparationRound? {
        if (!connected) return null
        val waiting = shelf.preparing()
        var unreachable = 0
        for (entry in waiting) {
            try { check(entry.book(), entry) }
            catch (cancelled: CancellationException) { throw cancelled }
            // A rejected key won't work on the next try either; reconnecting starts checks again.
            catch (_: ProviderAuthorizationException) { return null }
            catch (_: Exception) { unreachable++ }
        }
        if (shelf.preparing().isEmpty()) return null
        val next = PreparationRound(round.check + 1, if (waiting.isNotEmpty() && unreachable == waiting.size) round.errorRuns + 1 else 0)
        return next.takeIf { it.delayMinutes != null }
    }

    private fun report(change: PreparationChange) { announce(change); changed.tryEmit(change) }
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
