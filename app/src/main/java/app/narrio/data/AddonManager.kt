package app.narrio.data

import android.content.Context
import app.narrio.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AddonManager(
    http: OkHttpClient,
    initial: List<InstalledAddon>,
    private val persist: (List<InstalledAddon>) -> Unit = {},
    private val allowTestHttp: Boolean = false,
) : RecordingDiscovery {
    private val http = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val installedState = MutableStateFlow(initial)
    val installed = installedState.asStateFlow()
    private val statusState = MutableStateFlow<Map<String, String>>(emptyMap())
    val status = statusState.asStateFlow()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val nextRequest = ConcurrentHashMap<String, Long>()
    val revision get() = installed.value.hashCode()

    @Synchronized private fun update(change: (List<InstalledAddon>) -> List<InstalledAddon>) {
        val updated = change(installed.value)
        persist(updated)
        installedState.value = updated
    }
    fun enable(id: String, enabled: Boolean) = update { list -> list.map { if (it.id == id) it.copy(enabled = enabled) else it } }
    fun remove(id: String) = update { list -> list.filter { it.id != id } }

    suspend fun install(url: String): InstalledAddon {
        AddonManifest.secureUrl(url.trim())
        val manifest = fetch(Request.Builder().url(url.trim()).build(), 15_000)
        val addon = AddonManifest.parse(manifest.toString(), url.trim())
        update { list -> list.filter { it.id != addon.id } + addon.copy(enabled = list.firstOrNull { it.id == addon.id }?.enabled ?: true) }
        return addon
    }
    suspend fun refresh(id: String) {
        val before = installed.value.firstOrNull { it.id == id } ?: return
        val manifest = fetch(Request.Builder().url(before.manifestUrl).build(), 15_000)
        val addon = AddonManifest.parse(manifest.toString(), before.manifestUrl)
        require(addon.id == id) { "The refreshed add-on has a different ID. Import it separately." }
        update { list -> list.map { if (it.id == id) addon.copy(enabled = it.enabled) else it } }
        statusState.update { it - id }
    }

    override suspend fun search(query: String, category: String): List<Audiobook> = sources(query, "", "audiobook")
    override suspend fun searchBook(book: Audiobook, title: String): List<Audiobook> = sources(title, book.author.takeUnless(BookMetadata::unknown).orEmpty(), "audiobook")
    suspend fun ebooks(query: String): List<Audiobook> = sources(query, "", "ebook")
    override suspend fun recording(id: String): Audiobook = throw ProviderException("Choose a release to inspect its TorBox availability.")

    private suspend fun sources(title: String, author: String, type: String): List<Audiobook> = collect(
        installed.value.filter { it.enabled && it.source && it.contentType == type }
    ) { addon ->
        val adapter = addon.manifest["adapters"]!!.jsonObject["source"]!!.jsonObject
        rows(addon, adapter, title, author).mapNotNull { row ->
            val fields = mapped(adapter, row)
            val hash = fields["infoHash"].orEmpty().lowercase().ifBlank {
                Regex("(?i)urn:btih:([0-9a-f]{40})").find(fields["magnetUrl"].orEmpty())?.groupValues?.get(1)?.lowercase().orEmpty()
            }
            val name = fields["title"].orEmpty().trim()
            if (!hash.matches(Regex("[0-9a-f]{40}")) || name.isBlank()) return@mapNotNull null
            // Keep the existing hash identity and torrent delivery route for saved recordings.
            Audiobook("knaben:$hash", name, fields["author"].orEmpty().ifBlank { "Author not verified" },
                narrator = fields["narrator"].orEmpty().ifBlank { "Narrator not verified" }, language = fields["language"].orEmpty().ifBlank { "Language not verified" },
                description = "Release indexed by ${addon.name}. Narration, language and files must be checked before listening.",
                torrentHash = hash, magnetUri = fields["magnetUrl"].orEmpty().takeIf {
                    it.startsWith("magnet:?") && Regex("(?i)urn:btih:$hash(?:&|$)").containsMatchIn(it)
                } ?: "magnet:?xt=urn:btih:$hash", provider = "knaben", detailsLoaded = true,
                releaseSizeBytes = fields["sizeBytes"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0,
                seeders = fields["seeders"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0, sourceAddonName = addon.name)
        }
    }.distinctBy { it.torrentHash }

    suspend fun catalog(query: String): List<BookDetails> = collect(installed.value.filter { it.enabled && it.catalog }) { addon ->
        val adapter = addon.manifest["adapters"]!!.jsonObject["catalog"]!!.jsonObject["search"]!!.jsonObject
        rows(addon, adapter, query, "").mapNotNull { row ->
            val fields = mapped(adapter, row)
            val name = fields["title"].orEmpty()
            val mapping = adapter["response"]!!.jsonObject["mapping"]!!.jsonObject
            val authors = AddonManifest.values(row, mapping.text("authors")).flatMap { if (it is JsonArray) it.toList() else listOf(it) }.map { it.stringValue() }.filter(String::isNotBlank)
            if (name.isBlank() || authors.isEmpty()) return@mapNotNull null
            val cover = fields["cover"].orEmpty().let {
                if (addon.id == "open-library-metadata" && it.toLongOrNull()?.let { id -> id > 0 } == true) "https://covers.openlibrary.org/b/id/$it-L.jpg?default=false" else it
            }.takeIf { it.startsWith("https://") }.orEmpty()
            val link = when (addon.id) {
                "audible-audiobooks" -> fields["isbn"].orEmpty().takeIf { it.matches(Regex("[A-Z0-9]{10}")) }?.let { "https://www.audible.com/pd/$it" }.orEmpty()
                "open-library-metadata" -> (row as? JsonObject)?.text("key").orEmpty().takeIf { it.matches(Regex("/works/OL[0-9]+W")) }?.let { "https://openlibrary.org$it" }.orEmpty()
                else -> ""
            }
            BookDetails(name, authors, fields["narrator"].orEmpty().takeIf(String::isNotBlank)?.let(::listOf).orEmpty(),
                MetadataText.clean(fields["description"].orEmpty()), cover, fields["language"].orEmpty(), addon.name, link)
        }
    }

    private suspend fun <T> collect(addons: List<InstalledAddon>, read: suspend (InstalledAddon) -> List<T>): List<T> = supervisorScope {
        val outcomes = addons.map { addon -> async {
            try {
                val result = read(addon)
                statusState.update { it + (addon.id to "Available") }
                result to true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                statusState.update { it + (addon.id to "Unavailable. Try refreshing or retry later.") }
                emptyList<T>() to false
            }
        } }.awaitAll()
        if (outcomes.isNotEmpty() && outcomes.none { it.second }) throw ProviderException("The enabled add-ons are unavailable. Retry later or check Settings → Add-ons.")
        outcomes.flatMap { it.first }
    }

    private fun mapped(adapter: JsonObject, row: JsonElement) = adapter["response"]!!.jsonObject["mapping"]!!.jsonObject.mapValues { (_, path) ->
        AddonManifest.values(row, path.stringValue()).joinToString(", ") { it.stringValue() }
    }

    private suspend fun rows(addon: InstalledAddon, adapter: JsonObject, title: String, author: String): List<JsonElement> = locks.getOrPut(addon.id) { Mutex() }.withLock {
        val rate = addon.manifest["rateLimit"] as? JsonObject
        val rpm = rate?.number("requestsPerMinute")?.coerceIn(1, 120) ?: 60
        delay((nextRequest[addon.id]?.minus(androidFreeTime()) ?: 0).coerceAtLeast(0))
        val interval = maxOf(60_000 / rpm, rate?.number("retryAfterMs")?.coerceIn(0, 60_000) ?: 0)
        nextRequest[addon.id] = androidFreeTime() + interval
        val spec = adapter["request"]!!.jsonObject
        val replacements = mapOf("{TITLE}" to title.take(250), "{AUTHOR}" to author.take(200), "{QUERY}" to title.take(250))
        fun replace(value: String, encoded: Boolean = false): String = Regex("\\{(?:TITLE|AUTHOR|QUERY)\\}").replace(value) { match ->
            val replacement = replacements.getValue(match.value)
            if (encoded) java.net.URLEncoder.encode(replacement, "UTF-8").replace("+", "%20") else replacement
        }
        fun body(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.mapValues { body(it.value) })
            is JsonArray -> JsonArray(value.map { body(it) })
            is JsonPrimitive -> if (value.isString) JsonPrimitive(replace(value.content)) else value
        }
        val url = replace(spec.text("url"), true)
        if (!allowTestHttp) AddonManifest.secureUrl(url)
        val request = Request.Builder().url(url)
        (spec["headers"] as? JsonObject)?.forEach { (key, value) -> request.header(key, value.stringValue()) }
        if (spec.text("method") == "POST") request.post(body(spec["body"] ?: JsonObject(emptyMap())).toString().toRequestBody("application/json".toMediaType()))
        val result = fetch(request.build(), spec.number("timeout").takeIf { it > 0 }?.coerceIn(1_000, 40_000) ?: 20_000)
        val response = adapter["response"]!!.jsonObject
        AddonManifest.values(result, response.text("resultsPath")).flatMap { if (it is JsonArray) it.toList() else emptyList() }.take(100)
    }

    private fun androidFreeTime() = System.nanoTime() / 1_000_000

    private suspend fun fetch(request: Request, timeout: Long): JsonElement = withContext(Dispatchers.IO) {
        val client = http.newBuilder().callTimeout(timeout, TimeUnit.MILLISECONDS).build()
        val response = suspendCancellableCoroutine<Response> { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) { continuation.resume(response) { _, value, _ -> value.close() } }
            })
        }
        response.use {
            require(it.isSuccessful) { "The add-on request failed." }
            val source = it.body?.source() ?: error("Empty add-on response.")
            require(!source.request(2_000_001)) { "The add-on response is too large." }
            NarrioJson.parseToJsonElement(source.readUtf8())
        }
    }

    companion object {
        val bundledUrls = linkedMapOf(
            "audiobookbay" to "https://api.npoint.io/dee75259b6b8e0630dce",
            "tpb-audiobooks" to "https://api.npoint.io/526af6fc28a90bed10c8",
            "knaben-ebooks" to "https://api.npoint.io/3356bd187611b2a29f40",
            "knaben-audiobooks" to "https://api.npoint.io/bd3157954016bd9fd1ed",
            "audible-audiobooks" to "https://api.npoint.io/112b9e0e87362772f7c3",
            "open-library-metadata" to "https://api.npoint.io/2b23d8b5a9ef68a0090e",
        )
        fun create(context: Context, http: OkHttpClient): AddonManager {
            val preferences = context.getSharedPreferences("addons.v1", Context.MODE_PRIVATE)
            val defaults = bundledUrls.map { (id, url) -> AddonManifest.parse(context.assets.open("addons/$id.json").bufferedReader().use { it.readText() }, url) }
            val saved = preferences.getString("installed", null)
            val initial = if (saved == null) defaults else runCatching {
                NarrioJson.decodeFromString<List<InstalledAddon>>(saved).map { entry ->
                    AddonManifest.secureUrl(entry.manifestUrl)
                    AddonManifest.parse(entry.manifest.toString(), entry.manifestUrl).copy(enabled = entry.enabled)
                }
            }.getOrDefault(emptyList())
            return AddonManager(http, initial, persist = { list ->
                check(preferences.edit().putString("installed", NarrioJson.encodeToString(list)).commit()) { "Could not save add-on settings." }
            })
        }
    }
}
