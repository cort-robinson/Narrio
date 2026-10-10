package app.narrio

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.narrio.data.*
import app.narrio.domain.*
import app.narrio.ui.NarrioViewModel
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Controlled PCM/text fixtures. No speech-model download, delivery account, or live provider. */
@RunWith(AndroidJUnit4::class)
class ReadingSyncSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = (compose.activity.application as NarrioApplication).graph
    private val vm get() = ViewModelProvider(compose.activity)[NarrioViewModel::class.java]

    @Test fun settingsToggleAndAtomicProgressSurviveRepositoryRecreation() = runBlocking {
        compose.runOnIdle { vm.setBackgroundAlignment(true); vm.navigate(2) }
        compose.onNodeWithTag("settings-options").performScrollToNode(hasTestTag("background-alignment"))
        compose.onNodeWithTag("background-alignment").performScrollTo().assertIsOn().performClick().assertIsOff()
        assertFalse(graph.bookAlignment.enabled())
        compose.onNodeWithTag("background-alignment").performClick().assertIsOn()
        val key = AlignmentKey("smoke-${System.nanoTime()}", "edition", "recording", "part", 1, "layout")
        graph.library.save(Audiobook(key.bookId, "Sync storage", "Fixture", detailsLoaded = true))
        graph.library.putEdition(EbookEditionEntry(key.bookId, key.editionId, format = "TXT"))
        graph.alignmentJobs.save(AlignmentProgress(key, 60_000, 2, 1, 0, 30_000))
        assertEquals(60_000, RoomAlignmentJobs(graph.database).progress(key).nextWindowMs)
        val bookId = key.bookId
        val store = RoomSharedPositionStore(graph.library)
        val update = PositionUpdate(bookId, PositionOrigin.READING, text = ContentCursor("edition", "chapter", 5), basedOnSequence = 0)
        assertEquals(1, store.commit(update)!!.sequence)
        assertNull(RoomSharedPositionStore(graph.library).commit(update))
        assertEquals(5, RoomSharedPositionStore(graph.library).current(bookId)!!.text!!.offset)
        graph.library.remove(bookId)
    }

    @Test fun textStartSeeksAndPlayingAudioOwnsSharedPositionAfterDwell() = runBlocking {
        compose.waitUntil(15_000) { graph.playback.service?.initialized == true }
        compose.runOnIdle { vm.setBackgroundAlignment(false); vm.setReadAlongSync(false) }
        val id = "sync-smoke-${System.nanoTime()}"
        val wave = File(compose.activity.filesDir, "$id.wav")
        val size = 120 * 8000 * 2
        wave.outputStream().use { stream ->
            stream.write(ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + size); put("WAVEfmt ".toByteArray()); putInt(16)
                putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16); put("data".toByteArray()); putInt(size)
            }.array()); stream.write(ByteArray(size))
        }
        val part = AudioPart("$id-part", "$id.wav", "Chapter", 120_000, Uri.fromFile(wave).toString())
        val source = AudioSource("$id-source", "Fixture", "WAV", listOf(part))
        val book = Audiobook(id, "Fixture", "Fixture", language = "German", sources = listOf(source), detailsLoaded = true)
        val document = BookTextParser.parse("First line of this fixture.\n\nSecond line of this fixture.".toByteArray(), "TXT", book.title, book.author, "Fixture")
        val folder = File(compose.activity.filesDir, "follow-along/${BookTextParser.fingerprint(id.toByteArray())}").apply { mkdirs() }
        File(folder, "${document.id}.json").writeText(NarrioJson.encodeToString(document))
        graph.library.attachText(book, document.id)
        val passage = document.chapters.flatMap { it.passages }.last()
        val wanted = ContentCursor(document.id, passage.resource, passage.offset)
        graph.sharedPositions.commit(PositionUpdate(id, PositionOrigin.READING, text = wanted, textConfidence = MappingConfidence.EXACT, basedOnSequence = 0))
        try {
            withContext(Dispatchers.Main) { graph.playback.service!!.load(book, source, false) }
            assertEquals(MappingConfidence.ESTIMATED, graph.readingSync.state.value.confidence)
            assertTrue(graph.readingSync.state.value.audioJump!!.offerUndo)
            compose.waitUntil(10_000) { graph.playback.state.value.positionMs > 30_000 }
            withContext(Dispatchers.Main) { graph.playback.service!!.undoSyncJump() }
            assertFalse(graph.playback.state.value.playing)
            assertEquals(0, graph.playback.state.value.positionMs)
            assertEquals(PositionOrigin.READING, graph.sharedPositions.current(id)!!.origin)
            withContext(Dispatchers.Main) { graph.playback.service!!.load(book, source, false) }
            compose.waitUntil(10_000) { graph.playback.state.value.positionMs > 30_000 }
            withContext(Dispatchers.Main) { assertTrue(graph.playback.service!!.seekFromText(wanted)) }
            assertFalse(graph.playback.state.value.playing)
            withContext(Dispatchers.Main) { graph.playback.service!!.toggle() }
            compose.waitUntil(10_000) { graph.playback.state.value.playing }
            assertNull(graph.sharedPositions.commit(PositionUpdate(id, PositionOrigin.READING, text = wanted.copy(offset = 0), basedOnSequence = 1)))
            delay(3_000)
            assertEquals(PositionOrigin.READING, graph.sharedPositions.current(id)!!.origin)
            withTimeout(20_000) {
                while (graph.sharedPositions.current(id)?.origin != PositionOrigin.LISTENING) delay(250)
            }
            val position = graph.sharedPositions.current(id)!!
            assertEquals(2, position.sequence)
            assertEquals(source.id, position.audio!!.sourceId)
            assertEquals(MappingConfidence.ESTIMATED, position.textConfidence)
            // Undo restores history without taking a sentence tap's autoplay decision away.
            assertNotNull(position.text)
        } finally {
            withContext(Dispatchers.Main) { graph.playback.service?.forget() }
            graph.followAlong.remove(id); graph.library.remove(id); wave.delete()
            compose.runOnIdle { vm.setBackgroundAlignment(true) }
        }
    }
}
