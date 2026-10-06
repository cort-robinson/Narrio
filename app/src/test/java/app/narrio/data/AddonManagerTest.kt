package app.narrio.data

import app.narrio.domain.Audiobook
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
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

    @Test fun individualAudioAddonLookupsKeepTheirOwnResultsStatusAndFailures() = runBlocking {
        val first = at(bundled("knaben-audiobooks"), "https://example.com/first")
        val second = at(first.copy(manifest = JsonObject(first.manifest + ("id" to JsonPrimitive("second")))), "https://example.com/second")
        val requested = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            synchronized(requested) { requested += chain.request().url.encodedPath }
            assertNull(chain.request().header("Authorization"))
            if (chain.request().url.encodedPath == "/second") throw java.io.IOException("fixture failure")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"hits":[{"title":"Andy Weir - Project Hail Mary","hash":"$hash"}]}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val manager = AddonManager(client, listOf(first, second))
        val book = Audiobook("book", "Project Hail Mary", "Andy Weir")
        val states = mutableListOf<app.narrio.domain.SourceGroupStatus>()
        val found = manager.searchAddon(first.id, book, book.title, SourceSearchBudget()) { states += it }
        assertEquals(hash, found.single().torrentHash)
        assertEquals(first.name, found.single().sourceAddonName)
        assertEquals(listOf("/first"), requested)
        assertEquals(listOf(app.narrio.domain.SourceGroupStatus.WAITING, app.narrio.domain.SourceGroupStatus.SEARCHING), states)
        try { manager.searchAddon(second.id, book, book.title, SourceSearchBudget()) {}; fail("Expected a failure") }
        catch (_: java.io.IOException) { }
        assertEquals("Available", manager.status.value[first.id])
        assertTrue(manager.status.value[second.id]!!.contains("Unavailable"))
        manager.enable(first.id, false)
        try { manager.searchAddon(first.id, book, book.title, SourceSearchBudget()) {}; fail("Disabled source must not run") }
        catch (_: ProviderException) { }
        assertEquals(listOf("/first", "/second"), requested)
    }

    @Test fun allBundledManifestsValidateAndOlderLinksKeepTheSameIds() {
        val addons = AddonManager.bundledUrls.keys.map(::bundled)
        assertEquals(7, addons.size)
        assertEquals(2, addons.count { it.catalog })
        assertEquals(3, addons.count { it.source && it.contentType == "audiobook" })
        assertEquals(2, addons.count { it.contentType == "ebook" })
        assertEquals(1, addons.count { it.ebookSearch })
        assertEquals("audiobookbay", AddonManifest.parse(bundled("audiobookbay").manifest.toString(), "https://jsonkeeper.com/b/QL9DT").id)
    }

    @Test fun browserEbookSearchEncodesBookIdentityAndNeedsNoNetworkOrTorbox() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { error("Browser links must not issue network requests") }.build()
        val manager = AddonManager(client, listOf(bundled("annas-archive-ebooks")))
        val book = Audiobook("catalog:test", "A & B? #1", "Writer + Co")
        val link = manager.ebookSearchLinks(book).single()
        val url = link.url.toHttpUrl()
        assertEquals("Anna's Archive", link.name)
        assertEquals("https", url.scheme)
        assertEquals("annas-archive.gl", url.host)
        assertEquals("A & B? #1 Writer + Co", url.queryParameter("q"))
        assertEquals("epub", url.queryParameter("ext"))
        assertEquals(setOf("q", "ext"), url.queryParameterNames)
        assertTrue(manager.ebooks(book.title).isEmpty())
        assertTrue(manager.search(book.title).isEmpty())
        assertEquals("A & B? #1", manager.ebookSearchLinks(book.copy(author = "Author not verified")).single().url.toHttpUrl().queryParameter("q"))
        manager.enable("annas-archive-ebooks", false)
        assertTrue(manager.ebookSearchLinks(book).isEmpty())
        manager.remove("annas-archive-ebooks")
        assertTrue(manager.ebookSearchLinks(book).isEmpty())
    }

    @Test fun browserSearchManifestsRejectNonEbookAndNonBrowserRequests() {
        val manifest = bundled("annas-archive-ebooks").manifest.toString()
        for (invalid in listOf(manifest.replace("\"ebook\"", "\"audiobook\""), manifest.replace("\"GET\"", "\"POST\""),
            manifest.replace("\"method\":\"GET\"", "\"method\":\"GET\",\"headers\":{\"Authorization\":\"secret\"}"),
            manifest.replace("{QUERY}", "fixed"))) {
            assertTrue(runCatching { AddonManifest.parse(invalid, "https://example.com/addon.json") }.isFailure)
        }
    }

    @Test fun upgradesAddNewBundledProvidersOnceWithoutRestoringRemovedOrDisabledProviders() {
        val defaults = AddonManager.bundledUrls.keys.map(::bundled)
        val legacy = defaults.filterNot { it.ebookSearch }
        val saved = legacy.filterNot { it.id == "audiobookbay" }.map { it.copy(enabled = it.id != "knaben-ebooks") }
        val upgraded = AddonManager.addNewBundled(saved, defaults, legacy.map { it.id }.toSet())
        assertEquals(saved.map { it.id } + "annas-archive-ebooks", upgraded.map { it.id })
        assertFalse(upgraded.first { it.id == "knaben-ebooks" }.enabled)
        assertEquals(upgraded, AddonManager.addNewBundled(upgraded, defaults, defaults.map { it.id }.toSet()))
        val removed = upgraded.filterNot { it.ebookSearch }
        assertEquals(removed, AddonManager.addNewBundled(removed, defaults, defaults.map { it.id }.toSet()))
    }

    @Test fun savedPirateBayDefinitionFromTheRetiredProxyIsReplacedKeepingItsSwitch() {
        val defaults = AddonManager.bundledUrls.keys.map(::bundled)
        val current = bundled("tpb-audiobooks")
        val retired = current.copy(manifestUrl = "https://api.npoint.io/526af6fc28a90bed10c8", enabled = false)
        val imported = current.copy(manifestUrl = "https://example.com/my-tpb.json")
        val replaced = AddonManager.replaceRetiredBundled(listOf(retired), defaults).single()
        assertEquals(current.manifestUrl, replaced.manifestUrl)
        assertTrue(replaced.manifest.toString().contains("https://apibay.org/q.php"))
        assertFalse(replaced.enabled)
        assertEquals(listOf(imported), AddonManager.replaceRetiredBundled(listOf(imported), defaults))
    }

    @Test fun pirateBayEmptySearchPlaceholderIsNotAResult() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""[{"id":"0","name":"No results returned","info_hash":"${"0".repeat(40)}","seeders":"0","size":"0"}]"""))
            val manager = AddonManager(OkHttpClient(), listOf(at(bundled("tpb-audiobooks"), server.url("/q").toString())), allowTestHttp = true)
            assertTrue(manager.search("Unknown book").isEmpty())
        } finally { server.shutdown() }
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
            // Keep the stalled body longer than cancellation, within MockWebServer's shutdown grace period.
            server.enqueue(MockResponse().setBody("{}").setBodyDelay(3, TimeUnit.SECONDS))
            val job = launch { manager.ebooks("Book") }
            delay(1100)
            val cancelStarted = System.nanoTime()
            job.cancelAndJoin(); assertTrue(job.isCancelled)
            assertTrue("Cancellation must stop the body read immediately", (System.nanoTime() - cancelStarted) / 1_000_000 < 1000)
        } finally { server.shutdown() }
    }
}
