package app.narrio

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.narrio.domain.AudioPart
import app.narrio.domain.AudioSource
import app.narrio.playback.CompletionListener
import app.narrio.playback.ListeningCompletion
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A real ExoPlayer playing generated silent WAV parts through [CompletionListener]: only playing through the final part
 * finishes the book. No network, provider, or shelf data is involved.
 */
@RunWith(AndroidJUnit4::class)
class ListeningCompletionPlayerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val folder = File(instrumentation.targetContext.cacheDir, "completion-qa").apply { mkdirs() }
    private val book = "completion-qa-book"
    private val source = AudioSource("completion-qa", "Parts", "WAV", List(2) { AudioPart("completion-p$it", "p$it.wav", "Part ${it + 1}") })
    private val finished = CopyOnWriteArrayList<String>()
    private val resumed = CopyOnWriteArrayList<String>()
    private var recording: AudioSource = source
    private lateinit var player: ExoPlayer
    private val completion = ListeningCompletion()

    @Before fun setUp() = main {
        player = ExoPlayer.Builder(instrumentation.targetContext).build()
        player.addListener(CompletionListener(player, completion, { book to recording }, { finished += it }, { resumed += it }))
    }

    @After fun tearDown() { main { player.release() }; folder.deleteRecursively() }

    private fun <T> main(block: () -> T): T { var result: Result<T>? = null; instrumentation.runOnMainSync { result = runCatching(block) }; return result!!.getOrThrow() }

    /** 16-bit mono 8 kHz silence. */
    private fun wav(name: String, ms: Int): File {
        val samples = 8 * ms
        val data = samples * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data); put("WAVEfmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(8_000); putInt(16_000); putShort(2); putShort(16); put("data".toByteArray()); putInt(data)
        }
        return File(folder, name).apply { writeBytes(header.array() + ByteArray(data)) }
    }

    private fun load(files: List<File>, index: Int = 0, positionMs: Long = 0) = main {
        player.setMediaItems(files.mapIndexed { i, file -> MediaItem.Builder().setMediaId(source.parts[i].id).setUri(Uri.fromFile(file)).build() }, index, positionMs)
        completion.loaded(book, recording.id, index, positionMs)
        player.prepare()
    }

    private fun await(description: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!main(condition)) { assertTrue("Timed out waiting for $description", System.currentTimeMillis() < until); Thread.sleep(50) }
    }

    @Test fun playingThroughTheLastPartFinishesTheBook() {
        load(listOf(wav("a.wav", 1_200), wav("b.wav", 1_200)))
        main { player.play() }
        await("the end") { player.playbackState == Player.STATE_ENDED }
        assertEquals(listOf(book), finished)
        assertEquals(listOf(book), resumed)
    }

    @Test fun seekingOrSkippingToTheEndDoesNot() {
        load(listOf(wav("a.wav", 1_200), wav("b.wav", 1_200)))
        await("ready") { player.playbackState == Player.STATE_READY }
        // Restoring a session only loads it: nothing is finished or reopened.
        assertTrue(finished.isEmpty() && resumed.isEmpty())
        main { player.seekTo(1, 1_200); player.play() }
        await("the end") { player.playbackState == Player.STATE_ENDED }
        main { player.seekTo(1, 900); player.play() }
        await("the end again") { player.playbackState == Player.STATE_ENDED }
        assertTrue(finished.isEmpty())
    }

    @Test fun anEndThatIsNotThisRecordingsLastPartDoesNot() {
        // The player holds two parts while the recording has three: the timeline changed under it.
        recording = source.copy(parts = source.parts + AudioPart("completion-p2", "p2.wav", "Part 3"))
        load(listOf(wav("a.wav", 800), wav("b.wav", 800)))
        main { player.play() }
        await("the end") { player.playbackState == Player.STATE_ENDED }
        assertTrue(finished.isEmpty())
    }

    @Test fun aPlaybackErrorDoesNotFinish() {
        load(listOf(wav("a.wav", 800), File(folder, "missing.wav")))
        main { player.play() }
        await("the error") { player.playerError != null }
        assertTrue(finished.isEmpty())
    }
}
