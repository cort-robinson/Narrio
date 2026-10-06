package app.narrio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.narrio.data.BookmarkEntry
import app.narrio.data.contentCursor
import app.narrio.domain.*
import app.narrio.reader.*
import app.narrio.ui.ReaderMarks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Search, highlights, notes, and the shared bookmark list in the real navigator, with controlled fixtures only. */
@RunWith(AndroidJUnit4::class)
class ReaderAnnotationsExperienceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val seeds by lazy { ReaderSeeds(compose) }
    private val opened = mutableListOf<Audiobook>()

    @After fun cleanUp() { opened.forEach(seeds::remove) }

    private fun marks(book: Audiobook): ReaderMarks = seeds.reader(book).marks.value!!

    /** How many decoration elements Readium drew for [group] on the page. */
    private fun drawn(controller: ReaderController, group: String): Int =
        seeds.evaluate(controller, "document.querySelectorAll('[data-group=\"$group\"] *').length")?.toIntOrNull() ?: 0

    /** Shows the reader controls with a tap near the top of the page, away from links and highlights. */
    private fun showControls() {
        repeat(3) {
            if (compose.onAllNodesWithContentDescription("Search this book").fetchSemanticsNodes().isNotEmpty()) return
            compose.onNodeWithTag("reader-page").performTouchInput { click(androidx.compose.ui.geometry.Offset(width / 2f, height * .12f)) }
            runCatching { compose.waitUntil(2_500) { compose.onAllNodesWithContentDescription("Search this book").fetchSemanticsNodes().isNotEmpty() } }
        }
        compose.onNodeWithContentDescription("Search this book").assertIsDisplayed()
    }

    @Test fun searchGroupsMatchesByChapterJumpsAndStepsThroughThem() {
        val book = seeds.book("annotations-search", ReaderFixtures.sampleEpub(), "EPUB", "The Secret Garden").also { opened += it }
        val controller = seeds.open(book)
        // Search from another chapter, so a match is a jump into a different resource, mid-chapter.
        compose.runOnIdle { controller.jumpTo(controller.book.cursor("OEBPS/text/chapter3.xhtml", 0)) }
        compose.waitUntil(10_000) { controller.visible.value?.first?.resource == "OEBPS/text/chapter3.xhtml" }
        Thread.sleep(1_000)
        val start = controller.cursor.value!!
        showControls()
        compose.onNodeWithContentDescription("Search this book").performClick()
        compose.onNodeWithTag("reader-search-field").performTextInput("ayah")
        compose.waitUntil(20_000) { compose.onAllNodesWithText("chapters", substring = true).fetchSemanticsNodes().isNotEmpty() }
        val search = marks(book).search.state.value
        assertTrue("case-insensitive matches in several chapters: ${search.hits.size}", search.hits.size > 6)
        assertTrue(search.hits.all { it.match.equals("Ayah", ignoreCase = true) })
        compose.onNode(isHeading() and hasText("I · There Is No One Left", substring = true)).assertIsDisplayed()

        compose.onAllNodesWithTag("reader-search-result")[2].performClick()
        val hit = search.hits[2]
        try { compose.waitUntil(10_000) { controller.visible.value?.contains(hit.range.start) == true } }
        catch (timeout: Throwable) { throw AssertionError("match=${hit.range.start} visible=${controller.visible.value}", timeout) }
        Thread.sleep(2_000)
        assertTrue("the match stays on screen once the page settles", controller.visible.value?.contains(hit.range.start) == true)
        compose.onNodeWithTag("reader-search-pill").assertIsDisplayed()
        compose.waitUntil(5_000) { drawn(controller, SEARCH_GROUP) > 0 }
        assertEquals("Back to returns to where reading was", start, controller.returnPoint.value)

        compose.onNodeWithContentDescription("Next match").performClick()
        val next = marks(book).search.state.value.hits[3]
        compose.waitUntil(10_000) { controller.visible.value?.contains(next.range.start) == true && marks(book).search.state.value.current == 3 }
        assertEquals("stepping through matches keeps the place before the search", start, controller.returnPoint.value)

        compose.onNodeWithContentDescription("End search").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("reader-search-pill").fetchSemanticsNodes().isEmpty() }
        compose.waitUntil(5_000) { drawn(controller, SEARCH_GROUP) == 0 }
    }

    @Test fun selectedTextBecomesAHighlightWithANoteThatSurvivesRelayout() {
        val book = seeds.book("annotations-highlight", ReaderFixtures.sampleEpub(), "EPUB", "The Secret Garden").also { opened += it }
        val controller = seeds.open(book)
        // Select the first words of the first paragraph on the page, as a long press would.
        seeds.evaluate(controller, """(function () {
            var p = document.querySelector('p[data-narrio-o]'); var node = p.firstChild;
            var range = document.createRange(); range.setStart(node, 0); range.setEnd(node, 16);
            var selection = window.getSelection(); selection.removeAllRanges(); selection.addRange(range); return selection.toString(); })()""")
        val range = runBlocking { withContext(Dispatchers.Main) { controller.selection() } }!!
        val highlight = runBlocking { marks(book).highlight(range, HighlightColor.BLUE) }!!
        assertEquals("the stored text is the parser text of the range", "When Mary Lennox", highlight.text)
        runBlocking { withContext(Dispatchers.Main) { controller.clearSelection() } }
        compose.waitUntil(10_000) { drawn(controller, HIGHLIGHT_GROUP) > 0 }

        showControls()
        compose.onNodeWithTag("reader-contents").performClick()
        compose.onNodeWithTag("reader-tab-highlights").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("When Mary Lennox").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Highlight options").performClick()
        compose.onNodeWithText("Add note").performClick()
        compose.onNodeWithTag("reader-note-field").performTextInput("Her arrival")
        compose.onNodeWithTag("highlight-color-pink").performClick()
        compose.onNodeWithTag("reader-note-save").performClick()
        compose.waitUntil(5_000) { marks(book).highlights.value.singleOrNull()?.note == "Her arrival" }
        val saved = runBlocking { seeds.graph.library.annotations(book.id, controller.book.editionId).first().single() }
        assertEquals("pink", saved.color)
        assertEquals("Her arrival", saved.note)
        assertEquals(range.start.offset, saved.start().offset)

        // A larger font reflows every page; the highlight is redrawn from its cursors and the place is kept.
        compose.runOnIdle { seeds.reader(book).update { larger().larger() } }
        Thread.sleep(1_500)
        compose.waitUntil(10_000) { controller.visible.value?.contains(range.start) == true && drawn(controller, HIGHLIGHT_GROUP) > 0 }
    }

    @Test fun bookmarksFromReadingAndListeningShareOneList() = runBlocking {
        val source = AudioSource("annotations-source", "Recording", "MP3", listOf(AudioPart("part", "1.mp3", "Chapter", 120_000)))
        val book = Audiobook("annotations-bookmarks", "Shared marks", "Fixture", sources = listOf(source), detailsLoaded = true)
        opened += book
        val document = seeds.graph.followAlong.importLocal(("Chapter One\n\n" + (1..40).joinToString("\n\n") { "Paragraph $it of the shared bookmark fixture, long enough to fill a line or two." }).toByteArray(), "TXT", book)
        val passages = document.chapters.flatMap { it.passages }
        val anchored = passages[20]
        seeds.graph.followAlong.bind(book.id, TextBinding(document.id, source.id, "part", WHOLE_BOOK,
            listOf(TextAnchor(passages[0].id, 0, 0, true), TextAnchor(anchored.id, 60_000, 0, true), TextAnchor(passages.last().id, 118_000, 0, true))))
        // A listening bookmark from before ebooks: audio columns only.
        seeds.graph.library.bookmark(BookmarkEntry(bookId = book.id, sourceId = source.id, partId = "part", positionMs = 60_000, label = "Chapter"))
        val controller = seeds.open(book)
        val marks = marks(book)
        compose.waitUntil(10_000) { marks.bookmarks.value.size == 1 }
        val listened = marks.bookmarks.value.single()
        assertEquals("the listening bookmark has a page in the reader", anchored.offset, listened.cursor?.offset)
        assertEquals("1:00", listened.listening)

        // Turn past the chapter title (it has no narration timing) and bookmark a body page.
        compose.runOnIdle { controller.next(animated = false) }
        compose.waitUntil(10_000) { (controller.visible.value?.first?.offset ?: 0) > passages[1].offset }
        showControls()
        compose.onNodeWithTag("reader-bookmark-ribbon").performClick()
        compose.waitUntil(10_000) { marks.bookmarks.value.size == 2 }
        val stored = seeds.graph.library.bookmarks(book.id).first().first { it.editionId != null }
        assertEquals("a reading bookmark keeps the page it was made on", controller.visible.value!!.first.offset, stored.contentCursor()!!.offset)
        assertTrue("its listening time is mapped from narration", marks.bookmarks.value.first { it.place.id == stored.id }.listening != null)

        compose.onNodeWithTag("reader-contents").performClick()
        compose.onNodeWithTag("reader-tab-bookmarks").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("reader-bookmark-row").fetchSemanticsNodes().size == 2 }
        compose.onAllNodesWithTag("reader-bookmark-row")[1].performClick()
        compose.waitUntil(10_000) { controller.visible.value?.contains(listened.cursor!!) == true }
    }
}
