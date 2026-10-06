package app.narrio.data

import app.narrio.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.MultipartBody
import java.security.MessageDigest

/** Web-download delivery stays separate from website manifests, cookies, and torrent identities. */
class TorBoxWebEbooks(private val torbox: TorBoxDelivery, private val pollDelayMs: Long = 2_000, private val maxPolls: Int = 90) {
    suspend fun accountText(): List<Pair<String, BookTextSource>> = withContext(Dispatchers.IO) {
        list().filter { it.number("id") > 0 && ready(it) }.flatMap { item -> textFiles(item).map { item.text("name") to source(item, it) } }
    }

    /** Called only for a download the user has chosen. A miss may start a web download in TorBox. */
    suspend fun acquire(url: String, step: (String) -> Unit): BookTextSource = withContext(Dispatchers.IO) {
        ensureActive()
        AddonManifest.secureUrl(url)
        val hash = linkHash(url)
        step("Checking TorBox for this ebook")
        val data = torbox.request("webdl/checkcached", mapOf("hash" to hash, "format" to "object", "list_files" to "true"))["data"]
        val cached = when (data) {
            is JsonObject -> data[hash] as? JsonObject
            is JsonArray -> data.mapNotNull { it as? JsonObject }.firstOrNull { it.text("hash").equals(hash, true) }
            else -> null
        }
        val canonical = cached?.text("hash")?.ifBlank { hash } ?: hash
        var item = list().firstOrNull { it.text("hash").equals(hash, true) || it.text("hash").equals(canonical, true) }
        val id = item?.number("id")?.takeIf { it > 0 } ?: run {
            ensureActive()
            step(if (cached != null) "Getting the cached ebook from TorBox" else "Downloading this ebook in TorBox")
            val form = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("link", url)
                .addFormDataPart("as_queued", "false").addFormDataPart("add_only_if_cached", (cached != null).toString()).build()
            (torbox.request("webdl/createwebdownload", body = form)["data"] as? JsonObject)?.number("webdownload_id")
                ?.takeIf { it > 0 } ?: throw ProviderException("TorBox did not return an ebook download ID.")
        }
        repeat(maxPolls) { attempt ->
            ensureActive()
            if (item == null || !ready(item)) item = list(id).firstOrNull { it.number("id") == id }
            val current = item
            if (current != null && ready(current)) {
                val files = textFiles(current)
                val epubs = files.filter { textFileFormat(it.text("name")) == "EPUB" }
                val file = epubs.singleOrNull() ?: if (epubs.isEmpty()) files.singleOrNull() else null
                return@withContext source(current, file ?: throw ProviderException("This TorBox download has no single EPUB or text edition. Choose its ebook file in the browser."))
            }
            if (current?.text("download_state") in setOf("error", "failed") || !current?.text("error").isNullOrBlank())
                throw ProviderException("TorBox could not download this ebook. Try the website download instead.")
            val percent = current?.text("progress")?.toDoubleOrNull()?.let { (it.coerceIn(0.0, 1.0) * 100).toInt() }
            step("TorBox is preparing the ebook${percent?.let { " · $it%" }.orEmpty()}")
            if (attempt < maxPolls - 1) delay(pollDelayMs)
        }
        throw WebEbookPendingException()
    }

    private suspend fun list(id: Long? = null): List<JsonObject> {
        currentCoroutineContext().ensureActive()
        val params = mapOf("bypass_cache" to "true") + (id?.let { mapOf("id" to it.toString()) } ?: emptyMap())
        return when (val data = torbox.request("webdl/mylist", params)["data"]) {
            is JsonArray -> data.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(data)
            else -> emptyList()
        }
    }
    private fun source(item: JsonObject, file: JsonObject) = BookTextSource(
        "torbox-web:${item.number("id")}:${file.number("id")}", file.text("name").substringAfterLast('/'),
        format = textFileFormat(file.text("name"))!!, provider = "torbox-web", torrentId = item.number("id"), fileId = file.number("id"),
        attribution = "Ebook in your TorBox web downloads")
    private fun textFiles(item: JsonObject) = item.objects("files").filter {
        it["id"]?.jsonPrimitive?.longOrNull?.let { id -> id >= 0 } == true && textFileFormat(it.text("name")) in setOf("EPUB", "TXT") && !Regex("(?i)(^|[/ _-])(readme|license|sample|credits)([. _-]|$)").containsMatchIn(it.text("name"))
    }
    private fun ready(item: JsonObject) = item.flag("download_finished") && item.flag("download_present")
    companion object {
        internal fun linkHash(url: String) = MessageDigest.getInstance("MD5").digest(url.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

/** A queued TorBox item is kept for retry; it is never silently duplicated as a direct download. */
class WebEbookPendingException : Exception("TorBox is still preparing this ebook. Try the download again later; the existing TorBox item will be reused.")
