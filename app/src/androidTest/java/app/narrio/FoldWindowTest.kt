package app.narrio

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.window.layout.FoldingFeature
import androidx.window.testing.layout.FoldingFeature as TestFold
import androidx.window.testing.layout.TestWindowLayoutInfo
import androidx.window.testing.layout.WindowLayoutInfoPublisherRule
import app.narrio.ui.NarrioViewModel
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Injected Jetpack posture evidence; this does not claim physical Samsung validation. */
@RunWith(AndroidJUnit4::class)
class FoldWindowTest {
    @get:Rule(order = 0) val postures = WindowLayoutInfoPublisherRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun simulatedHingesKeepControlsClearAndPreserveTheRecording() {
        assumeTrue(compose.activity.resources.configuration.screenWidthDp >= 600)
        val graph = (compose.activity.application as NarrioApplication).graph
        val book = runBlocking { graph.catalog.recording("secret_garden_1105_librivox") }
        val source = book.sources.first { it.format == "MP3" }
        compose.waitUntil(20_000) { graph.playback.service?.initialized == true }
        compose.runOnIdle {
            CoroutineScope(Dispatchers.Main).launch {
                graph.playback.service!!.load(book, source, autoplay = false, partId = source.parts[1].id, positionMs = 120_000)
                graph.playback.service!!.speed(1f)
                ViewModelProvider(compose.activity)[NarrioViewModel::class.java].playerOpen.value = true
            }
        }
        compose.waitUntil(5000) { graph.playback.state.value.partIndex == 1 }

        val vertical = TestFold(compose.activity, size = 24, orientation = FoldingFeature.Orientation.VERTICAL)
        postures.overrideWindowLayoutInfo(TestWindowLayoutInfo(listOf(vertical)))
        compose.waitForIdle()
        val search = compose.onNodeWithText("Search books or authors").fetchSemanticsNode().boundsInRoot
        val rewind = compose.onNodeWithContentDescription("Rewind 30 seconds").fetchSemanticsNode().boundsInRoot
        assertTrue("Discovery must stay left of a separating hinge", search.right < vertical.bounds.left)
        assertTrue("Transport must stay right of a separating hinge", rewind.left > vertical.bounds.right)
        capture("fold-hinge.png")

        val horizontal = TestFold(compose.activity, size = 24, orientation = FoldingFeature.Orientation.HORIZONTAL)
        postures.overrideWindowLayoutInfo(TestWindowLayoutInfo(listOf(horizontal)))
        compose.waitForIdle()
        val heading = compose.onNodeWithText("Now playing").fetchSemanticsNode().boundsInRoot
        val controls = compose.onNodeWithContentDescription("Rewind 30 seconds").fetchSemanticsNode().boundsInRoot
        assertTrue("Artwork belongs above the tabletop hinge", heading.bottom < horizontal.bounds.top)
        assertTrue("Controls belong below the tabletop hinge", controls.top > horizontal.bounds.bottom)
        assertEquals(source.parts[1].id, graph.playback.state.value.part!!.id)
        assertEquals(120_000L, graph.playback.state.value.positionMs)
        assertFalse(graph.playback.state.value.playing)
        capture("fold-tabletop.png")
        println("Injected posture evidence: $vertical; $horizontal; stable part=${source.parts[1].id}, position=120000")
    }

    private fun capture(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        compose.activity.filesDir.resolve(name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
