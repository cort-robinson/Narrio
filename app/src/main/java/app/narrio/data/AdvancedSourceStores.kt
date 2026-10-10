package app.narrio.data

import android.content.SharedPreferences
import app.narrio.domain.Audiobook
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/*
 * Listener choices made in Advanced sourcing. They live in the app's private preferences, not Room, are excluded
 * from backup with the rest of Narrio's data, and are removed with the book (see [AdvancedSourceStores.forget]).
 */

/** The few string values these stores need; SharedPreferences on the phone, a map in tests. */
interface TextValues {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

class PreferenceValues(private val preferences: SharedPreferences) : TextValues {
    override fun get(key: String): String? = preferences.getString(key, null)
    override fun put(key: String, value: String?) { preferences.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply() }
}

class MemoryValues : TextValues {
    private val values = mutableMapOf<String, String>()
    override fun get(key: String) = values[key]
    override fun put(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
}

/** A release the listener said isn't this book. [key] is its torrent hash, else its recording ID. */
@Serializable
data class HiddenRelease(val key: String, val title: String, val provider: String = "", val hiddenAtMs: Long = 0)

/** "Not this book": releases kept out of a book's best match, versions, and source sections until unhidden. */
class HiddenReleaseStore(private val values: TextValues, private val now: () -> Long = System::currentTimeMillis) {
    private val state = MutableStateFlow(read())
    val hidden: StateFlow<Map<String, List<HiddenRelease>>> = state.asStateFlow()

    fun keys(bookId: String): Set<String> = state.value[bookId].orEmpty().map { it.key }.toSet()

    fun hide(bookId: String, recording: Audiobook, provider: String = "") = update { all ->
        val key = HiddenReleases.key(recording)
        val entry = HiddenRelease(key, recording.releaseTitle.ifBlank { recording.title }, provider, now())
        all + (bookId to (all[bookId].orEmpty().filter { it.key != key } + entry))
    }

    fun unhide(bookId: String, key: String) = update { all ->
        val left = all[bookId].orEmpty().filter { it.key != key }
        if (left.isEmpty()) all - bookId else all + (bookId to left)
    }

    fun forget(bookId: String) = update { it - bookId }

    private fun update(change: (Map<String, List<HiddenRelease>>) -> Map<String, List<HiddenRelease>>) = synchronized(this) {
        val next = change(state.value)
        if (next == state.value) return@synchronized
        values.put(KEY, if (next.isEmpty()) null else NarrioJson.encodeToString(next))
        state.value = next
    }

    private fun read(): Map<String, List<HiddenRelease>> = runCatching {
        values.get(KEY)?.let { NarrioJson.decodeFromString<Map<String, List<HiddenRelease>>>(it) }
    }.getOrNull().orEmpty()

    private companion object { const val KEY = "hiddenReleases.v1" }
}

/** The last words the listener searched sources with, per book. */
class SourceWordsStore(private val values: TextValues) {
    fun get(bookId: String): String? = values.get(KEY + bookId)
    fun set(bookId: String, words: String?) = values.put(KEY + bookId, SourceWords.clean(words))
    fun forget(bookId: String) = values.put(KEY + bookId, null)
    private companion object { const val KEY = "sourceWords.v1:" }
}

/**
 * Files chosen inside a release, in listening order, per book, release, and audio format. File paths identify them,
 * because the same file has a different part ID before and after TorBox prepares a release.
 */
class FileSelectionStore(private val values: TextValues) {
    private val state = MutableStateFlow(read())
    val selections: StateFlow<Map<String, List<String>>> = state.asStateFlow()

    fun get(key: String): List<String>? = state.value[key]

    fun set(key: String, names: List<String>?) = synchronized(this) {
        val next = if (names.isNullOrEmpty()) state.value - key else state.value + (key to names.distinct())
        if (next == state.value) return@synchronized
        values.put(KEY, if (next.isEmpty()) null else NarrioJson.encodeToString(next))
        state.value = next
    }

    fun forget(bookId: String) = synchronized(this) {
        val next = state.value.filterKeys { !it.startsWith("$bookId|") }
        if (next == state.value) return@synchronized
        values.put(KEY, if (next.isEmpty()) null else NarrioJson.encodeToString(next))
        state.value = next
    }

    private fun read(): Map<String, List<String>> = runCatching {
        values.get(KEY)?.let { NarrioJson.decodeFromString<Map<String, List<String>>>(it) }
    }.getOrNull().orEmpty()

    private companion object { const val KEY = "fileSelections.v1" }
}

/** Phone folders and files a book's local recordings may read, so removing the book can give the access back. */
class LocalAudioGrants(private val values: TextValues) {
    fun get(bookId: String): Set<String> = all()[bookId].orEmpty().toSet()

    fun add(bookId: String, uris: Collection<String>) = synchronized(this) {
        val all = all()
        save(all + (bookId to (all[bookId].orEmpty() + uris).distinct()))
    }

    /** Every phone file or folder some book still reads. */
    fun used(): Set<String> = all().values.flatten().toSet()

    /** Removes the book's grants and returns those no other book still uses. */
    fun forget(bookId: String): Set<String> = synchronized(this) {
        val all = all()
        val mine = all[bookId].orEmpty().toSet()
        val rest = all - bookId
        save(rest)
        mine - rest.values.flatten().toSet()
    }

    private fun all(): Map<String, List<String>> = runCatching {
        values.get(KEY)?.let { NarrioJson.decodeFromString<Map<String, List<String>>>(it) }
    }.getOrNull().orEmpty()

    private fun save(all: Map<String, List<String>>) = values.put(KEY, if (all.isEmpty()) null else NarrioJson.encodeToString(all))

    private companion object { const val KEY = "localAudioGrants.v1" }
}
