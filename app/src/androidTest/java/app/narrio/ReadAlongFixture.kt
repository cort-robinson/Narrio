package app.narrio

import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.narrio.domain.*
import app.narrio.reader.NarratedPlace
import app.narrio.reader.Narration
import app.narrio.reader.ReaderController
import app.narrio.reader.ReaderFixtures
import app.narrio.ui.NarrioViewModel
import app.narrio.ui.ReaderState
import app.narrio.ui.ReaderViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A controlled read-along book: ten minutes of real (silent) WAV audio played by Media3, a sample EPUB edition,
 * and recognized-style anchors over its first [ANCHORED] of passages, so narration there is EXACT and later text is
 * an estimate. No account, network, or speech model is involved.
 */
class ReadAlongFixture private constructor(private val compose: ReaderRule, val book: Audiobook, val source: AudioSource, val document: BookText,
                                           val binding: TextBinding, private val wave: File) {
    val graph get() = (compose.activity.application as NarrioApplication).graph
    val vm get() = ViewModelProvider(compose.activity)[NarrioViewModel::class.java]
    val part: AudioPart get() = source.parts.single()
    /** The media time of the last anchor; narration after it is estimated. */
    val anchoredUntilMs: Long get() = binding.anchors.maxOf { it.positionMs }

    /** Where narration is at [positionMs], by the same rules read along uses. */
    fun place(positionMs: Long): NarratedPlace? =
        Narration.place(document, Narration.mapped(document, source, 0, DURATION_MS, listOf(binding), positionMs), words = false)

    fun reader(): ReaderViewModel = ViewModelProvider(compose.activity, ReaderViewModel.factory(book.id))["reader:${book.id}", ReaderViewModel::class.java]

    /** The open reader's controller once its first page is laid out. */
    fun controller(): ReaderController {
        lateinit var controller: ReaderController
        compose.waitUntil(30_000) { (reader().state.value as? ReaderState.Ready)?.session?.controller?.takeIf { it.ready.value }?.also { controller = it } != null }
        return controller
    }

    fun onMain(block: suspend () -> Unit) = runBlocking { withContext(Dispatchers.Main) { block() } }
    fun seek(positionMs: Long) = onMain { graph.playback.service!!.seek(positionMs) }

    fun remove() = runBlocking {
        compose.runOnIdle { vm.closeReader(); vm.playerOpen.value = false }
        withContext(Dispatchers.Main) { graph.playback.service?.forget() }
        graph.followAlong.remove(book.id)
        graph.library.remove(book.id)
        wave.delete()
    }

    companion object {
        const val DURATION_MS = 600_000L
        const val ANCHORED = .6

        fun create(compose: ReaderRule, id: String = "read-along-fixture"): ReadAlongFixture {
            val graph = (compose.activity.application as NarrioApplication).graph
            val vm = ViewModelProvider(compose.activity)[NarrioViewModel::class.java]
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("settings put secure immersive_mode_confirmations confirmed").close()
            compose.runOnIdle {
                graph.preferences.edit().putBoolean("notificationAsked", true).remove("readerSettings").apply()
                vm.setReadAlongSync(false); vm.setBackgroundAlignment(false); vm.updateAppearance(AppearanceSettings())
            }
            val wave = File(compose.activity.filesDir, "$id.wav")
            val size = (DURATION_MS / 1000 * 8000 * 2).toInt()
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + size); put("WAVEfmt ".toByteArray()); putInt(16)
                putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16); put("data".toByteArray()); putInt(size)
            }.array()
            wave.outputStream().use { stream -> stream.write(header); stream.write(ByteArray(size)) }
            val part = AudioPart("$id-part", "$id.wav", "The whole book", durationMs = DURATION_MS, archiveUrl = Uri.fromFile(wave).toString())
            val source = AudioSource("$id-source", "Native fixture audio", "WAV", listOf(part))
            val book = Audiobook(id, "The Secret Garden", "Frances Hodgson Burnett", "Synthetic silence", sources = listOf(source), detailsLoaded = true)
            runBlocking { graph.followAlong.remove(book.id) }
            compose.waitUntil(15_000) { graph.playback.service?.initialized == true }
            runBlocking { withContext(Dispatchers.Main) { graph.playback.service!!.load(book, source, false, part.id, 0) } }
            val document = runBlocking { graph.followAlong.importLocal(ReaderFixtures.sampleEpub(24), "EPUB", book) }
            val passages = document.chapters.flatMap { it.passages }
            val words = passages.runningFold(0L) { total, passage -> total + passage.weight }
            val total = words.last().toDouble()
            val anchored = (passages.size * ANCHORED).toInt()
            val anchors = (0 until anchored step 3).map { index -> TextAnchor(passages[index].id, ((words[index] / total) * (DURATION_MS - 5_000)).toLong(), 0, auto = true) }
            val binding = TextBinding(document.id, source.id, part.id, WHOLE_BOOK, anchors)
            runBlocking { graph.followAlong.bind(book.id, binding) }
            compose.waitUntil(10_000) { vm.bookText.value.bindings.any { it.anchors.isNotEmpty() } }
            return ReadAlongFixture(compose, book, source, document, binding, wave)
        }
    }
}
