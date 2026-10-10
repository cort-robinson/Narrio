package app.narrio.ui

import app.narrio.domain.*
import org.junit.Assert.*
import org.junit.Test

class SourceResultsModelTest {
    private val book = Audiobook("catalog:phm", "Project Hail Mary", "Andy Weir", provider = "catalog")
    private fun release(id: String, provider: String = "knaben", cache: String = "cached", formats: List<String> = listOf("M4B"), title: String = "Project Hail Mary - Andy Weir", addon: String = "") =
        Audiobook(id, "Project Hail Mary", "Andy Weir", provider = provider, cacheState = cache, cachedFormats = if (cache == "cached") formats else emptyList(), releaseTitle = title,
            sourceAddonName = addon, sources = formats.map { AudioSource("$id-$it", "Audio", it, listOf(AudioPart("$id-$it-1", "book.${it.lowercase()}", "Book", sizeBytes = 400_000_000))) })
    private fun best(recording: Audiobook, vararg reasons: BestMatchReason) = BestMatch(recording, reasons.toList(), "torbox-search")
    private val listed: (String) -> Boolean = { true }

    @Test fun aBetterMatchWaitsOnceTheListenerHasTouchedThePage() {
        val first = best(release("a"), BestMatchReason.FREE_PUBLIC_RECORDING)
        val better = best(release("b"), BestMatchReason.READY_TO_STREAM)
        val shown = PinnedBest().next(first, interacted = false, stillListed = listed)
        // Before any touch the card may still improve.
        assertEquals("b", shown.next(better, interacted = false, stillListed = listed).shown?.recording?.id)
        // After a touch it holds still and offers the better match instead.
        val held = shown.next(better, interacted = true, stillListed = listed)
        assertEquals("a", held.shown?.recording?.id)
        assertEquals("b", held.pending?.recording?.id)
        assertEquals("b", held.accept().shown?.recording?.id)
        assertNull(held.accept().pending)
    }

    @Test fun theSameReleaseRefreshesInPlaceAndARetractedOneIsDropped() {
        val unchecked = best(release("a", cache = "unchecked"), BestMatchReason.NEEDS_PREPARING)
        val cached = best(release("a"), BestMatchReason.READY_TO_STREAM)
        val pinned = PinnedBest(unchecked).next(cached, interacted = true, stillListed = listed)
        assertEquals(cached, pinned.shown); assertNull(pinned.pending)
        // A search started again lists nothing yet: the old card goes rather than pointing at a vanished release.
        assertNull(pinned.next(null, interacted = true) { false }.shown)
    }

    @Test fun reasonsReadAsPlainWords() {
        val recording = release("a", title = "Project Hail Mary - Andy Weir (read by Ray Porter) [Unabridged]")
        val copy = bestMatchCopy(best(recording, BestMatchReason.READY_TO_STREAM, BestMatchReason.PREFERRED_FORMAT, BestMatchReason.NARRATOR_KNOWN, BestMatchReason.STRONG_MATCH), book)
        assertEquals("Ready now · M4B · Read by Ray Porter", copy.headline)
        assertEquals("Matches this title and author", copy.detail)
        val uncached = release("u", cache = "uncached").copy(seeders = 12)
        val prep = best(uncached, BestMatchReason.NEEDS_PREPARING, BestMatchReason.WELL_SEEDED)
        assertEquals("Needs time to get ready · M4B", bestMatchCopy(prep, book).headline)
        assertEquals("12 seeders", bestMatchCopy(prep, book).detail)
        assertTrue(needsPreparing(prep))
        assertFalse(needsPreparing(best(release("p", provider = "archive", cache = "unchecked"), BestMatchReason.FREE_PUBLIC_RECORDING)))
    }

    @Test fun summariesAndEmptyStatesNameWhatHappened() {
        val providers = listOf(SourceProvider("archive", "LibriVox", SourceProviderKind.BUILT_IN, true, 0, false, false),
            SourceProvider("torbox-search", "TorBox search", SourceProviderKind.BUILT_IN, true, 1, true, false),
            SourceProvider("addon:abb", "AudiobookBay", SourceProviderKind.ADDON, false, 2, true, true)).associateBy { it.id }
        fun search(vararg groups: SourceGroup, complete: Boolean = true) = StreamedSourceSearch(book, groups.toList(), complete = complete)
        val off = SourceGroup("addon:abb", "AudiobookBay", SourceGroupStatus.SKIPPED)

        val running = search(SourceGroup("archive", "LibriVox", SourceGroupStatus.DONE, listOf(release("a", "archive"))), SourceGroup("torbox-search", "TorBox search", SourceGroupStatus.SEARCHING), off, complete = false)
        assertEquals("1 of 2 sources answered · 1 found", searchSummary(running, tally(running, providers, connected = true)))
        assertEquals("Off in settings", groupStatusLabel(off, providers["addon:abb"], connected = true))

        val failed = search(SourceGroup("archive", "LibriVox", SourceGroupStatus.FAILED, message = "Timed out"), SourceGroup("torbox-search", "TorBox search", SourceGroupStatus.FAILED), off)
        val failedTally = tally(failed, providers, true)
        assertEquals(NoMatchKind.ALL_FAILED, noMatchCopy(failed, failedTally).kind)
        assertEquals("None of your 2 sources answered. Check your connection, then try again.", noMatchCopy(failed, failedTally).message)
        assertEquals("Timed out", groupStatusLabel(failed.groups[0], providers["archive"], true))

        // Disconnected: only the public source searched; the TorBox ones say why they didn't.
        val disconnected = search(SourceGroup("archive", "LibriVox", SourceGroupStatus.DONE), SourceGroup("torbox-search", "TorBox search", SourceGroupStatus.SKIPPED), off)
        val disconnectedTally = tally(disconnected, providers, connected = false)
        assertEquals("Needs TorBox", groupStatusLabel(disconnected.groups[1], providers["torbox-search"], connected = false))
        assertEquals(NoMatchKind.NEEDS_TORBOX, noMatchCopy(disconnected, disconnectedTally).kind)
        assertEquals("LibriVox has no recording of this book. Connect TorBox to also search 1 more source.", noMatchCopy(disconnected, disconnectedTally).message)
        assertEquals("1 source searched · 0 found · 1 need TorBox", searchSummary(disconnected, disconnectedTally))

        val possibleOnly = search(SourceGroup("archive", "LibriVox", SourceGroupStatus.DONE, possible = listOf(release("p", "archive"))))
        assertEquals(NoMatchKind.POSSIBLE_ONLY, noMatchCopy(possibleOnly, tally(possibleOnly, providers, true)).kind)
    }

    @Test fun beforeTheFirstSnapshotEverySourceSearchesOrSaysWhyNot() {
        val providers = listOf(SourceProvider("archive", "LibriVox", SourceProviderKind.BUILT_IN, true, 0, false, false),
            SourceProvider("torbox-search", "TorBox search", SourceProviderKind.BUILT_IN, true, 1, true, false),
            SourceProvider("addon:abb", "AudiobookBay", SourceProviderKind.ADDON, false, 2, true, true))
        val waiting = pendingSourceSearch(book, providers, connected = false, searching = true, error = null)
        assertFalse(waiting.complete)
        assertEquals(listOf(SourceGroupStatus.SEARCHING, SourceGroupStatus.SKIPPED, SourceGroupStatus.SKIPPED), waiting.groups.map { it.status })
        assertEquals(listOf("Needs TorBox", "Off in settings"), waiting.groups.drop(1).map { skippedReason(it, null, connected = false) })
        // A lookup that threw fails each active source with its message, so Retry and the recovery copy apply.
        val thrown = pendingSourceSearch(book, providers, connected = true, searching = false, error = "No connection.")
        assertTrue(thrown.complete)
        assertEquals(listOf(SourceGroupStatus.FAILED, SourceGroupStatus.FAILED, SourceGroupStatus.SKIPPED), thrown.groups.map { it.status })
        assertEquals(NoMatchKind.ALL_FAILED, noMatchCopy(thrown, tally(thrown, providers.associateBy { it.id }, true)).kind)
    }
}
