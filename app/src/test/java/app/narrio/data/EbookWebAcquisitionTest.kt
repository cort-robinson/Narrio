package app.narrio.data

import app.narrio.domain.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class EbookWebAcquisitionTest {
    private val text = "A complete paragraph from a controlled ebook fixture."
    private fun body() = MockResponse().setHeader("Content-Type", "text/plain").setBody(text)

    @Test fun torboxDeliveryIsTriedFirstAndNeverReceivesTheBrowserSession() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(body())
            val original = "https://ebooks.example/book.txt"
            val acquisition = EbookWebAcquisition(OkHttpClient(), { url, _ ->
                assertEquals(original, url)
                BookTextSource("web:1", "book.txt", format = "TXT", provider = "torbox-web")
            }, { server.url("/cdn/book.txt").toString() }, allowTestHttp = true)
            val result = acquisition.acquire(EbookDownloadRequest(original, "book.txt", "text/plain", cookie = "verification=private", referrer = "https://ebooks.example/record"), true) {}
            assertEquals(text, result.bytes.toString(Charsets.UTF_8))
            assertEquals("torbox-web", result.provider)
            assertNull(server.takeRequest().getHeader("Cookie"))
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun torboxFailureFallsBackToTheVerifiedWebsiteSession() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(body())
            val acquisition = EbookWebAcquisition(OkHttpClient(), { _, _ -> throw ProviderException("Unavailable") }, { error("Unused") }, true)
            val result = acquisition.acquire(EbookDownloadRequest(server.url("/book.txt").toString(), "book.txt", "text/plain", "fixture-agent", "verification=fixture",
                server.url("/record?private=value").toString()), true) {}
            assertEquals("web", result.provider)
            val request = server.takeRequest()
            assertEquals("verification=fixture", request.getHeader("Cookie"))
            assertEquals("fixture-agent", request.getHeader("User-Agent"))
            assertFalse(request.getHeader("Referer").orEmpty().contains("private"))
            assertNull(request.getHeader("Authorization"))
        } finally { server.shutdown() }
    }

    @Test fun redirectsNeverForwardCookiesOrReferrersToAnotherOrigin() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val redirected = server.url("/final.txt").newBuilder().host("127.0.0.1").build()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", redirected))
            server.enqueue(body())
            val acquisition = EbookWebAcquisition(OkHttpClient(), { _, _ -> error("Disconnected") }, { error("Unused") }, true)
            acquisition.acquire(EbookDownloadRequest(server.url("/book.txt").toString(), "book.txt", "text/plain", cookie = "verification=fixture",
                referrer = server.url("/record").toString()), false) {}
            assertEquals("verification=fixture", server.takeRequest().getHeader("Cookie"))
            val request = server.takeRequest()
            assertNull(request.getHeader("Cookie")); assertNull(request.getHeader("Referer"))
        } finally { server.shutdown() }
    }

    @Test fun pendingAndCancelledTorboxTransfersDoNotStartDuplicateWebsiteDownloads() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            for (failure in listOf(WebEbookPendingException(), CancellationException("Cancelled"))) {
                val acquisition = EbookWebAcquisition(OkHttpClient(), { _, _ -> throw failure }, { error("Unused") }, true)
                assertSame(failure, runCatching { acquisition.acquire(EbookDownloadRequest(server.url("/book.txt").toString(), "book.txt", "text/plain"), true) {} }.exceptionOrNull())
            }
            assertEquals(0, server.requestCount)
        } finally { server.shutdown() }
    }

    private fun epub(): ByteArray = java.io.ByteArrayOutputStream().also { output -> java.util.zip.ZipOutputStream(output).use { zip ->
        mapOf("mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="book.opf"/></rootfiles></container>""",
            "book.opf" to """<package xmlns:dc="http://purl.org/dc/elements/1.1/"><metadata><dc:title>A Book</dc:title></metadata><manifest><item id="one" href="one.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="one"/></spine></package>""",
            "one.xhtml" to "<html><body><p>$text</p></body></html>").forEach { (path, content) ->
            zip.putNextEntry(java.util.zip.ZipEntry(path)); zip.write(content.toByteArray()); zip.closeEntry()
        }
    } }.toByteArray()

    @Test fun anUnnamedBinDownloadIsKeptWhenItsContentsAreAnEpub() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream").setBody(okio.Buffer().write(epub())))
            val acquisition = EbookWebAcquisition(OkHttpClient(), { _, _ -> error("Disconnected") }, { error("Unused") }, true)
            val result = acquisition.acquire(EbookDownloadRequest(server.url("/annas-arch-5b55b4d7e34d.epub").toString(), "annas-arch-5b55b4d7e34d.bin", "application/octet-stream"), false) {}
            assertEquals("EPUB", result.format)
            server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream").setBody("%PDF-1.7 not an ebook"))
            assertTrue(runCatching { acquisition.acquire(EbookDownloadRequest(server.url("/file").toString(), "downloadfile.bin", "application/octet-stream"), false) {} }
                .exceptionOrNull()?.message.orEmpty().contains("isn't an EPUB"))
        } finally { server.shutdown() }
    }

    @Test fun htmlVerificationAndUnsupportedFormatsNeverBecomeEbookContent() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val acquisition = EbookWebAcquisition(OkHttpClient(), { _, _ -> error("Disconnected") }, { error("Unused") }, true)
            assertTrue(runCatching { acquisition.acquire(EbookDownloadRequest(server.url("/book.pdf").toString(), "book.pdf", "application/pdf"), false) {} }.isFailure)
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>Verify your browser</html>"))
            assertTrue(runCatching { acquisition.acquire(EbookDownloadRequest(server.url("/book.epub").toString(), "book.epub", "application/epub+zip"), false) {} }.exceptionOrNull()?.message.orEmpty().contains("verification"))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody("<!DOCTYPE html><html>Verify your browser</html>"))
            assertTrue(runCatching { acquisition.acquire(EbookDownloadRequest(server.url("/book.txt").toString(), "book.txt", "text/plain"), false) {} }.exceptionOrNull()?.message.orEmpty().contains("verification"))
        } finally { server.shutdown() }
    }
}
