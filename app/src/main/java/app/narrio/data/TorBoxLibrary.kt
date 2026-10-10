package app.narrio.data

import app.narrio.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** The three kinds of TorBox download that can hold audio. Their files are requested through different endpoints. */
enum class TorBoxKind(val list: String, val prefix: String, val delivery: String, val label: String) {
    TORRENT("torrents/mylist", "torbox", "torbox", "Torrent"),
    USENET("usenet/mylist", "torbox-usenet", "torbox-usenet", "Usenet"),
    WEB("webdl/mylist", "torbox-web", "torbox-web", "Web download");

    companion object {
        /** The kind a part or recording ID belongs to; plain "torbox:" IDs are torrents. */
        fun of(id: String): TorBoxKind = entries.sortedByDescending { it.prefix.length }.firstOrNull { id.startsWith("${it.prefix}:") } ?: TORRENT
    }
}

/** One download in the listener's TorBox account with at least one audio file. */
data class TorBoxItem(
    val kind: TorBoxKind, val id: Long, val name: String, val hash: String, val createdAt: String,
    val ready: Boolean, val sizeBytes: Long, val audioFiles: Int, val state: String,
)

/**
 * Lists every TorBox download with audio (torrents, Usenet, and web downloads), newest first, for an explicit choice.
 * Nothing is added to the account and no audio is fetched.
 */
class TorBoxLibrary(private val torbox: TorBoxDelivery) {
    /** Every kind is read; a kind the account's plan can't list is skipped unless all of them fail. */
    suspend fun items(): List<TorBoxItem> = raw().map { (kind, item) -> item(kind, item) }.let(::newestFirst)

    suspend fun recording(item: TorBoxItem): Audiobook {
        val json = raw(item.kind).firstOrNull { it.number("id") == item.id } ?: throw ProviderException("This download is no longer in your TorBox account.")
        return recording(item.kind, json)
    }

    /** All audio files of one account download, in the layouts playback uses. */
    suspend fun sources(kind: TorBoxKind, id: Long, book: Audiobook): List<AudioSource> {
        val json = raw(kind).firstOrNull { it.number("id") == id } ?: throw ProviderException("This download is no longer in your TorBox account.")
        return sources(kind, json, book)
    }

    private suspend fun raw(): List<Pair<TorBoxKind, JsonObject>> = coroutineScope {
        val lists = TorBoxKind.entries.map { kind -> async {
            kind to try { Result.success(raw(kind)) } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) { Result.failure(error) }
        } }.map { it.await() }
        lists.firstOrNull { it.second.isSuccess }
            ?: throw (lists.first().second.exceptionOrNull() as? ProviderException ?: ProviderException("Your TorBox library couldn't be read. Try again."))
        lists.flatMap { (kind, result) -> result.getOrDefault(emptyList()).map { kind to it } }
            .filter { (_, item) -> item.objects("files").any { isBookAudioFile(it.text("name")) } }
    }

    private suspend fun raw(kind: TorBoxKind): List<JsonObject> = withContext(Dispatchers.IO) {
        when (val data = torbox.request(kind.list, mapOf("bypass_cache" to "true"))["data"]) {
            is JsonArray -> data.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(data)
            else -> emptyList()
        }
    }

    companion object {
        internal fun ready(item: JsonObject) = item.flag("download_finished") && item.flag("download_present")

        internal fun item(kind: TorBoxKind, json: JsonObject) = TorBoxItem(kind, json.number("id"), json.text("name").ifBlank { "Untitled download" },
            json.text("hash").lowercase(), json.text("created_at"), ready(json), json.number("size"),
            json.objects("files").count { isBookAudioFile(it.text("name")) },
            when {
                ready(json) -> "Ready to listen"
                json.text("download_state").lowercase() in setOf("error", "failed") || json.text("error").isNotBlank() -> "Couldn't get it ready"
                else -> "Getting ready in TorBox"
            })

        /** ISO timestamps sort as text; the newer (higher) ID breaks ties and covers missing dates. */
        fun newestFirst(items: List<TorBoxItem>) = items.sortedWith(compareByDescending<TorBoxItem> { it.createdAt }.thenByDescending { it.id })

        /** Every search word appears in the download's name; punctuation and accents are ignored. */
        fun search(items: List<TorBoxItem>, query: String): List<TorBoxItem> {
            val words = BookIdentity.normalize(query).split(' ').filter(String::isNotBlank)
            return if (words.isEmpty()) items else items.filter { item -> " ${BookIdentity.normalize(item.name)} ".let { name -> words.all { " $it " in name } } }
        }

        internal fun sources(kind: TorBoxKind, json: JsonObject, book: Audiobook): List<AudioSource> {
            val mapped = TorBoxDelivery.mapSources(json, book)
            if (kind == TorBoxKind.TORRENT) return mapped
            fun rename(id: String) = kind.prefix + id.removePrefix("torbox")
            // Usenet and web-download files are requested through their own endpoints; the ID prefix says which.
            return mapped.map { source -> source.copy(id = rename(source.id), delivery = kind.delivery, textFiles = emptyList(),
                parts = source.parts.map { it.copy(id = rename(it.id)) }) }
        }

        internal fun recording(kind: TorBoxKind, json: JsonObject): Audiobook {
            val id = json.number("id")
            val book = Audiobook("${kind.prefix}:$id", json.text("name").ifBlank { "TorBox audiobook" }, "Your TorBox library",
                language = "Language not listed", narrator = "Narrator not listed", provider = "torbox", releaseTitle = json.text("name"),
                description = "Audio files in your TorBox account. Check this source's narration and language before listening.",
                detailsLoaded = true, torrentHash = if (kind == TorBoxKind.TORRENT) json.text("hash") else "")
            val sources = sources(kind, json, book)
            return book.copy(sources = sources, cacheState = if (ready(json)) "cached" else "uncached",
                cachedFormats = if (ready(json)) sources.map { it.format } else emptyList(), filesVerified = true,
                releaseSizeBytes = json.number("size"))
        }
    }
}
