package app.narrio.data

import app.narrio.domain.Audiobook
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class AddonManagerTest {
    private val hash = "a".repeat(40)
    private fun bundled(id: String) = AddonManifest.parse(File("src/main/assets/addons/$id.json").readText(), AddonManager.bundledUrls.getValue(id))
    private fun at(addon: InstalledAddon, url: String): InstalledAddon {
        val adapters = addon.manifest["adapters"]!!.jsonObject
        val source = adapters["source"]!!.jsonObject
        val request = source["request"]!!.jsonObject
        return addon.copy(manifest = JsonObject(addon.manifest + ("adapters" to JsonObject(adapters + ("source" to JsonObject(source + ("request" to JsonObject(request + ("url" to JsonPrimitive(url))))))))))
    }

    @Test fun allSixBundledManifestsValidateAndOlderLinksKeepTheSameIds() {
        val addons = AddonManager.bundledUrls.keys.map(::bundled)
        assertEquals(6, addons.size)
        assertEquals(2, addons.count { it.catalog })
        assertEquals(3, addons.count { it.source && it.contentType == "audiobook" })
        assertEquals(1, addons.count { it.contentType == "ebook" })
        assertEquals("audiobookbay", AddonManifest.parse(bundled("audiobookbay").manifest.toString(), "https://jsonkeeper.com/b/QL9DT").id)
    }

    @Test fun pathsSupportArraysIndexesAndNumericObjectKeys() {
        val root = NarrioJson.parseToJsonElement("""{"authors":[{"name":"One"},{"name":"Two"}],"narrators":[{"name":"Reader"}],"product_images":{"500":"cover"}}""")
        assertEquals(listOf("One", "Two"), AddonManifest.values(root, "authors[].name").map { it.stringValue() })
        assertEquals("Reader", AddonManifest.values(root, "narrators[0].name").single().stringValue())
        assertEquals("cover", AddonManifest.values(root, "product_images.500").single().stringValue())
        assertTrue(AddonManifest.values(root, "narrators[9].name").isEmpty())
    }

    @Test fun unsupportedManifestsAndPrivateUrlsAreRejected() {
        val manifest = bundled("audiobookbay").manifest.toString()
        listOf(manifest.replace("1.0.0", "2.0.0"), manifest.replace("\"POST\"", "\"DELETE\""),
            manifest.replace("\"Accept\"", "\"Authorization\""), manifest.replace("\"json\"", "\"javascript\""))
            .forEach { assertTrue(runCatching { AddonManifest.parse(it, "https://example.com/addon") }.isFailure) }
        listOf("http://example.com", "https://127.0.0.1", "https://localhost", "https://example.local", "https://key@example.com", "https://example.com:8443")
            .forEach { assertTrue(runCatching { AddonManifest.secureUrl(it) }.isFailure) }
    }

    @Test fun postBodySafelySubstitutesTitleAuthorAndMapsOnlyHashIdentifiedReleases() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"results":[{"title":"Book - Author","author":"Author","infoHash":"$hash","seeders":4,"sizeBytes":123},{"title":"Bad","infoHash":"invalid"}]}"""))
            val addon = at(bundled("audiobookbay"), server.url("/search").toString())
            val manager = AddonManager(OkHttpClient(), listOf(addon), allowTestHttp = true)
            val book = manager.searchBook(Audiobook("catalog", "A \"book\" & sequel", "Author"), "A \"book\" & sequel").single()
            val request = server.takeRequest()
            val body = NarrioJson.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals("POST", request.method)
            assertEquals("A \"book\" & sequel", body.text("title")); assertEquals("Author", body.text("author"))
            assertNull(request.getHeader("Authorization"))
            assertEquals("knaben:$hash", book.id); assertEquals("AudiobookBay", book.sourceAddonName)
            assertEquals("unchecked", book.cacheState); assertTrue(book.sources.isEmpty()); assertFalse(book.filesVerified)
        } finally { server.shutdown() }
    }

    @Test fun getQueriesAreEncodedAndRootArraysAreMapped() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""[{"name":"Book","info_hash":"$hash","seeders":"7","size":"42"}]"""))
            val manager = AddonManager(OkHttpClient(), listOf(at(bundled("tpb-audiobooks"), server.url("/q?q={TITLE}&cat=102").toString().replace("%7BTITLE%7D", "{TITLE}"))), allowTestHttp = true)
            val book = manager.search("A & B? #1").single()
            assertEquals("A & B? #1", server.takeRequest().requestUrl!!.queryParameter("q"))
            assertEquals(7L, book.seeders); assertEquals(42L, book.releaseSizeBytes)
        } finally { server.shutdown() }
    }

    @Test fun disabledProvidersAreNeverCalledAndFailureDoesNotHideAnotherProvidersResults() = runBlocking {
        val server = MockWebServer(); server.start()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest) = if (request.path == "/bad") MockResponse().setResponseCode(503) else
                MockResponse().setBody("""{"hits":[{"title":"Book","hash":"$hash"}]}""")
        }
        try {
            val manager = AddonManager(OkHttpClient(), listOf(at(bundled("audiobookbay"), server.url("/disabled").toString()).copy(enabled = false),
                at(bundled("tpb-audiobooks"), server.url("/bad").toString()), at(bundled("knaben-audiobooks"), server.url("/good").toString())), allowTestHttp = true)
            assertEquals(hash, manager.search("Book").single().torrentHash)
            assertEquals(2, server.requestCount)
            assertTrue(manager.status.value.getValue("tpb-audiobooks").startsWith("Unavailable"))
            manager.enable("knaben-audiobooks", false)
            assertTrue(runCatching { manager.search("Book") }.isFailure)
        } finally { server.shutdown() }
    }

    @Test fun catalogMapsAuthorsNarratorDescriptionCoverAndDoesNotCreateAudio() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"products":[{"title":"Book","asin":"B000000001","authors":[{"name":"Writer"}],"narrators":[{"name":"Reader"}],"merchandising_summary":"<p>Story</p>","product_images":{"500":"https://images.example.com/cover"}}]}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val manager = AddonManager(client, listOf(bundled("audible-audiobooks")))
        val metadata = manager.catalog("Book").single()
        val book = BookCatalog.collapse(listOf(metadata), 1).single()
        assertEquals("Writer", book.author); assertEquals("Reader", book.narrator)
        assertEquals("Story", book.description); assertTrue(book.coverUrl.startsWith("https://"))
        assertEquals("catalog", book.provider); assertTrue(book.sources.isEmpty()); assertTrue(book.torrentHash.isBlank())
        assertNull(requests.single().header("Authorization"))
    }

    @Test fun importsDeduplicateAliasesAndSettingsSurviveReconstruction() = runBlocking {
        val manifest = bundled("audiobookbay").manifest.toString()
        val client = OkHttpClient.Builder().addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(manifest.toResponseBody("application/json".toMediaType())).build() }.build()
        var saved = emptyList<InstalledAddon>()
        val manager = AddonManager(client, listOf(bundled("audiobookbay")), persist = { saved = it })
        manager.enable("audiobookbay", false)
        manager.install("https://jsonkeeper.com/b/QL9DT")
        assertEquals(1, saved.size); assertFalse(saved.single().enabled)
        manager.refresh("audiobookbay"); assertFalse(saved.single().enabled)
        val restored = AddonManager(client, saved)
        assertFalse(restored.installed.value.single().enabled)
        manager.remove("audiobookbay"); assertTrue(saved.isEmpty())
        manager.install("https://jsonkeeper.com/b/QL9DT"); assertTrue(saved.single().enabled)
    }

    @Test fun ebookAddonsAreSeparateFromAudioAndCancellationStopsSlowRequests() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"hits":[{"title":"Book - Writer","hash":"$hash"}]}"""))
            val manager = AddonManager(OkHttpClient(), listOf(at(bundled("knaben-ebooks"), server.url("/ebook").toString())), allowTestHttp = true)
            assertTrue(manager.search("Book").isEmpty()); assertEquals(0, server.requestCount)
            assertEquals(hash, manager.ebooks("Book").single().torrentHash)
            val body = NarrioJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertTrue(body["categories"]!!.jsonArray.any { it.stringValue() == "9001000" })
            server.enqueue(MockResponse().setBody("{}").setBodyDelay(10, TimeUnit.SECONDS))
            val job = launch { manager.ebooks("Book") }
            delay(1100); job.cancelAndJoin(); assertTrue(job.isCancelled)
        } finally { server.shutdown() }
    }
}
