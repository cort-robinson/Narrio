package app.narrio.data

import app.narrio.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A page read the way a browser reads it, without showing it. [script] is a JavaScript expression that returns a
 * string once the page has what's needed, or null while it is still loading or passing a browser check.
 */
fun interface BrowserPages {
    suspend fun read(url: String, script: String, timeoutMs: Long): String
}

/**
 * The website's browser check didn't pass by itself on this phone and wants the reader. Completing it once at [url]
 * in the in-app browser shares its cookies with later hidden searches and downloads.
 */
class BrowserCheckException(val url: String, message: String) : java.io.IOException(message)

/** One search result: a file Anna's Archive identifies by its content MD5. */
data class AnnasRecord(val md5: String, val title: String, val author: String, val language: String, val format: String, val sizeBytes: Long = 0)

/**
 * Anna's Archive searched and downloaded without the reader visiting the website. Its pages sit behind a browser check
 * that a hidden WebView passes by itself. Downloads use Libgen.li's mirror when it has the file, otherwise Anna's free
 * partner server after its wait. No membership key, account, or browser cookie leaves this class.
 */
class AnnasArchive(http: OkHttpClient, private val pages: BrowserPages, private val libgen: String = LIBGEN, private val allowTestHttp: Boolean = false) {
    private val http = http.newBuilder().followRedirects(false).followSslRedirects(false).build()

    suspend fun search(url: String): List<AnnasRecord> {
        validate(url)
        val page = pages.read(url, SEARCH_SCRIPT, SEARCH_TIMEOUT_MS)
        if (page == BROWSER_CHECK) throw BrowserCheckException(url, "Anna's Archive wants a quick browser check on this phone. Open it, then Narrio searches by itself again.")
        val rows = NarrioJson.parseToJsonElement(page) as? JsonArray ?: return emptyList()
        return rows.mapNotNull { row ->
            val item = row as? JsonObject ?: return@mapNotNull null
            val md5 = item.text("md5").lowercase()
            val title = item.text("title").trim()
            val format = textFileFormat(".${item.text("format").trim()}")?.takeIf { it in setOf("EPUB", "TXT") }
            if (!md5.matches(MD5) || title.isBlank() || format == null) return@mapNotNull null
            AnnasRecord(md5, title, item.text("author").trim().trim(';', ',').trim(), item.text("language").trim(), format, size(item.text("size")))
        }.distinctBy { it.md5 }.take(MAX_RESULTS)
    }

    /** Matched records only; [host] is the mirror the search ran on, which also serves the record's download pages. */
    fun candidates(book: Audiobook, records: List<AnnasRecord>, host: String, words: String = ""): List<EbookCandidate> = records.mapNotNull { record ->
        val confidence = EbookMatch.confidence(book, listOf(record.title, record.author).filter(String::isNotBlank).joinToString(" - "), words)
        if (confidence == MatchConfidence.NONE) return@mapNotNull null
        EbookCandidate(BookTextSource("annas:${record.md5}", record.title, record.author, record.format, PROVIDER,
            "https://$host/md5/${record.md5}", attribution = "Anna's Archive", language = record.language, sizeBytes = record.sizeBytes), confidence)
    }

    /** "1.2MB", "640.5kB" or "0.9 MB" as bytes; 0 when the listing has no readable size. */
    internal fun size(text: String): Long {
        val match = Regex("(?i)^\\s*(\\d+(?:\\.\\d+)?)\\s*([kmg]?)i?b\\s*$").find(text) ?: return 0
        val unit = when (match.groupValues[2].lowercase()) { "k" -> 1_000.0; "m" -> 1_000_000.0; "g" -> 1_000_000_000.0; else -> 1.0 }
        return (match.groupValues[1].toDouble() * unit).toLong()
    }

    /** A direct file link for a found record: Libgen.li first, since it needs no wait, then Anna's slow partner server. */
    suspend fun download(source: BookTextSource, step: (String) -> Unit): EbookDownloadRequest {
        val record = source.url.toHttpUrl()
        validate(source.url)
        val md5 = source.id.removePrefix("annas:").takeIf { it.matches(MD5) && record.encodedPath == "/md5/$it" }
            ?: throw ProviderException("This Anna's Archive result is unreadable. Search again.")
        step("Finding a download on Anna's Archive")
        libgen(md5)?.let { return EbookDownloadRequest(it, "", "", sourceName = "Anna's Archive") }
        step("Waiting for Anna's Archive's free download server. This can take a minute")
        val slow = record.newBuilder().encodedPath("/slow_download/$md5/0/0").query(null).build().toString()
        val link = try { pages.read(slow, slowScript(md5), SLOW_TIMEOUT_MS) }
            catch (timedOut: TimeoutCancellationException) {
                throw ProviderException("Anna's Archive didn't offer a download in time. Retry, or open it under Search ebook websites.")
            }
        if (link == BROWSER_CHECK) throw BrowserCheckException(source.url, "Anna's Archive wants a quick browser check on this phone. Complete it, then tap the ebook's download.")
        validate(link)
        return EbookDownloadRequest(link, "", "", sourceName = "Anna's Archive")
    }

    private suspend fun libgen(md5: String): String? = withContext(Dispatchers.IO) {
        try {
            val page = http.readCancellable(Request.Builder().url("$libgen/ads.php?md5=$md5").build()) { response ->
                if (!response.isSuccessful) return@readCancellable ""
                val source = response.body?.source() ?: return@readCancellable ""
                if (source.request(MAX_PAGE_BYTES + 1L)) "" else source.readUtf8()
            }
            Regex("get\\.php\\?md5=$md5&(?:amp;)?key=([A-Za-z0-9]+)").find(page)?.let { "$libgen/get.php?md5=$md5&key=${it.groupValues[1]}" }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    private fun validate(url: String) { if (!allowTestHttp) AddonManifest.secureUrl(url) else url.toHttpUrl() }

    companion object {
        const val ADDON_ID = "annas-archive-ebooks"
        const val PROVIDER = "annas-archive"
        internal const val LIBGEN = "https://libgen.li"
        private val MD5 = Regex("[0-9a-f]{32}")
        private const val MAX_RESULTS = 20
        private const val MAX_PAGE_BYTES = 512 * 1024
        // The first visit passes a browser check before the results page loads.
        const val SEARCH_TIMEOUT_MS = 30_000L
        // The free server asks for a wait of up to about a minute before it shows its link.
        private const val SLOW_TIMEOUT_MS = 150_000L
        internal const val BROWSER_CHECK = "browser-check"
        // DDoS-Guard's page once its automatic check has given up and asks the reader instead.
        private const val CHECK_FAILED = "if (/could not verify your browser automatically/i.test(document.body ? document.body.innerText : '')) return '$BROWSER_CHECK';"

        /** Result rows, or an empty list once a results page without any has loaded; null on the browser check. */
        internal val SEARCH_SCRIPT = """
            (() => {
              $CHECK_FAILED
              if (document.readyState !== 'complete' || !location.pathname.startsWith('/search') || !/Anna/.test(document.title)) return null;
              return JSON.stringify([...document.querySelectorAll('a.js-vim-focus[href^="/md5/"]')].map(a => {
                const row = a.closest('div.border-b') || a.parentElement.parentElement;
                const author = [...row.querySelectorAll('a')].find(link => link.querySelector('[class*="user-edit"]'));
                const meta = (row.innerText || '').split('\n').find(line => / · [A-Z0-9]{2,5} · /.test(line)) || '';
                const parts = meta.split(' · ');
                return { md5: a.getAttribute('href').slice(5), title: a.textContent, author: author ? author.textContent : '',
                  language: parts.length > 1 ? parts[0].replace(/\s*\[[^\]]*\]\s*$/, '') : '', format: parts.length > 1 ? parts[1] : '',
                  size: parts.length > 2 ? parts[2] : '' };
              }));
            })()
        """.trimIndent()

        /** The partner server's file link once its countdown ends. The link names part of the record's MD5. */
        internal fun slowScript(md5: String) = """
            (() => {
              $CHECK_FAILED
              const links = [...document.querySelectorAll('main a[href^="https://"]')].map(a => a.href)
                .filter(href => { try { return new URL(href).host !== location.host && href.toLowerCase().includes('${md5.take(12)}'); } catch (e) { return false; } });
              return links.length ? links.sort((a, b) => a.length - b.length)[0] : null;
            })()
        """.trimIndent()
    }
}

/** Anna's Archive results in their own ebook-source section; no TorBox account is needed. */
class AnnasArchiveEbookLookup(private val archive: AnnasArchive, private val addons: AddonManager, private val id: String) : EbookLookup {
    override suspend fun search(book: Audiobook, recordings: List<AudioSource>, budget: SourceSearchBudget, words: String, status: suspend (SourceGroupStatus) -> Unit): EbookLookupResult {
        val url = addons.ebookSearchUrl(id, book, words) ?: return EbookLookupResult(emptyList())
        status(SourceGroupStatus.SEARCHING)
        // Its browser check can take longer than the shared lookup budget the API sources use.
        val records = try { withTimeout(AnnasArchive.SEARCH_TIMEOUT_MS + 5_000) { archive.search(url) } }
            catch (check: BrowserCheckException) { return EbookLookupResult(emptyList(), check.message, check.url) }
        return EbookLookupResult(archive.candidates(book, records, url.toHttpUrl().host, words))
    }
}
