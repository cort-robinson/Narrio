package app.narrio

import android.net.Uri
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.narrio.domain.*
import app.narrio.playback.*
import app.narrio.ui.NarrioViewModel
import app.narrio.ui.SleepTimerDialog
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The sleep timer pauses at the end of a part with a fade, and the room moves between parts and shows time left. */
@RunWith(AndroidJUnit4::class)
class SleepTimerExperienceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = (compose.activity.application as NarrioApplication).graph
    private val vm get() = ViewModelProvider(compose.activity)[NarrioViewModel::class.java]
    private val service get() = graph.playback.service!!
    private val state get() = graph.playback.state.value

    /** Two 20-second parts of silence. */
    private fun fixture(): Pair<Audiobook, List<File>> {
        val size = 20 * 8000 * 2
        val files = List(2) { i ->
            File(compose.activity.filesDir, "sleep-fixture-$i.wav").also { wave ->
                val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                    put("RIFF".toByteArray()); putInt(36 + size); put("WAVEfmt ".toByteArray()); putInt(16)
                    putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16); put("data".toByteArray()); putInt(size)
                }.array()
                wave.outputStream().use { stream -> stream.write(header); stream.write(ByteArray(size)) }
            }
        }
        val parts = files.mapIndexed { i, wave -> AudioPart("sleep-part-$i", wave.name, "Part ${i + 1}", durationMs = 20_000, archiveUrl = Uri.fromFile(wave).toString()) }
        val source = AudioSource("sleep-source", "Native fixture audio", "WAV", parts)
        return Audiobook("sleep-fixture", "A Gentle Stop", "Native test fixture", "Synthetic silence", sources = listOf(source), detailsLoaded = true) to files
    }

    private fun <T> main(block: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main) { block() } }

    @Test fun endOfPartFadesOutPausesAtTheBoundaryAndRestoresTheVolume() {
        val (book, files) = fixture()
        val source = book.sources.single()
        try {
            compose.waitUntil(15_000) { graph.playback.service?.initialized == true }
            main { service.load(book, source, false, source.parts.first().id, 0); service.speed(1f) }
            compose.runOnIdle { graph.preferences.edit().putBoolean("notificationAsked", true).apply(); vm.playerOpen.value = true }
            compose.waitUntil(5_000) { state.durationMs > 0 }

            // Whole-book time from both parts' lengths, and a part-by-part step that leaves playback paused.
            compose.onNodeWithTag("book-time-left").assertTextEquals("1 m left in book")
            compose.onNodeWithContentDescription("Next part").performClick()
            compose.waitUntil(5_000) { state.partIndex == 1 }
            assertFalse(state.playing)
            compose.onNodeWithContentDescription("Previous part").performClick()
            compose.waitUntil(5_000) { state.partIndex == 0 }

            compose.onNodeWithText("Sleep").performScrollTo().performClick()
            compose.onNodeWithText("At the end of this chapter").assertDoesNotExist()
            compose.onNodeWithText("At the end of this audio part").performClick()
            compose.onNodeWithText("End of part").assertExists()
            assertEquals(SleepMode.END_OF_PART, state.sleep.mode)

            main { service.seek(16_000); service.toggle() }
            var faded = false
            compose.waitUntil(15_000) {
                if (main { service.volume } < 1f) faded = true
                !state.playing && !state.sleep.active
            }
            assertTrue("The narration fades before pausing", faded)
            assertEquals(1f, main { service.volume }, 0f)
            assertTrue("Paused at the start of the next part: part ${state.partIndex} at ${state.positionMs}",
                state.partIndex == 1 && state.positionMs <= 1_000)
            compose.onNodeWithText("Sleep").assertExists()
        } finally {
            main { service.forget(); graph.library.remove(book.id) }
            files.forEach(File::delete)
        }
    }

    @Test fun plusFifteenExtendsARunningTimer() {
        val (book, files) = fixture()
        val source = book.sources.single()
        try {
            compose.waitUntil(15_000) { graph.playback.service?.initialized == true }
            main { service.load(book, source, false, source.parts.first().id, 0) }
            compose.runOnIdle { graph.preferences.edit().putBoolean("notificationAsked", true).apply(); vm.playerOpen.value = true }
            compose.onNodeWithText("Sleep").performScrollTo().performClick()
            compose.onNodeWithText("In 15 minutes").performClick()
            val until = state.sleep.untilMs
            compose.onNodeWithText("15m").performScrollTo().performClick()
            compose.onNodeWithText("In 15 minutes").assertIsSelected()
            compose.onNodeWithContentDescription("Add 15 minutes").performClick()
            compose.waitUntil(5_000) { state.sleep.untilMs >= until + SLEEP_EXTENSION_MS }
            compose.onNodeWithTag("sleep-status").assertTextEquals("Pauses in about 30 minutes.")
            compose.onNodeWithText("Turn timer off").performClick()
            compose.waitUntil(5_000) { !state.sleep.active }
        } finally {
            main { service.forget(); graph.library.remove(book.id) }
            files.forEach(File::delete)
        }
    }
}

/** Which sleep options a recording offers, checked against fixed listening states. */
@RunWith(AndroidJUnit4::class)
class SleepTimerDialogTest {
    @get:Rule val compose = createComposeRule()
    private val single = AudioSource("m4b", "Whole book", "M4B", listOf(AudioPart("m", "book.m4b", "Book")))
    private val parts = AudioSource("mp3", "Parts", "MP3", List(3) { AudioPart("p$it", "p$it.mp3", "Part ${it + 1}") })
    private var listening by mutableStateOf(ListeningState())

    private fun show(state: ListeningState) {
        listening = state
    }

    @Test fun endOfChapterNeedsChaptersAndEndOfPartNeedsSeveralParts() {
        val chosen = mutableListOf<SleepMode>()
        show(ListeningState(source = single, durationMs = 3_600_000))
        compose.setContent { MaterialTheme { SleepTimerDialog(listening, { chosen += it.mode }, {}, true, {}, {}) } }
        compose.onNodeWithText("At the end of this chapter").assertDoesNotExist()
        compose.onNodeWithText("At the end of this audio part").assertDoesNotExist()
        compose.onNodeWithText("Shake to keep listening").assertExists()

        compose.runOnIdle { show(listening.copy(chapters = listOf(Chapter("One", 0), Chapter("Two", 1_800_000)))) }
        compose.onNodeWithText("At the end of this chapter").performClick()
        assertEquals(listOf(SleepMode.END_OF_CHAPTER), chosen)
        compose.onNodeWithText("At the end of this audio part").assertDoesNotExist()

        compose.runOnIdle { show(ListeningState(source = parts, durationMs = 3_600_000)) }
        compose.onNodeWithText("At the end of this audio part").assertExists()
        compose.onNodeWithText("At the end of this chapter").assertDoesNotExist()
        compose.onNodeWithText("Turn timer off").assertIsSelected()
        compose.onNodeWithText("+15 min").assertDoesNotExist()
    }

    @Test fun aRunningChapterTimerShowsItsChoiceAndTimeLeft() {
        val state = ListeningState(source = single, durationMs = 3_600_000, positionMs = 600_000, chapters = listOf(Chapter("One", 0), Chapter("Two", 1_800_000)),
            sleep = SleepTimer(SleepMode.END_OF_CHAPTER, stop = PartPlace(0, 1_800_000)))
        var extended = 0
        show(state)
        compose.setContent { MaterialTheme { SleepTimerDialog(listening, {}, { extended++ }, null, {}, {}) } }
        compose.onNodeWithText("At the end of this chapter").assertIsSelected()
        compose.onNodeWithText("Turn timer off").assertIsNotSelected()
        compose.onNodeWithTag("sleep-status").assertTextEquals("Pauses at the end of this chapter, in about 20 minutes.")
        compose.onNodeWithText("Shake to keep listening").assertDoesNotExist()
        compose.onNodeWithContentDescription("Add 15 minutes").performClick()
        assertEquals(1, extended)
    }
}
