package app.narrio.data

import app.narrio.domain.*
import org.junit.Assert.*
import org.junit.Test

class BestMatchRankingTest {
    private val book = Audiobook("catalog", "Project Hail Mary", "Andy Weir", provider = "catalog", language = "English")
    private fun release(id: String, format: String = "M4B", ready: Boolean = true, seeders: Long = 0) = book.copy(id = id, provider = "knaben",
        cacheState = if (ready) "cached" else "uncached", cachedFormats = if (ready) listOf(format) else emptyList(), seeders = seeders,
        language = "Language not verified", sources = listOf(AudioSource(id, "Audio", format, listOf(AudioPart(id, "book.${format.lowercase()}", "Book")), delivery = "torbox")))
    private fun choose(vararg recordings: Audiobook, phone: Set<String> = emptySet(), format: String = "M4B") = BestMatchRanking.choose(book,
        recordings.mapIndexed { i, recording -> SourceGroup("p$i", "Provider $i", SourceGroupStatus.DONE, listOf(recording)) }, phone, format)

    @Test fun phoneBeatsReadyStreamingAndSeeders() {
        val downloaded = release("local", "MP3", ready = false)
        val best = choose(release("cached", seeders = 900), downloaded, phone = setOf("local"))!!
        assertEquals("local", best.recording.id)
        assertTrue(BestMatchReason.ON_PHONE in best.reasons)
        assertTrue(BestMatchReason.READY_TO_STREAM in best.reasons)
        assertFalse(BestMatchReason.NEEDS_PREPARING in best.reasons)
    }

    @Test fun readyAlwaysBeatsUncachedEvenWithPreferredFormatAndSeeders() {
        val best = choose(release("pending", ready = false, seeders = 900), release("ready", "MP3"))!!
        assertEquals("ready", best.recording.id)
        assertTrue(BestMatchReason.READY_TO_STREAM in best.reasons)
    }

    @Test fun weakPossibleResultsAreNotChosenAutomatically() {
        val possible = release("possible").copy(author = "Author not verified")
        assertNull(BestMatchRanking.choose(book, listOf(SourceGroup("p", "p", SourceGroupStatus.DONE, possible = listOf(possible)))))
    }

    @Test fun strongEvidenceBeatsPreferredFormat() {
        val weak = release("weak").copy(author = "Author not verified")
        assertEquals("strong", choose(weak, release("strong", "MP3"))!!.recording.id)
    }

    @Test fun defaultM4bBeatsLanguageNarratorAndSeedersThenRememberedMp3Wins() {
        val mp3 = release("mp3", "MP3", seeders = 100).copy(narrator = "Ray Porter", language = "English")
        val m4b = release("m4b")
        assertEquals("m4b", choose(mp3, m4b)!!.recording.id)
        assertEquals("mp3", choose(mp3, m4b, format = "MP3")!!.recording.id)
        assertTrue(BestMatchReason.PREFERRED_FORMAT in choose(m4b)!!.reasons)
    }

    @Test fun unavailablePreferredFormatDoesNotCountAsReady() {
        val recording = release("multi", "MP3").copy(sources = release("multi", "MP3").sources + release("m4b").sources)
        assertFalse(BestMatchReason.PREFERRED_FORMAT in choose(recording)!!.reasons)
    }

    @Test fun knownMatchingLanguageBeatsUnknownAndWrongLanguage() {
        val unknown = release("unknown", seeders = 900)
        val spanish = release("spanish").copy(language = "Spanish")
        val english = release("english").copy(language = "eng")
        val best = choose(unknown, spanish, english)!!
        assertEquals("english", best.recording.id)
        assertTrue(BestMatchReason.LANGUAGE_MATCH in best.reasons)
        assertFalse(BestMatchReason.LANGUAGE_MATCH in choose(unknown)!!.reasons)
    }

    @Test fun explicitUnabridgedBeatsUnknownThenKnownNarratorThenSeeders() {
        val unnamed = release("unnamed", seeders = 900)
        val full = release("full").copy(title = "Andy Weir - Project Hail Mary [Unabridged]")
        assertEquals("full", choose(unnamed, full)!!.recording.id)
        assertTrue(BestMatchReason.UNABRIDGED in choose(full)!!.reasons)
        assertFalse(BestMatchReason.UNABRIDGED in choose(unnamed)!!.reasons)
        val named = unnamed.copy(id = "named", seeders = 0, narrator = "Ray Porter")
        assertEquals("named", choose(unnamed, named)!!.recording.id)
        assertTrue(BestMatchReason.NARRATOR_KNOWN in choose(named)!!.reasons)
        assertEquals("healthy", choose(unnamed.copy(seeders = 0), release("healthy", seeders = 12))!!.recording.id)
    }

    @Test fun catalogNarratorDoesNotClaimRecordingEvidence() {
        val catalogHint = release("hint").copy(narrator = "Ray Porter", narratorFromCatalog = true)
        assertFalse(BestMatchReason.NARRATOR_KNOWN in choose(catalogHint)!!.reasons)
    }

    @Test fun uncachedOnlyBestReportsNeedsPreparingAndPublicReportsFree() {
        val pending = choose(release("pending", ready = false, seeders = 12))!!
        assertEquals(listOf(BestMatchReason.NEEDS_PREPARING, BestMatchReason.STRONG_MATCH, BestMatchReason.PREFERRED_FORMAT,
            BestMatchReason.WELL_SEEDED), pending.reasons)
        val public = release("public").copy(provider = "archive", cacheState = "unchecked", cachedFormats = emptyList(),
            narrator = "Volunteer Reader", language = "English")
        assertTrue(BestMatchReason.FREE_PUBLIC_RECORDING in choose(public)!!.reasons)
    }

    @Test fun tiesKeepProviderPriorityAndEmptySearchHasNoBest() {
        assertEquals("p0", choose(release("first"), release("second"))!!.providerId)
        assertNull(choose())
    }

    @Test fun projectedCollectionAndHashBasedDownloadIdentityRankCorrectly() {
        val collection = release("collection").copy(title = "Andy Weir collection", bookFilesSelected = true, torrentHash = "a".repeat(40))
        val best = choose(release("other"), collection, phone = setOf("a".repeat(40)))!!
        assertEquals(collection, best.recording); assertTrue(BestMatchReason.STRONG_MATCH in best.reasons)
    }
}
