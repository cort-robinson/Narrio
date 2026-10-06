package app.narrio

import android.content.pm.ActivityInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import app.narrio.domain.ContentCursor
import app.narrio.reader.Narration
import app.narrio.reader.ReaderController
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Read along in the real navigator with real Media3 playback of a controlled fixture: no account, network, or model. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class ReadAlongExperienceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var fixture: ReadAlongFixture
    private val graph get() = fixture.graph
    private val vm get() = fixture.vm

    @Before fun seed() { fixture = ReadAlongFixture.create(compose) }

    @After fun cleanUp() {
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        fixture.remove()
    }

    private fun marks(controller: ReaderController, selector: String): Int =
        fixture.evaluate(controller, "document.querySelectorAll('$selector').length")?.toIntOrNull() ?: 0

    private fun ReadAlongFixture.evaluate(controller: ReaderController, script: String): String? {
        var result: String? = null
        onMain { result = controller.evaluate(script) }
        return result
    }

    /** The page web view on screen (the pager keeps its neighbours too): its content origin in reader-page pixels, and its size. */
    private fun webContent(controller: ReaderController): Pair<Offset, Offset> {
        val node = compose.onNodeWithTag("reader-page").fetchSemanticsNode()
        fun webViews(view: android.view.View): List<android.webkit.WebView> = (view as? android.webkit.WebView)?.let { listOf(it) }
            ?: (view as? android.view.ViewGroup)?.let { group -> (0 until group.childCount).flatMap { webViews(group.getChildAt(it)) } } ?: emptyList()
        var shift = Offset.Zero
        var size = Offset.Zero
        fixture.onMain {
            val at = IntArray(2)
            val web = webViews(controller.navigator!!.publicationView).first { view ->
                view.getLocationOnScreen(at); view.isShown && at[0] + view.width / 2 in node.positionOnScreen.x.toInt()..(node.positionOnScreen.x + node.size.width).toInt()
            }
            web.getLocationOnScreen(at)
            shift = Offset(at[0] + web.paddingLeft - node.positionOnScreen.x, at[1] + web.paddingTop - node.positionOnScreen.y)
            size = Offset((web.width - web.paddingLeft - web.paddingRight).toFloat(), (web.height - web.paddingTop - web.paddingBottom).toFloat())
        }
        return shift to size
    }

    /** A point on a line of text, in the reader-page node's pixels, and the character there. */
    private fun textPoint(controller: ReaderController): Pair<Offset, ContentCursor> {
        val (shift, size) = webContent(controller)
        for (fraction in listOf(.3f, .34f, .38f, .42f, .46f, .5f, .55f, .6f)) {
            val point = Offset(size.x * .5f, size.y * fraction)
            var cursor: ContentCursor? = null
            fixture.onMain { cursor = controller.cursorAt(point.x, point.y) }
            cursor?.let { return point + shift to it }
        }
        throw AssertionError("No text found on the page to tap")
    }

    @Test fun readsAlongFromTheListeningRoomFollowingSeekingAndKeepingPlace() {
        compose.runOnIdle { vm.playerOpen.value = true }
        compose.onNodeWithTag("read-along").performClick()
        val controller = fixture.controller()
        // Narration at 0 s is drawn on the first page, and the page follows it.
        try { compose.waitUntil(15_000) { marks(controller, ".narrio-said") > 0 && controller.following.value } }
        catch (timeout: Throwable) {
            throw AssertionError("marks=${marks(controller, ".narrio-said")} groups=${fixture.evaluate(controller, "Array.from(document.querySelectorAll('[data-group]')).map(function(e){return e.id+':'+e.children.length}).join(',')")} " +
                "following=${controller.following.value} visible=${controller.visible.value} place0=${fixture.place(0)} text=${vm.bookText.value.document?.id}/${vm.bookText.value.bindings.size} " +
                "playing=${graph.playback.state.value.book?.id}", timeout)
        }
        compose.onNodeWithTag("read-along-tray").assertIsDisplayed()
        compose.onNodeWithContentDescription("Skip forward 30 seconds").assertIsDisplayed()

        // A seek several pages ahead turns the page to the narrated sentence, still marked as synced.
        val ahead = fixture.place(200_000)!!
        fixture.seek(200_000)
        try { compose.waitUntil(15_000) { controller.visible.value?.contains(ahead.cursor) == true } }
        catch (timeout: Throwable) { throw AssertionError("follow target=${ahead.cursor} visible=${controller.visible.value} following=${controller.following.value}", timeout) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Synced with narration").fetchSemanticsNodes().isNotEmpty() }

        // A manual page turn pauses following; Back to narration returns.
        compose.runOnIdle { controller.next(animated = false) }
        compose.waitUntil(10_000) { !controller.following.value }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("back-to-narration").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("back-to-narration").performClick()
        compose.waitUntil(10_000) { controller.following.value && controller.visible.value?.contains(ahead.cursor) == true }

        // Tapping a sentence plays from its start and keeps playback paused.
        val (point, tapped) = textPoint(controller)
        val sentence = Narration.passageAt(fixture.document, tapped)!!
        val before = graph.playback.state.value.positionMs
        compose.onNodeWithTag("reader-page").performTouchInput { click(point) }
        try { compose.waitUntil(10_000) { fixture.place(graph.playback.state.value.positionMs)?.passageId == sentence.id } }
        catch (timeout: Throwable) {
            throw AssertionError("tap at $point on $tapped (${sentence.id}) moved $before -> ${graph.playback.state.value.positionMs} " +
                "place=${fixture.place(graph.playback.state.value.positionMs)?.passageId} chrome=${compose.onAllNodesWithContentDescription("Contents").fetchSemanticsNodes().size}", timeout)
        }
        assertFalse("a sentence tap keeps play/pause", graph.playback.state.value.playing)

        // Past the last anchor, narration is only estimated: a dotted mark and "≈", never a confident wash.
        val estimatedAt = fixture.anchoredUntilMs + 40_000
        assertEquals(app.narrio.domain.MappingConfidence.ESTIMATED, fixture.place(estimatedAt)!!.confidence)
        fixture.seek(estimatedAt)
        compose.waitUntil(15_000) { marks(controller, ".narrio-estimated") > 0 && marks(controller, ".narrio-said") == 0 }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("≈ Estimated place").fetchSemanticsNodes().isNotEmpty() }

        // Rotation keeps the narrated sentence on screen and leaves playback where it was.
        val narrated = fixture.place(estimatedAt)!!
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        compose.waitUntil(20_000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        // The reader can reopen its session while it moves into the wider shell; always check the live one.
        fun live() = (fixture.reader().state.value as? app.narrio.ui.ReaderState.Ready)?.session?.controller
        try { compose.waitUntil(30_000) { live()?.let { it.ready.value && it.following.value && it.visible.value?.contains(narrated.cursor) == true } == true } }
        catch (timeout: Throwable) { throw AssertionError("rotated target=${narrated.cursor} visible=${live()?.visible?.value} following=${live()?.following?.value}", timeout) }
        assertEquals(estimatedAt, graph.playback.state.value.positionMs)
        assertTrue("no Undo after rotation", compose.onAllNodesWithText("Undo").fetchSemanticsNodes().isEmpty())
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        compose.waitUntil(20_000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }

        // Back returns to the Listening room with the recording where it was.
        compose.runOnIdle { vm.back() }
        compose.waitUntil(10_000) { vm.reader.value == null && vm.playerOpen.value }
        assertEquals(estimatedAt, graph.playback.state.value.positionMs)
    }

    /** A point on the page (reader-page pixels) whose character satisfies [wanted], or, with null, a point beside the text. */
    private fun pagePoint(controller: ReaderController, wanted: ((ContentCursor) -> Boolean)?): Offset {
        val (shift, size) = webContent(controller)
        for (step in 1 until 60) {
            val point = Offset(size.x * .5f, size.y * step / 60f)
            var cursor: ContentCursor? = null
            fixture.onMain { cursor = controller.cursorAt(point.x, point.y) }
            val found = cursor
            if (if (wanted == null) found == null else found != null && wanted(found)) return point + shift
        }
        throw AssertionError("No such point on the page")
    }

    @Test fun highlightsSearchAndBookmarksKeepWorkingWhileReadingAlong() {
        fixture.seek(200_000)
        compose.runOnIdle { vm.playerOpen.value = true }
        compose.onNodeWithTag("read-along").performClick()
        val controller = fixture.controller()
        val narrated = fixture.place(200_000)!!
        compose.waitUntil(15_000) { controller.following.value && controller.visible.value?.contains(narrated.cursor) == true && marks(controller, ".narrio-said") > 0 }
        val marks = fixture.reader().marks.value!!

        // A highlight on the narrated sentence is drawn alongside the narration, not replaced by it.
        val highlight = kotlinx.coroutines.runBlocking { marks.highlight(narrated.sentence) }!!
        compose.waitUntil(10_000) { marks(controller, "[data-group=\"narrio-highlights\"] *") > 0 }
        assertTrue("narration stays drawn", marks(controller, ".narrio-said") > 0)

        // Tapping the highlight opens its tray; it doesn't seek.
        val before = graph.playback.state.value.positionMs
        // The centre of the highlight's first drawn line box, where Readium's own hit test finds it.
        val box = org.json.JSONObject(fixture.evaluate(controller,
            "JSON.stringify(document.querySelector('[data-group=\\\"narrio-highlights\\\"] > div > *').getBoundingClientRect())")!!.let { if (it.startsWith("\"")) org.json.JSONTokener(it).nextValue() as String else it })
        val density = compose.activity.resources.displayMetrics.density
        val (shift, _) = webContent(controller)
        val onHighlight = Offset(((box.getDouble("left") + box.getDouble("width") / 2) * density).toFloat(), ((box.getDouble("top") + box.getDouble("height") / 2) * density).toFloat()) + shift
        compose.onNodeWithTag("reader-page").performTouchInput { click(onHighlight) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("reader-highlight-tray").fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(800)
        assertEquals("a highlight tap doesn't seek", before, graph.playback.state.value.positionMs)

        // The controls, the bookmark ribbon, and search all work while reading along.
        compose.onNodeWithTag("reader-page").performTouchInput { click(pagePoint(controller, null)) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("reader-bookmark-ribbon").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("reader-bookmark-ribbon").performClick()
        compose.waitUntil(10_000) { marks.bookmarks.value.isNotEmpty() }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Search this book").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Search this book").performClick()
        compose.onNodeWithTag("reader-search-field").performTextInput("Martha")
        compose.waitUntil(20_000) { compose.onAllNodesWithTag("reader-search-result").fetchSemanticsNodes().isNotEmpty() }
        assertFalse("the first match is elsewhere", controller.visible.value!!.contains(marks.search.state.value.hits.first().range.start))
        compose.onAllNodesWithTag("reader-search-result")[0].performClick()
        compose.waitUntil(10_000) { !controller.following.value && controller.visible.value?.contains(marks.search.state.value.hits.first().range.start) == true }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("back-to-narration").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("back-to-narration").performClick()
        compose.waitUntil(10_000) { controller.following.value && controller.visible.value?.contains(narrated.cursor) == true }
    }

    @Test fun startingFromAPageMovesTheNarrationThereWithUndo() {
        compose.runOnIdle { vm.read(fixture.book.id) }
        val controller = fixture.controller()
        val chapter = controller.book.readingOrder[4]
        compose.runOnIdle { controller.jumpTo(controller.book.cursor(controller.book.resourceName(chapter), 0)) }
        compose.waitUntil(10_000) { controller.visible.value?.first?.resource == controller.book.resourceName(chapter) }
        Thread.sleep(800)
        compose.onNodeWithTag("reader-page").performTouchInput { click(Offset(width * .5f, height * .08f)) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("start-read-along").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("start-read-along").performClick()

        // The page leads: narration moves to it, more than 30 s, and offers Undo.
        compose.waitUntil(15_000) { graph.playback.state.value.positionMs > 30_000 }
        val page = controller.visible.value!!.first
        val narrated = fixture.place(graph.playback.state.value.positionMs)!!
        assertEquals("page=$page narrated=${narrated.cursor} at ${graph.playback.state.value.positionMs} shared=${kotlinx.coroutines.runBlocking { graph.sharedPositions.current(fixture.book.id) }}",
            page.resource, narrated.cursor.resource)
        assertFalse(graph.playback.state.value.playing)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Jumped to where you read").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("read-along-tray").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(10_000) { graph.playback.state.value.positionMs < 30_000 }
    }
}
