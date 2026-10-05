package app.narrio

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.narrio.data.BookmarkEntry
import app.narrio.domain.*
import app.narrio.playback.ListeningState
import app.narrio.reader.*
import app.narrio.ui.ListeningBookmarksSheet
import app.narrio.ui.NarrioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Captures search, highlight, note, and bookmark states for visual review (not part of CI). Screenshots go to the
 * app's external files under `annotations-qa/`. Window size, density, and font scale overrides are restored.
 */
@RunWith(AndroidJUnit4::class)
class ReaderAnnotationsVisualQa {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val seeds by lazy { ReaderSeeds(compose) }
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val books = mutableListOf<Audiobook>()
    private val output by lazy { File(compose.activity.getExternalFilesDir(null), "annotations-qa").apply { mkdirs() } }
    private var originalSize: String? = null
    private var originalDensity: String? = null
    private var originalScale: String? = null

    private fun command(command: String): String = automation.executeShellCommand(command).use {
        java.io.FileInputStream(it.fileDescriptor).bufferedReader().readText()
    }
    private fun shell(command: String) { automation.executeShellCommand(command).close(); Thread.sleep(1_500) }

    @Before fun rememberDisplay() {
        originalSize = Regex("Override size: (\\S+)").find(command("wm size"))?.groupValues?.get(1)
        originalDensity = Regex("Override density: (\\d+)").find(command("wm density"))?.groupValues?.get(1)
        originalScale = command("settings get system font_scale").trim().takeIf { it.isNotEmpty() && it != "null" }
    }

    @After fun restore() {
        shell("wm size ${originalSize ?: "reset"}"); shell("wm density ${originalDensity ?: "reset"}")
        shell("settings put system font_scale ${originalScale ?: "1.0"}")
        books.forEach(seeds::remove)
    }

    private fun capture(name: String) {
        compose.waitForIdle(); Thread.sleep(900)
        val bitmap = requireNotNull(automation.takeScreenshot()) { "Couldn't capture the display" }
        File(output, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun book(id: String) = seeds.book(id, ReaderFixtures.sampleEpub(24), "EPUB", "The Secret Garden").also { books += it }

    /** Highlights the first [length] characters after [skip] of the [paragraph]th paragraph on the page. */
    private fun highlight(controller: ReaderController, paragraph: Int, skip: Int, length: Int, color: HighlightColor, note: String = ""): Highlight {
        seeds.evaluate(controller, """(function () {
            var p = document.querySelectorAll('p[data-narrio-o]')[$paragraph]; var node = p.firstChild;
            var range = document.createRange(); range.setStart(node, $skip); range.setEnd(node, ${skip + length});
            var s = window.getSelection(); s.removeAllRanges(); s.addRange(range); return 1; })()""")
        val range = runBlocking { withContext(Dispatchers.Main) { controller.selection() } }!!
        runBlocking { withContext(Dispatchers.Main) { controller.clearSelection() } }
        return runBlocking { seeds.reader(controller.book.let { b -> books.first { it.id == b.bookId } }).marks.value!!.highlight(range, color, note) }!!
    }

    /** The screen position of the centre of the client rect returned by [script], in the WebView on screen. */
    private fun point(controller: ReaderController, script: String): Pair<Int, Int> {
        val json = JSONObject(seeds.evaluate(controller, "(function(){var r=($script);return {x:(r.left+r.right)/2,y:(r.top+r.bottom)/2,w:innerWidth};})()")!!)
        var result = 0 to 0
        compose.activity.runOnUiThread {
            fun find(view: android.view.View): android.webkit.WebView? = when {
                view is android.webkit.WebView && view.isShown && view.getGlobalVisibleRect(android.graphics.Rect()) -> view
                view is android.view.ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
                else -> null
            }
            val web = find(compose.activity.window.decorView)!!
            val location = IntArray(2).also(web::getLocationOnScreen)
            val scale = web.width / json.getDouble("w")
            result = (location[0] + json.getDouble("x") * scale).toInt() to (location[1] + json.getDouble("y") * scale).toInt()
        }
        compose.waitForIdle()
        return result
    }

    /** A system tap or long press, so the WebView handles it exactly as a finger. */
    private fun tapPage(at: Pair<Int, Int>, long: Boolean = false) {
        val (x, y) = at
        automation.executeShellCommand(if (long) "input swipe $x $y $x $y 1200" else "input tap $x $y").close()
        Thread.sleep(if (long) 1_800 else 900)
    }

    private fun showControls() {
        if (compose.onAllNodesWithContentDescription("Search this book").fetchSemanticsNodes().isNotEmpty()) return
        repeat(3) {
            // Away from the fixture's highlights, which take their own taps.
            compose.onNodeWithTag("reader-page").performTouchInput { click(androidx.compose.ui.geometry.Offset(width / 2f, height * .12f)) }
            val shown = runCatching { compose.waitUntil(2_500) { compose.onAllNodesWithContentDescription("Search this book").fetchSemanticsNodes().isNotEmpty() } }.isSuccess
            if (shown) return
        }
        capture("controls-missing"); error("The reader controls didn't appear")
    }

    private fun states(mode: ThemeMode, prefix: String, id: String) {
        val book = book(id)
        val controller = seeds.open(book, AppearanceSettings(mode = mode))
        compose.runOnIdle { controller.jumpTo(controller.book.cursor("OEBPS/text/chapter2.xhtml", 0)) }
        compose.waitUntil(10_000) { controller.cursor.value?.resource == "OEBPS/text/chapter2.xhtml" }
        Thread.sleep(1_200)
        highlight(controller, 1, 0, 70, HighlightColor.YELLOW)
        highlight(controller, 2, 20, 60, HighlightColor.BLUE, "Compare with Mrs Medlock's description in chapter III.")
        val marks = seeds.reader(book).marks.value!!
        runBlocking { marks.addBookmark(controller.visible.value!!.first) }
        Thread.sleep(1_500)
        capture("$prefix-reading")

        // A real long press selects a word and raises the selection toolbar.
        tapPage(point(controller, "(function(){var p=document.querySelectorAll('p[data-narrio-o]')[3];var r=document.createRange();r.setStart(p.firstChild,4);r.setEnd(p.firstChild,9);return r.getBoundingClientRect();})()"), long = true)
        Thread.sleep(1_500)
        capture("$prefix-selection")
        runBlocking { withContext(Dispatchers.Main) { controller.clearSelection() } }
        Thread.sleep(800)

        // Tapping a highlight opens its tray.
        tapPage(point(controller, "(function(){var p=document.querySelectorAll('p[data-narrio-o]')[1];var r=document.createRange();r.setStart(p.firstChild,10);r.setEnd(p.firstChild,14);return r.getBoundingClientRect();})()"))
        runCatching { compose.waitUntil(5_000) { compose.onAllNodesWithTag("reader-highlight-tray").fetchSemanticsNodes().isNotEmpty() } }
        capture("$prefix-tray")
        if (compose.onAllNodesWithTag("reader-highlight-tray").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithContentDescription("Add a note").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("reader-highlight-sheet").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("reader-note-field").performTextInput("Mary's first sight of the house.")
            capture("$prefix-note-sheet")
            compose.onNodeWithText("Cancel").performClick()
            compose.waitForIdle(); Thread.sleep(800)
        }

        showControls()
        capture("$prefix-controls")
        compose.onNodeWithContentDescription("Search this book").performClick()
        Thread.sleep(600)
        capture("$prefix-search-hint")
        compose.onNodeWithTag("reader-search-field").performTextInput("ayah")
        compose.waitUntil(20_000) { compose.onAllNodesWithText("chapters", substring = true).fetchSemanticsNodes().isNotEmpty() }
        shell("input keyevent KEYCODE_BACK") // keyboard
        capture("$prefix-search-results")
        compose.onAllNodesWithTag("reader-search-result")[1].performClick()
        val hit = seeds.reader(book).marks.value!!.search.state.value.hits[1]
        compose.waitUntil(10_000) { controller.visible.value?.contains(hit.range.start) == true }
        Thread.sleep(1_500)
        capture("$prefix-search-match")
        compose.onNodeWithContentDescription("End search").performClick()

        showControls()
        compose.onNodeWithTag("reader-contents").performClick()
        compose.onNodeWithTag("reader-tab-bookmarks").performClick()
        Thread.sleep(800)
        capture("$prefix-bookmarks")
        compose.onNodeWithTag("reader-tab-highlights").performClick()
        Thread.sleep(800)
        capture("$prefix-highlights")
        shell("input keyevent KEYCODE_BACK")
        compose.runOnIdle { seeds.vm.closeReader() }
        compose.waitForIdle()
    }

    @Test fun phoneNightAndDay() {
        states(ThemeMode.NIGHT, "phone-night", "annotations-qa-night")
        states(ThemeMode.DAY, "phone-day", "annotations-qa-day")
    }

    @Test fun searchEmptyAndLargeText() {
        shell("settings put system font_scale 1.3")
        val book = book("annotations-qa-large")
        val controller = seeds.open(book, AppearanceSettings(mode = ThemeMode.NIGHT))
        highlight(controller, 1, 0, 50, HighlightColor.GREEN)
        showControls()
        compose.onNodeWithContentDescription("Search this book").performClick()
        compose.onNodeWithTag("reader-search-field").performTextInput("nightingale")
        compose.waitUntil(20_000) { compose.onAllNodesWithText("No matches", substring = true).fetchSemanticsNodes().isNotEmpty() }
        capture("phone-large-search-empty")
        compose.onNodeWithContentDescription("Close search").performClick()
        if (compose.onAllNodesWithContentDescription("Search this book").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithTag("reader-page").performTouchInput { click(center) }
        Thread.sleep(600)
        tapPage(point(controller, "(function(){var p=document.querySelectorAll('p[data-narrio-o]')[1];var r=document.createRange();r.setStart(p.firstChild,10);r.setEnd(p.firstChild,14);return r.getBoundingClientRect();})()"))
        runCatching { compose.waitUntil(5_000) { compose.onAllNodesWithTag("reader-highlight-tray").fetchSemanticsNodes().isNotEmpty() } }
        capture("phone-large-tray")
    }

    @Test fun unfolded() {
        shell("wm size 2448x1848"); shell("wm density 395")
        val book = book("annotations-qa-unfolded")
        val controller = seeds.open(book, AppearanceSettings(mode = ThemeMode.NIGHT))
        highlight(controller, 1, 0, 60, HighlightColor.PINK, "A note")
        showControls()
        compose.onNodeWithContentDescription("Search this book").performClick()
        compose.onNodeWithTag("reader-search-field").performTextInput("ayah")
        compose.waitUntil(20_000) { compose.onAllNodesWithText("chapters", substring = true).fetchSemanticsNodes().isNotEmpty() }
        shell("input keyevent KEYCODE_BACK")
        capture("unfolded-night-search")
        compose.onNodeWithContentDescription("Close search").performClick()
        compose.onNodeWithContentDescription("End search").let { if (compose.onAllNodesWithContentDescription("End search").fetchSemanticsNodes().isNotEmpty()) it.performClick() }
        showControls()
        compose.onNodeWithTag("reader-contents").performClick()
        compose.onNodeWithTag("reader-tab-highlights").performClick()
        Thread.sleep(800)
        capture("unfolded-night-highlights")
    }

    /** The Listening room's sheet, rendered on its own with a fixture recording and paired text. */
    @Test fun listeningBookmarks() {
        val source = AudioSource("qa-source", "Recording", "MP3", listOf(AudioPart("p1", "1.mp3", "Part 1", 600_000), AudioPart("p2", "2.mp3", "Part 2", 600_000)))
        val book = Audiobook("annotations-qa-listening", "The Secret Garden", "Frances Hodgson Burnett", sources = listOf(source), detailsLoaded = true)
        books += book
        val document = runBlocking { seeds.graph.followAlong.importLocal(("Chapter One\n\n" + ReaderFixtures.sampleParagraphs.joinToString("\n\n")).toByteArray(), "TXT", book) }
        val passages = document.chapters.flatMap { it.passages }
        runBlocking {
            seeds.graph.followAlong.bind(book.id, TextBinding(document.id, source.id, "p1", WHOLE_BOOK, listOf(TextAnchor(passages[1].id, 30_000, 0, true), TextAnchor(passages.last().id, 590_000, 0, true))))
            seeds.graph.library.bookmark(BookmarkEntry(bookId = book.id, sourceId = source.id, partId = "p1", positionMs = 30_000, label = "Part 1"))
            seeds.graph.library.bookmark(BookmarkEntry(bookId = book.id, sourceId = source.id, partId = "p2", positionMs = 192_000, label = "Part 2"))
            seeds.graph.library.bookmark(BookmarkEntry(bookId = book.id, sourceId = "", partId = "", positionMs = 0, label = "She had not wanted a little girl at all, and when Mary was born…",
                editionId = document.id, resource = passages[3].resource, offset = passages[3].offset, normalizationVersion = 1, progression = .5))
        }
        for (mode in listOf(ThemeMode.NIGHT, ThemeMode.DAY)) {
            compose.activity.runOnUiThread {
                compose.activity.setContent {
                    NarrioTheme(AppearanceSettings(mode = mode)) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            ListeningBookmarksSheet(seeds.vm, ListeningState(book, source), book) {}
                        }
                    }
                }
            }
            Thread.sleep(2_500)
            capture("phone-${mode.name.lowercase()}-listening-bookmarks")
        }
    }
}
