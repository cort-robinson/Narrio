package app.narrio

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.narrio.domain.*
import app.narrio.playback.SleepMode
import app.narrio.ui.NarrioViewModel
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Captures the Listening room's time left, part steps, sleep dialog, and More options menu in Night and Day for visual
 * review (not part of CI). Screenshots go to the app's external files under `sleep-timer-qa/`. Synthetic silence.
 */
@RunWith(AndroidJUnit4::class)
class SleepTimerVisualQa {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = (compose.activity.application as NarrioApplication).graph
    private val vm get() = ViewModelProvider(compose.activity)[NarrioViewModel::class.java]
    private val service get() = graph.playback.service!!
    private val output by lazy { File(compose.activity.getExternalFilesDir(null), "sleep-timer-qa").apply { mkdirs() } }

    private fun <T> main(block: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main) { block() } }

    private fun capture(name: String) {
        compose.waitForIdle(); Thread.sleep(1_200)
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(output, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun listeningRoom() {
        val size = 60 * 8000 * 2
        val files = List(12) { i ->
            File(compose.activity.filesDir, "sleep-qa-$i.wav").also { wave ->
                val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                    put("RIFF".toByteArray()); putInt(36 + size); put("WAVEfmt ".toByteArray()); putInt(16)
                    putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16); put("data".toByteArray()); putInt(size)
                }.array()
                wave.outputStream().use { stream -> stream.write(header); stream.write(ByteArray(size)) }
            }
        }
        val parts = files.mapIndexed { i, wave -> AudioPart("sleep-qa-$i", wave.name, "Chapter ${i + 1}: The Locked Garden", durationMs = 2_940_000, archiveUrl = Uri.fromFile(wave).toString()) }
        val book = Audiobook("sleep-qa", "The Secret Garden", "Frances Hodgson Burnett", "Synthetic silence", sources = listOf(AudioSource("sleep-qa", "Native fixture audio", "WAV", parts)), detailsLoaded = true)
        val source = book.sources.single()
        try {
            compose.waitUntil(15_000) { graph.playback.service?.initialized == true }
            main { service.load(book, source, false, parts[3].id, 30_000); service.speed(1.25f) }
            compose.runOnIdle { graph.preferences.edit().putBoolean("notificationAsked", true).apply(); vm.playerOpen.value = true }
            for (mode in listOf(ThemeMode.NIGHT, ThemeMode.DAY)) {
                val name = mode.name.lowercase()
                compose.runOnIdle { vm.updateAppearance(AppearanceSettings(mode = mode)) }
                main { service.sleep(SleepMode.OFF) }
                capture("room-$name")
                compose.onNodeWithText("Sleep").performScrollTo().performClick()
                capture("sleep-dialog-$name")
                compose.onNodeWithText("At the end of this audio part").performClick()
                compose.onNodeWithText("End of part").performScrollTo().performClick()
                capture("sleep-dialog-running-$name")
                compose.onNodeWithText("Done").performClick()
                compose.onNodeWithContentDescription("More options").performClick()
                capture("menu-$name")
                back()
            }
        } finally {
            compose.runOnIdle { vm.updateAppearance(AppearanceSettings()) }
            main { service.speed(1f); service.forget(); graph.library.remove(book.id) }
            files.forEach(File::delete)
        }
    }

    private fun back() { InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close(); Thread.sleep(800) }
}
