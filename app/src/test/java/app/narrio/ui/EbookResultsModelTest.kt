package app.narrio.ui

import app.narrio.data.DeviceSourceProviderSettings
import app.narrio.domain.*
import org.junit.Assert.*
import org.junit.Test

class EbookResultsModelTest {
    private val book = Audiobook("catalog:pp", "Pride and Prejudice", "Jane Austen", language = "English", provider = "catalog")
    private fun ebook(id: String, provider: String = "gutenberg") = BookTextSource(id, "Pride and Prejudice", "Jane Austen", "EPUB", provider)
    private fun provider(id: String, order: Int, enabled: Boolean = true, torBox: Boolean = false) =
        SourceProvider(id, "Source $id", SourceProviderKind.BUILT_IN, enabled, order, torBox, false)

    @Test fun reasonsReadAsPlainWords() {
        val copy = bestEbookCopy(BestEbook(ebook("c", "archive"), listOf(EbookMatchReason.WITH_RECORDING, EbookMatchReason.STRONG_MATCH,
            EbookMatchReason.EPUB, EbookMatchReason.LANGUAGE_MATCH), DeviceSourceProviderSettings.RECORDING_FILES), book)
        assertEquals("Comes with this recording · EPUB", copy.headline)
        assertEquals("Most likely the narrated edition · Matches this title and author · English", copy.detail)
        assertEquals("Free public-domain ebook · EPUB", bestEbookCopy(BestEbook(ebook("g"), listOf(EbookMatchReason.PUBLIC_DOMAIN), "gutenberg"), book).headline)
    }

    @Test fun aBetterEbookWaitsOnceTheReaderHasTouchedTheSheet() {
        val first = BestEbook(ebook("a"), emptyList(), "gutenberg")
        val better = BestEbook(ebook("b"), emptyList(), DeviceSourceProviderSettings.RECORDING_FILES)
        val held = PinnedEbook().next(first, interacted = false) { true }.next(better, interacted = true) { true }
        assertEquals("a", held.shown?.edition?.id)
        assertEquals("b", held.accept().shown?.edition?.id)
    }

    @Test fun anEmptySearchSaysWhatToDoNext() {
        val providers = listOf(provider("torbox", 0, torBox = true), provider("gutenberg", 1))
        val byId = providers.associateBy { it.id }
        val pending = pendingEbookSearch(book, providers, connected = false, recording = false, searching = false, error = null)
        val nothing = pending.copy(groups = pending.groups.map { if (it.status == SourceGroupStatus.SEARCHING) it.copy(status = SourceGroupStatus.DONE) else it }, complete = true)
        val tally = ebookTally(nothing, byId, connected = false)
        assertEquals(NoMatchKind.NEEDS_TORBOX, ebookNoMatchCopy(nothing, tally).kind)
        assertEquals("Source gutenberg has no ebook of this book. Connect TorBox to also check 1 more source.", ebookNoMatchCopy(nothing, tally).message)
        val possible = nothing.copy(groups = nothing.groups.map { if (it.providerId == "gutenberg") it.copy(possible = listOf(ebook("p"))) else it })
        assertEquals(NoMatchKind.POSSIBLE_ONLY, ebookNoMatchCopy(possible, ebookTally(possible, byId, false)).kind)
        assertEquals("1 source searched · 0 found · 1 need TorBox", searchSummary(possible.complete, ebookTally(possible, byId, false)))
        // A lookup that couldn't start fails every active section with its reason.
        val failed = pendingEbookSearch(book, providers, connected = true, recording = false, searching = false, error = "No connection")
        assertEquals(listOf(SourceGroupStatus.FAILED, SourceGroupStatus.FAILED), failed.groups.map { it.status })
        assertEquals(NoMatchKind.ALL_FAILED, ebookNoMatchCopy(failed, ebookTally(failed, byId, true)).kind)
    }
}
