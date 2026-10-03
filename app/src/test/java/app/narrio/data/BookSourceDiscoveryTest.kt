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
