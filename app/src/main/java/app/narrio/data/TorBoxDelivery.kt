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

    /** Checks a key before it's saved, cancelled with its caller; every failure is a [ProviderException] worded for the person entering it. */
    suspend fun connect(candidate: String): String {
        val me = Request.Builder().url((baseUrl + "user/me").toHttpUrl()).header("Authorization", "Bearer $candidate").build()
        val root = try { http.readCancellable(me, ::readResponse) }
            catch (error: ProviderAuthorizationException) {
                throw ProviderException("TorBox didn't accept this API key. Copy the whole key from your TorBox settings and check that your plan includes API access.")
            } catch (error: ProviderException) { throw error }
            catch (error: java.io.IOException) { throw ProviderException("Couldn't reach TorBox. Check your internet connection and try again.") }
        val data = root["data"] as? JsonObject
        if (data?.number("plan") == 0L) throw ProviderException("This TorBox account needs a plan with API access. Public recordings can still play directly.")
        return "TorBox connected"
    }

    override suspend fun prepare(book: Audiobook): Preparation = withContext(Dispatchers.IO) {
        val existing = list().firstOrNull { book.torrentHash.isNotBlank() && it.text("hash").equals(book.torrentHash, true) }
        if (existing != null) return@withContext preparation(existing)
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
        if (book.magnetUri.startsWith("magnet:?")) form.addFormDataPart("magnet", book.magnetUri)
        else {
        if (book.torrentUrl.isBlank()) throw ProviderException("This recording has no torrent source. Choose another recording.")
        // Upload a real .torrent file. Archive URLs are not assumed to be magnets.
        val torrent = http.newCall(Request.Builder().url(book.torrentUrl).build()).execute().use { r ->
            if (!r.isSuccessful) throw ProviderException("The recording's torrent is unavailable. Try direct streaming.")
            val body = r.body ?: throw ProviderException("The torrent source is empty.")
            if (body.contentLength() > 4_000_000) throw ProviderException("This torrent file is too large.")
            body.bytes().also { if (it.size > 4_000_000) throw ProviderException("This torrent file is too large.") }
        }
        form.addFormDataPart("file", "recording.torrent", torrent.toRequestBody("application/x-bittorrent".toMediaType()))
        }
        val id = create(form)
        if (id > 0) list().firstOrNull { it.number("id") == id }?.let(::preparation) ?: Preparation(id, false, 0f, "Preparing in TorBox") else Preparation(0, false, 0f, "Queued in TorBox")
    }

    private fun create(form: MultipartBody.Builder): Long {
        val body = form.addFormDataPart("seed", "1").addFormDataPart("allow_zip", "false").addFormDataPart("as_queued", "false").build()
        val data = request("torrents/createtorrent", body = body)["data"] as? JsonObject
            ?: throw ProviderException("TorBox did not return a preparation identifier. Check your TorBox dashboard.")
        return data.number("torrent_id")
    }

    /** Book-text files (EPUB/TXT) in already-cached releases, by hash. Nothing is added to the account. */
    suspend fun cachedTextFiles(hashes: List<String>): Map<String, List<String>> = cachedText(hashes).mapValues { (_, files) -> files.map { it.first } }

    /** Cached ebook files by release hash, each with its size in bytes (0 when TorBox doesn't say). */
    suspend fun cachedText(hashes: List<String>): Map<String, List<Pair<String, Long>>> = withContext(Dispatchers.IO) {
        hashes.map { it.lowercase() }.filter { it.matches(Regex("[a-f0-9]{40}")) }.distinct().chunked(100).flatMap { batch ->
            parseCached(request("torrents/checkcached", mapOf("hash" to batch.joinToString(","), "format" to "object", "list_files" to "true"))["data"], ::isBookTextFile)
                .map { (hash, item) -> hash to item.objects("files").map { it.text("name") to (it.number("size") ?: 0L).toLong() } }
        }.filter { it.second.isNotEmpty() }.toMap()
    }

    /** Ebook files already present in the account, labelled with their release name for matching. */
    suspend fun accountText(): List<Pair<String, BookTextSource>> = withContext(Dispatchers.IO) {
        list().filter { preparation(it).ready }.flatMap { item ->
            textSources(item).map { item.text("name") to it.copy(attribution = "Ebook in your TorBox account") }
        }
    }

    /**
     * Resolves a file in a cached ebook release. The release is added to the account only after TorBox
     * confirms it's cached, so this never starts an uncached download.
     */
    suspend fun cachedTextLink(hash: String, magnet: String, fileName: String): String = withContext(Dispatchers.IO) {
        var item = list().firstOrNull { it.text("hash").equals(hash, true) }
        if (item == null) {
            if (cachedTextFiles(listOf(hash))[hash.lowercase()].isNullOrEmpty()) throw ProviderException("This ebook is no longer cached in TorBox.")
            if (!magnet.startsWith("magnet:?")) throw ProviderException("This ebook release has no magnet link.")
            val id = create(MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("magnet", magnet))
            item = list().firstOrNull { (id > 0 && it.number("id") == id) || it.text("hash").equals(hash, true) }
                ?: throw ProviderException("TorBox is still adding this ebook. Try again shortly.")
        }
        if (!preparation(item).ready) throw ProviderException("TorBox is still preparing this ebook. Try again shortly.")
        val file = item.objects("files").firstOrNull { sameFile(it.text("name"), fileName) || sameFile(fileName, it.text("name")) }
            ?: throw ProviderException("The ebook file is missing from this TorBox release.")
        resolve(AudioPart(fileName, fileName, fileName, torrentId = item.number("id"), fileId = file.number("id")))
    }

    /** Playback's default path must pass this check before adding any new torrent. */
    suspend fun prepareCached(book: Audiobook, format: String): Preparation {
        val checked = checkCached(listOf(book)).first()
        if (checked.cacheState != "cached" || format !in checked.cachedFormats)
            throw ProviderException("This format isn't cached in TorBox. Choose another ready source, or explicitly prepare it in TorBox.")
        return prepare(book)
    }

    suspend fun checkCached(books: List<Audiobook>): List<Audiobook> = withContext(Dispatchers.IO) {
        val hashes = books.map { it.torrentHash.lowercase() }.filter { it.matches(Regex("[a-f0-9]{40}")) }.distinct()
        val cached = hashes.chunked(100).flatMap { batch ->
            parseCached(searchRequest("torrents/checkcached", mapOf("hash" to batch.joinToString(","), "format" to "object", "list_files" to "true"))["data"]).entries
        }.associate { it.toPair() }
        books.map { book ->
            val item = cached[book.torrentHash.lowercase()]
            val files = item?.objects("files").orEmpty()
            val indexedSources = if (book.provider == "knaben" && item != null) mapSources(item, book).map { source ->
                source.copy(id = "cache:${book.torrentHash}:${source.format}", parts = source.parts.map { it.copy(torrentId = null, fileId = null) })
            } else null
            val formats = if (book.provider == "archive") book.sources.filter { source ->
                source.parts.isNotEmpty() && source.parts.all { part -> files.any { sameFile(it.text("name"), part.name) } }
            }.map { it.format } else indexedSources?.map { it.format } ?: files.map { audioFormat(it.text("name")) }.distinct()
            book.copy(cacheState = if (formats.isNotEmpty()) "cached" else "uncached", cachedFormats = formats,
                sources = indexedSources?.takeIf { it.isNotEmpty() } ?: book.sources)
        }
    }

    suspend fun refresh(book: Audiobook, torrentId: Long): Preparation = account().preparation(book, torrentId)

    /** The account's items, listed once, so several preparations can be checked with one request. */
    suspend fun account(): PreparationAccount = withContext(Dispatchers.IO) { Account(list()) }

    private inner class Account(private val items: List<JsonObject>) : PreparationAccount {
        private fun item(book: Audiobook, torrentId: Long) =
            items.firstOrNull { if (torrentId > 0) it.number("id") == torrentId else book.torrentHash.isNotBlank() && it.text("hash").equals(book.torrentHash, true) }
        override fun preparation(book: Audiobook, torrentId: Long) =
            item(book, torrentId)?.let(::preparation) ?: Preparation(torrentId, false, 0f, "Waiting for TorBox", missing = true)
        override fun sources(book: Audiobook, torrentId: Long): List<AudioSource> {
            val item = item(book, torrentId)?.takeIf { preparation(it).ready } ?: throw ProviderException("This recording is still being prepared in TorBox.")
            return mapSources(item, book)
        }
    }

    override suspend fun status(torrentId: Long): Preparation = withContext(Dispatchers.IO) {
        val item = list().firstOrNull { it.number("id") == torrentId }
            ?: return@withContext Preparation(torrentId, false, 0f, "Waiting for TorBox", missing = true)
        preparation(item)
    }

    internal fun preparation(item: JsonObject) = Preparation(
        item.number("id"), item.flag("download_finished") && item.flag("download_present"),
        (item.text("progress").toFloatOrNull()?.takeIf { it.isFinite() } ?: 0f).coerceIn(0f, 1f),
        when (val state = item.text("download_state")) {
            "cached", "completed", "uploading" -> if (item.flag("download_finished") && item.flag("download_present")) "Ready to listen" else "Preparing files"
            "stalled (no seeds)" -> "Waiting for available peers"
            "metaDL" -> "Finding audio files"
            "downloading" -> "Preparing in TorBox"
            "paused" -> "Paused in TorBox"
            else -> if (failedState(state)) "Couldn't get it ready" else state.ifBlank { "Preparing in TorBox" }
        },
        downloadBytesPerSecond = item.number("download_speed"), etaSeconds = item.number("eta"),
        seeds = item["seeds"]?.let { item.number("seeds") }, checkedAtMs = System.currentTimeMillis(),
        stalled = item.text("download_state") == "stalled (no seeds)",
        problem = if (failedState(item.text("download_state"))) "TorBox couldn't fetch this release." else "",
    )

    override suspend fun sources(book: Audiobook, torrentId: Long): List<AudioSource> = withContext(Dispatchers.IO) {
        val item = list().firstOrNull { it.number("id") == torrentId } ?: throw ProviderException("TorBox is still preparing this recording.")
        if (!preparation(item).ready) throw ProviderException("This recording is still being prepared in TorBox. Choose a cached source to listen now.")
        mapSources(item, book)
    }

    suspend fun library(query: String = ""): List<Audiobook> = withContext(Dispatchers.IO) {
        val terms = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        list().filter { item -> terms.all { item.text("name").contains(it, true) } && item.objects("files").any { f -> isAudioFile(f.text("name")) } }.map { item ->
            val book = Audiobook("torbox:${item.number("id")}", item.text("name").ifBlank { "TorBox audiobook" },
                "Your TorBox library", language = "Language not listed", narrator = "Narrator not listed", provider = "torbox",
                description = "Audio files in your TorBox account. Check this source's narration and language before listening.", detailsLoaded = true,
                torrentHash = item.text("hash"))
            val sources = mapSources(item, book)
            book.copy(sources = sources, cacheState = if (preparation(item).ready) "cached" else "uncached", cachedFormats = if (preparation(item).ready) sources.map { it.format } else emptyList())
        }
    }

    override fun resolve(part: AudioPart): String {
        if (part.torrentId == null || part.fileId == null) return part.archiveUrl
        val result = request("torrents/requestdl", params = mapOf("token" to token(), "torrent_id" to part.torrentId.toString(), "file_id" to part.fileId.toString(), "redirect" to "false"))
        val url = result["data"].stringValue()
        if (!url.startsWith("https://")) throw ProviderException("TorBox did not return a secure audio link. Try again.")
        return url
    }

    private suspend fun list(): List<JsonObject> = searchRequest("torrents/mylist", mapOf("bypass_cache" to "true"))["data"].let {
        when (it) { is JsonArray -> it.mapNotNull { x -> x as? JsonObject }; is JsonObject -> listOf(it); else -> emptyList() }
    }

    internal fun webTextLink(webId: Long, fileId: Long): String = request("webdl/requestdl", params = mapOf(
        "token" to token(), "web_id" to webId.toString(), "file_id" to fileId.toString(), "redirect" to "false"))["data"].stringValue()

    /** Read-only search delivery calls are cancellable; synchronous playback resolution stays synchronous. */
    private suspend fun searchRequest(path: String, params: Map<String, String>): JsonObject {
        val url = (baseUrl + path).toHttpUrl().newBuilder().apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
        return http.readCancellable(Request.Builder().url(url).header("Authorization", "Bearer ${token()}").build(), ::readResponse)
    }

    internal fun request(path: String, params: Map<String, String> = emptyMap(), body: RequestBody? = null, overrideToken: String? = null): JsonObject {
        val url = (baseUrl + path).toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val builder = Request.Builder().url(url).header("Authorization", "Bearer ${overrideToken ?: token()}")
        if (body != null) builder.post(body)
        return http.newCall(builder.build()).execute().use(::readResponse)
    }

    private fun readResponse(r: Response): JsonObject {
            if (r.code == 401 || r.code == 403) throw ProviderAuthorizationException("TorBox could not authorize this request. Check your API key and account's API access in Settings.")
            if (r.code == 429) throw ProviderException("TorBox needs a moment between requests. Please retry shortly.")
            if (!r.isSuccessful) throw ProviderException("TorBox is unavailable (${r.code}). Retry when your connection returns.")
            val root = runCatching { NarrioJson.parseToJsonElement(r.body?.string().orEmpty()).jsonObject }
                .getOrElse { throw ProviderException("TorBox returned an unreadable response. Try again.") }
            // Do not expose raw provider responses: they can echo credentials or sensitive URLs.
            if (!root.flag("success")) throw ProviderException("TorBox couldn't complete this request. Check your plan, available download slots, and source in the TorBox dashboard.")
            return root
    }

    companion object {
        /** TorBox's terminal download states; it won't fetch these without a new request. */
        private fun failedState(state: String) = Regex("(?i)^(error|failed|dead)|missingfiles").containsMatchIn(state)
        private fun sameFile(a: String, b: String) = a == b || a.endsWith("/$b")
        private fun audioFormat(name: String) = when (val ext = name.substringAfterLast('.').uppercase()) { "MP3", "M4B" -> ext; else -> "OTHER" }
        internal fun parseCached(data: JsonElement?, keep: (String) -> Boolean = ::isBookAudioFile): Map<String, JsonObject> {
            val values = when (data) {
                is JsonObject -> data.mapNotNull { (hash, value) -> (value as? JsonObject)?.let { hash to it } }
                is JsonArray -> data.mapNotNull { (it as? JsonObject)?.let { item -> item.text("hash") to item } }
                else -> emptyList()
            }
            return values.mapNotNull { (key, item) ->
                val hash = item.text("hash").ifBlank { key }.lowercase()
                if (!hash.matches(Regex("[a-f0-9]{40}"))) return@mapNotNull null
                val files = (item["files"] as? JsonArray).orEmpty().mapIndexedNotNull { index, file ->
                    val obj = file as? JsonObject
                    val name = obj?.text("name")?.ifBlank { obj.text("path") } ?: file.stringValue()
                    if (!keep(name)) null else buildJsonObject {
                        put("id", index); put("name", name); put("size", obj?.number("size") ?: 0)
                    }
                }
                hash to buildJsonObject { put("files", JsonArray(files)); put("id", 0) }
            }.toMap()
        }
        private fun isBookTextFile(name: String) = textFileFormat(name).let { it == "EPUB" || it == "TXT" } &&
            !Regex("(?i)(^|[/ _-])(readme|license|info|credits|sample|nfo)([. _-]|$)").containsMatchIn(name)

        internal fun textSources(item: JsonObject): List<BookTextSource> {
            val torrentId = item.number("id")
            return item.objects("files").mapNotNull { file ->
                val name = file.text("name")
                val format = textFileFormat(name) ?: return@mapNotNull null
                if (Regex("(?i)(^|[/ _-])(readme|license|info|credits)([. _-]|$)").containsMatchIn(name)) return@mapNotNull null
                BookTextSource("torbox:$torrentId:${file.number("id")}", name.substringAfterLast('/'), format = format, provider = "torbox", torrentId = torrentId, fileId = file.number("id"), attribution = "Companion file from your TorBox audio source")
            }
        }

        fun mapSources(item: JsonObject, book: Audiobook): List<AudioSource> {
            val torrentId = item.number("id")
            val textFiles = textSources(item)
            val files = item.objects("files").filter { isBookAudioFile(it.text("name")) }
            fun toPart(f: JsonObject): AudioPart {
                val original = book.sources.flatMap { it.parts }.firstOrNull { it.name == f.text("name") || f.text("name").endsWith("/${it.name}") }
                return AudioPart("torbox:$torrentId:${f.number("id")}", f.text("name"),
                    original?.title ?: f.text("short_name").ifBlank { f.text("name").substringAfterLast('/') }.substringBeforeLast('.').replace('_', ' '),
                    durationMs = original?.durationMs ?: 0, torrentId = torrentId, fileId = f.number("id"), sizeBytes = f.number("size"))
            }
            return listOf("mp3", "m4b", "other").mapNotNull { format ->
                var group = files.filter { val ext = it.text("name").substringAfterLast('.').lowercase(); when (format) { "other" -> ext !in setOf("mp3", "m4b"); else -> ext == format } }
                val archiveNames = book.sources.firstOrNull { it.format.equals(format, true) }?.parts?.map { it.name }.orEmpty()
                if (book.bookFilesSelected && archiveNames.isEmpty()) return@mapNotNull null
                if (archiveNames.isNotEmpty()) {
                    if (archiveNames.any { name -> group.none { sameFile(it.text("name"), name) } }) return@mapNotNull null
                    group = group.filter { f -> archiveNames.any { name -> sameFile(f.text("name"), name) } }
                }
                if (format == "mp3") group = group.filter { !it.text("name").contains("64kb", true) }.ifEmpty { group }
                if (group.isEmpty()) null else AudioSource("torbox:$torrentId:$format", if (format == "m4b") "Whole-book audio" else "Ordered audio parts",
                    format.uppercase(), group.sortedWith { a, b -> AudioOrdering.compare(a.text("name"), b.text("name")) }.map(::toPart), "torbox", torrentId, textFiles)
            }
        }
    }
}
