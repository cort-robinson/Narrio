package app.narrio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.narrio.data.*
import app.narrio.domain.*
import app.narrio.ui.*
import androidx.compose.ui.semantics.SemanticsProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Discover's Continue row, recent searches, and the shelf's sort, search, and Finished section against a controlled
 * [ReadingLibrary]. Books, places, and formats are synthetic fixtures; no provider or playback result is implied.
 */
@RunWith(AndroidJUnit4::class)
class ShelfOrganizingExperienceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = (compose.activity.application as NarrioApplication).graph
    private val vm get() = ViewModelProvider(compose.activity)[NarrioViewModel::class.java]

    private val parts = AudioSource("shelf-parts", "Ordered audio parts", "MP3", List(6) { AudioPart("shelf-p$it", "p$it.mp3", "Part ${it + 1}", durationMs = 600_000) })
    private val heard = Audiobook("shelf-heard", "The Lantern Keeper", "Mara Ellison", provider = "archive", detailsLoaded = true, sources = listOf(parts))
    private val read = Audiobook("shelf-read", "Notes from a Quiet Harbor", "Ines Calder", provider = "catalog", detailsLoaded = true)
    private val both = Audiobook("shelf-both", "The Salt Road Letters", "Tomas Wren", provider = "archive", detailsLoaded = true, sources = listOf(parts))
    /** Enough unstarted books to make the shelf searchable; their titles sort after the three above. */
    private val filler = List(7) { Audiobook("shelf-filler-$it", "Zephyr Tales ${it + 1}", "Quinn Abbott", provider = "catalog", detailsLoaded = true) }
    private fun edition(book: Audiobook) = EbookEdition("ed-${book.id}", book.id, book.title, book.author, "EPUB", "en", "Imported from your device", "local", active = true)
    private val formats = mapOf(
        heard.id to BookFormats(heard.id, audio = true, audioSource = parts,
            position = SharedPosition(heard.id, PositionOrigin.LISTENING, audio = AudioCursor(parts.id, "shelf-p1", 300_000), updatedAtMs = 30)),
        read.id to BookFormats(read.id, editions = listOf(edition(read)), textChapter = 4,
            position = SharedPosition(read.id, PositionOrigin.READING, text = ContentCursor("ed-${read.id}", "ch4.xhtml", 0, progression = .31), updatedAtMs = 20)),
        both.id to BookFormats(both.id, audio = true, editions = listOf(edition(both)), audioSource = parts,
            position = SharedPosition(both.id, PositionOrigin.LISTENING, audio = AudioCursor(parts.id, "shelf-p4", 60_000), updatedAtMs = 10)),
    ) + filler.associate { it.id to BookFormats(it.id, editions = listOf(edition(it))) }
    private val fake = FakeReadingLibrary(formats)
    private val books get() = listOf(heard, read, both) + filler

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
    private fun described(text: String) = compose.onAllNodesWithContentDescription(text).fetchSemanticsNodes().isNotEmpty()
    private fun finishedAt(book: Audiobook) = runBlocking { graph.library.find(book.id)?.finishedAt ?: -1 }
    private fun seed() = runBlocking {
        books.forEach { graph.library.save(it) }
        graph.library.progress(heard.id, NarrioJson.encodeToString(parts), "shelf-p1", 300_000, 30)
        graph.library.progress(both.id, NarrioJson.encodeToString(parts), "shelf-p4", 60_000, 10)
        compose.runOnIdle { vm.readingLibrary.value = fake; vm.connected.value = false; vm.setShelfSort(ShelfSort.RECENT); vm.clearRecentSearches() }
    }

    @After fun clean() = runBlocking {
        books.forEach { graph.library.remove(it.id) }
        compose.runOnIdle { vm.setShelfSort(ShelfSort.RECENT); vm.clearRecentSearches() }
    }

    @Test fun continueOffersEveryBookInProgressAndDropsFinishedOnes() {
        seed()
        theme(ThemeMode.NIGHT)
        compose.runOnIdle { vm.navigate(0) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("continue-row").fetchSemanticsNodes().isNotEmpty() }
        // Each book resumes in the mode that last moved it; the newest place comes first.
        compose.onNodeWithContentDescription("Resume ${heard.title}").assertIsDisplayed()
        // Cards scrolled off screen have no visible bounds, so the row's own child order is compared.
        val cards = compose.onNodeWithTag("continue-row").onChildren().fetchSemanticsNodes().map { it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString() }
        val at = { title: String -> cards.indexOfFirst { title in it }.also { assertTrue("$title on the row", it >= 0) } }
        assertTrue(at(heard.title) < at(read.title) && at(read.title) < at(both.title))
        compose.onNodeWithContentDescription("Continue reading ${read.title}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Resume ${both.title}").performScrollTo().assertIsDisplayed()
        capture("continue-row-night")
        theme(ThemeMode.DAY)
        capture("continue-row-day")

        // Tapping a card opens the book; finishing a book takes it off the row.
        compose.onNode(hasText(both.title) and hasClickAction()).performClick()
        compose.waitUntil(5_000) { vm.selection.value.book?.id == both.id }
        compose.runOnIdle { vm.navigate(0); vm.setFinished(both.id, true); vm.setFinished(read.id, true) }
        compose.waitUntil(5_000) { !described("Resume ${both.title}") && !described("Continue reading ${read.title}") }
        // One book left: a single full-width card under the mode's own heading.
        compose.onNodeWithContentDescription("Resume ${heard.title}").assertIsDisplayed()
        compose.onNodeWithTag("continue-row").assertDoesNotExist()
        capture("continue-single-day")
    }

    @Test fun recentSearchesAreRememberedAndCanBeForgotten() {
        seed()
        theme(ThemeMode.DAY)
        compose.runOnIdle { vm.navigate(0) }
        // Submitting from the keyboard remembers the query (set directly, so no catalog search is sent).
        compose.onNodeWithTag("discover-search").performClick()
        compose.runOnIdle { vm.query.value = "the lantern keeper" }
        compose.onNodeWithTag("discover-search").performImeAction()
        compose.runOnIdle { assertEquals(listOf("the lantern keeper"), vm.recentSearches.value); vm.query.value = "" }
        compose.runOnIdle { vm.rememberSearch("Dune"); vm.rememberSearch("THE LANTERN KEEPER") }
        assertEquals(listOf("THE LANTERN KEEPER", "Dune"), vm.recentSearches.value)
        assertFalse(shown("Recent searches"))
        compose.onNodeWithTag("discover-search").performClick()
        compose.waitUntil(5_000) { shown("Recent searches") }
        compose.onNodeWithText("Dune").assertIsDisplayed()
        capture("recent-searches-day")
        theme(ThemeMode.NIGHT)
        capture("recent-searches-night")
        compose.onNodeWithContentDescription("Remove Dune from recent searches").performClick()
        compose.waitUntil(5_000) { !shown("Dune") }
        assertEquals(listOf("THE LANTERN KEEPER"), vm.recentSearches.value)
        assertEquals(RecentSearches.encode(listOf("THE LANTERN KEEPER")), graph.preferences.getString(RecentSearches.PREFERENCE, null))
        compose.onNodeWithContentDescription("Clear all recent searches").performClick()
        compose.waitUntil(5_000) { !shown("Recent searches") }
        assertTrue(vm.recentSearches.value.isEmpty())
    }

    @Test fun shelfSortsSearchesAndKeepsFinishedBooksApart() {
        seed()
        theme(ThemeMode.NIGHT)
        compose.runOnIdle { vm.navigate(1) }
        compose.waitUntil(10_000) { books.all { book -> vm.shelf.value.any { it.bookId == book.id } } }
        val total = vm.shelf.value.size
        compose.onNodeWithText("$total books").assertExists()

        // Search narrows by title or author, and the count follows.
        compose.onNodeWithTag("shelf-search").performTextInput("ines")
        compose.waitUntil(5_000) { !shown(heard.title) }
        compose.onNode(hasText(read.title) and hasClickAction()).assertIsDisplayed()
        compose.onNodeWithText("1 of $total books").assertExists()
        capture("shelf-search-night")
        compose.onNodeWithTag("shelf-search").performTextReplacement("nothing like this")
        compose.onNodeWithText("No books match").assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear shelf search").performClick()
        compose.waitUntil(5_000) { shown("$total books") }
        compose.onNodeWithTag("shelf-search").performImeAction()

        // Title order files "The Lantern Keeper" under L, and the choice is kept.
        compose.onNodeWithTag("shelf-sort").performClick()
        compose.onNodeWithText("Title").performClick()
        compose.waitForIdle()
        assertEquals(ShelfSort.TITLE, vm.shelfSort.value)
        assertEquals("TITLE", graph.preferences.getString(ShelfSort.PREFERENCE, null))
        // Neighbours are compared on screen together, so the check holds on short windows too.
        val top = { title: String -> compose.onNode(hasText(title) and hasClickAction()).fetchSemanticsNode().boundsInRoot.top }
        compose.onNodeWithTag("shelf").performScrollToNode(hasText(read.title))
        assertTrue(top(heard.title) < top(read.title))
        compose.onNodeWithTag("shelf").performScrollToNode(hasText(both.title))
        assertTrue(top(read.title) < top(both.title))
        theme(ThemeMode.DAY)
        compose.onNodeWithTag("shelf").performScrollToIndex(0)
        capture("shelf-sort-title-day")

        // Mark as finished moves the book under a folded Finished heading; Mark as not finished brings it back.
        compose.onNodeWithTag("shelf").performScrollToNode(hasContentDescription("More for ${heard.title}"))
        compose.onNodeWithContentDescription("More for ${heard.title}").performClick()
        compose.onNodeWithText("Mark as finished").performClick()
        compose.waitUntil(5_000) { finishedAt(heard) > 0 }
        // The confirmation can sit over the heading at the bottom of the list; let it go first.
        compose.waitUntil(15_000) { shown("Marked as finished") }
        compose.waitUntil(15_000) { !shown("Marked as finished") }
        compose.onNodeWithTag("shelf").performScrollToNode(hasTestTag("finished-section"))
        compose.onNodeWithTag("finished-section").assert(hasStateDescription("Collapsed"))
        assertFalse(shown(heard.title))
        compose.onNodeWithTag("finished-section").performClick()
        compose.onNodeWithTag("shelf").performScrollToNode(hasContentDescription("More for ${heard.title}"))
        compose.onNodeWithText("Finished").assertExists()
        capture("shelf-finished-day")
        theme(ThemeMode.NIGHT)
        capture("shelf-finished-night")
        compose.onNodeWithContentDescription("More for ${heard.title}").performClick()
        compose.onNodeWithText("Mark as not finished").performClick()
        compose.waitUntil(5_000) { finishedAt(heard) == 0L }
        compose.onNodeWithTag("shelf").performScrollToNode(hasText("Part 2 of 6 · 25%"))
    }

    private fun hasStateDescription(value: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)
}
