package app.narrio

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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

/** Closing Now playing keeps the shelf and position, survives a restart, and can be undone. */
@RunWith(AndroidJUnit4::class)
class NowPlayingDismissTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = (compose.activity.application as NarrioApplication).graph
    private val vm get() = ViewModelProvider(compose.activity)[NarrioViewModel::class.java]
    private val service get() = graph.playback.service!!

    private fun fixture(): Pair<Audiobook, File> {
        val wave = File(compose.activity.filesDir, "dismiss-fixture.wav")
        val size = 60 * 8000 * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + size); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16); put("data".toByteArray()); putInt(size)
        }.array()
        wave.outputStream().use { stream -> stream.write(header); stream.write(ByteArray(size)) }
        val part = AudioPart("dismiss-part", "dismiss-fixture.wav", "Chapter 1", durationMs = 60_000, archiveUrl = Uri.fromFile(wave).toString())
        val source = AudioSource("dismiss-source", "Native fixture audio", "WAV", listOf(part))
        return Audiobook("dismiss-fixture", "A Quiet Ending", "Native test fixture", "Synthetic silence", sources = listOf(source), detailsLoaded = true) to wave
    }

    private fun <T> main(block: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main) { block() } }

    @Test fun closingNowPlayingKeepsPlaceAndStaysClosedUntilListeningAgain() {
        val (book, wave) = fixture()
        val source = book.sources.single()
        try {
            compose.waitUntil(15_000) { graph.playback.service?.initialized == true }
            main { service.load(book, source, false, source.parts.single().id, 42_000) }
            compose.runOnIdle { graph.preferences.edit().putBoolean("notificationAsked", true).apply(); vm.playerOpen.value = true }

            // Close sits in the heading's menu, away from Bookmark.
            compose.onNodeWithContentDescription("Bookmark this moment").assertIsDisplayed()
            compose.onAllNodesWithText("Close player").assertCountEquals(0)
            compose.onNodeWithContentDescription("More options").performClick()
            compose.onNodeWithText("Close player").performClick()
            compose.waitUntil(5_000) { graph.playback.state.value.book == null }
            compose.runOnIdle { assertFalse(vm.playerOpen.value) }
            compose.onAllNodesWithText("Now playing").assertCountEquals(0)
            val saved = main { graph.library.find(book.id) }
            assertEquals(42_000L, saved?.positionMs)
            // The next launch restores nothing until something plays again.
            assertNull(main { service.restorable() })

            compose.onNodeWithText("Undo").performClick()
            compose.waitUntil(5_000) { graph.playback.state.value.book?.id == book.id }
            assertEquals(42_000L, graph.playback.state.value.positionMs)
            assertFalse(graph.playback.state.value.playing)

            compose.runOnIdle { vm.dismissPlayback() }
            compose.waitUntil(5_000) { graph.playback.state.value.book == null }
            Thread.sleep(10)
            main { service.load(book, source, false) }
            assertEquals(book.id, main { service.restorable() }?.bookId)
        } finally {
            main { service.forget(); graph.library.remove(book.id) }
            wave.delete()
        }
    }
}
