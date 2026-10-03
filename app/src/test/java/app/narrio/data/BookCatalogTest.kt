package app.narrio.data

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class BookCatalogTest {
    private fun product(title: String = "Project Hail Mary", author: String = "Andy Weir", narrator: String = "Ray Porter", asin: String = "B08G9PRS1K", description: String = "A space adventure.") = """{
        "asin":"$asin","title":"$title","content_type":"Product","authors":[{"name":"$author"}],
        "narrators":[{"name":"$narrator"}],"publisher_summary":"$description","product_images":{"500":"https://images.example/book.jpg"}
    }"""
    private fun server(block: suspend (MockWebServer, BookCatalog) -> Unit) = runBlocking {
        val server = MockWebServer(); server.start()
        val metadata = BookMetadata(OkHttpClient(), server.url("/audible/").toString(), server.url("/library/").toString())
        try { block(server, BookCatalog(metadata, server.url("/google").toString())) } finally { server.shutdown() }
    }
    private fun audible(server: MockWebServer, vararg products: String) = server.enqueue(MockResponse().setBody("""{"products":[${products.joinToString(",")}]}"""))

    @Test fun editionsCollapseToOneRichBookWithoutAnyAudioOrAccountRequests() = server { server, catalog ->
        audible(server, product(), product(narrator = "Other Reader", asin = "B08G9PRS2K"), product(title = "Another Book"))
        val book = catalog.search("Project Hail Mary").single()
        assertEquals("Andy Weir", book.author); assertEquals("A space adventure.", book.description)
        assertTrue(book.coverUrl.startsWith("https://")); assertEquals("catalog", book.provider)
        assertTrue(book.sources.isEmpty()); assertTrue(book.torrentHash.isEmpty()); assertEquals("unchecked", book.cacheState)
        assertFalse(book.narratorFromCatalog); assertEquals("Narrator depends on source", book.narrator)
        assertEquals(1, server.requestCount); assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test fun sameTitleWithDifferentAuthorsAndSequelsRemainDistinct() = server { server, catalog ->
        audible(server, product("The Gift", "Author One"), product("The Gift", "Author Two"), product("The Gift 2", "Author One"))
        val books = catalog.search("The Gift")
        assertEquals(3, books.size); assertEquals(3, books.map { it.id }.distinct().size)
    }

    @Test fun knownPublisherAndEditionSuffixesCollapseWithoutHidingMeaningfulSubtitles() = server { server, catalog ->
        audible(server, product("Pride and Prejudice", "Jane Austen"), product("Pride and Prejudice (Penguin Classics)", "Jane Austen"),
            product("Pride and Prejudice (Special Edition)", "Jane Austen"), product("Pride and Prejudice: A Play", "Jane Austen"))
        val books = catalog.search("Pride and Prejudice")
        assertEquals(listOf("Pride and Prejudice", "Pride and Prejudice: A Play"), books.map { it.title })
    }

    @Test fun identitiesSurviveProviderOrderEditionAndPunctuationChanges() {
        val first = BookDetails("Project Hail Mary [Unabridged]", listOf("Andy Weir"), emptyList(), "Story", "https://cover", "", "Audible", "https://audible")
        val second = first.copy(title = "Project Hail Mary", authors = listOf("Weir, Andy"), provider = "Google Books", url = "https://google")
        assertEquals(BookCatalog.collapse(listOf(first, second), 1).single().id, BookCatalog.collapse(listOf(second), 2).single().id)
    }

    @Test fun googleFallbackAddsDescriptionAndSecureArtworkAfterPrimaryOutage() = server { server, catalog ->
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("""{"items":[{"id":"book-id","volumeInfo":{"title":"Project Hail Mary","authors":["Andy Weir"],"description":"<p>A &amp; B.</p>","imageLinks":{"thumbnail":"http://books.google.com/cover.jpg"}}}]}"""))
        val book = catalog.search("Project Hail Mary").single()
        assertEquals("A & B.", book.description); assertEquals("Google Books", book.metadataSource)
        assertEquals("https://books.google.com/cover.jpg", book.coverUrl)
        assertEquals(2, server.requestCount)
        val requests = List(2) { server.takeRequest() }
        assertEquals("books", requests.last().requestUrl?.queryParameter("printType"))
        assertTrue(requests.all { it.getHeader("Authorization") == null })
    }

    @Test fun openLibraryFallbackHydratesWorkDescriptions() = server { server, catalog ->
        repeat(2) { server.enqueue(MockResponse().setResponseCode(429)) }
        server.enqueue(MockResponse().setBody("""{"docs":[{"key":"/works/OL123W","title":"Project Hail Mary","author_name":["Andy Weir"],"cover_i":456}]}"""))
        server.enqueue(MockResponse().setBody("""{"description":{"value":"A space adventure."}}"""))
        val book = catalog.search("Project Hail Mary").single()
        assertEquals("Open Library", book.metadataSource); assertEquals("A space adventure.", book.description)
        assertEquals("/library/works/OL123W.json", List(4) { server.takeRequest() }.last().requestUrl?.encodedPath)
    }

    @Test fun richResultsExcludeSparseRecordsAndCacheDoesNotRepeatSearch() = server { server, catalog ->
        audible(server, product(), product("Project Hail Mary Companion", description = ""))
        server.enqueue(MockResponse().setBody("""{"items":[]}"""))
        assertEquals(1, catalog.search("Project Hail Mary").size)
        assertEquals(1, catalog.search(" project hail mary ").size)
        assertEquals(2, server.requestCount)
    }

    @Test fun totalOutageIsRetryableAndDoesNotBecomeAnEmptySuccess() = server { server, catalog ->
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }
        assertTrue(runCatching { catalog.search("Project Hail Mary") }.exceptionOrNull() is ProviderException)
        audible(server, product())
        assertEquals(1, catalog.search("Project Hail Mary").size); assertEquals(4, server.requestCount)
    }

    @Test fun cancellationStopsOutstandingCatalogLookup() = server { server, catalog ->
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        coroutineScope {
            val lookup = async { catalog.search("Project Hail Mary") }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
            lookup.cancelAndJoin(); assertTrue(lookup.isCancelled)
        }
    }
}
