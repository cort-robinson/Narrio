package app.narrio.data

import app.narrio.domain.AudioSource
import app.narrio.domain.Audiobook

/*
 * Which recording a shelf row's audio belongs to. The row keeps one book JSON, which getting another recording ready
 * replaces, so the row's book never identifies its played audio by itself. Resume, Continue, restoring the player,
 * and listening from a reading page all resolve it here, through [AppGraph.recordingFor][app.narrio.AppGraph.recordingFor].
 */

/** The same recording, whichever id it carries: a search result's own id, or the book id it was adopted under. */
fun recordingKey(recording: Audiobook): String = recording.recordingId.ifBlank { recording.id }

fun sameRecording(a: Audiobook, b: Audiobook): Boolean = recordingKey(a) == recordingKey(b) ||
    a.torrentHash.isNotBlank() && a.torrentHash.equals(b.torrentHash, ignoreCase = true)

private val PREPARING_STATES = PreparationStates.TRACKED

/**
 * [book]'s audio [source] when which recording played it isn't known: described as it is, without borrowing another
 * recording's narrator.
 */
fun playedAudio(book: Audiobook, source: AudioSource): Audiobook =
    Audiobook(book.id, book.title, book.author, coverUrl = book.coverUrl, description = book.description, detailsLoaded = true,
        provider = if (source.delivery == "archive") "archive" else "torbox", cacheState = "cached", cachedFormats = listOf(source.format),
        sources = listOf(source), recordingId = "played:${source.id}")

/**
 * The recording [entry]'s played audio belongs to, or null when nothing has played. [listening] is the remembered
 * one. Older shelves remember only the row's book, which counts unless it's a recording being got ready (that
 * replaces the row's book, but not the audio that played); then the played audio is described as it is.
 */
fun playedRecording(entry: ShelfEntry, listening: Audiobook?, pending: PreparationRecord?): Audiobook? {
    val played = entry.source() ?: return null
    val saved = entry.book()
    if (listening != null) return saved.takeIf { it.provider != "catalog" && sameRecording(it, listening) }
        ?: listening.copy(title = saved.title, author = saved.author, coverUrl = saved.coverUrl.ifBlank { listening.coverUrl }, description = saved.description, sources = listOf(played))
    val rowIsPending = pending?.let { sameRecording(saved, it.recording) } ?: (entry.state in PREPARING_STATES)
    // A row whose prepared torrent is the one that played belongs to that recording after all.
    val playedPrepared = entry.preparationId > 0 && played.parts.any { it.torrentId == entry.preparationId }
    if (saved.provider != "catalog" && (!rowIsPending || playedPrepared)) return saved
    return playedAudio(saved, played)
}

/**
 * The recording [entry] is getting ready: [pending]'s (with its prepared audio once ready), else, for a row without
 * a record, the row's book while preparing.
 */
fun preparingRecording(entry: ShelfEntry?, pending: PreparationRecord?): Audiobook? {
    if (entry == null || entry.state !in PREPARING_STATES) return null
    pending?.let { return it.ready ?: it.recording }
    return entry.book().takeIf { it.provider != "catalog" }
}
