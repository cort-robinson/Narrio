package app.narrio.data

import app.narrio.domain.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class AnnasArchiveTest {
    private val book = Audiobook("book", "Project Hail Mary", "Andy Weir")
    private val md5 = "2c8084544b69de594dc73074a7471170"
    private val results = """[
        {"md5":"$md5","title":"Project Hail Mary","author":"Andy Weir;","language":"English","format":"EPUB"},
        {"md5":"$md5","title":"Project Hail Mary","author":"Andy Weir","language":"English","format":"EPUB"},
        {"md5":"5b55b4d7e34d49ebe26bbb0e02abd9d7","title":"Project Hail Mary","author":"","language":"","format":"EPUB"},
        {"md5":"not-an-md5","title":"Project Hail Mary","author":"Andy Weir","language":"English","format":"EPUB"},
        {"md5":"0123456789abcdef0123456789abcdef","title":"Project Hail Mary","author":"Andy Weir","language":"English","format":"PDF"},
        {"md5":"fedcba9876543210fedcba9876543210","title":"The Martian","author":"Andy Weir","language":"English","format":"EPUB"}
    ]"""

    @Test fun searchKeepsReadableEbookResultsAndMatchesThemLikeOtherSources() = runBlocking {
        var requested = ""
        val archive = AnnasArchive(OkHttpClient(), { url, script, _ -> requested = url; assertTrue(script.contains("js-vim-focus")); results })
        val records = archive.search("https://annas-archive.gl/search?q=Project%20Hail%20Mary%20Andy%20Weir&ext=epub")
        assertEquals("https://annas-archive.gl/search?q=Project%20Hail%20Mary%20Andy%20Weir&ext=epub", requested)
        assertEquals(listOf(md5, "5b55b4d7e34d49ebe26bbb0e02abd9d7", "fedcba9876543210fedcba9876543210"), records.map { it.md5 })
        assertEquals("Andy Weir", records.first().author)
        val candidates = archive.candidates(book, records, "annas-archive.gl")
        assertEquals(listOf(MatchConfidence.STRONG, MatchConfidence.POSSIBLE), candidates.map { it.confidence })
        val first = candidates.first().source
        assertEquals("annas:$md5", first.id)
        assertEquals("https://annas-archive.gl/md5/$md5", first.url)
        assertEquals(AnnasArchive.PROVIDER, first.provider)
        assertEquals("EPUB", first.format)
    }

    @Test fun searchRejectsAnInsecureMirrorBeforeLoadingIt() = runBlocking {
        val archive = AnnasArchive(OkHttpClient(), { _, _, _ -> error("Not loaded") })
        assertTrue(runCatching { archive.search("http://annas-archive.gl/search?q=x") }.isFailure)
    }

    @Test fun libgenMirrorIsUsedWithoutWaitingForTheSlowServer() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody("""<a href="get.php?md5=$md5&key=ALQ1X27BG4F4348X"><h2>GET</h2></a>"""))
            val base = server.url("/").toString().trimEnd('/')
            val archive = AnnasArchive(OkHttpClient(), { _, _, _ -> error("The slow server isn't needed") }, base, allowTestHttp = true)
            val request = archive.download(source("$base/md5/$md5"), {})
            assertEquals("$base/get.php?md5=$md5&key=ALQ1X27BG4F4348X", request.url)
            assertEquals("", request.cookie)
            assertEquals("/ads.php?md5=$md5", server.takeRequest().path)
        } finally { server.shutdown() }
    }

    @Test fun recordsLibgenDoesNotHaveWaitForTheSlowServerOnTheSearchedMirror() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody("<html>No download here</html>"))
            val base = server.url("/").toString().trimEnd('/')
            var loaded = ""
            val archive = AnnasArchive(OkHttpClient(), { url, script, _ ->
                loaded = url; assertTrue(script.contains(md5.take(12))); "https://files.example/annas-arch-${md5.take(12)}.epub"
            }, base, allowTestHttp = true)
            val steps = mutableListOf<String>()
            val request = archive.download(source("https://annas-archive.gl/md5/$md5"), { steps += it })
            assertEquals("https://annas-archive.gl/slow_download/$md5/0/0", loaded)
            assertEquals("https://files.example/annas-arch-${md5.take(12)}.epub", request.url)
            assertTrue(steps.last().contains("free download server"))
        } finally { server.shutdown() }
    }

    @Test fun aResultWhoseIdAndPageDisagreeIsNeverDownloaded() = runBlocking {
        val archive = AnnasArchive(OkHttpClient(), { _, _, _ -> error("Not loaded") }, "https://libgen.invalid")
        val tampered = source("https://annas-archive.gl/md5/5b55b4d7e34d49ebe26bbb0e02abd9d7")
        assertTrue(runCatching { archive.download(tampered, {}) }.exceptionOrNull() is ProviderException)
    }

    private fun source(url: String) = BookTextSource("annas:$md5", "Project Hail Mary", "Andy Weir", "EPUB", AnnasArchive.PROVIDER, url)
}
