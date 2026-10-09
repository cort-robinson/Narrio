package app.narrio

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.narrio.domain.*
import app.narrio.reader.ReaderFixtures
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** While a book plays, its controls stay docked at the bottom of a book's details and the reader, not only at home. */
@RunWith(AndroidJUnit4::class)
class PersistentPlayerTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val seeds = ReaderSeeds(compose)
    private val graph get() = seeds.graph
    private val vm get() = seeds.vm
    private val service get() = graph.playback.service!!

    private fun fixture(): Pair<Audiobook, File> {
        val wave = File(compose.activity.filesDir, "docked-fixture.wav")
        val size = 60 * 8000 * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + size); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16); put("data".toByteArray()); putInt(size)
        }.array()
        wave.outputStream().use { stream -> stream.write(header); stream.write(ByteArray(size)) }
        val part = AudioPart("docked-part", "docked-fixture.wav", "Chapter 1", durationMs = 60_000, archiveUrl = Uri.fromFile(wave).toString())
        val source = AudioSource("docked-source", "Native fixture audio", "WAV", listOf(part))
        return Audiobook("docked-fixture", "A Steady Voice", "Native test fixture", "Synthetic silence", sources = listOf(source), detailsLoaded = true) to wave
    }

    private fun <T> main(block: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main) { block() } }

    @Test fun playerStaysDockedOnDetailsAndInTheReader() {
        assumeCompact()
        val (audio, wave) = fixture()
        val source = audio.sources.single()
        val text = seeds.book("docked-reader", ReaderFixtures.sampleEpub(), "EPUB", "The Secret Garden")
        try {
            compose.waitUntil(15_000) { graph.playback.service?.initialized == true }
            main { service.load(audio, source, false, source.parts.single().id, 12_000) }
            compose.runOnIdle { graph.preferences.edit().putBoolean("notificationAsked", true).apply(); vm.playerOpen.value = false }

            // A book's details keep the player under them.
            compose.runOnIdle { vm.open(audio) }
            compose.waitForIdle()
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val docked = compose.onNodeWithTag("mini-player").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals("Docked at the bottom edge", root.bottom, docked.bottom, 1f)
            compose.onNodeWithContentDescription("Skip forward 30 seconds").assertIsDisplayed()
            capture("docked-details.png")

            // So does the reader, with the page ending where the player begins.
            seeds.open(text)
            val inReader = compose.onNodeWithTag("mini-player").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val page = compose.onNodeWithTag("reader-page").fetchSemanticsNode().boundsInRoot
            assertTrue("The page stays above the player", page.bottom <= inReader.top + 1f)
            capture("docked-reader.png")

            // Opening it leaves the reader for the Listening room.
            compose.onNodeWithTag("mini-player").performClick()
            compose.waitUntil(5_000) { vm.reader.value == null && vm.playerOpen.value }
            compose.onNodeWithText("Now playing").assertIsDisplayed()
        } finally {
            seeds.remove(text)
            main { service.forget(); graph.library.remove(audio.id) }
            wave.delete()
        }
    }

    private fun capture(name: String) {
        Thread.sleep(400)
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        compose.activity.filesDir.resolve(name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun assumeCompact() = org.junit.Assume.assumeTrue(compose.activity.resources.configuration.screenWidthDp < 600)
}
