package app.narrio

import android.net.Uri
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.narrio.data.*
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
import java.util.Collections

/**
 * Audio files added from the phone, end to end, with controlled files: synthetic silence written to Narrio's own
 * storage at test time (no audio is kept in the repository). Covers reading and naming same-named files, real local
 * playback, a file choice, a bookmark in a file left out, downloads, restart recovery, and a file that disappears.
 */
@RunWith(AndroidJUnit4::class)
class LocalAudioPlaybackTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = (compose.activity.application as NarrioApplication).graph
    private val vm get() = ViewModelProvider(compose.activity)[NarrioViewModel::class.java]
    private val service get() = graph.playback.service!!
    private val state get() = graph.playback.state.value
    private val book = Audiobook("catalog:local-fixture", "A Quiet Book", "Native Fixture", provider = "catalog", detailsLoaded = true)

    private fun wave(file: File, seconds: Int) {
        file.parentFile!!.mkdirs()
        val size = seconds * 8000 * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + size); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16); put("data".toByteArray()); putInt(size)
        }.array()
        file.outputStream().use { stream -> stream.write(header); stream.write(ByteArray(size)) }
    }

    private fun <T> main(block: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main) { block() } }

    @Test fun phoneFilesImportPlayRecoverAndRespectTheirChosenFiles() {
        val folder = File(compose.activity.filesDir, "qa-local-audio").apply { deleteRecursively() }
        val files = listOf("Disc 1/01.wav", "Disc 2/01.wav", "Disc 2/02.wav").map { File(folder, it).also { file -> wave(file, 20) } }
        val messages = Collections.synchronizedList(mutableListOf<String>())
        val listening = CoroutineScope(Dispatchers.Main).launch { vm.messages.collect { messages += it } }
        try {
            // Same-named files keep their folder; their lengths are read from the files. Own files need no lasting grant.
            val import = runBlocking { graph.localAudio.fromDocuments(files.map(Uri::fromFile).reversed()) }
            assertEquals(listOf("Disc 1/01.wav", "Disc 2/01.wav", "Disc 2/02.wav"), import.files.map { it.path })
            assertTrue(import.files.all { it.durationMs in 19_000..21_000 })
            assertTrue(import.grants.isEmpty())
            val recording = LocalAudio.recording(book, import.files).forBook(book)
            val source = recording.sources.single()
            val (first, left, last) = source.parts
            graph.localAudio.commit(book.id, import)
            graph.localManifests.put(book.id, source)

            // Real playback from the phone file.
            compose.waitUntil(15_000) { graph.playback.service?.initialized == true }
            main { service.load(recording, source, true) }
            compose.waitUntil(10_000) { state.playing && state.positionMs > 300 }
            assertEquals(first.id, state.part?.id)
            assertNull(state.error)

            // Only the outer files, last one first: playback follows, keeping the playing file's place.
            graph.releaseFiles.save(book.id, recording, source, listOf(last, first).map(FileChoices::fileKey))
            main { service.load(recording, source, false, first.id, 2_000) }
            compose.waitUntil(5_000) { state.source?.parts?.map { it.id } == listOf(last.id, first.id) }
            assertEquals(first.id, state.part?.id)
            assertEquals(source.id, state.source?.id)

            // A bookmark in the file left out doesn't play another file at its time.
            compose.runOnIdle { vm.jumpBookmark(book.id, AudioCursor(source.id, left.id, 5_000)) }
            compose.waitUntil(5_000) { messages.any { "isn't chosen" in it } }
            assertEquals(first.id, state.part?.id)

            // Phone files are never downloaded again.
            compose.runOnIdle { vm.download(recording, source, LocalAudio.DELIVERY) }
            compose.waitUntil(5_000) { messages.any { "already on your phone" in it } }
            assertTrue(graph.offline.books.value.none { it.book.id == book.id })

            // After a restart the saved layout comes back with its file choice and place.
            main { service.load(recording, source, false, first.id, 7_000) }
            main { service.forget() }
            val entry = main { service.restorable() }
            assertEquals(book.id, entry?.bookId)
            main { service.load(entry!!.book(), entry.source()!!, false) }
            compose.waitUntil(5_000) { state.book?.id == book.id }
            assertEquals(listOf(last.id, first.id), state.source?.parts?.map { it.id })
            assertEquals(first.id, state.part?.id)
            assertEquals(7_000L, state.positionMs)

            // A file that's gone stops Listen with a way forward instead of a playback error later.
            main { service.forget() }
            files[2].delete()
            compose.runOnIdle { vm.start(recording, source, LocalAudio.DELIVERY) }
            compose.waitUntil(5_000) { messages.any { "can no longer open" in it } }
            assertNotEquals(book.id, state.book?.id)
        } finally {
            listening.cancel()
            main { service.forget(); graph.library.remove(book.id) }
            graph.releaseFiles.forget(book.id)
            graph.localAudio.forget(book.id)
            folder.deleteRecursively()
        }
    }
}
