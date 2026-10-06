package app.narrio.data

import app.narrio.domain.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

/** Release names below were observed in public Knaben audiobook search results; no account or cache data. */
class StreamingAutomaticSelectionTest {
    private fun book(title: String, author: String) = Audiobook("catalog:$title", title, author, provider = "catalog")
    private fun release(title: String, hash: Char = 'a', seeders: Long = 5, cached: Boolean = true, provider: String = "knaben", author: String = "Author not verified",
                        narrator: String = "Narrator not verified", language: String = "Language not verified") = Audiobook(
        if (provider == "archive") "archive:$title" else "knaben:${hash.toString().repeat(40)}", title, author, narrator, language, provider = provider,
        detailsLoaded = true, torrentHash = if (provider == "archive") "" else hash.toString().repeat(40), seeders = seeders,
        magnetUri = "magnet:?xt=urn:btih:${hash.toString().repeat(40)}", cacheState = if (cached) "cached" else "uncached", cachedFormats = if (cached) listOf("M4B") else emptyList(),
        sources = listOf(AudioSource("source:$title", "Whole book", "M4B", listOf(AudioPart("part:$title", "book.m4b", "Book", archiveUrl = "https://audio")),
            delivery = if (provider == "archive") "archive" else "torbox")),
    )
    private fun provider(books: List<Audiobook>) = object : RecordingDiscovery {
        override suspend fun search(query: String, category: String) = books
        override suspend fun recording(id: String) = books.first { it.id == id }
    }

    @Test fun releaseNamesWithSubtitlesTagsAndNarratorsAreConfidentMatches() {
        val strong = mapOf(
            book("Atomic Habits", "James Clear") to listOf("James Clear - Atomic Habits - An Easy Way to Build Good Habits",
                "Atomic Habits - James Clear - 2018 (miok) [Audiobook] (Self-Help)"),
            book("A Game of Thrones", "George R. R. Martin") to listOf("A Game of Thrones - George R.R. Martin - 2003 (miok) [Audiobook] (Fantasy)"),
            book("The Fellowship of the Ring", "J. R. R. Tolkien") to listOf("J.R.R Tolkien - The Fellowship of the Ring Audio Book (w/ Chapte ..."),
            book("The Hobbit", "J. R. R. Tolkien") to listOf("THE HOBBIT - JRR Tolkien. Read Nicol Williamson-Abr-{FerraBit}", "The Hobbit By J. R. R. Tolkien - BBC Radio 4 Dramatisation"),
            book("Sapiens", "Yuval Noah Harari") to listOf("Sapiens - A Brief History of Humankind - Yuval Noah Harari"),
            book("Sapiens: A Brief History of Humankind", "Yuval Noah Harari") to listOf("Sapiens: A Brief History of Humankind - Yuval Harari"),
            book("Dune", "Frank Herbert") to listOf("Dune by Frank Herbert (dramatized audio 256kbps) audio book",
                "Фрэнк Херберт. Дюна (Английский) / Frank Herbert - Dune [read by Scott Brick, Orlagh Cassidy, Euan Morton, Sim"),
            book("Ready Player One", "Ernest Cline") to listOf("Ernest Cline: Ready Player One"),
        )
        for ((book, names) in strong) for (name in names) assertEquals(name, MatchConfidence.STRONG, SourceQuality.confidence(book, release(name)))
    }

    @Test fun partialUploadsOtherSeriesBooksAndAmbiguousNamesAreNeverChosenAutomatically() {
        val none = mapOf(
            book("The Way of Kings", "Brandon Sanderson") to listOf("Brandon Sanderson - The Way of Kings (4 of 5) [GraphicAudio]"),
            book("Mistborn", "Brandon Sanderson") to listOf("Brandon Sanderson - Mistborn (series after Vin)", "Brandon Sanderson - Mistborn Series [Books 1-4] MacMillan Audio"),
            book("Dune", "Frank Herbert") to listOf("Frank Herbert - Dune (Books 1-6)", "Frank Herbert - Фрэнк Герберт - Dune / Дюна - 6 книг [Scott Brick and cast, 2007 г., разный, MP3]",
                "Dune.AudioBook.Complete", "Frank Herbert - Dune Messiah (2007) edition", "Brian Herbert, Kevin J. Anderson - Mentats of Dune"),
            book("The Hobbit", "J. R. R. Tolkien") to listOf("Tolkien Reads Excerpts From The Hobbit from Facsimile Gift Edition CD 2018 MP3"),
        )
        for ((book, names) in none) for (name in names) assertEquals(name, MatchConfidence.NONE, SourceQuality.confidence(book, release(name)))
        // The title without author evidence, or a series name before another book's title, needs the listener's review.
        val possible = mapOf(
            book("Harry Potter and the Sorcerer's Stone", "J.K. Rowling") to "Harry Potter and The Sorcerer's Stone Audiobook by Jim Dale",
            book("The Hunger Games", "Suzanne Collins") to "Suzanne Collins - The Hunger Games: Mockingjay",
            book("Educated", "Tara Westover") to "Educated: A Memior - Tara Westover",
        )
        for ((book, name) in possible) assertEquals(name, MatchConfidence.POSSIBLE, SourceQuality.confidence(book, release(name)))
    }

    @Test fun editionsComeFromProviderMetadataThenReleaseNames() {
        assertEquals(SourceEdition("", "English", "George Guidall"), SourceQuality.edition(release("[Английский] Frank Herbert / Фрэнк Герберт - Dune / Дюна [George Guidall, Davina Porter, 1993, MP3, 128 kbps]")))
        assertEquals(SourceEdition(SourceQuality.DRAMATIZED, "", ""), SourceQuality.edition(release("J.R.R. Tolkien - The Hobbit - NPR Radio Drama [flac]")))
        assertEquals(SourceEdition(SourceQuality.ABRIDGED, "", ""), SourceQuality.edition(release("THE HOBBIT - JRR Tolkien. Read Nicol Williamson-Abr-{FerraBit}")))
        assertEquals("Spanish", SourceQuality.edition(release("Frank Herbert - 01 - Dune - Dune (Audiobook Spanish)")).language)
        assertEquals("Jim Dale", SourceQuality.edition(release("Harry Potter and The Sorcerer's Stone Audiobook by Jim Dale")).narrator)
        assertEquals("", SourceQuality.edition(release("Brandon Sanderson - The Final Empire [Graphic Aud]")).narrator)
        val public = release("Dune (version 2)", provider = "archive", narrator = "Karen Savage", language = "English", author = "Frank Herbert")
        assertEquals(SourceEdition("", "English", "Karen Savage"), SourceQuality.edition(public))
    }

    @Test fun versionsAppearOnlyWhenNarrationEditionLanguageOrPublicReadingDiffers() {
        val porter = release("Andy Weir - Project Hail Mary [Ray Porter, 2021, MP3]", 'a')
        val unnamed = release("Andy Weir - Project Hail Mary", 'b')
        assertEquals(listOf(porter), SourceQuality.versions(listOf(porter, unnamed)))
        assertEquals(listOf(unnamed), SourceQuality.versions(listOf(unnamed, porter)))
        val dramatized = release("Andy Weir - Project Hail Mary - Full Cast Audio Drama", 'c')
        val otherReader = release("Andy Weir - Project Hail Mary [Someone Else, 2022, MP3]", 'd')
        val publicReading = release("Project Hail Mary", provider = "archive", narrator = "Volunteer Reader", language = "English", author = "Andy Weir")
        assertEquals(listOf(unnamed, dramatized, otherReader, publicReading), SourceQuality.versions(listOf(unnamed, porter, dramatized, otherReader, publicReading)))
    }

    @Test fun readySourceIsChosenWithoutSlowFileChecksAndWeakMatchesWaitForReview() = runBlocking {
        val hailMary = book("Project Hail Mary", "Andy Weir")
        val abridged = release("Andy Weir - Project Hail Mary (Abridged)", 'a', seeders = 90)
        val full = release("Andy Weir - Project Hail Mary", 'b', seeders = 3)
        val spanish = release("Andy Weir - Project Hail Mary (Audiobook Spanish)", 'c', seeders = 400)
        val titleOnly = release("Project Hail Mary audiobook", 'd', seeders = 50)
        val unverified = release("Andy Weir - Project Hail Mary [MP3]", 'e', cached = false).copy(sources = emptyList())
        val deadUnverified = unverified.copy(id = "knaben:${"f".repeat(40)}", torrentHash = "f".repeat(40), seeders = 0)
        val torboxIndex = provider(listOf(full.copy(description = "Second index"), spanish))
        val discovery = StreamingSourceFixture(provider(emptyList()), listOf(provider(listOf(abridged, full, titleOnly, unverified, deadUnverified)), torboxIndex),
            { emptyList() }, { candidates -> candidates.map { if (it.cacheState == "cached") it else it.copy(cacheState = "uncached") } },
            { error("A ready recording exists, so torrent metadata must not be fetched") })
        val result = discovery.search(hailMary, true)
        assertNull(result.error)
        assertEquals(listOf(full.id, abridged.id, spanish.id), result.recordings.map { it.id })
        // Strong but unverified files first, then a cached title-only match; a dead, unverified release is useless.
        assertEquals(listOf(unverified.id, titleOnly.id), result.possible.map { it.id })
    }

    @Test fun uncachedReleasesAreVerifiedOnlyWhenNothingIsReady() = runBlocking {
        val hailMary = book("Project Hail Mary", "Andy Weir")
        val uncached = release("Andy Weir - Project Hail Mary", 'a', cached = false).copy(sources = emptyList())
        val loaded = mutableListOf<String>()
        val discovery = StreamingSourceFixture(provider(emptyList()), listOf(provider(listOf(uncached))), { emptyList() }, { it }, { recording ->
            loaded += recording.id
            recording.copy(filesVerified = true, sources = release("files").sources)
        })
        val result = discovery.search(hailMary, true)
        assertEquals(listOf(uncached.id), loaded)
        assertEquals(listOf(uncached.id), result.recordings.map { it.id })
        assertFalse(SourceQuality.ready(result.recordings.single()))
    }

    @Test fun torBoxSearchSendsTheAccountKeyAndKeepsOnlyPossibleAudioReleases() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"success":true,"data":{"torrents":[
            {"hash":"${"A".repeat(40)}","raw_title":"Andy Weir - Project Hail Mary [M4B]","magnet":"magnet:?xt=urn:btih:${"a".repeat(40)}","last_known_seeders":12,"size":500,"tracker":"Tracker"},
            {"hash":"${"b".repeat(40)}","raw_title":"Project Hail Mary 2026 1080p WEB-DL x264","last_known_seeders":900},
            {"hash":"${"c".repeat(40)}","raw_title":"Andy Weir - Project Hail Mary EPUB","last_known_seeders":40},
            {"hash":"not-a-hash","raw_title":"Andy Weir - Project Hail Mary"}]}}"""))
        server.start()
        try {
            val search = TorBoxSearchDiscovery(OkHttpClient(), { "test-secret" }, server.url("/").toString())
            val results = search.search("Project Hail Mary: A Novel")
            val request = server.takeRequest()
            assertEquals("Bearer test-secret", request.getHeader("Authorization"))
            assertEquals(listOf("torrents", "search", "Project Hail Mary: A Novel"), request.requestUrl!!.pathSegments)
            val only = results.single()
            assertEquals("knaben:${"a".repeat(40)}", only.id)
            assertEquals(12, only.seeders); assertEquals(500, only.releaseSizeBytes); assertEquals("knaben", only.provider)
            assertTrue(TorBoxSearchDiscovery(OkHttpClient(), { null }, server.url("/").toString()).search("Project Hail Mary").isEmpty())
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
}
