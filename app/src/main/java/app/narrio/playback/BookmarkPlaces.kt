package app.narrio.playback

import app.narrio.AppGraph
import app.narrio.data.BookmarkEntry
import app.narrio.data.contentCursor
import app.narrio.domain.*

/*
 * One bookmark list for reading and listening. A bookmark stores the place where it was made and, when narration
 * confirms it exactly, the matching place in the other mode. A missing place is mapped whenever the list is shown,
 * so it improves as alignment does and keeps its "≈" while estimated. Audio-only rows from before ebooks keep working.
 */

/** A bookmark with its place in each mode; either may be absent when it can't be mapped. */
data class BookmarkPlace(
    val entry: BookmarkEntry,
    val text: ContentCursor?,
    val textConfidence: MappingConfidence,
    val audio: AudioCursor?,
    val audioConfidence: MappingConfidence,
) {
    val id: Long get() = entry.id
}

/** The stored listening place; text-only bookmarks keep blank source and part ids. */
fun BookmarkEntry.audioCursor(): AudioCursor? =
    if (sourceId.isBlank() || partId.isBlank()) null else AudioCursor(sourceId, partId, positionMs.coerceAtLeast(0))

/** Fills the place that wasn't stored. Stored places are exact by definition. */
suspend fun resolveBookmark(entry: BookmarkEntry, mapAudio: suspend (ContentCursor) -> MappedAudio?, mapText: suspend (AudioCursor) -> MappedText?): BookmarkPlace {
    val text = entry.contentCursor()
    val audio = entry.audioCursor()
    val mappedText = if (text == null && audio != null) mapText(audio)?.takeIf { it.confidence != MappingConfidence.UNMAPPED } else null
    val mappedAudio = if (audio == null && text != null) mapAudio(text)?.takeIf { it.confidence != MappingConfidence.UNMAPPED } else null
    return BookmarkPlace(entry,
        text ?: mappedText?.text, if (text != null) MappingConfidence.EXACT else mappedText?.confidence ?: MappingConfidence.UNMAPPED,
        audio ?: mappedAudio?.audio, if (audio != null) MappingConfidence.EXACT else mappedAudio?.confidence ?: MappingConfidence.UNMAPPED)
}

/**
 * The row for a new bookmark made at [text] (reading) or [audio] (listening). The other mode's place is stored
 * only when [mapped] is exact; an estimate is mapped again each time the list is shown.
 */
fun newBookmark(bookId: String, label: String, text: ContentCursor? = null, audio: AudioCursor? = null,
                mappedText: MappedText? = null, mappedAudio: MappedAudio? = null, now: Long = System.currentTimeMillis()): BookmarkEntry {
    val place = text ?: mappedText?.takeIf { it.confidence == MappingConfidence.EXACT }?.text
    val time = audio ?: mappedAudio?.takeIf { it.confidence == MappingConfidence.EXACT }?.audio
    require(place != null || time != null) { "A bookmark needs a place." }
    return BookmarkEntry(bookId = bookId, sourceId = time?.sourceId.orEmpty(), partId = time?.partId.orEmpty(), positionMs = time?.positionMs ?: 0,
        label = label, createdAt = now, editionId = place?.editionId, resource = place?.resource, offset = place?.offset,
        normalizationVersion = place?.normalizationVersion, progression = place?.progression, locatorJson = place?.locatorJson?.takeIf { it.isNotEmpty() })
}

/** Reading-mode order: by place in the edition, then unplaced rows by time. */
fun List<BookmarkPlace>.inReadingOrder(position: (ContentCursor) -> Long?): List<BookmarkPlace> =
    sortedWith(compareBy<BookmarkPlace>({ it.text?.let(position) == null }, { it.text?.let(position) ?: 0L }, { -it.entry.createdAt }))

/** Listening order: by part, then time; rows without a listening place follow by text progression. */
fun List<BookmarkPlace>.inListeningOrder(parts: List<AudioPart>): List<BookmarkPlace> =
    sortedWith(compareBy<BookmarkPlace>({ it.audio == null }, { it.audio?.let { audio -> resumeIndex(parts, audio.partId) } ?: 0 },
        { it.audio?.positionMs ?: 0L }, { it.text?.progression ?: 0.0 }, { -it.entry.createdAt }))

/** Maps one book's places with the sync engine, never across a recording that doesn't match the edition. */
class BookmarkMapping(private val graph: AppGraph, private val bookId: String) {
    private val snapshots = HashMap<String, MappingSnapshot?>()

    private suspend fun snapshot(sourceId: String): MappingSnapshot? = snapshots.getOrPut(sourceId) {
        graph.mappingRepository.snapshot(bookId, sourceId)?.takeIf { graph.readingSync.pairing(bookId, it) != PairingStatus.MISMATCH }
    }

    /** The recording reading is mapped to: the one last listened to. */
    suspend fun recordingId(): String? = graph.sharedPositions.current(bookId)?.audio?.sourceId
        ?: graph.library.find(bookId)?.let { it.source()?.id ?: it.book().sources.firstOrNull { source -> source.parts.isNotEmpty() }?.id }

    suspend fun audioFor(cursor: ContentCursor, sourceId: String? = null): MappedAudio? =
        (sourceId ?: recordingId())?.let { snapshot(it) }?.let { MappingEngine.audioFor(it, cursor) }

    suspend fun textFor(audio: AudioCursor): MappedText? = snapshot(audio.sourceId)?.let { MappingEngine.textFor(it, audio) }

    suspend fun resolve(entries: List<BookmarkEntry>): List<BookmarkPlace> = entries.map { resolveBookmark(it, { cursor -> audioFor(cursor) }, ::textFor) }
}
