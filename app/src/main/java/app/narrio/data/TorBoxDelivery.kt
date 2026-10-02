package app.narrio.data

import app.narrio.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class TorBoxDelivery(
    private val http: OkHttpClient,
    private val credential: () -> String?,
    private val baseUrl: String = "https://api.torbox.app/v1/api/",
) : DeliveryProvider {
    private fun token() = credential()?.takeIf { it.isNotBlank() } ?: throw ProviderException("Connect TorBox in Settings to use this source.")

    suspend fun connect(candidate: String): String = withContext(Dispatchers.IO) {
        val root = request("user/me", overrideToken = candidate)
        val data = root["data"] as? JsonObject
        if (data?.number("plan") == 0L) throw ProviderException("This TorBox account needs a plan with API access. Public recordings can still play directly.")
        "TorBox connected"
    }

    override suspend fun prepare(book: Audiobook): Preparation = withContext(Dispatchers.IO) {
        require(book.torrentUrl.isNotBlank()) { "This recording has no torrent source. Use direct streaming." }
        val existing = list().firstOrNull { book.torrentHash.isNotBlank() && it.text("hash").equals(book.torrentHash, true) }
        if (existing != null) return@withContext preparation(existing)
        // Upload a real .torrent file. Archive URLs are not assumed to be magnets.
        val torrent = http.newCall(Request.Builder().url(book.torrentUrl).build()).execute().use { r ->
            if (!r.isSuccessful) throw ProviderException("The recording's torrent is unavailable. Try direct streaming.")
            val body = r.body ?: throw ProviderException("The torrent source is empty.")
            if (body.contentLength() > 4_000_000) throw ProviderException("This torrent file is too large.")
            body.bytes().also { if (it.size > 4_000_000) throw ProviderException("This torrent file is too large.") }
        }
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "recording.torrent", torrent.toRequestBody("application/x-bittorrent".toMediaType()))
            .addFormDataPart("seed", "1").addFormDataPart("allow_zip", "false").addFormDataPart("as_queued", "false").build()
        val data = request("torrents/createtorrent", body = form)["data"] as? JsonObject
            ?: throw ProviderException("TorBox did not return a preparation identifier. Check your TorBox dashboard.")
        val id = data.number("torrent_id")
        if (id > 0) list().firstOrNull { it.number("id") == id }?.let(::preparation) ?: Preparation(id, false, 0f, "Preparing in TorBox") else Preparation(0, false, 0f, "Queued in TorBox")
    }

    suspend fun refresh(book: Audiobook, torrentId: Long): Preparation = withContext(Dispatchers.IO) {
        val item = list().firstOrNull { if (torrentId > 0) it.number("id") == torrentId else it.text("hash").equals(book.torrentHash, true) }
        item?.let(::preparation) ?: Preparation(torrentId, false, 0f, "Waiting for TorBox")
    }

    override suspend fun status(torrentId: Long): Preparation = withContext(Dispatchers.IO) {
        val item = list().firstOrNull { it.number("id") == torrentId }
            ?: return@withContext Preparation(torrentId, false, 0f, "Waiting for TorBox")
        preparation(item)
    }

    private fun preparation(item: JsonObject) = Preparation(
        item.number("id"), item.flag("download_finished") && item.flag("download_present"),
        (item.text("progress").toFloatOrNull()?.takeIf { it.isFinite() } ?: 0f).coerceIn(0f, 1f),
        when (val state = item.text("download_state")) {
            "cached", "completed", "uploading" -> if (item.flag("download_finished") && item.flag("download_present")) "Ready to listen" else "Preparing files"
            "stalled (no seeds)" -> "Waiting for available peers"
            "metaDL" -> "Finding audio files"
            "downloading" -> "Preparing in TorBox"
            "paused" -> "Paused in TorBox"
            else -> state.ifBlank { "Preparing in TorBox" }
        },
    )

    override suspend fun sources(book: Audiobook, torrentId: Long): List<AudioSource> = withContext(Dispatchers.IO) {
        val item = list().firstOrNull { it.number("id") == torrentId } ?: throw ProviderException("TorBox is still preparing this recording.")
        mapSources(item, book)
    }

    suspend fun library(query: String = ""): List<Audiobook> = withContext(Dispatchers.IO) {
        list().filter { it.text("name").contains(query, true) && it.objects("files").any { f -> isAudioFile(f.text("name")) } }.map { item ->
            val book = Audiobook("torbox:${item.number("id")}", item.text("name").ifBlank { "TorBox audiobook" },
                "Your TorBox library", language = "Language not listed", narrator = "Narrator not listed", provider = "torbox",
                description = "Audio files in your TorBox account. Check this source's narration and language before listening.", detailsLoaded = true)
            book.copy(sources = mapSources(item, book))
        }
    }

    override fun resolve(part: AudioPart): String {
        if (part.torrentId == null || part.fileId == null) return part.archiveUrl
        val result = request("torrents/requestdl", params = mapOf("token" to token(), "torrent_id" to part.torrentId.toString(), "file_id" to part.fileId.toString(), "redirect" to "false"))
        val url = result["data"].stringValue()
        if (!url.startsWith("https://")) throw ProviderException("TorBox did not return a secure audio link. Try again.")
        return url
    }

    private fun list(): List<JsonObject> = request("torrents/mylist", params = mapOf("bypass_cache" to "true"))["data"].let {
        when (it) { is JsonArray -> it.mapNotNull { x -> x as? JsonObject }; is JsonObject -> listOf(it); else -> emptyList() }
    }

    private fun request(path: String, params: Map<String, String> = emptyMap(), body: RequestBody? = null, overrideToken: String? = null): JsonObject {
        val url = (baseUrl + path).toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val builder = Request.Builder().url(url).header("Authorization", "Bearer ${overrideToken ?: token()}")
        if (body != null) builder.post(body)
        return http.newCall(builder.build()).execute().use { r ->
            if (r.code == 401 || r.code == 403) throw ProviderException("TorBox could not authorize this request. Check your API key and account's API access in Settings.")
            if (r.code == 429) throw ProviderException("TorBox needs a moment between requests. Please retry shortly.")
            if (!r.isSuccessful) throw ProviderException("TorBox is unavailable (${r.code}). Retry when your connection returns.")
            val root = runCatching { NarrioJson.parseToJsonElement(r.body?.string().orEmpty()).jsonObject }
                .getOrElse { throw ProviderException("TorBox returned an unreadable response. Try again.") }
            // Do not expose raw provider responses: they can echo credentials or sensitive URLs.
            if (!root.flag("success")) throw ProviderException("TorBox couldn't complete this request. Check your plan, available download slots, and source in the TorBox dashboard.")
            root
        }
    }

    companion object {
        fun mapSources(item: JsonObject, book: Audiobook): List<AudioSource> {
            val torrentId = item.number("id")
            val files = item.objects("files").filter { isAudioFile(it.text("name")) && !Regex("(^|[/ _-])(sample|trailer)([/ _.-]|$)", RegexOption.IGNORE_CASE).containsMatchIn(it.text("name")) }
            fun toPart(f: JsonObject): AudioPart {
                val original = book.sources.flatMap { it.parts }.firstOrNull { it.name == f.text("name") || f.text("name").endsWith("/${it.name}") }
                return AudioPart("torbox:$torrentId:${f.number("id")}", f.text("name"),
                    original?.title ?: f.text("short_name").ifBlank { f.text("name").substringAfterLast('/') }.substringBeforeLast('.').replace('_', ' '),
                    durationMs = original?.durationMs ?: 0, torrentId = torrentId, fileId = f.number("id"))
            }
            return listOf("mp3", "m4b", "other").mapNotNull { format ->
                var group = files.filter { val ext = it.text("name").substringAfterLast('.').lowercase(); when (format) { "other" -> ext !in setOf("mp3", "m4b"); else -> ext == format } }
                val archiveNames = book.sources.firstOrNull { it.format.equals(format, true) }?.parts?.map { it.name }.orEmpty()
                if (archiveNames.isNotEmpty()) group = group.filter { f -> archiveNames.any { name -> f.text("name") == name || f.text("name").endsWith("/$name") } }.ifEmpty { group }
                if (format == "mp3") group = group.filter { !it.text("name").contains("64kb", true) }.ifEmpty { group }
                if (group.isEmpty()) null else AudioSource("torbox:$torrentId:$format", if (format == "m4b") "Whole-book audio" else "Ordered audio parts",
                    format.uppercase(), group.sortedWith { a, b -> AudioOrdering.compare(a.text("name"), b.text("name")) }.map(::toPart), "torbox", torrentId)
            }
        }
    }
}
