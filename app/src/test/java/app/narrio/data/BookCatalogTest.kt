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
    private fun browsing(block: suspend (MockWebServer, BookCatalog) -> Unit) = runBlocking {
        val server = MockWebServer(); server.start()
        val metadata = BookMetadata(OkHttpClient(), server.url("/audible/").toString(), server.url("/library/").toString())
        val charts = AudiobookCharts(metadata, server.url("/apple/").toString(), now = { 1_790_000_000_000 })
        try { block(server, BookCatalog(metadata, server.url("/google").toString(), charts = charts)) } finally { server.shutdown() }
    }
    private fun entry(id: Int, name: String, artist: String = "Author $id", released: String = "2020-01-01T00:00:00-07:00") = """{
        "im:name":{"label":"$name"},"im:artist":{"label":"$artist"},"id":{"attributes":{"im:id":"$id"}},
        "im:releaseDate":{"label":"$released"},"im:image":[{"label":"https://art.example/$id/170x170bb.png"}]
    }"""
    private fun chart(server: MockWebServer, vararg entries: String) = server.enqueue(MockResponse().setBody("""{"feed":{"entry":[${entries.joinToString(",")}]}}"""))
    private fun lookup(server: MockWebServer, vararg ids: Int) = server.enqueue(MockResponse().setBody("""{"results":[${ids.joinToString(",") {
        """{"collectionId":$it,"description":"<b>Story $it.</b>","artworkUrl100":"https://art.example/$it/100x100bb.jpg"}"""
    }}]}"""))

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

    @Test fun browsingListsEstablishedChartBooksBeforeNewReleasesWithDescriptionsFromOneLookup() = browsing { server, catalog ->
        chart(server, entry(12, "Out This Week", released = "2026-09-18T00:00:00-07:00"), *(1..9).map { entry(it, "Book $it (Unabridged)") }.toTypedArray(),
            entry(10, "Fourth Wing (1 of 2) [Dramatized Adaptation] : The Empyrean 1 (Empyrean)"),
            entry(11, "Next Year's Thriller", released = "2027-03-01T00:00:00-07:00"))
        lookup(server, *(1..9).toList().toIntArray(), 12)
        val books = catalog.search("", "Mystery")
        assertEquals((1..9).map { "Book $it" } + "Out This Week", books.map { it.title })
        assertEquals("Story 1.", books.first().description); assertEquals("https://art.example/1/600x600bb.jpg", books.first().coverUrl)
        assertEquals("Apple Books", books.first().metadataSource); assertTrue(books.all { it.provider == "catalog" && it.sources.isEmpty() })
        val requests = List(2) { server.takeRequest() }
        assertEquals("/apple/us/rss/topaudiobooks/limit=60/genre=50000051/json", requests.first().requestUrl?.encodedPath)
        assertEquals(((1..9) + 12).joinToString(","), requests.last().requestUrl?.queryParameter("id"))
        assertTrue(requests.all { it.getHeader("Authorization") == null })
        assertEquals(books, catalog.search("", "Mystery")); assertEquals(2, server.requestCount)
    }

    @Test fun storeTitlesDropSeriesAndMarketingLabelsButKeepRealSubtitles() {
        assertEquals("Dungeon Crawler Carl", AudiobookCharts.title("Dungeon Crawler Carl: Dungeon Crawler Carl, Book 1 (Unabridged)"))
        assertEquals("Yesteryear", AudiobookCharts.title("Yesteryear: A GMA Book Club Pick: A Novel (Unabridged)"))
        assertEquals("Triptych", AudiobookCharts.title("Triptych: A Will Trent Novel (Unabridged)"))
        assertEquals("Big Little Truths", AudiobookCharts.title("Big Little Truths: A Novel: The Sequel to Big Little Lies (Unabridged)"))
        assertEquals("Fourth Wing", AudiobookCharts.title("Fourth Wing (Empyrean)"))
        assertEquals("The American Way of Killing: The Invention of an Epidemic", AudiobookCharts.title("The American Way of Killing : The Invention of an Epidemic"))
        assertEquals("Say Nothing: A True Story of Murder and Memory in Northern Ireland", AudiobookCharts.title("Say Nothing: A True Story of Murder and Memory in Northern Ireland (Unabridged)"))
        assertEquals(listOf("Stephen King", "Peter Straub"), AudiobookCharts.authors("Stephen King & Peter Straub"))
        assertEquals(listOf("W. Lee Warren, MD"), AudiobookCharts.authors("W. Lee Warren, MD"))
    }

    @Test fun chartOutageFallsBackToKeywordBrowsingWithoutStoreExclusivesAndRetriesSoon() = runBlocking {
        val server = MockWebServer(); server.start()
        var time = 1_790_000_000_000
        val metadata = BookMetadata(OkHttpClient(), server.url("/audible/").toString(), server.url("/library/").toString())
        val catalog = BookCatalog(metadata, server.url("/google").toString(), now = { time }, charts = AudiobookCharts(metadata, server.url("/apple/").toString(), now = { time }))
        try {
            server.enqueue(MockResponse().setResponseCode(503))
            audible(server, product(), product("Original Story", "Audio Author", asin = "B08G9PRS3K").replace("\"content_type\"", "\"publisher_name\":\"Audible Originals\",\"content_type\""))
            assertEquals(listOf("Project Hail Mary"), catalog.search("").map { it.title })
            assertEquals("bestsellers", List(2) { server.takeRequest() }.last().requestUrl?.queryParameter("keywords"))
            time += 6 * 60_000
            chart(server, *(1..8).map { entry(it, "Book $it") }.toTypedArray()); lookup(server, *(1..8).toList().toIntArray())
            assertEquals(8, catalog.search("").size)
            assertEquals("/apple/us/rss/topaudiobooks/limit=60/json", server.takeRequest().requestUrl?.encodedPath)
        } finally { server.shutdown() }
    }

    @Test fun typedSearchStillUsesTheFullCatalogIncludingStoreExclusives() = browsing { server, catalog ->
        audible(server, product("The Dispatcher", "John Scalzi").replace("\"content_type\"", "\"publisher_name\":\"Audible Originals\",\"content_type\""))
        assertEquals(listOf("The Dispatcher"), catalog.search("The Dispatcher").map { it.title })
        assertEquals("/audible/products", server.takeRequest().requestUrl?.encodedPath)
    }
}
