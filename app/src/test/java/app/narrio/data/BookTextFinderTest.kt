package app.narrio.data

import app.narrio.domain.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class BookTextFinderTest {
    private val book = Audiobook("pp", "Pride and Prejudice", "Jane Austen")
    private val hash = "6feffce1abdab62dd8a01a2caee00d5d7bc0e20d"

    @Test fun ebookReleasesNeedTheExactTitleAndEveryAuthorName() {
        assertTrue(EbookMatch.matches(book, "Pride and Prejudice - Jane Austen - eBook [EPUB, MOBI]"))
        assertTrue(EbookMatch.matches(book, "Jane Austen - Pride and Prejudice (Penguin Classics) (epub,mobi)"))
        assertTrue(EbookMatch.matches(book, "Austen, Jane/Pride and Prejudice by Jane Austen.epub"))
        assertTrue(EbookMatch.matches(book, "Pride and Prejudice - Austen, Jane"))
        assertFalse(EbookMatch.matches(book, "Pride and Prejudice and Zombies - Seth Grahame-Smith, Jane Austen"))
        assertFalse(EbookMatch.matches(book, "Pride and Prejudice - Study Guide - Jane Austen"))
        assertFalse(EbookMatch.matches(book, "Jane Austen - Complete Works [EPUB]"))
        assertFalse(EbookMatch.matches(book, "Pride and Prejudice [EPUB]"))
        assertFalse(EbookMatch.matches(book.copy(author = "Author not verified"), "Pride and Prejudice - Jane Austen"))
    }

    @Test fun ebookIndexUsesTheEbookCategoryOnly() {
        val root = NarrioJson.parseToJsonElement("""{"hits":[
            {"hash":"${hash.uppercase()}","title":"Pride and Prejudice - Jane Austen [EPUB]","categoryId":[10000000,9001000],"seeders":46,"magnetUrl":"magnet:?xt=urn:btih:$hash"},
            {"hash":"${"b".repeat(40)}","title":"Pride and Prejudice - Jane Austen","categoryId":[1003000],"seeders":9}]}""").jsonObject
        val ebooks = KnabenDiscovery.parse(root, KnabenDiscovery.EBOOKS)
        assertEquals(listOf(hash), ebooks.map { it.torrentHash })
        assertEquals(listOf("b".repeat(40)), KnabenDiscovery.parse(root).map { it.torrentHash })
    }

    /** Lookup asks TorBox only whether files are cached; the release is added only when its text is fetched. */
    @Test fun cachedEbookReleaseIsOfferedWithoutAddingItToTheAccount() = runTest {
        val knaben = MockWebServer().apply { start() }
        val torbox = MockWebServer().apply { start() }
        try {
            knaben.enqueue(MockResponse().setBody("""{"hits":[{"hash":"$hash","title":"Jane Austen - Pride and Prejudice (epub,mobi)","categoryId":[9001000],"seeders":46,"magnetUrl":"magnet:?xt=urn:btih:$hash"}]}"""))
            torbox.enqueue(MockResponse().setBody("""{"success":true,"data":[]}"""))
            torbox.enqueue(MockResponse().setBody("""{"success":true,"data":{"$hash":{"name":"PP","files":[{"name":"PP/cover.jpg"},{"name":"PP/Pride and Prejudice.mobi"},{"name":"PP/Pride and Prejudice.epub"}]}}}"""))
            val delivery = TorBoxDelivery(OkHttpClient(), { "test-secret" }, torbox.url("/").toString())
            val finder = BookTextFinder(GutenbergTextDiscovery(OkHttpClient(), knaben.url("/missing/").toString()), KnabenDiscovery(OkHttpClient(), knaben.url("/").toString()), delivery)
            val attempted = mutableListOf<BookTextSource>()
            val found = finder.find(book, null, connected = true, step = {}) { attempted += it }
            assertTrue(found)
            val candidate = attempted.single()
            assertEquals("torbox-cache", candidate.provider)
            assertEquals("PP/Pride and Prejudice.epub", candidate.fileName)
            assertEquals("EPUB", candidate.format)
            assertEquals(listOf("/torrents/mylist", "/torrents/checkcached"), List(torbox.requestCount) { torbox.takeRequest().requestUrl!!.encodedPath })
            assertEquals("9001000", knaben.takeRequest().requestUrl!!.queryParameter("c"))
        } finally { knaben.shutdown(); torbox.shutdown() }
    }

    @Test fun readyWebEbooksSurviveTorrentAccountFailuresAndKeepExactBookMatching() = runTest {
        val gutenberg = MockWebServer().apply { start() }
        val torbox = MockWebServer().apply { start() }
        try {
            gutenberg.enqueue(MockResponse().setBody("""{"results":[]}"""))
            torbox.enqueue(MockResponse().setResponseCode(500))
            val delivery = TorBoxDelivery(OkHttpClient(), { "fixture-key" }, torbox.url("/").toString())
            val matching = BookTextSource("torbox-web:12:3", "Pride and Prejudice - Jane Austen.epub", format = "EPUB", provider = "torbox-web", torrentId = 12, fileId = 3)
            val unrelated = matching.copy(id = "wrong", title = "Pride and Prejudice - Study Guide - Jane Austen.epub")
            val finder = BookTextFinder(GutenbergTextDiscovery(OkHttpClient(), gutenberg.url("/").toString()),
                KnabenDiscovery(OkHttpClient(), gutenberg.url("/").toString()), delivery, ebookSearch = { emptyList() },
                webAccountText = { listOf("" to matching, "" to unrelated) })
            val candidates = finder.candidates(book, emptyList(), true) {}
            assertEquals(listOf(matching), candidates.results)
            assertTrue(candidates.incomplete)
            assertEquals(1, torbox.requestCount)
        } finally { gutenberg.shutdown(); torbox.shutdown() }
    }

    @Test fun automaticTextMustBeLongEnoughForTheRecording() {
        fun text(words: Int) = BookText("d", "Book", "Author", "TXT", "", listOf(TextChapter("c", "C", listOf(TextPassage("p", List(words) { "word" }.joinToString(" "), "text", 0)))))
        val sevenHours = AudioSource("s", "Parts", "MP3", listOf(AudioPart("a", "a.mp3", "A", durationMs = 7 * 3_600_000L)))
        // A cover scan or track list beside a seven-hour recording isn't its book.
        assertFalse(BookTextFinder.plausible(text(180), book, sevenHours))
        assertFalse(BookTextFinder.plausible(text(10_000), book, sevenHours))
        assertTrue(BookTextFinder.plausible(text(60_000), book, sevenHours))
        // Without a known length, any substantial text qualifies.
        assertTrue(BookTextFinder.plausible(text(2_500), book, null))
    }

    @Test fun recordingCompanionFilesComeFirstAndTimingTracksNeedAChoice() {
        val source = AudioSource("s", "Parts", "MP3", emptyList(), textFiles = listOf(
            BookTextSource("vtt", "part1.vtt", format = "VTT", provider = "archive"),
            BookTextSource("txt", "book.txt", format = "TXT", provider = "archive"),
            BookTextSource("epub", "book.epub", format = "EPUB", provider = "archive"),
        ))
        assertEquals(listOf("epub", "txt"), BookTextFinder.companions(source).map { it.id })
    }

    /** Find ebook lists every match in evidence order; one failing provider marks the list incomplete, not empty. */
    @Test fun candidatesListEveryMatchAndReportAFailedProvider() = runTest {
        val gutenberg = MockWebServer().apply { start() }
        val torbox = MockWebServer().apply { start() }
        try {
            gutenberg.enqueue(MockResponse().setBody("""{"results":[
                {"id":1342,"title":"Pride and Prejudice","authors":[{"name":"Austen, Jane"}],"languages":["en"],"copyright":false,"media_type":"Text","formats":{"application/epub+zip":"https://www.gutenberg.org/ebooks/1342.epub3.images"}},
                {"id":35688,"title":"Pride and Prejudice and Zombies","authors":[{"name":"Grahame-Smith, Seth"}],"languages":["en"],"copyright":false,"media_type":"Text","formats":{"application/epub+zip":"https://www.gutenberg.org/ebooks/35688.epub"}}]}"""))
            torbox.enqueue(MockResponse().setResponseCode(500))
            val delivery = TorBoxDelivery(OkHttpClient(), { "test-secret" }, torbox.url("/").toString())
            val finder = BookTextFinder(GutenbergTextDiscovery(OkHttpClient(), gutenberg.url("/").toString()), KnabenDiscovery(OkHttpClient(), gutenberg.url("/").toString()), delivery,
                ebookSearch = { emptyList() })
            val source = AudioSource("s", "Parts", "MP3", emptyList(), textFiles = listOf(BookTextSource("companion", "Pride and Prejudice.epub", format = "EPUB", provider = "archive")))
            val steps = mutableListOf<String>()
            val found = finder.candidates(book, listOf(source), connected = true) { steps += it }
            assertEquals(listOf("companion", "gutenberg:1342"), found.results.map { it.id })
            assertTrue(found.incomplete)
            assertEquals(listOf("Checking this recording's files", "Checking your TorBox ebooks", "Checking TorBox for a cached ebook", "Checking Project Gutenberg"), steps)
        } finally { gutenberg.shutdown(); torbox.shutdown() }
    }
}
