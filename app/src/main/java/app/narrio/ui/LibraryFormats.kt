package app.narrio.ui

import app.narrio.domain.*
import kotlin.math.floor

/**
 * What one book offers on this phone: a recording, ebook editions, the shared place, and how well the active
 * edition follows the narration. Shelf rows, filters, Continue, and the details actions all read this one value.
 */
data class BookFormats(
    val bookId: String,
    val audio: Boolean = false,
    val editions: List<EbookEdition> = emptyList(),
    val pairing: PairingStatus = PairingStatus.UNCHECKED,
    val position: SharedPosition? = null,
    /** 1-based chapter of [SharedPosition.text] in the active edition, when its chapters are known. */
    val textChapter: Int? = null,
    /** The recording [SharedPosition.audio] belongs to, for part counts and whole-book progress. */
    val audioSource: AudioSource? = null,
) {
    val ebook: Boolean get() = editions.isNotEmpty()
    val activeEdition: EbookEdition? get() = editions.firstOrNull { it.active } ?: editions.firstOrNull()

    /** The mode that last moved the shared place, while that format is still here. Continue reopens it. */
    val lastMode: PositionOrigin? get() = position?.origin?.takeIf { available(it) }

    /** Details lead with the last mode, otherwise listening when a recording exists. */
    val leadingMode: PositionOrigin? get() = lastMode ?: when { audio -> PositionOrigin.LISTENING; ebook -> PositionOrigin.READING; else -> null }

    fun available(mode: PositionOrigin) = if (mode == PositionOrigin.READING) ebook else audio
}

/** Shelf filters. Audiobooks and Ebooks include books that have both; Both narrows to the pairs. */
enum class ShelfFilter(val label: String) {
    ALL("All"), AUDIOBOOKS("Audiobooks"), EBOOKS("Ebooks"), BOTH("Both");

    fun matches(formats: BookFormats): Boolean = when (this) {
        ALL -> true
        AUDIOBOOKS -> formats.audio
        EBOOKS -> formats.ebook
        BOTH -> formats.audio && formats.ebook
    }
}

/** A place in one mode, ready to show: "Ch 12 · 43%" or "Part 3 of 12 · 43%". [fraction] is null when unknown. */
data class PlaceSummary(val mode: PositionOrigin, val label: String, val fraction: Float?, val confidence: MappingConfidence = MappingConfidence.EXACT)

/** Where the shared place stands in the mode that last moved it. */
fun placeSummary(formats: BookFormats): PlaceSummary? {
    val position = formats.position ?: return null
    return when (position.origin) {
        PositionOrigin.READING -> position.text?.let { readingPlace(it, formats.textChapter) }
        PositionOrigin.LISTENING -> position.audio?.let { listeningPlace(it, formats.audioSource) }
    }
}

/** The same place in the other mode, when sync has mapped it. Estimated places carry their confidence for "≈". */
fun counterpartPlace(formats: BookFormats): PlaceSummary? {
    val position = formats.position ?: return null
    if (formats.pairing == PairingStatus.MISMATCH) return null
    return when (position.origin) {
        PositionOrigin.READING -> position.audio?.takeIf { formats.audio && position.audioConfidence != MappingConfidence.UNMAPPED }
            ?.let { listeningPlace(it, formats.audioSource).copy(confidence = position.audioConfidence) }
        PositionOrigin.LISTENING -> position.text?.takeIf { formats.ebook && position.textConfidence != MappingConfidence.UNMAPPED }
            ?.let { readingPlace(it, formats.textChapter).copy(confidence = position.textConfidence) }
    }
}

fun readingPlace(cursor: ContentCursor, chapter: Int?): PlaceSummary {
    val fraction = cursor.progression.coerceIn(0.0, 1.0).toFloat()
    return PlaceSummary(PositionOrigin.READING, listOfNotNull(chapter?.let { "Ch $it" }, "${percent(fraction)}%").joinToString(" · "), fraction)
}

/** Whole-book progress only when every part reports its length; otherwise the time into the part, never a guess. */
fun listeningPlace(cursor: AudioCursor, source: AudioSource?): PlaceSummary {
    val parts = source?.parts.orEmpty()
    val index = resumeIndex(parts, cursor.partId)
    val fraction = parts.takeIf { it.isNotEmpty() && it.all { part -> part.durationMs > 0 } }?.let { known ->
        ((known.take(index).sumOf { it.durationMs } + cursor.positionMs).toFloat() / known.sumOf { it.durationMs }).coerceIn(0f, 1f)
    }
    val time = formatTime(cursor.positionMs)
    val label = when {
        parts.size > 1 && fraction != null -> "Part ${index + 1} of ${parts.size} · ${percent(fraction)}%"
        parts.size > 1 -> "Part ${index + 1} of ${parts.size} · $time in"
        fraction != null -> "$time · ${percent(fraction)}%"
        else -> "$time in"
    }
    return PlaceSummary(PositionOrigin.LISTENING, label, fraction)
}

/** A started book never reads as 0%, and an unfinished one never as 100%. */
private fun percent(fraction: Float): Int = when {
    fraction <= 0f -> 0
    fraction >= 1f -> 100
    else -> floor(fraction * 100).toInt().coerceIn(1, 99)
}

/** Screen readers hear "Chapter 12" rather than "C H 12". */
fun spokenPlace(label: String): String = label.replace(Regex("\\bCh (\\d)"), "Chapter $1").replace(" · ", ", ")

/** How well the active edition follows the recording, in the listener's terms. Null when there's no pair to judge. */
data class PairingCopy(val title: String, val detail: String)

fun pairingCopy(formats: BookFormats): PairingCopy? {
    if (!formats.audio || !formats.ebook) return null
    return when (formats.pairing) {
        PairingStatus.MATCHES -> PairingCopy("Matches the narration", "Reading and listening share one place.")
        PairingStatus.PARTIAL -> PairingCopy("Partly matches the narration", "Some passages differ from the recording, so switching modes may land a little early or late.")
        PairingStatus.MISMATCH -> PairingCopy("Doesn't match the narration", "Reading and listening keep separate places. Choose another edition to share one.")
        PairingStatus.UNCHECKED -> PairingCopy("Not checked against the narration yet", "Narrio compares this edition with the recording while you listen.")
    }
}

/** Copy for a filter that matches nothing on a non-empty shelf. */
fun emptyFilterCopy(filter: ShelfFilter): Pair<String, String> = when (filter) {
    ShelfFilter.ALL -> "Nothing saved yet" to "Save a book or start listening. Your books, progress, and bookmarks stay here on this device."
    ShelfFilter.AUDIOBOOKS -> "No audiobooks here yet" to "Books you listen to, or save from a recording, appear here."
    ShelfFilter.EBOOKS -> "No ebooks here yet" to "Add an EPUB or text file from this phone, or use Find ebook on a book's page."
    ShelfFilter.BOTH -> "No books with both formats yet" to "Find an ebook for an audiobook, or a recording for an ebook, to read and listen from one place."
}
