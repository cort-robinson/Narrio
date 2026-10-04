package app.narrio.playback

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.ConnectivityManager
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.MediaExtractorCompat
import androidx.media3.extractor.DefaultExtractorsFactory
import app.narrio.data.*
import app.narrio.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class SyncTarget(
    val book: Audiobook,
    val source: AudioSource,
    val part: AudioPart,
    val positionMs: Long,
    val durationMs: Long,
    val document: BookText,
    val binding: TextBinding?,
)

enum class SyncPhase { IDLE, NEEDS_MODEL, DOWNLOADING_MODEL, LISTENING, UNSUPPORTED, FAILED }
data class SyncStatus(val phase: SyncPhase = SyncPhase.IDLE, val progress: Float = 0f, val modelBytes: Long = 0, val message: String = "")

/**
 * Keeps book text in step with narration by recognizing short windows of the audio on the device and
 * placing them in the text. Windows near the playhead come first, so the passage being heard is
 * anchored within seconds; results persist as timing anchors and are never recomputed for a window.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class NarrationSync(
    private val context: Context,
    http: OkHttpClient,
    private val offline: OfflineStore,
    torbox: TorBoxDelivery,
    private val models: SpeechModelStore,
) {
    val status = MutableStateFlow(SyncStatus())
    private val parts = ConcurrentHashMap<String, AudioPart>()
    private val links = ConcurrentHashMap<String, String>()
    private val upstream = DefaultDataSource.Factory(context, OkHttpDataSource.Factory(http.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).build()))
    // Reads phone downloads when present; streaming reads are never written to the offline cache.
    private val audio: DataSource.Factory = offline.playbackFactory { RefreshingDataSource(upstream.createDataSource(), torbox, parts, links) }
    private val attempted = mutableSetOf<String>()
    private var meteredAllowed = false
    private var loaded: Pair<String, Model>? = null
    private val alignments = mutableMapOf<String, NarrationAlignment>()

    fun allowModelDownload() { meteredAllowed = true; if (status.value.phase == SyncPhase.NEEDS_MODEL) status.value = SyncStatus() }

    /** Recognize this part again, after its anchors were reset. */
    fun retry(partId: String) { attempted.removeAll { "|$partId|" in it } }

    /** Runs until cancelled. [target] reflects the current playback and attached text. */
    suspend fun run(target: () -> SyncTarget?, save: suspend (String, TextBinding) -> Unit) {
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val delayMs = step(target, save)
                delay(delayMs)
            }
        } finally {
            withContext(NonCancellable) { release() }
            if (status.value.phase == SyncPhase.LISTENING) status.value = SyncStatus()
        }
    }

    private suspend fun step(target: () -> SyncTarget?, save: suspend (String, TextBinding) -> Unit): Long {
        val current = target() ?: return idle()
        if (current.document.timedSourceId.isNotBlank() || current.durationMs <= 0) return idle()
        val model = models.modelFor(current.document.language.ifBlank { current.book.language })
            ?: return 5000L.also { status.value = SyncStatus(SyncPhase.UNSUPPORTED) }
        val folder = models.installed(model) ?: run {
            val metered = context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered != false
            if (metered && !meteredAllowed) { status.value = SyncStatus(SyncPhase.NEEDS_MODEL, modelBytes = model.bytes); return 2000 }
            status.value = SyncStatus(SyncPhase.DOWNLOADING_MODEL, 0f, model.bytes)
            try { models.install(model) { status.value = SyncStatus(SyncPhase.DOWNLOADING_MODEL, it.coerceIn(0f, 1f), model.bytes) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                meteredAllowed = false
                status.value = SyncStatus(SyncPhase.NEEDS_MODEL, modelBytes = model.bytes, message = (error as? ProviderException)?.message ?: "The narration sync model couldn't download. Try again.")
                return 2000
            }
        }
        val binding = current.binding?.takeIf { it.documentId == current.document.id } ?: TextBinding(current.document.id, current.source.id, current.part.id, WHOLE_BOOK)
        val lines = FollowAlongTiming.passages(current.document, binding.chapterId)
        if (lines.isEmpty()) return idle()
        val window = nextWindow(current, binding) ?: return idle()
        status.value = SyncStatus(SyncPhase.LISTENING)
        val key = attemptKey(current, binding, window)
        attempted += key
        val anchors = try {
            val samples = withContext(Dispatchers.IO) { decode(current.part, window, WINDOW_MS) }
            val words = withContext(Dispatchers.Default) { recognize(folder, samples.first, samples.second) }
            val alignment = alignments.getOrPut("${current.document.id}|${binding.chapterId}") { NarrationAlignment(lines) }
            withContext(Dispatchers.Default) { place(alignment, current, binding, words, window) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            // Unreadable or unreachable audio: estimated timing remains, and this window isn't retried.
            emptyList()
        }
        if (anchors.isNotEmpty()) {
            // Re-read the binding so a listener's concurrent match is preserved.
            val latest = target()?.takeIf { it.part.id == current.part.id && it.document.id == current.document.id } ?: return 0
            val base = latest.binding?.takeIf { it.chapterId == binding.chapterId } ?: binding
            val merged = FollowAlongTiming.mergeAuto(current.document, base, anchors, current.durationMs)
            if (merged != base) runCatching { save(current.book.id, merged) }
        }
        return 0
    }

    private fun idle(): Long { if (status.value.phase != SyncPhase.IDLE) status.value = SyncStatus(); return 2000 }

    private fun attemptKey(target: SyncTarget, binding: TextBinding, startMs: Long) =
        "${target.document.id}|${target.source.id}|${target.part.id}|${binding.chapterId}|${startMs / SPACING_MS}"

    /** The playhead first, then ahead of it, then behind it; windows already anchored or tried are skipped. */
    private fun nextWindow(target: SyncTarget, binding: TextBinding): Long? {
        val anchored = binding.anchors.filter { it.auto }.map { it.positionMs }
        val latestStart = (target.durationMs - WINDOW_MS).coerceAtLeast(0)
        return OFFSETS.asSequence().map { offset ->
            // Snap to a grid so repeated passes over the same region reuse the same windows.
            val raw = (target.positionMs + offset).coerceIn(0, latestStart)
            (raw / SPACING_MS * SPACING_MS).coerceAtMost(latestStart)
        }.distinct().firstOrNull { start ->
            attemptKey(target, binding, start) !in attempted && anchored.none { it in start - COVERED_MS until start + WINDOW_MS + COVERED_MS }
        }
    }

    private fun place(alignment: NarrationAlignment, target: SyncTarget, binding: TextBinding, words: List<SpokenWord>, startMs: Long): List<TextAnchor> {
        if (words.size < 6) return emptyList()
        // Search near the current estimate first; a confident match elsewhere is still accepted.
        val timeline = FollowAlongTiming.timeline(target.document, binding, target.durationMs)
        val expected = timeline?.takeIf { binding.anchors.any { it.auto } }?.let { alignment.expected(it, startMs) }
        if (expected != null) {
            val local = alignment.match(words, (expected - LOCAL_WORDS).coerceAtLeast(0) until (expected + LOCAL_WORDS).coerceAtMost(alignment.size))
            if (local.isNotEmpty()) return local
        }
        return alignment.match(words)
    }

    /** Decodes [lengthMs] from [startMs] to 16 kHz mono, using the same extractors and seek map as the player. */
    private fun decode(part: AudioPart, startMs: Long, lengthMs: Long): Pair<ShortArray, Long> {
        val uri = stableAudioUri(part)
        parts[uri] = part
        val extractor = MediaExtractorCompat(DefaultExtractorsFactory(), audio)
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(Uri.parse(uri), 0)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw ProviderException("This audio has no readable track.")
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            extractor.seekTo(startMs * 1000, MediaExtractorCompat.SEEK_TO_PREVIOUS_SYNC)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).apply { configure(format, null, null, 0); start() }
            val endUs = (startMs + lengthMs) * 1000
            val mono = FloatCollector()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var float = false
            var inputDone = false
            var firstUs = -1L
            val info = MediaCodec.BufferInfo()
            val deadline = System.currentTimeMillis() + 120_000
            while (System.currentTimeMillis() < deadline) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0 || extractor.sampleTime > endUs) { codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                val out = codec.dequeueOutputBuffer(info, 10_000)
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val changed = codec.outputFormat
                    rate = changed.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = changed.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    float = changed.containsKey(MediaFormat.KEY_PCM_ENCODING) && changed.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (out >= 0) {
                    val buffer = codec.getOutputBuffer(out)!!.order(ByteOrder.nativeOrder())
                    buffer.position(info.offset); buffer.limit(info.offset + info.size)
                    val frames = info.size / (channels * if (float) 4 else 2)
                    for (frame in 0 until frames) {
                        val us = info.presentationTimeUs + frame * 1_000_000L / rate
                        var sum = 0f
                        repeat(channels) { sum += if (float) buffer.getFloat() else buffer.getShort() / 32768f }
                        if (us < startMs * 1000 || us >= endUs) continue
                        if (firstUs < 0) firstUs = us
                        mono.add(sum / channels)
                    }
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            if (firstUs < 0) throw ProviderException("No audio was decoded for this window.")
            return resample(mono.toArray(), rate) to firstUs / 1000
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }; extractor.release()
        }
    }

    private fun resample(input: FloatArray, rate: Int): ShortArray {
        val step = rate.toDouble() / SAMPLE_RATE
        val output = ShortArray((input.size / step).toInt())
        for (i in output.indices) {
            // Average the source samples this output sample covers, a simple low-pass for downsampling.
            val from = (i * step).toInt()
            val to = ((i + 1) * step).toInt().coerceIn(from + 1, input.size)
            var sum = 0f
            for (j in from until to) sum += input[j]
            output[i] = ((sum / (to - from)).coerceIn(-1f, 1f) * 32767).toInt().toShort()
        }
        return output
    }

    private fun recognize(folder: File, samples: ShortArray, offsetMs: Long): List<SpokenWord> {
        val model = loaded?.takeIf { it.first == folder.path }?.second ?: Model(folder.path).also { model ->
            loaded?.second?.close(); loaded = folder.path to model
        }
        val words = mutableListOf<SpokenWord>()
        fun collect(json: String) {
            (NarrioJson.parseToJsonElement(json) as? JsonObject)?.objects("result")?.forEach { word ->
                val start = word["start"]?.jsonPrimitive?.doubleOrNull ?: return@forEach
                val end = word["end"]?.jsonPrimitive?.doubleOrNull ?: start
                words += SpokenWord(word.text("word"), offsetMs + (start * 1000).toLong(), offsetMs + (end * 1000).toLong())
            }
        }
        Recognizer(model, SAMPLE_RATE.toFloat()).use { recognizer ->
            recognizer.setWords(true)
            var at = 0
            while (at < samples.size) {
                val count = minOf(CHUNK, samples.size - at)
                if (recognizer.acceptWaveForm(samples.copyOfRange(at, at + count), count)) collect(recognizer.result)
                at += count
            }
            collect(recognizer.finalResult)
        }
        return words
    }

    private fun release() { loaded?.second?.close(); loaded = null; alignments.clear() }

    private class FloatCollector {
        private var data = FloatArray(1 shl 16)
        private var size = 0
        fun add(value: Float) { if (size == data.size) data = data.copyOf(size * 2); data[size++] = value }
        fun toArray(): FloatArray = data.copyOf(size)
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val CHUNK = 4000
        private const val WINDOW_MS = 20_000L
        private const val SPACING_MS = 30_000L
        private const val COVERED_MS = 20_000L
        private const val LOCAL_WORDS = 1500
        private val OFFSETS = listOf(0L, 30_000, 60_000, 120_000, 180_000, 300_000, 420_000, 600_000, 900_000, -60_000, -180_000)
    }
}
