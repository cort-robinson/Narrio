package app.narrio.data

import android.content.SharedPreferences
import app.narrio.domain.Audiobook
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/*
 * A shelf row keeps one book JSON, so the recording that owns the played audio is kept here, keyed by book id, without
 * file lists or descriptions; the recording being got ready is [PreparationRecord]'s. No Room schema change.
 */

/** The recording a book's played audio belongs to, and every audio source each of its recordings has played. */
@Serializable
data class PlayedRecordings(val current: Audiobook, val sources: Map<String, List<String>> = emptyMap())

/** The ids that identify one recording, whichever id it carries: its provider id and its release hash. */
fun recordingKeys(recording: Audiobook): Set<String> =
    setOf(recording.recordingId.ifBlank { recording.id }, recording.torrentHash.lowercase()).filter(String::isNotBlank).toSet()

private fun compact(recording: Audiobook) = recording.copy(sources = emptyList(), description = "")

/** One JSON value per book under [prefix] in [preferences], observable as a map. */
private class BookValues<T : Any>(private val preferences: SharedPreferences, private val prefix: String, private val decode: (String) -> T, private val encode: (T) -> String) {
    private val state = MutableStateFlow(preferences.all.mapNotNull { (key, value) ->
        if (!key.startsWith(prefix) || value !is String) null else runCatching { decode(value) }.getOrNull()?.let { key.removePrefix(prefix) to it }
    }.toMap())
    val all: StateFlow<Map<String, T>> = state.asStateFlow()
    operator fun get(bookId: String): T? = state.value[bookId]
    fun put(bookId: String, value: T) {
        if (state.value[bookId] == value) return
        preferences.edit().putString(prefix + bookId, encode(value)).apply()
        state.update { it + (bookId to value) }
    }
    fun remove(bookId: String) {
        if (bookId !in state.value) return
        preferences.edit().remove(prefix + bookId).apply()
        state.update { it - bookId }
    }
}

/** The recording each book is listened to from, with the audio each of its recordings played. */
class ListeningRecordings(preferences: SharedPreferences) {
    private val values = BookValues(preferences, "played:", { NarrioJson.decodeFromString<PlayedRecordings>(it) }, { NarrioJson.encodeToString(it) })
    /** Book id to the recording its played audio belongs to, with that book's played sources. */
    val all: StateFlow<Map<String, PlayedRecordings>> = values.all

    init {
        // Version-1 values held only the recording, under "listening:".
        preferences.all.filterKeys { it.startsWith(LEGACY) }.forEach { (key, value) ->
            val recording = (value as? String)?.let { runCatching { NarrioJson.decodeFromString<Audiobook>(it) }.getOrNull() }
            if (recording != null && values[key.removePrefix(LEGACY)] == null) values.put(key.removePrefix(LEGACY), PlayedRecordings(recording))
            preferences.edit().remove(key).apply()
        }
    }

    operator fun get(bookId: String): Audiobook? = values[bookId]?.current

    /** [recording] (carrying its book's id) played [sourceId]; it becomes the book's recording. */
    fun played(recording: Audiobook, sourceId: String) {
        val previous = values[recording.id]
        val key = recordingKeys(recording).first()
        val sources = previous?.sources.orEmpty().let { it + (key to (listOf(sourceId) + it[key].orEmpty()).distinct().take(8)) }
        values.put(recording.id, PlayedRecordings(compact(recording), sources))
    }

    /** Remembers [recording] as the book's without new audio, for shelves saved before this was kept. */
    fun adoptExisting(recording: Audiobook) { if (values[recording.id] == null) values.put(recording.id, PlayedRecordings(compact(recording))) }

    /** Audio sources [recording] has played for [bookId], most recent first. */
    fun playedSources(bookId: String, recording: Audiobook): List<String> {
        val keys = recordingKeys(recording)
        return values[bookId]?.sources.orEmpty().filterKeys { it in keys }.values.flatten()
    }

    /** A recording saved under its own id is now [bookId]'s: its history moves with it. */
    fun move(fromId: String, bookId: String) {
        val old = values[fromId] ?: return
        values.remove(fromId)
        val target = values[bookId]
        values.put(bookId, PlayedRecordings(target?.current ?: old.current.copy(id = bookId, recordingId = old.current.recordingId.ifBlank { fromId }),
            old.sources + target?.sources.orEmpty()))
    }

    fun remove(bookId: String) = values.remove(bookId)

    /** Ids a search result of the listener's recording can carry, so ranking keeps it first. */
    fun keys(bookId: String): Set<String> = get(bookId)?.let(::recordingKeys).orEmpty()

    private companion object { const val LEGACY = "listening:" }
}
