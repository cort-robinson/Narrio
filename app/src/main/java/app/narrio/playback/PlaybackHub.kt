package app.narrio.playback

import app.narrio.domain.*
import kotlinx.coroutines.flow.MutableStateFlow

data class ListeningState(
    val book: Audiobook? = null,
    val source: AudioSource? = null,
    val partIndex: Int = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val speed: Float = 1f,
    val sleep: SleepTimer = SleepTimer(),
    val chapters: List<Chapter> = emptyList(),
    val error: String? = null,
) {
    val part: AudioPart? get() = source?.parts?.getOrNull(partIndex)
    /** Whole-book place when every part's length is known. */
    val bookTime: BookTime? get() = source?.parts?.let { app.narrio.domain.bookTime(it, partIndex, positionMs, durationMs) }
    /** The chapter playing now in this part, or null when the part has no chapters. */
    val chapter: Chapter? get() = chapters.getOrNull(chapterIndexAt(chapters, positionMs))
    /** Progress for thin progress lines: the whole book when known, otherwise this part. */
    val progress: Float get() = bookTime?.fraction ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val hasLength: Boolean get() = durationMs > 0 || bookTime != null
    fun sleepRemainingMs(nowMs: Long = System.currentTimeMillis()): Long? = app.narrio.playback.sleepRemainingMs(sleep, partIndex, positionMs, durationMs, speed, nowMs)
}

class PlaybackHub {
    val state = MutableStateFlow(ListeningState())
    var service: ListeningService? = null
    var visible = false
}
