package app.narrio.data

import app.narrio.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class BookMetadataTest {
    private fun release(title: String = "Andy Weir - Project Hail Mary [M4B, Unabridged]") = Audiobook(
        "knaben:release", title, "Author not verified", "Narrator not verified", "Language not verified",
        description = "Indexed release. Inspect the files.", provider = "knaben", detailsLoaded = true,
        torrentHash = "a".repeat(40), magnetUri = "magnet:?xt=urn:btih:${"a".repeat(40)}",
        cacheState = "cached", cachedFormats = listOf("M4B"),
        sources = listOf(AudioSource("original-layout", "Whole book", "M4B", listOf(AudioPart("original-part", "book.m4b", "Book")))),
    )
    private fun product(title: String = "Project Hail Mary", author: String = "Andy Weir", narrator: String = "Ray Porter", asin: String = "B08G9PRS1K") = """{
        "asin":"$asin","title":"$title","content_type":"Product","language":"english",
        "authors":[{"name":"$author"}],"narrators":[{"name":"$narrator"}],
        "publisher_summary":"<p>A story &amp; a mission.</p><p>Don&#8217;t miss it.</p>",
        "product_images":{"500":"https://images.example/500.jpg","1024":"https://images.example/1024.jpg","2048":"http://insecure.example/cover.jpg"}
    }"""
    private fun server(block: suspend (MockWebServer, BookMetadata) -> Unit) = runBlocking {
        val server = MockWebServer(); server.start()
        try { block(server, BookMetadata(OkHttpClient(), server.url("/audible/").toString(), server.url("/library/").toString(), { 10_000 }, appleUrl = server.url("/apple/").toString())) }
        finally { server.shutdown() }
    }
    private fun audible(server: MockWebServer, vararg products: String) {
        server.enqueue(MockResponse().setBody("""{"products":[${products.joinToString(",")}]}"""))
    }
    private fun noLibraryMatch(server: MockWebServer) { server.enqueue(MockResponse().setBody("""{"docs":[]}""")) }

    @Test fun audibleBannerArtIsReplacedByTheEbookCoverOnceAndRemembered() = server { server, metadata ->
        audible(server, product().replace("\"content_type\"", "\"publisher_name\":\"Audible Studios\",\"content_type\"")
            .replace("https://images.example/1024.jpg", "https://m.media-amazon.com/images/I/banner._SL1024_.jpg"))
        server.enqueue(MockResponse().setBody("""{"results":[
            {"kind":"ebook","trackName":"Project Hail Mary: A Novel","artistName":"Other Writer","artworkUrl100":"https://art.example/wrong/100x100bb.jpg"},
            {"kind":"ebook","trackName":"Project Hail Mary (Italian edition)","artistName":"Andy Weir","artworkUrl100":"https://art.example/italian/100x100bb.jpg"},
            {"kind":"ebook","trackName":"Project Hail Mary: A Novel","artistName":"Andy Weir","artworkUrl100":"https://art.example/phm/100x100bb.jpg"}]}"""))
        val result = metadata.enrich(release())
        assertEquals("https://art.example/phm/600x600bb.jpg", result.coverUrl)
        server.takeRequest()
        val search = server.takeRequest().requestUrl!!
        assertEquals("/apple/search", search.encodedPath); assertEquals("Andy Weir", search.queryParameter("term"))
        assertEquals("authorTerm", search.queryParameter("attribute")); assertEquals("ebook", search.queryParameter("entity"))
        val branded = BookDetails("Project Hail Mary", listOf("Andy Weir"), emptyList(), "", "https://m.media-amazon.com/x.jpg", "", "Audible", "", "Audible Studios")
        val clean = metadata.unbranded(branded)!!
        assertEquals("https://art.example/phm/600x600bb.jpg", clean.coverUrl); assertFalse(clean.brandedCover)
        assertEquals(2, server.requestCount)
    }

    @Test fun failedCoverLookupKeepsStoreArtAndIsNotRemembered() = server { server, metadata ->
        val branded = BookDetails("Hopeless", listOf("Colleen Hoover"), emptyList(), "", "https://m.media-amazon.com/x.jpg", "", "Audible", "", "Audible Studios")
        server.enqueue(MockResponse().setResponseCode(403))
        assertNull(metadata.unbranded(branded))
        server.enqueue(MockResponse().setBody("""{"results":[{"trackName":"Hopeless","artistName":"Colleen Hoover","artworkUrl100":"https://art.example/h/100x100bb.jpg"}]}"""))
        assertEquals("https://art.example/h/600x600bb.jpg", metadata.unbranded(branded)?.coverUrl)
    }

    @Test fun onlyAudibleProducedArtCountsAsBranded() {
        fun details(publisher: String) = BookDetails("Book", listOf("Author"), emptyList(), "", "https://covers.example/a.jpg", "", "Audible", "", publisher)
        assertTrue(details("Audible Originals").brandedCover)
        assertTrue(details("Pottermore Publishing and Audible Studios").brandedCover)
        assertFalse(details("Macmillan Audio").brandedCover)
    }

    @Test fun matchingCatalogEnrichesAllFieldsWithoutChangingPlayableIdentity() = server { server, metadata ->
        audible(server, product())
        val original = release(); val result = metadata.enrich(original)
        assertEquals("Project Hail Mary", result.title); assertEquals("Andy Weir", result.author)
        assertEquals("Ray Porter", result.narrator); assertTrue(result.narratorFromCatalog)
        assertEquals("A story & a mission.\nDon\u2019t miss it.", result.description)
        assertEquals("https://images.example/1024.jpg", result.coverUrl)
        assertEquals(original.title, result.releaseTitle); assertEquals("Audible", result.metadataSource)
        assertEquals(original.id, result.id); assertEquals(original.sources, result.sources)
        assertEquals(original.torrentHash, result.torrentHash); assertEquals(original.magnetUri, result.magnetUri)
        assertEquals(original.cachedFormats, result.cachedFormats); assertEquals(original.language, result.language)
        val request = server.takeRequest()
        assertEquals("/audible/products", request.requestUrl?.encodedPath)
        assertNull(request.getHeader("Authorization"))
        assertTrue(request.requestUrl?.queryParameter("response_groups").orEmpty().contains("product_extended_attrs"))
        assertFalse(request.requestUrl?.queryParameter("keywords").orEmpty().contains("M4B"))
    }

    @Test fun differentAuthorsForTheSameTitleStayUnknown() = server { server, metadata ->
        audible(server, product("The Gift", "Author One"), product("The Gift", "Author Two", asin = "B08G9PRS2K"))
        noLibraryMatch(server)
        val original = release("The Gift")
        assertEquals(original, metadata.enrich(original))
    }

    @Test fun narratorAmbiguityDoesNotSelectTheFirstRecording() = server { server, metadata ->
        audible(server, product(narrator = "First Reader"), product(narrator = "Second Reader", asin = "B08G9PRS2K"))
        val result = metadata.enrich(release())
        assertEquals("Andy Weir", result.author); assertEquals("Narrator not verified", result.narrator)
        assertFalse(result.narratorFromCatalog)
    }

    @Test fun narratorHintSelectsTheMatchingCatalogEdition() = server { server, metadata ->
        audible(server, product(narrator = "First Reader"), product(narrator = "Second Reader", asin = "B08G9PRS2K"))
        val result = metadata.enrich(release("Andy Weir - Project Hail Mary [Second Reader, MP3]"))
        assertEquals("Second Reader", result.narrator)
        assertEquals("https://www.audible.com/pd/B08G9PRS2K", result.metadataUrl)
        assertTrue(result.narratorFromCatalog)
    }

    @Test fun summariesCollectionsAndWrongAuthorsDoNotReplaceABook() = server { server, metadata ->
        for (original in listOf(release(), release("Andy Weir - Project Hail Mary Complete Collection"),
            release("Project Hail Mary").copy(author = "Someone Else"))) {
            audible(server, if (original.title.contains("Collection") || original.author == "Someone Else") product() else product("Summary of Project Hail Mary", "Study Guides"))
            noLibraryMatch(server)
            assertEquals(original, metadata.enrich(original))
        }
    }

    @Test fun podcastWithAnExactTitleIsNotAnAudiobookMatch() = server { server, metadata ->
        audible(server, product().replace("\"Product\"", "\"Podcast\""))
        noLibraryMatch(server)
        assertEquals(release(), metadata.enrich(release()))
    }

    @Test fun sourceNarratorIsPreservedEvenWhenTheCatalogHasAnotherEdition() = server { server, metadata ->
        audible(server, product())
        val result = metadata.enrich(release("Project Hail Mary").copy(author = "Andy Weir", narrator = "A Source Reader"))
        assertEquals("A Source Reader", result.narrator); assertFalse(result.narratorFromCatalog)
    }

    @Test fun bilingualReleaseAndReversedAuthorNameMatchWithoutGuessingLanguage() = server { server, metadata ->
        audible(server, product())
        val original = release("[Английский] Weir Andy / Вейер Энди - Project Hail Mary / Проект [Ray Porter, 2021, MP3, 128 kbps]")
        val result = metadata.enrich(original)
        assertEquals("Andy Weir", result.author); assertEquals("Project Hail Mary", result.title)
        assertEquals("Ray Porter", result.narrator); assertEquals(original.language, result.language)
        assertEquals(original.title, result.releaseTitle)
    }

    @Test fun cachedBookInformationDoesNotReuseAnotherReleasesFiles() = server { server, metadata ->
        audible(server, product())
        metadata.enrich(release())
        val other = release().copy(id = "torbox:99", provider = "torbox", sources = emptyList(), torrentHash = "b".repeat(40), cacheState = "uncached")
        val result = metadata.enrich(other)
        assertEquals(1, server.requestCount); assertEquals("Ray Porter", result.narrator)
        assertEquals(other.id, result.id); assertEquals(other.sources, result.sources); assertEquals("uncached", result.cacheState)
        assertEquals(other.torrentHash, result.torrentHash)
    }

    @Test fun libraryFallbackLoadsWorkDescriptionAuthorAndCoverAfterRateLimiting() = server { server, metadata ->
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setBody("""{"docs":[{"key":"/works/OL123W","title":"Project Hail Mary","author_name":["Andy Weir"],"cover_i":456}]}"""))
        server.enqueue(MockResponse().setBody("""{"description":{"value":"A <b>space</b> adventure &amp; friendship."}}"""))
        val result = metadata.enrich(release())
        assertEquals("Open Library", result.metadataSource); assertEquals("Andy Weir", result.author)
        assertEquals("A space adventure & friendship.", result.description)
        assertEquals("https://covers.openlibrary.org/b/id/456-L.jpg?default=false", result.coverUrl)
        assertEquals("Narrator not verified", result.narrator)
        val requests = List(3) { server.takeRequest() }
        assertTrue(requests.all { it.getHeader("Authorization") == null })
        assertEquals("/library/works/OL123W.json", requests.last().requestUrl?.encodedPath)
    }

    @Test fun failedWorkDescriptionStillKeepsTheFallbackCover() = server { server, metadata ->
        audible(server)
        server.enqueue(MockResponse().setBody("""{"docs":[{"key":"/works/OL123W","title":"Project Hail Mary","author_name":["Andy Weir"],"cover_i":456}]}"""))
        server.enqueue(MockResponse().setResponseCode(503))
        val result = metadata.enrich(release())
        assertTrue(result.coverUrl.contains("/456-L.jpg")); assertEquals(release().description, result.description)
    }

    @Test fun outagesKeepSavedMetadataAndRemainRetryable() = server { server, metadata ->
        repeat(2) { server.enqueue(MockResponse().setResponseCode(503)) }
        val original = release()
        assertEquals(original, metadata.enrich(original))
        audible(server, product())
        val saved = metadata.enrich(original)
        assertEquals("Andy Weir", saved.author); assertEquals(3, server.requestCount)
        repeat(2) { server.enqueue(MockResponse().setResponseCode(503)) }
        assertEquals(saved, metadata.enrich(saved, force = true))
    }

    @Test fun cancellingLookupPropagatesToTheCaller() = server { server, metadata ->
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        coroutineScope {
            val lookup = async { metadata.enrich(release()) }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
            lookup.cancelAndJoin(); assertTrue(lookup.isCancelled)
        }
    }

    @Test fun archiveReadersInitialsEntitiesAndOriginalArtworkArePreserved() {
        val root = NarrioJson.parseToJsonElement("""{
            "metadata":{"identifier":"recording","title":"A Book","creator":["An Author","Another Author"],"narrator":["J. R. Reader","Second Reader"],"description":"<p>A &quot;story&quot; &#x2014; &#8217; &amp; more.</p><script>ignore this</script>"},
            "files":[{"name":"__ia_thumb.jpg"},{"name":"Book Front Cover.jpg","source":"original"},{"name":"book.mp3","source":"original"}]
        }""").jsonObject
        val book = ArchiveDiscovery.parseRecording(root)
        assertEquals("J. R. Reader, Second Reader", book.narrator)
        assertEquals("An Author, Another Author", book.author)
        assertEquals("A \"story\" \u2014 \u2019 & more.", book.description)
        assertEquals("https://archive.org/download/recording/Book%20Front%20Cover.jpg", book.coverUrl)
        val descriptionOnly = root["metadata"]!!.jsonObject.toMutableMap().apply { remove("narrator"); put("description", kotlinx.serialization.json.JsonPrimitive("Read in English by J. R. Reader.")) }
        assertEquals("J. R. Reader", ArchiveDiscovery.parseBook(kotlinx.serialization.json.JsonObject(descriptionOnly), false).narrator)
    }

    @Test fun savedBookJsonStaysCompatibleAndMetadataUpdatesRetainSourceState() {
        val old = NarrioJson.decodeFromString<Audiobook>("""{"id":"old","title":"Old title","author":"An Author"}""")
        assertEquals("", old.metadataSource); assertFalse(old.narratorFromCatalog)
        val original = release()
        val updated = original.withMetadataFrom(original.copy(title = "Project Hail Mary", author = "Andy Weir", narratorFromCatalog = true,
            metadataSource = "Audible", metadataUpdatedAtMs = 123, sources = emptyList(), cacheState = "uncached"))
        val restored = NarrioJson.decodeFromString<Audiobook>(NarrioJson.encodeToString(updated))
        assertEquals(original.sources, restored.sources); assertEquals(original.cacheState, restored.cacheState)
        assertEquals(123L, restored.metadataUpdatedAtMs); assertTrue(restored.narratorFromCatalog)
    }
}
