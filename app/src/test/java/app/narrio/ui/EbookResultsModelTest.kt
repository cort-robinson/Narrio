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

    /** Other choices: one flat list without the best match, sure matches first, then the likeliest fit with the narration. */
    @Test fun otherChoicesAreOneFlatListWithPlainNotes() {
        val best = ebook("best")
        val sample = BookTextSource("sample", "Pride and Prejudice (Sample)", "Jane Austen", "EPUB", "torbox-cache", language = "en")
        val french = BookTextSource("fr", "Orgueil et Préjugés", "Jane Austen", "EPUB", "annas", language = "French")
        val english = BookTextSource("en", "Pride and Prejudice", "Jane Austen", "TXT", "annas", language = "English")
        val maybe = BookTextSource("maybe", "Pride and Prejudice [EPUB]", "", "EPUB", "gutenberg")
        val search = StreamedEbookSearch(book, listOf(
            EbookGroup("addon:cache", "Knaben Ebooks", SourceGroupStatus.DONE, editions = listOf(sample)),
            EbookGroup("addon:annas", "Anna's Archive", SourceGroupStatus.DONE, editions = listOf(french, english)),
            EbookGroup("gutenberg", "Project Gutenberg", SourceGroupStatus.DONE, editions = listOf(best), possible = listOf(maybe)),
        ), complete = true)
        val choices = ebookChoices(search, book, shownId = "best")
        assertEquals(listOf("en", "sample", "fr", "maybe"), choices.map { it.edition.id })
        assertEquals("Anna's Archive", choices.first().source)
        assertEquals(ChoiceNote(ChoiceNoteKind.LIKELY, "Likely matches the narration"), ebookChoiceNote(choices[0], recording = true))
        assertNull(ebookChoiceNote(choices[0], recording = false))
        assertEquals(ChoiceNote(ChoiceNoteKind.CAUTION, "May not follow the narration: abridged or a sample"), ebookChoiceNote(choices[1], recording = true))
        assertEquals("Abridged or a sample", ebookChoiceNote(choices[1], recording = false)!!.text)
        assertEquals(ChoiceNoteKind.CHECK, ebookChoiceNote(choices[3], recording = true)!!.kind)
        // The language shows only when it differs from the book's.
        assertEquals("Jane Austen · TXT", ebookChoiceDetail(choices[0], book))
        assertEquals("Jane Austen · French · EPUB", ebookChoiceDetail(choices[2], book))
    }

    @Test fun searchWordSuggestionsComeFromTheBooksOwnDetails() {
        val wizard = Audiobook("w", "A Wizard of Earthsea: The Earthsea Cycle, Book 1", "Ursula K. Le Guin", provider = "catalog",
            description = "The first Earthsea novel. Originally published as \"The Wizard of the Archipelago\" in some markets.")
        val words = ebookWordSuggestions(wizard)
        assertEquals("A Wizard of Earthsea: The Earthsea Cycle, Book 1 Ursula K. Le Guin", words.bookDetails)
        assertEquals(listOf(
            EbookWordChoice("Title only", "A Wizard of Earthsea: The Earthsea Cycle, Book 1"),
            EbookWordChoice("Without subtitle", "A Wizard of Earthsea Ursula K. Le Guin"),
            EbookWordChoice("Original title", "The Wizard of the Archipelago Ursula K. Le Guin"),
        ), words.choices)
        // A plain title has nothing to shorten and no other title to offer.
        assertEquals(listOf("Title only"), ebookWordSuggestions(book).choices.map { it.label })
        // Searching for the book's own details is the default search, not custom words.
        assertEquals("", customEbookWords(" pride and prejudice  JANE AUSTEN ", book))
        assertEquals("Pride Prejudice", customEbookWords(" Pride Prejudice ", book))
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
