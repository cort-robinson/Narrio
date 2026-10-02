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
    val sleepUntil: Long = 0,
    val sleepAtEnd: Boolean = false,
    val chapters: List<Chapter> = emptyList(),
    val error: String? = null,
) {
    val part: AudioPart? get() = source?.parts?.getOrNull(partIndex)
}

class PlaybackHub {
    val state = MutableStateFlow(ListeningState())
    var service: ListeningService? = null
    var visible = false
}
