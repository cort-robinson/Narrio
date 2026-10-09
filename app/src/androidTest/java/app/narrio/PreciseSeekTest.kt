package app.narrio

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.narrio.domain.*
import app.narrio.ui.NarrioViewModel
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Back 10 s, precise seeking by sliding up from the bar, and swiping a banner away. */
@RunWith(AndroidJUnit4::class)
class PreciseSeekTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = (compose.activity.application as NarrioApplication).graph
    private val vm get() = ViewModelProvider(compose.activity)[NarrioViewModel::class.java]
    private val service get() = graph.playback.service!!
    private val position get() = graph.playback.state.value.positionMs

    private fun fixture(): Pair<Audiobook, File> {
        val wave = File(compose.activity.filesDir, "seek-fixture.wav")
        val size = 600 * 8000 * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + size); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16); put("data".toByteArray()); putInt(size)
        }.array()
        wave.outputStream().use { stream -> stream.write(header); stream.write(ByteArray(size)) }
        val part = AudioPart("seek-part", "seek-fixture.wav", "Chapter 1", durationMs = 600_000, archiveUrl = Uri.fromFile(wave).toString())
        val source = AudioSource("seek-source", "Native fixture audio", "WAV", listOf(part))
        return Audiobook("seek-fixture", "A Careful Place", "Native test fixture", "Synthetic silence", sources = listOf(source), detailsLoaded = true) to wave
    }

    private fun <T> main(block: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main) { block() } }

    @Test fun slidingUpSeeksFinelyAndBannersSwipeAway() {
        val (book, wave) = fixture()
        val source = book.sources.single()
        try {
            compose.waitUntil(15_000) { graph.playback.service?.initialized == true }
            main { service.load(book, source, false, source.parts.single().id, 300_000) }
            compose.runOnIdle { graph.preferences.edit().putBoolean("notificationAsked", true).apply(); vm.playerOpen.value = true }
            compose.waitUntil(5_000) { graph.playback.state.value.durationMs > 0 }

            compose.onNodeWithContentDescription("Rewind 10 seconds").performClick()
            compose.waitUntil(5_000) { position == 290_000L }

            // Touching the bar anywhere never moves the place; only dragging does.
            val bar = compose.onNodeWithContentDescription("Listening position")
            val quarter = bar.fetchSemanticsNode().size.width / 4f
            val density = compose.activity.resources.displayMetrics.density
            bar.performTouchInput { click(centerLeft + Offset(12 * density, 0f)) }
            bar.performTouchInput { down(centerRight - Offset(12 * density, 0f)); advanceEventTime(600); up() }
            compose.waitForIdle()
            assertEquals(290_000L, position)

            // At the bar, the place follows the finger: a quarter of the bar is about a quarter of the part.
            bar.performTouchInput { down(center); moveBy(Offset(quarter / 2, 0f)); moveBy(Offset(quarter / 2, 0f)) }
            compose.onNodeWithText("Slide up to seek more precisely").assertExists()
            capture("precise-seek-follow.png")
            bar.performTouchInput { up() }
            compose.waitUntil(5_000) { position != 290_000L }
            assertTrue("Following the finger moves minutes", position - 290_000L > 100_000L)
            val coarse = position

            // Slid well above the bar, the same quarter moves only seconds, even pressed far from the thumb.
            // The small sideways drift of sliding up does not seek.
            bar.performTouchInput { down(centerLeft + Offset(12 * density, 0f)); moveBy(Offset(-10 * density, -120 * density)); moveBy(Offset(-10 * density, -120 * density)) }
            bar.performTouchInput { moveBy(Offset(quarter / 2, 0f)); moveBy(Offset(quarter / 2, 0f)) }
            compose.waitForIdle()
            compose.onNodeWithText("Fine seeking").assertExists()
            capture("precise-seek-fine.png")
            bar.performTouchInput { up() }
            compose.waitUntil(5_000) { position != coarse }
            assertTrue("Fine seeking moves a few seconds, not minutes: ${position - coarse}", position - coarse in 2_000L..20_000L)
            compose.onAllNodesWithText("Fine seeking").assertCountEquals(0)

            // Banners can be swiped away rather than waited out.
            compose.runOnIdle { vm.messages.tryEmit("Saved to your shelf") }
            compose.onNodeWithText("Saved to your shelf").assertIsDisplayed().performTouchInput { swipeLeft() }
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Saved to your shelf").fetchSemanticsNodes().isEmpty() }
        } finally {
            main { service.forget(); graph.library.remove(book.id) }
            wave.delete()
        }
    }

    private fun capture(name: String) {
        // The card is its own window; let it draw before taking the screen.
        Thread.sleep(500)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        compose.activity.filesDir.resolve(name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
