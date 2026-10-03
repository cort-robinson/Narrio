package app.narrio.data

import app.narrio.domain.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class BookTextProviderTest {
    @Test fun publicSearchUsesTitleAndNeverSendsTorBoxCredentials() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"results":[{"id":113,"title":"The Secret Garden","authors":[{"name":"Burnett, Frances Hodgson"}],"languages":["en"],"copyright":false,"media_type":"Text","formats":{"application/epub+zip":"http://www.gutenberg.org/ebooks/113.epub3.noimages"}}]}"""))
            val result = GutenbergTextDiscovery(OkHttpClient(), server.url("/").toString()).search("Secret Garden")
            assertEquals("EPUB", result.single().format)
            assertTrue(result.single().url.startsWith("https://www.gutenberg.org/"))
            val request = server.takeRequest()
            assertEquals("Secret Garden", request.requestUrl?.queryParameter("search"))
            assertEquals("false", request.requestUrl?.queryParameter("copyright"))
            assertNull(request.getHeader("Authorization"))
        } finally { server.shutdown() }
    }

    @Test fun publicSearchRejectsUnknownRightsAndUntrustedDownloadHosts() {
        val root = NarrioJson.parseToJsonElement("""{"results":[{"id":1,"title":"Unknown","copyright":null,"media_type":"Text","formats":{"text/plain":"https://gutenberg.org/book.txt"}},{"id":2,"title":"Redirected","copyright":false,"media_type":"Text","formats":{"text/plain":"https://example.com/book.txt"}}]}""").jsonObject
        assertTrue(GutenbergTextDiscovery.parseResults(root).isEmpty())
    }

    @Test fun companionEbooksAreKeptSeparateFromOrderedAudioAndTemporaryLinks() {
        val item = NarrioJson.parseToJsonElement("""{"id":7,"files":[{"id":1,"name":"Book/2.mp3"},{"id":2,"name":"Book/1.mp3"},{"id":3,"name":"Book/Book.epub"},{"id":4,"name":"Book/readme.txt"},{"id":5,"name":"Book/1.vtt"}]}""").jsonObject
        val source = TorBoxDelivery.mapSources(item, Audiobook("book", "Book", "Author")).single()
        assertEquals(listOf("Book/1.mp3", "Book/2.mp3"), source.parts.map { it.name })
        assertEquals(listOf("EPUB", "VTT"), source.textFiles.map { it.format })
        assertTrue(source.textFiles.all { it.url.isBlank() && it.torrentId == 7L && it.fileId != null })
    }
}
