package app.narrio.data

import app.narrio.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BookSourceDiscoveryTest {
    private val book = Audiobook("catalog:book", "Project Hail Mary", "Andy Weir", provider = "catalog", description = "The story.", coverUrl = "https://cover", metadataSource = "Audible")
    private fun release(title: String = "Andy Weir - Project Hail Mary [M4B, Unabridged]", author: String = "Author not verified", provider: String = "knaben") = Audiobook(
        "release:$title:$provider", title, author, provider = provider, detailsLoaded = true, torrentHash = "a".repeat(40),
        cacheState = "cached", cachedFormats = listOf("M4B"), sources = listOf(AudioSource("source", "Whole book", "M4B",
            listOf(AudioPart("part", "book.m4b", "Book", archiveUrl = "https://audio")), delivery = if (provider == "archive") "archive" else "torbox")),
    )
    private fun provider(books: List<Audiobook>, full: Audiobook? = null) = object : RecordingDiscovery {
        override suspend fun search(query: String, category: String) = books
        override suspend fun recording(id: String) = full ?: books.first { it.id == id }
    }

    @Test fun eragonLiveReleaseNamesKeepIndividualBooksAndRejectOtherVolumesAndBundles() {
        val eragon = book.copy(title = "Eragon", author = "Christopher Paolini")
        // Public Knaben names observed during the regression investigation; no account or cache data.
        val matching = listOf("Christopher Paolini - Eragon", "Christopher Paolini - Eragon 2002",
            "Christopher Paolini.Eragon.The Inheritance Cycle 1", "Paolini - Eragon", "C. Paolini - Eragon")
        for (name in matching) assertTrue(name, SourceQuality.matches(eragon, release(name)))
        val wrong = listOf("Eragon Series Audio Books", "Inheritance (Eragon Book 4) Audiobook MP3 format",
            "Eragon book 4 inheritance audiobook", "Eragon, Eldest, Brisingr - Christopher Paolini",
            "Eragon, Eldest, Brisingr - Christopher Paolini.The Inheritance Cycle 1",
            "Christopher Paolini - Eragon 2", "Christopher Paolini - Eragon 10", "Someone Paolini - Eragon", "P. Paolini - Eragon",
            "Christopher Paolini - Eragon [Summary]", "Christopher Paolini - Eragon [Complete Series]", "Eragon")
        for (name in wrong) assertFalse(name, SourceQuality.matches(eragon, release(name)))
        assertFalse(SourceQuality.matches(book.copy(title = "It", author = "Stephen King"), release("King - It")))
    }

    @Test fun cachedSeriesAndAccountFilesReachEragonSourceChoices() = runBlocking {
        val eragon = book.copy(title = "Eragon", author = "Christopher Paolini")
        val series = release("Christopher Paolini.Eragon.The Inheritance Cycle 1").copy(sources = emptyList(), cacheState = "unchecked")
        val surname = release("Paolini - Eragon").copy(torrentHash = "b".repeat(40), sources = emptyList(), cacheState = "unchecked")
        val titleOnly = release("01_ERAGON Audiobook").copy(torrentHash = "c".repeat(40), sources = emptyList(), cacheState = "unchecked")
        val bundle = release("Eragon, Eldest, Brisingr - Christopher Paolini")
        val cachedSource = release().sources.single().copy(parts = listOf(AudioPart("part", "Christopher Paolini/Eragon/Eragon.m4b", "Eragon")))
        val accountBook = titleOnly.copy(provider = "torbox", id = "torbox:42", sources = listOf(cachedSource), cacheState = "cached")
        val discovery = BookSourceDiscovery(provider(emptyList()), provider(listOf(series, surname, titleOnly, bundle)), { query ->
            assertEquals("", query)
            listOf(accountBook)
        }, { candidates ->
            assertEquals(listOf(series.id, surname.id, titleOnly.id, bundle.id), candidates.map { it.id })
            candidates.map { it.copy(sources = listOf(cachedSource), cacheState = "cached", cachedFormats = listOf("M4B")) }
        })
        val result = discovery.search(eragon, true)
        assertNull(result.error)
        assertEquals(listOf(accountBook.id, series.id, surname.id), result.recordings.map { it.id })
        assertTrue(result.recordings.all { it.sources.single().parts.single().name == "Christopher Paolini/Eragon/Eragon.m4b" })
    }

    @Test fun cachedCollectionSelectsOnlyTheRequestedBookAndKeepsDifferentBooksSeparate() = runBlocking {
        val eragon = book.copy(title = "Eragon", author = "Christopher Paolini")
        // Real public DED827306725EB1F65E98D8642B74264A9BAD752 release and manifest filenames.
        val collection = release("Eragon, Eldest, Brisingr - Christopher Paolini").copy(cacheState = "unchecked", sources = emptyList())
        val files = listOf("Brisingr/Brisingr Part 1.m4b", "Brisingr/Brisingr Part 2.m4b",
            "Eldest/Eldest Part 2.m4b", "Eldest/Eldest Part1.m4b", "Eragon/Eragon.m4b")
        val cached = collection.copy(cacheState = "cached", sources = listOf(release().sources.single().copy(parts = files.mapIndexed { i, path -> AudioPart("file:$i", path, path) })))
        val discovery = BookSourceDiscovery(provider(emptyList()), provider(listOf(collection)), { emptyList() }, { candidates ->
            assertEquals(listOf(collection.id), candidates.map { it.id })
            listOf(cached)
        })
        val chosen = discovery.search(eragon, true).recordings.single()
        assertEquals(listOf("Eragon/Eragon.m4b"), chosen.sources.single().parts.map { it.name })
        assertEquals("cached", chosen.cacheState)
        assertEquals(listOf("M4B"), chosen.cachedFormats)
        assertEquals(listOf(chosen), SourceQuality.filter(eragon, listOf(chosen)))
        val eldest = SourceQuality.filter(eragon.copy(title = "Eldest"), listOf(cached)).single()
        assertNotEquals(chosen.id, eldest.id)
        assertEquals(listOf("Eldest/Eldest Part 2.m4b", "Eldest/Eldest Part1.m4b"), eldest.sources.single().parts.map { it.name })
        assertTrue(SourceQuality.filter(eragon, listOf(cached.copy(title = "Someone Else - Eragon Collection"))).isEmpty())
        assertTrue(SourceQuality.filter(eragon, listOf(cached.copy(title = "Eragon Collection - Someone Else and Christopher Paolini"))).isEmpty())
        assertTrue(SourceQuality.filter(eragon, listOf(cached.copy(title = "Christopher Paolini - Summary of Eragon"))).isEmpty())
        assertTrue(SourceQuality.filter(eragon, listOf(cached.copy(title = "Christopher Paolini - Eragon 2"))).isEmpty())
        assertTrue(SourceQuality.filter(eragon, listOf(cached.copy(title = "Inheritance (Eragon Book 4) - Christopher Paolini"))).isEmpty())
        val noBookFolder = cached.copy(sources = listOf(cached.sources.single().copy(parts = listOf(AudioPart("wrong", "Inheritance (Eragon Book 4).m4b", "Wrong volume")))))
        assertTrue(SourceQuality.filter(eragon, listOf(noBookFolder)).isEmpty())
        // Account names can be just the author; actual fresh files still identify this book.
        assertEquals(listOf("Eragon/Eragon.m4b"), SourceQuality.filter(eragon, listOf(cached.copy(title = "Christopher Paolini", provider = "torbox"))).single().sources.single().parts.map { it.name })
    }

    @Test fun catalogSubtitleFallbackFindsBaseTitleAndPreservesPartialProviderSuccess() = runBlocking {
        val detailed = book.copy(title = "Project Hail Mary: A Novel")
        val recording = release("Project Hail Mary", "Andy Weir", "archive")
        val queries = mutableListOf<String>()
        val archive = object : RecordingDiscovery {
            override suspend fun search(query: String, category: String): List<Audiobook> {
                queries += query
                if (':' in query) throw ProviderException("Unavailable")
                return listOf(recording)
            }
            override suspend fun recording(id: String) = recording
        }
        val result = BookSourceDiscovery(archive, provider(emptyList()), { error("Disconnected") }, { error("Disconnected") }).search(detailed, false)
        assertEquals(listOf("Project Hail Mary: A Novel", "Project Hail Mary"), queries)
        assertEquals(recording.id, result.recordings.single().id)
        assertNotNull(result.error)
        assertFalse(SourceQuality.matches(detailed, release("Andy Weir - Project Hail Mary 2")))
    }

    @Test fun titleAuthorAndReleaseEvidenceRejectWrongBooks() {
        assertTrue(SourceQuality.matches(book, release()))
        assertTrue(SourceQuality.matches(book, release("Project Hail Mary", "Weir, Andy", "archive")))
        for (candidate in listOf(release(author = "Someone Else"), release("Andy Weir - Project Hail Mary 2"),
            release("Andy Weir - Project Hail Mary Complete Collection"), release("Andy Weir - Summary of Project Hail Mary"),
            release("Andy Weir - Project Hail Mary [Summary]"), release("Andy Weir - Project Hail Mary [Complete Collection]"),
            release("Andy Weir - Project Hail Mary [Sequel]"),
            release("Project Hail Mary"), release("Andy Weir - Project Hail"))) {
            assertFalse(candidate.title, SourceQuality.matches(book, candidate))
        }
    }

    @Test fun editionMetadataFindsBaseTitleRecordingWithoutChangingSourceIdentity() = runBlocking {
        val suffixed = book.copy(title = "Project Hail Mary (Special Edition)")
        val recording = release("Project Hail Mary", "Andy Weir", "archive")
        val archive = object : RecordingDiscovery {
            override suspend fun search(query: String, category: String): List<Audiobook> {
                assertEquals("Project Hail Mary", query)
                return listOf(recording)
            }
            override suspend fun recording(id: String) = recording
        }
        val results = BookSourceDiscovery(archive, provider(emptyList()), { emptyList() }, { it }).search(suffixed, false)
        assertEquals(recording.id, results.recordings.single().id)
        assertTrue(SourceQuality.matches(book, release("Project Hail Mary (Special Edition)", "Andy Weir", "archive")))
    }

    @Test fun filtersSamplesUncheckedUnavailableAndEmptyAudioThenDeduplicatesReadySources() {
        val valid = release()
        val public = release("Project Hail Mary", "Andy Weir", "archive").copy(torrentHash = "b".repeat(40), cacheState = "unchecked", cachedFormats = emptyList())
        val sample = valid.copy(id = "sample", sources = listOf(valid.sources.single().copy(parts = listOf(AudioPart("sample", "sample.m4b", "Sample")))))
        val invalid = listOf(sample, valid.copy(id = "empty", sources = emptyList()), valid.copy(id = "unknown", cacheState = "unchecked"),
            valid.copy(id = "uncached", cacheState = "uncached"), valid.copy(id = "wrong-format", cachedFormats = listOf("MP3")))
        val filtered = SourceQuality.filter(book, listOf(public, valid, valid.copy(id = "duplicate")) + invalid)
        assertEquals(listOf(valid.id, public.id), filtered.map { it.id })
    }

    @Test fun providerFailuresKeepMatchingPublicAudioAndNeverShowUncheckedIndexedSources() = runBlocking {
        val public = release("Project Hail Mary", "Andy Weir", "archive")
        val discovery = BookSourceDiscovery(provider(listOf(public)), provider(listOf(release())), { emptyList() }, { throw ProviderException("Unavailable") })
        val results = discovery.search(book, true)
        assertEquals(listOf(public.id), results.recordings.map { it.id }); assertNotNull(results.error)
    }

    @Test fun disconnectedDiscoveryUsesOnlyPublicRecordingsAndRechecksHydratedIdentity() = runBlocking {
        val partial = release("Project Hail Mary", "Andy Weir", "archive").copy(detailsLoaded = false)
        val wrong = partial.copy(author = "Someone Else", detailsLoaded = true)
        val indexed = object : RecordingDiscovery {
            override suspend fun search(query: String, category: String): List<Audiobook> = error("Must not search indexed sources without delivery")
            override suspend fun recording(id: String): Audiobook = error("Unused")
        }
        val discovery = BookSourceDiscovery(provider(listOf(partial), wrong), indexed, { error("Must not read an account") }, { error("Must not check cache") })
        assertTrue(discovery.search(book, false).recordings.isEmpty())
    }

    @Test fun workDetailsNeverOverwriteSourceNarratorLanguageOrPlayableIdentity() {
        val original = release().copy(narrator = "Unknown reader", language = "Language not verified")
        val described = SourceQuality.describe(original, book.copy(narrator = "Ray Porter", narratorFromCatalog = true))
        assertEquals(original.id, described.id); assertEquals(original.sources, described.sources); assertEquals(original.torrentHash, described.torrentHash)
        assertEquals(original.narrator, described.narrator); assertEquals(original.language, described.language); assertFalse(described.narratorFromCatalog)
        assertEquals(original.title, described.releaseTitle); assertEquals(book.title, described.title)
    }
}
