package app.narrio.playback

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import app.narrio.domain.AudioSource

/**
 * Whether listening reached the end of a book by playing it. The player's ended state alone isn't enough: seeking or
 * skipping to the end of the last part ends playback too. Each load, seek, part change, or recovery anchors playback
 * where it starts; the book finishes only when its final part plays from that anchor to the end for at least
 * [minPlayedMs], or the whole of a shorter part.
 */
class ListeningCompletion(private val minPlayedMs: Long = MIN_PLAYED_MS) {
    private var recording: Pair<String, String>? = null
    private var anchorIndex = -1
    private var anchorMs = 0L

    /** A recording was put in the player at part [index], [positionMs]; restoring it never finishes or resumes anything. */
    fun loaded(bookId: String, sourceId: String, index: Int, positionMs: Long) { recording = bookId to sourceId; anchor(index, positionMs) }

    /** Playback (re)starts from here: a seek, skip, natural move into the next part, or a retry after an error. */
    fun anchor(index: Int, positionMs: Long) { anchorIndex = index; anchorMs = positionMs.coerceAtLeast(0) }

    /** Playback failed; nothing counts again until the listener resumes, which anchors afresh. */
    fun failed() { anchorIndex = -1 }

    fun cleared() { recording = null; anchorIndex = -1 }

    /** The player ended at [endMs] into part [index] of [partCount] of this recording. True when that is a finish. */
    fun ended(bookId: String, sourceId: String, index: Int, partCount: Int, endMs: Long): Boolean =
        recording == (bookId to sourceId) && partCount > 0 && index == partCount - 1 && anchorIndex == index &&
            endMs > 0 && endMs - anchorMs >= minOf(minPlayedMs, endMs)

    /** Listening plays from somewhere other than the last moments of the book, so a finished book is being heard again. */
    fun awayFromEnd(index: Int, partCount: Int, positionMs: Long, durationMs: Long): Boolean =
        !(index == partCount - 1 && durationMs > 0 && durationMs - positionMs < minPlayedMs)

    companion object { const val MIN_PLAYED_MS = 5_000L }
}

/**
 * Feeds [completion] from [player]. [recording] is the book id and recording in the player now; the timeline must
 * still be that recording's parts. [finished] reports a natural finish, [resumed] a book playing away from its end.
 */
class CompletionListener(
    private val player: Player,
    private val completion: ListeningCompletion,
    private val recording: () -> Pair<String, AudioSource>?,
    private val finished: (String) -> Unit,
    private val resumed: (String) -> Unit,
) : Player.Listener {
    private fun current(): Pair<String, AudioSource>? = recording()?.takeIf { (_, source) ->
        player.mediaItemCount == source.parts.size && source.parts.getOrNull(player.currentMediaItemIndex)?.id == player.currentMediaItem?.mediaId
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) =
        completion.anchor(newPosition.mediaItemIndex, newPosition.positionMs)

    override fun onPlayerError(error: PlaybackException) = completion.failed()

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState != Player.STATE_ENDED) return
        val (bookId, source) = current() ?: return
        if (completion.ended(bookId, source.id, player.currentMediaItemIndex, source.parts.size, player.currentPosition)) finished(bookId)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (!isPlaying) return
        val (bookId, source) = current() ?: return
        if (completion.awayFromEnd(player.currentMediaItemIndex, source.parts.size, player.currentPosition, player.duration)) resumed(bookId)
    }
}
