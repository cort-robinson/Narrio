package app.narrio.data

import app.narrio.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderEbookSearchTest {
    private val book = Audiobook("pp", "Pride and Prejudice", "Jane Austen", language = "English", provider = "catalog")
    private val companion = BookTextSource("companion", "Pride and Prejudice - Jane Austen.epub", format = "EPUB", provider = "archive")
    private val recording = AudioSource("rec", "Parts", "MP3", emptyList(), textFiles = listOf(companion))
    private fun ebook(id: String, title: String = "Pride and Prejudice - Jane Austen", format: String = "EPUB", provider: String = "gutenberg", hash: String = "", file: String = "") =
        BookTextSource(id, title, format = format, provider = provider, attribution = "Fixture $id", torrentHash = hash, fileName = file)
    private fun provider(id: String, order: Int, enabled: Boolean = true, torBox: Boolean = false) =
        SourceProvider(id, "Source $id", SourceProviderKind.BUILT_IN, enabled, order, torBox, false)
    private fun settings(vararg providers: SourceProvider) = object : SourceProviderSettings {
        override val providers = MutableStateFlow(providers.toList())
        override fun setEnabled(id: String, enabled: Boolean) = Unit
        override fun move(id: String, index: Int) = Unit
    }
    private fun lookup(block: suspend () -> List<EbookCandidate>) = object : EbookLookup {
        override suspend fun search(book: Audiobook, recordings: List<AudioSource>, budget: SourceSearchBudget, words: String, status: suspend (SourceGroupStatus) -> Unit) =
            EbookLookupResult(budget.run { block() })
    }
    private fun strong(source: BookTextSource) = EbookCandidate(source, MatchConfidence.STRONG)

    /** Before, Find ebook waited for each source in turn; now each section and the best match appear as soon as they can. */
    @Test fun fastSourcesLeadWhileASlowOneTimesOutAndRetryKeepsTheRest() = runTest {
        var slowAnswers = false
        val engine = ProviderEbookSearch(settings(provider("fast", 0), provider("slow", 1)), { source ->
            when (source.id) {
                "fast" -> lookup { delay(250); listOf(strong(ebook("gutenberg:1"))) }
                else -> lookup { if (slowAnswers) { delay(500); listOf(strong(ebook("slow:1", provider = "torbox-cache"))) } else { delay(30_000); emptyList() } }
            }
        }, now = { currentTime })
        val start = currentTime
        val session = engine.start(book, emptyList(), true, backgroundScope)
        advanceTimeBy(250); runCurrent()
        assertEquals("gutenberg:1", session.state.value.best!!.edition.id)
        assertEquals(SourceGroupStatus.SEARCHING, session.state.value.groups[1].status)
        assertFalse(session.state.value.complete)
        advanceTimeBy(14_750); runCurrent()
        assertTrue(session.state.value.complete)
        assertEquals(15_000L, currentTime - start)
        val timedOut = session.state.value.groups[1]
        assertEquals(SourceGroupStatus.FAILED, timedOut.status)
        assertTrue(timedOut.message!!.contains("timed out"))

        slowAnswers = true
        session.retry("slow"); runCurrent()
        assertEquals(SourceGroupStatus.SEARCHING, session.state.value.groups[1].status)
        assertEquals(listOf("gutenberg:1"), session.state.value.groups[0].editions.map { it.id })
        advanceTimeBy(500); runCurrent()
        assertEquals(SourceGroupStatus.DONE, session.state.value.groups[1].status)
        assertEquals(listOf("slow:1"), session.state.value.groups[1].editions.map { it.id })
        assertTrue(session.state.value.complete)
    }

    @Test fun theRecordingsOwnEditionLeadsAndAnEbookFoundTwiceAppearsOnce() = runTest {
        val hash = "a".repeat(40)
        val cached = ebook("knaben:$hash:pp.epub", provider = "torbox-cache", hash = hash, file = "pp.epub")
        val possible = ebook("gutenberg:2", title = "Pride and Prejudice")
        // Gutenberg leads the order, yet the file that ships with the recording is the likelier narrated edition.
        val engine = ProviderEbookSearch(settings(provider("gutenberg", 0), provider("first-index", 1), provider("second-index", 2),
            provider(DeviceSourceProviderSettings.RECORDING_FILES, 3)), { source ->
            when (source.id) {
                "gutenberg" -> lookup { listOf(strong(ebook("gutenberg:1")), EbookCandidate(possible, MatchConfidence.POSSIBLE)) }
                "first-index", "second-index" -> lookup { listOf(strong(cached)) }
                else -> RecordingEbookLookup
            }
        })
        val session = engine.start(book, listOf(recording), true, backgroundScope); runCurrent()
        val result = session.state.value
        assertTrue(result.complete)
        assertEquals(DeviceSourceProviderSettings.RECORDING_FILES, result.best!!.providerId)
        assertEquals("companion", result.best!!.edition.id)
        assertEquals(listOf(EbookMatchReason.WITH_RECORDING, EbookMatchReason.STRONG_MATCH, EbookMatchReason.EPUB), result.best!!.reasons)
        assertEquals(listOf(cached.id), result.groups[1].editions.map { it.id })
        assertTrue(result.groups[2].editions.isEmpty())
        assertEquals(listOf("Source second-index"), result.groups[1].alsoFoundBy[cached.id])
        // A possible match is listed for the reader's check, never chosen for them.
        assertEquals(listOf(possible.id), result.groups[0].possible.map { it.id })
    }

    @Test fun onlyPossibleMatchesLeaveNoBestMatch() = runTest {
        val engine = ProviderEbookSearch(settings(provider("gutenberg", 0)), { lookup { listOf(EbookCandidate(ebook("g"), MatchConfidence.POSSIBLE)) } })
        val session = engine.start(book, emptyList(), true, backgroundScope); runCurrent()
        assertNull(session.state.value.best)
        assertEquals(1, session.state.value.groups.single().possible.size)
    }

    @Test fun skippedSourcesSayWhyAndAnAllSkippedSearchFinishesAtOnce() = runTest {
        val engine = ProviderEbookSearch(settings(provider("off", 0, enabled = false), provider("torbox", 1, torBox = true),
            provider(DeviceSourceProviderSettings.RECORDING_FILES, 2)), { error("No skipped source is searched") })
        val session = engine.start(book, emptyList(), connected = false, backgroundScope); runCurrent()
        assertEquals(listOf("Disabled", "Connect TorBox", "No recording yet"), session.state.value.groups.map { it.message })
        assertTrue(session.state.value.groups.all { it.status == SourceGroupStatus.SKIPPED })
        assertTrue(session.state.value.complete)
    }

    @Test fun recordingFilesAreAtLeastPossibleAndTimingTracksAreLeftOut() = runTest {
        val source = AudioSource("s", "Parts", "MP3", emptyList(), textFiles = listOf(companion,
            BookTextSource("unnamed", "book.epub", format = "EPUB", provider = "archive"),
            BookTextSource("vtt", "part1.vtt", format = "VTT", provider = "archive")))
        val found = RecordingEbookLookup.search(book, listOf(source), SourceSearchBudget()) {}.found
        assertEquals(mapOf("companion" to MatchConfidence.STRONG, "unnamed" to MatchConfidence.POSSIBLE), found.associate { it.source.id to it.confidence })
    }

    /** One torrent-account failure keeps the ready web download and reports the source for Retry. */
    @Test fun accountLookupKeepsWebDownloadsWhenTorrentsFail() = runBlocking {
        val torbox = MockWebServer().apply { start() }
        try {
            torbox.enqueue(MockResponse().setResponseCode(500))
            val matching = BookTextSource("torbox-web:12:3", "Pride and Prejudice - Jane Austen.epub", format = "EPUB", provider = "torbox-web", torrentId = 12, fileId = 3)
            val unrelated = matching.copy(id = "wrong", title = "Pride and Prejudice - Study Guide - Jane Austen.epub")
            val finder = BookTextFinder(GutenbergTextDiscovery(OkHttpClient(), torbox.url("/").toString()), KnabenDiscovery(OkHttpClient(), torbox.url("/").toString()),
                TorBoxDelivery(OkHttpClient(), { "fixture-key" }, torbox.url("/").toString()), ebookSearch = { emptyList() }, webAccountText = { listOf("" to matching, "" to unrelated) })
            val result = AccountEbookLookup(finder).search(book, emptyList(), SourceSearchBudget()) {}
            assertEquals(listOf(matching), result.found.map { it.source })
            assertNotNull(result.failure)
        } finally { torbox.shutdown() }
    }

    /** An ebook add-on asks TorBox only whether its releases are cached, and a confident first title stops the search. */
    @Test fun addonReleasesNeedCachedFilesAndAConfidentTitleStopsTheVariants() = runBlocking {
        val hash = "b".repeat(40)
        val detailed = book.copy(title = "Pride and Prejudice: A Novel of Manners")
        val queries = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val body = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            synchronized(queries) { queries += NarrioJson.parseToJsonElement(body).jsonObject["query"]!!.jsonPrimitive.content }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"hits":[{"title":"Pride and Prejudice - Jane Austen [EPUB]","hash":"$hash","seeders":12}]}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val torbox = MockWebServer().apply { start() }
        try {
            torbox.enqueue(MockResponse().setBody("""{"success":true,"data":{"$hash":{"name":"PP","files":[{"name":"PP/cover.jpg"},{"name":"PP/Pride and Prejudice.epub"}]}}}"""))
            val addon = AddonManifest.parse(File("src/main/assets/addons/knaben-ebooks.json").readText(), AddonManager.bundledUrls.getValue("knaben-ebooks"))
            val finder = BookTextFinder(GutenbergTextDiscovery(OkHttpClient()), KnabenDiscovery(OkHttpClient()), TorBoxDelivery(OkHttpClient(), { "fixture-key" }, torbox.url("/").toString()))
            val states = mutableListOf<SourceGroupStatus>()
            val result = AddonEbookLookup(finder, AddonManager(client, listOf(addon)), addon.id).search(detailed, emptyList(), SourceSearchBudget()) { states += it }
            val found = result.found.single()
            assertEquals(MatchConfidence.STRONG, found.confidence)
            assertEquals("PP/Pride and Prejudice.epub", found.source.fileName)
            assertEquals("torbox-cache", found.source.provider)
            assertEquals(listOf("Pride and Prejudice: A Novel of Manners Jane Austen"), queries)
            assertTrue(SourceGroupStatus.CHECKING in states)
            assertEquals(listOf("/torrents/checkcached"), List(torbox.requestCount) { torbox.takeRequest().requestUrl!!.encodedPath })
        } finally { torbox.shutdown() }
    }

    /** The reader's own words reach every source, and a retry searches with them again rather than the book's details. */
    @Test fun customWordsReachEverySourceAndItsRetry() = runTest {
        val asked = mutableListOf<Pair<String, String>>()
        var failFirst = true
        val engine = ProviderEbookSearch(settings(provider("first", 0), provider("second", 1)), { source ->
            object : EbookLookup {
                override suspend fun search(book: Audiobook, recordings: List<AudioSource>, budget: SourceSearchBudget, words: String, status: suspend (SourceGroupStatus) -> Unit): EbookLookupResult {
                    asked += source.id to words
                    return if (source.id == "second" && failFirst) { failFirst = false; EbookLookupResult(emptyList(), "Ebook lookup failed. Retry.") } else EbookLookupResult(emptyList())
                }
            }
        })
        val session = engine.start(book, emptyList(), true, backgroundScope, words = "  Pride Prejudice Austen ")
        runCurrent()
        session.retry("second"); runCurrent()
        assertEquals(listOf("first" to "Pride Prejudice Austen", "second" to "Pride Prejudice Austen", "second" to "Pride Prejudice Austen"), asked.sortedBy { it.first })
        // Default searches are unchanged: no words.
        asked.clear()
        engine.start(book, emptyList(), true, backgroundScope); runCurrent()
        assertTrue(asked.all { it.second == "" })
    }

    /** A UK title the catalog doesn't know is a possible match by the reader's words; words never confirm a match by themselves. */
    @Test fun customWordsAdmitPossibleMatchesOnly() {
        val potter = Audiobook("hp", "Harry Potter and the Sorcerer's Stone", "J.K. Rowling", provider = "catalog")
        val uk = "Harry Potter and the Philosopher's Stone - J. K. Rowling.epub"
        assertEquals(MatchConfidence.NONE, EbookMatch.confidence(potter, uk))
        assertEquals(MatchConfidence.POSSIBLE, EbookMatch.confidence(potter, uk, "Philosopher's Stone Rowling"))
        // Author words may be left out of a file name; the title words may not.
        assertEquals(MatchConfidence.POSSIBLE, EbookMatch.confidence(potter, "Harry Potter and the Philosopher's Stone.epub", "philosopher's stone rowling"))
        assertEquals(MatchConfidence.NONE, EbookMatch.confidence(potter, "Harry Potter and the Chamber of Secrets.epub", "Philosopher's Stone Rowling"))
        assertEquals(MatchConfidence.NONE, EbookMatch.confidence(potter, uk, "Rowling"))
        // Unrelated releases stay out unless the reader asked for them.
        assertEquals(MatchConfidence.NONE, EbookMatch.confidence(potter, "Philosopher's Stone - Study Guide.epub", "Philosopher's Stone"))
        assertEquals(MatchConfidence.STRONG, EbookMatch.confidence(potter, "Harry Potter and the Sorcerer's Stone - J. K. Rowling", "Philosopher's Stone"))
    }

    /** Gutenberg and an ebook add-on search the reader's words as typed, once, instead of the title variants. */
    @Test fun customWordsReplaceTheBooksOwnQueries() = runBlocking {
        val gutendex = MockWebServer().apply { start() }
        val queries = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val body = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            synchronized(queries) { queries += NarrioJson.parseToJsonElement(body).jsonObject["query"]!!.jsonPrimitive.content }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"hits":[]}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        try {
            gutendex.enqueue(MockResponse().setBody("""{"results":[]}"""))
            val finder = BookTextFinder(GutenbergTextDiscovery(OkHttpClient(), gutendex.url("/").toString()), KnabenDiscovery(OkHttpClient()),
                TorBoxDelivery(OkHttpClient(), { "fixture-key" }))
            GutenbergEbookLookup(finder).search(book.copy(title = "Pride and Prejudice: A Novel"), emptyList(), SourceSearchBudget(), "Orgueil et préjugés") {}
            assertEquals("Orgueil et préjugés", gutendex.takeRequest().requestUrl!!.queryParameter("search"))
            val addon = AddonManifest.parse(File("src/main/assets/addons/knaben-ebooks.json").readText(), AddonManager.bundledUrls.getValue("knaben-ebooks"))
            AddonEbookLookup(finder, AddonManager(client, listOf(addon)), addon.id).search(book.copy(title = "Pride and Prejudice: A Novel"), emptyList(), SourceSearchBudget(), "Orgueil et préjugés") {}
            assertEquals(listOf("Orgueil et préjugés"), queries)
        } finally { gutendex.shutdown() }
    }

    @Test fun ebookSourcesKeepGutenbergLastAndTheirOwnSettings() = runTest {
        fun addon(id: String, type: String) = InstalledAddon(buildJsonObject {
            put("id", id); put("name", id); put("contentType", type); put("provides", buildJsonArray { add("source") })
        }, "https://example.com/$id", true)
        var saved = SourceProviderPreferences()
        val addons = AddonManager(OkHttpClient(), listOf(addon("audio", "audiobook"), addon("index", "ebook")))
        val ebooks = DeviceSourceProviderSettings(addons, backgroundScope, saved, SourceCatalog.EBOOK) { saved = it }; runCurrent()
        assertEquals(listOf(DeviceSourceProviderSettings.RECORDING_FILES, DeviceSourceProviderSettings.TORBOX_EBOOKS, "addon:index", DeviceSourceProviderSettings.GUTENBERG),
            ebooks.providers.value.map { it.id })
        assertTrue(ebooks.providers.value.first { it.id == DeviceSourceProviderSettings.TORBOX_EBOOKS }.requiresTorBox)
        ebooks.setEnabled(DeviceSourceProviderSettings.GUTENBERG, false); ebooks.move(DeviceSourceProviderSettings.GUTENBERG, 0)
        val restored = DeviceSourceProviderSettings(addons, backgroundScope, saved, SourceCatalog.EBOOK); runCurrent()
        assertEquals(DeviceSourceProviderSettings.GUTENBERG, restored.providers.value.first().id)
        assertFalse(restored.providers.value.first().enabled)
        // Listening sources are a separate list and keep their own order.
        val audio = DeviceSourceProviderSettings(addons, backgroundScope, SourceProviderPreferences()); runCurrent()
        assertEquals(listOf("archive", "torbox-library", "addon:audio"), audio.providers.value.map { it.id })
    }
}
