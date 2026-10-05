package app.narrio.data

import app.narrio.domain.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Browser session values stay in memory and are never passed to TorBox or saved with an edition. */
data class EbookDownloadRequest(val url: String, val fileName: String, val mimeType: String, val userAgent: String = "", val cookie: String = "", val referrer: String = "", val sourceName: String = "Ebook website")
data class DownloadedWebEbook(val bytes: ByteArray, val format: String, val attribution: String, val provider: String)

class EbookWebAcquisition(
    http: OkHttpClient,
    private val torboxAcquire: suspend (String, (String) -> Unit) -> BookTextSource,
    private val torboxResolve: suspend (BookTextSource) -> String,
    private val allowTestHttp: Boolean = false,
) {
    private val http = http.newBuilder().followRedirects(false).followSslRedirects(false).build()

    suspend fun acquire(request: EbookDownloadRequest, connected: Boolean, step: (String) -> Unit): DownloadedWebEbook {
        validate(request.url)
        val format = format(request.fileName, request.mimeType)
            ?: throw ProviderException("Choose an EPUB or text download. PDF, Kindle, and other file types cannot be opened.")
        if (connected) {
            try {
                val source = torboxAcquire(request.url, step)
                val url = torboxResolve(source)
                step("Saving the TorBox ebook on this phone")
                return DownloadedWebEbook(download(request.copy(url = url, cookie = "", referrer = ""), source.format), source.format, "${request.sourceName} via TorBox", "torbox-web")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (pending: WebEbookPendingException) { throw pending }
            catch (_: Exception) { step("TorBox couldn't handle this link. Using the website download") }
        } else step("Saving the website ebook on this phone")
        return DownloadedWebEbook(download(request, format), format, "${request.sourceName} website download", "web")
    }

    private suspend fun download(original: EbookDownloadRequest, format: String): ByteArray = withContext(Dispatchers.IO) {
        val originalUrl = original.url.toHttpUrl()
        var url = originalUrl
        repeat(6) {
            ensureActive()
            validate(url.toString())
            val request = Request.Builder().url(url)
            if (original.userAgent.isNotBlank()) request.header("User-Agent", original.userAgent)
            // Verified-browser cookies are never forwarded to another origin or to a TorBox CDN.
            if (url.host == originalUrl.host && url.scheme == originalUrl.scheme && url.port == originalUrl.port) {
                if (original.cookie.isNotBlank()) request.header("Cookie", original.cookie)
                original.referrer.takeIf(String::isNotBlank)?.let { referrer ->
                    runCatching { referrer.toHttpUrl() }.getOrNull()?.takeIf { it.host == url.host }?.let {
                        request.header("Referer", it.newBuilder().query(null).fragment(null).build().toString())
                    }
                }
            }
            val response = fetch(request.build())
            response.use {
                if (it.code in setOf(301, 302, 303, 307, 308)) {
                    url = url.resolve(it.header("Location").orEmpty()) ?: throw ProviderException("The ebook download has an invalid redirect.")
                } else {
                    if (!it.isSuccessful) throw ProviderException("The website couldn't download this ebook. Complete its verification or choose another download link.")
                    val body = it.body ?: throw ProviderException("The ebook download was empty.")
                    if (it.header("Content-Type").orEmpty().contains("text/html", true)) throw ProviderException("This link returned a web page. Complete its verification and tap the final ebook download.")
                    if (body.contentLength() > BookTextParser.MAX_FILE_BYTES) throw ProviderException("This ebook exceeds Narrio's 20 MB file limit.")
                    val bytes = BookTextParser.readBounded(body.byteStream())
                    ensureActive()
                    val prefix = bytes.take(512).toByteArray().toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
                    if (prefix.startsWith("<!doctype html", true) || prefix.startsWith("<html", true))
                        throw ProviderException("This link returned a web page. Complete its verification and tap the final ebook download.")
                    BookTextParser.parse(bytes, format, "Downloaded ebook")
                    return@withContext bytes
                }
            }
        }
        throw ProviderException("The ebook download redirected too many times. Choose another download link.")
    }

    private suspend fun fetch(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) { continuation.resume(response) { _, value, _ -> value.close() } }
        })
    }
    private fun validate(url: String) { if (!allowTestHttp) AddonManifest.secureUrl(url) else url.toHttpUrl() }
    companion object {
        internal fun format(name: String, mime: String): String? = when {
            textFileFormat(name) in setOf("EPUB", "TXT") -> textFileFormat(name)
            mime.substringBefore(';').trim().equals("application/epub+zip", true) -> "EPUB"
            mime.substringBefore(';').trim().equals("text/plain", true) -> "TXT"
            else -> null
        }
    }
}
