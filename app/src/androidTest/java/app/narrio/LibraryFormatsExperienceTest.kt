package app.narrio

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.narrio.data.*
import app.narrio.domain.*
import app.narrio.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Shelf filters, format marks, Continue, details actions, Find ebook, import, and jump feedback against a controlled
 * [ReadingLibrary]. Formats, positions, and pairing are synthetic fixtures; no reader, sync, or provider result is implied.
 */
@RunWith(AndroidJUnit4::class)
class LibraryFormatsExperienceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = (compose.activity.application as NarrioApplication).graph
    private val vm get() = ViewModelProvider(compose.activity)[NarrioViewModel::class.java]

    private val parts = AudioSource("fixture-parts", "Ordered audio parts", "MP3", List(12) { AudioPart("fixture-p$it", "p$it.mp3", "Part ${it + 1}", durationMs = 600_000) })
    private val audioOnly = Audiobook("fixture-audio-only", "The Lantern Keeper", "Mara Ellison", "Ruth Golding", provider = "archive", detailsLoaded = true, sources = listOf(parts))
    private val ebookOnly = Audiobook("fixture-ebook-only", "Notes from a Quiet Harbor", "Ines Calder", provider = "catalog", detailsLoaded = true)
    private val both = Audiobook("fixture-both", "The Salt Road Letters", "Tomas Wren", "Kara Shallenberg", provider = "archive", detailsLoaded = true, sources = listOf(parts))
    private fun edition(book: Audiobook, attribution: String = "Project Gutenberg · ebook 1342") = EbookEdition("ed-${book.id}", book.id, book.title, book.author, "EPUB", "en", attribution, "gutenberg", active = true)
    private val cursor = ContentCursor("ed-fixture-both", "ch12.xhtml", 40, progression = .437)
    private val formats = mapOf(
        audioOnly.id to BookFormats(audioOnly.id, audio = true, audioSource = parts,
            position = SharedPosition(audioOnly.id, PositionOrigin.LISTENING, audio = AudioCursor(parts.id, "fixture-p2", 300_000), updatedAtMs = 1)),
        ebookOnly.id to BookFormats(ebookOnly.id, editions = listOf(edition(ebookOnly, "Imported from your device"))),
        both.id to BookFormats(both.id, audio = true, editions = listOf(edition(both)), pairing = PairingStatus.PARTIAL, audioSource = parts, textChapter = 12,
            position = SharedPosition(both.id, PositionOrigin.READING, text = cursor, audio = AudioCursor(parts.id, "fixture-p4", 130_000),
                textConfidence = MappingConfidence.EXACT, audioConfidence = MappingConfidence.ESTIMATED, updatedAtMs = 2)),
    )
    private val fake = FakeReadingLibrary(formats)

    private fun suffix(): String {
        val window = compose.activity.resources.configuration
        return if (window.screenWidthDp >= 600) "unfolded" else if (window.fontScale > 1.2f) "phone-large-text" else "phone"
    }
    private fun theme(mode: ThemeMode) = compose.runOnIdle { vm.updateAppearance(vm.appearance.value.copy(mode = mode)) }
    private fun capture(name: String) {
        compose.waitForIdle(); runBlocking { delay(700) }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val folder = File(compose.activity.filesDir, "qa-captures").apply { mkdirs() }
        File(folder, "$name-${suffix()}.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private fun seed() = runBlocking {
        listOf(audioOnly, ebookOnly, both).forEach { graph.library.save(it) }
        graph.library.progress(audioOnly.id, NarrioJson.encodeToString(parts), "fixture-p2", 300_000, 10)
        graph.library.progress(both.id, NarrioJson.encodeToString(parts), "fixture-p4", 120_000, 5)
        compose.runOnIdle { vm.readingLibrary.value = fake; vm.connected.value = false }
    }

    @After fun clean() = runBlocking { listOf(audioOnly, ebookOnly, both, fake.importable).forEach { graph.library.remove(it.id) } }

    @Test fun shelfFiltersByFormatAndContinueReopensTheLastMode() {
        seed()
        theme(ThemeMode.NIGHT)
        compose.runOnIdle { vm.navigate(1) }
        compose.waitUntil(10_000) { shown(both.title) && shown(audioOnly.title) }
        compose.onNodeWithTag("shelf").performScrollToNode(hasText("Ch 12 · 43%"))
        compose.onNodeWithText("Ch 12 · 43%").assertExists()
        compose.onNode(hasText(both.title) and hasText("Audiobook") and hasText("Ebook") and hasClickAction()).assertExists()
        compose.onNode(hasText(ebookOnly.title) and hasText("Ebook") and hasClickAction()).assert(!hasText("Audiobook"))
        capture("shelf-all-night")

        fun visible(filter: String, vararg titles: String) {
            compose.onNodeWithText(filter).performClick()
            compose.waitForIdle()
            titles.forEach { compose.onNodeWithTag("shelf").performScrollToNode(hasText(it)) }
            listOf(audioOnly, ebookOnly, both).filter { it.title !in titles }.forEach { assertFalse("${it.title} under $filter", shown(it.title)) }
            compose.onNodeWithTag("shelf").performScrollToIndex(0)
        }
        visible("Ebooks", ebookOnly.title, both.title)
        theme(ThemeMode.DAY)
        capture("shelf-ebooks-day")
        visible("Both", both.title)
        visible("Audiobooks", audioOnly.title, both.title)
        visible("All", audioOnly.title, ebookOnly.title, both.title)

        // A filter with nothing in it explains how to fill it and offers the way back.
        compose.runOnIdle { fake.formats.value = formats - both.id + (both.id to formats.getValue(both.id).copy(editions = emptyList())) }
        compose.onNodeWithText("Both").performClick()
        compose.onNodeWithText("No books with both formats yet").assertIsDisplayed()
        capture("shelf-both-empty-day")
        compose.onNodeWithText("Show all", substring = true).performClick()
        compose.runOnIdle { fake.formats.value = formats }

        // Continue on the pair reopens reading, where it was last moved.
        compose.onNodeWithTag("shelf").performScrollToNode(hasContentDescription("Continue reading ${both.title}"))
        compose.onNodeWithContentDescription("Continue reading ${both.title}").performClick()
        compose.waitUntil(5_000) { vm.reader.value != null }
        assertEquals(both.id, vm.reader.value!!.book.id)
        assertEquals("Ch 12 · 43%", vm.reader.value!!.place!!.label)
        compose.onNodeWithTag("reader").assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(5_000) { vm.reader.value == null }
        assertEquals(1, vm.destination.value)
        // An ebook-only book that hasn't been read yet still offers Read on the shelf.
        compose.onNodeWithTag("shelf").performScrollToNode(hasContentDescription("Read ${ebookOnly.title}"))
        compose.onNodeWithContentDescription("Read ${ebookOnly.title}").assertExists()
    }

    @Test fun detailsOfferReadListenAndFindForEachFormatCombination() {
        seed()
        theme(ThemeMode.NIGHT)
        // Audio only: Listen leads; Find ebook replaces Read.
        compose.runOnIdle { vm.selection.value = SelectionState(audioOnly) }
        compose.onNodeWithTag("listen-action").assertIsEnabled()
        compose.onNodeWithTag("find-ebook-action").assertIsDisplayed()
        compose.onNodeWithText("Listening · Part 3 of 12 · 20%").assertIsDisplayed()
        compose.onNodeWithText("Save").assertDoesNotExist()
        compose.onNodeWithText("Saved").assertIsDisplayed()
        capture("details-audio-night")

        // Find ebook opens the sheet, names the provider being checked, then lists what it found.
        compose.runOnIdle {
            fake.findDelayMs = 2_000
            fake.found = BookTextFinder.Candidates(listOf(
                BookTextSource("gutenberg:1", audioOnly.title, audioOnly.author, "EPUB", "gutenberg", attribution = "Project Gutenberg · ebook 1", language = "en"),
                BookTextSource("torbox-cache:1", "${audioOnly.title} - ${audioOnly.author} [EPUB]", format = "EPUB", provider = "torbox-cache", attribution = "Cached ebook release via TorBox")), incomplete = true)
        }
        compose.onNodeWithTag("find-ebook-action").performClick()
        compose.onNodeWithText("Find an ebook").assertIsDisplayed()
        compose.onNodeWithText("Checking Project Gutenberg…").assertIsDisplayed()
        capture("find-ebook-searching-night")
        compose.waitUntil(10_000) { shown("Cached ebook release via TorBox") }
        compose.onNodeWithText("Some providers didn't respond, so this list may be incomplete.").assertExists()
        compose.onNodeWithText("Choose an EPUB or text file").assertExists()
        capture("find-ebook-results-night")
        compose.runOnIdle { fake.findDelayMs = 0; fake.found = BookTextFinder.Candidates(emptyList(), incomplete = false) }
        compose.onNodeWithText("Search again").performClick()
        compose.waitUntil(10_000) { shown("No ebook with this exact title and author turned up. Add your own EPUB or text file below.") }
        theme(ThemeMode.DAY)
        capture("find-ebook-empty-day")
        // Adding an edition closes the sheet.
        compose.runOnIdle { vm.ebookSearch.value = EbookSearchState(audioOnly.id, searched = true, added = "new-edition") }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("ebook-sheet").fetchSemanticsNodes().isEmpty() }

        // Ebook only: Read leads; Find audiobook waits to be asked; no pairing to judge.
        compose.runOnIdle { vm.selection.value = SelectionState(ebookOnly); vm.sourceSearch.value = SourceSearchState(ebookOnly) }
        compose.onNodeWithTag("read-action").assertIsEnabled()
        compose.onNodeWithTag("find-audiobook-action").assertIsDisplayed()
        compose.onNodeWithText("Ebook · EPUB · Imported from your device").assertIsDisplayed()
        compose.onNodeWithText("Not checked against the narration yet").assertDoesNotExist()
        capture("details-ebook-day")

        // Both: the mode used last leads, the other opens at its mapped place, and the pair's match is explained.
        compose.runOnIdle { vm.selection.value = SelectionState(both); vm.sourceSearch.value = SourceSearchState() }
        compose.onNodeWithTag("read-action").assertIsEnabled()
        compose.onNodeWithTag("listen-action").assertIsEnabled()
        compose.onNodeWithText("Reading · Ch 12 · 43%").assertIsDisplayed()
        compose.onNodeWithContentDescription("Listening starts at about Part 5 of 12, 35%, estimated").assertIsDisplayed()
        compose.onNodeWithTag("book-details").performScrollToNode(hasText("Partly matches the narration"))
        compose.onNodeWithText("Choose another edition").assertIsDisplayed()
        capture("details-both-day")
        theme(ThemeMode.NIGHT)
        capture("details-both-night")
        compose.runOnIdle { fake.formats.value = formats + (both.id to formats.getValue(both.id).copy(pairing = PairingStatus.MISMATCH)) }
        compose.onNodeWithText("Doesn't match the narration").assertExists()
        // Sync stays off for a pair that doesn't match, so no mapped place is offered.
        compose.onNodeWithContentDescription("Listening starts at about Part 5 of 12, 35%, estimated").assertDoesNotExist()

        compose.onNodeWithText("Choose another edition").performScrollTo().performClick()
        compose.onNodeWithText("Choose an edition").assertIsDisplayed()
        compose.onNodeWithText("On this phone").assertExists()
        compose.waitUntil(10_000) { !vm.ebookSearch.value.searching }
        capture("choose-edition-night")
    }

    @Test fun addingAnEbookFileOpensItsBookOrExplainsTheFile() {
        seed()
        theme(ThemeMode.DAY)
        compose.runOnIdle { vm.navigate(1) }
        compose.runOnIdle { fake.importError = ProviderException(unsupportedEbook("Dune.azw3")) }
        compose.runOnIdle { vm.beginEbookImport(null); vm.importEbook(Uri.parse("content://fixture/Dune.azw3")) }
        compose.onNodeWithText("Couldn't add this ebook").assertIsDisplayed()
        compose.onNodeWithText("Kindle files (MOBI, AZW3) can't be opened. Choose an EPUB or a UTF-8 .txt file.").assertIsDisplayed()
        compose.onNodeWithText("Choose another file").assertIsDisplayed()
        capture("import-error-day")
        compose.onNodeWithText("Dismiss").performClick()
        compose.onNodeWithText("Couldn't add this ebook").assertDoesNotExist()

        compose.runOnIdle { fake.importError = null; fake.importDelayMs = 1_500; vm.beginEbookImport(null); vm.importEbook(Uri.parse("content://fixture/harbor.epub")) }
        compose.onNodeWithText("Adding your ebook").assertIsDisplayed()
        theme(ThemeMode.NIGHT)
        capture("import-working-night")
        compose.waitUntil(10_000) { vm.selection.value.book?.id == fake.importable.id }
        compose.onNodeWithTag("read-action").assertIsEnabled()
        compose.onNodeWithTag("find-audiobook-action").assertIsDisplayed()
        capture("import-opened-night")
    }

    @Test fun jumpSnackbarNamesThePlaceAndUndoes() {
        theme(ThemeMode.NIGHT)
        var undone = false
        compose.runOnIdle { vm.announceJump(PositionJump(both.id, PositionOrigin.READING, "Ch 12 · 43%", MappingConfidence.ESTIMATED) { undone = true }) }
        compose.onNodeWithText("Jumped to where you read").assertIsDisplayed()
        compose.onNodeWithText("≈ Ch 12 · 43%").assertIsDisplayed()
        compose.onNodeWithContentDescription("Jumped to where you read, about Chapter 12, 43%, estimated").assertExists()
        capture("jump-undo-night")
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(5_000) { undone }
        theme(ThemeMode.DAY)
        compose.runOnIdle { vm.announceJump(PositionJump(both.id, PositionOrigin.LISTENING, "Part 5 of 12 · 35%") {}) }
        compose.onNodeWithText("Jumped to where you listened").assertIsDisplayed()
        capture("jump-undo-day")
    }
}

/** A controlled [ReadingLibrary]: formats are set by the test; imports return a fixed ebook-only book. */
class FakeReadingLibrary(initial: Map<String, BookFormats>) : ReadingLibrary {
    val formats = MutableStateFlow(initial)
    val importable = Audiobook("fixture-imported", "Notes from a Quiet Harbor", "Ines Calder", provider = "catalog", detailsLoaded = true)
    @Volatile var importError: Exception? = null
    @Volatile var importDelayMs = 0L
    @Volatile var found = BookTextFinder.Candidates(emptyList(), incomplete = false)
    @Volatile var findDelayMs = 0L
    override fun observeShelf(): Flow<Map<String, BookFormats>> = formats
    override fun observeBook(book: Audiobook): Flow<BookFormats> =
        formats.map { it[book.id] ?: BookFormats(book.id, audio = book.provider != "catalog" && book.sources.isNotEmpty()) }
    override suspend fun importBook(uri: Uri): Audiobook {
        delay(importDelayMs)
        importError?.let { throw it }
        val edition = EbookEdition("ed-imported", importable.id, importable.title, importable.author, "EPUB", attribution = "Imported from your device", active = true)
        formats.update { it + (importable.id to BookFormats(importable.id, editions = listOf(edition))) }
        return importable
    }
    override suspend fun importEdition(book: Audiobook, uri: Uri): EbookEdition = throw ProviderException("Not used by this fixture.")
    override suspend fun addEdition(book: Audiobook, candidate: BookTextSource): EbookEdition = throw ProviderException("Not used by this fixture.")
    override suspend fun activate(bookId: String, editionId: String) = Unit
    override suspend fun findEditions(book: Audiobook, connected: Boolean, step: (String) -> Unit): BookTextFinder.Candidates {
        step("Checking Project Gutenberg"); delay(findDelayMs); return found
    }
}
