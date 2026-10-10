package app.narrio.ui

import app.narrio.data.BookMetadata
import app.narrio.data.OfflineBook
import app.narrio.data.PreparationRecord
import app.narrio.data.playedRecording
import app.narrio.data.preparingRecording
import app.narrio.data.sameRecording
import app.narrio.data.ShelfEntry
import app.narrio.data.SourceQuality
import app.narrio.domain.*

/*
 * One book page, whichever way the listener arrives: which recording is theirs, what the primary action says, and
 * what the recording chooser lists. Pure functions, so "Resume never silently swaps the recording" is tested
 * without a device.
 */

/** The book a saved recording belongs to, as recording discovery looks for it: its id, title, author, and details. */
fun Audiobook.catalogIdentity(): Audiobook = if (provider == "catalog") this else Audiobook(
    id, title, author, narrator = if (narratorFromCatalog) narrator else "Narrator not listed", language = language, description = description,
    durationMs = durationMs, coverUrl = coverUrl, subjects = subjects, provider = "catalog", detailsLoaded = true,
    metadataSource = metadataSource, metadataUrl = metadataUrl, metadataUpdatedAtMs = metadataUpdatedAtMs, narratorFromCatalog = narratorFromCatalog,
)

/**
 * The recording a listener already uses for a book. [source] is the audio last played and [place] where it stopped;
 * both are null when the recording was saved or prepared but never played. [offline] is a finished phone copy that
 * Listen plays: the played audio itself, or any copy of a recording not started yet. [preparing] means TorBox is
 * still getting this recording ready.
 */
data class CurrentRecording(
    val recording: Audiobook, val source: AudioSource?, val place: AudioCursor?, val offline: OfflineBook?, val preparing: Boolean,
) {
    val started: Boolean get() = place != null && (place.positionMs > 0 || source?.parts?.firstOrNull()?.id.let { it != null && it != place.partId })
    /** Whole-book progress, when every part's length is known. */
    val fraction: Float? get() = place?.let { listeningPlace(it, source).fraction }
}

/**
 * [opened] is the book on screen; [entry] its shelf row; [listening] the recording its played audio belongs to; and
 * [pending] the one TorBox is getting ready. A book never played or saved with a recording has none, and keeps the
 * best-match behaviour.
 */
fun currentRecording(opened: Audiobook, entry: ShelfEntry?, listening: Audiobook?, downloads: List<OfflineBook>, pending: PreparationRecord? = null): CurrentRecording? {
    val saved = entry?.book()
    val played = entry?.source()
    val base = when {
        played != null -> playedRecording(entry, listening, pending)!!
        saved != null && saved.provider != "catalog" -> saved
        opened.provider != "catalog" && opened.sources.isNotEmpty() -> opened
        else -> return null
    }
    // The page's own copy is fresher (checked cache, prepared formats) when it is the same recording.
    val recording = opened.takeIf { it.provider != "catalog" && sameRecording(it, base) }?.let { if (it.sources.isEmpty()) it.copy(sources = base.sources) else it } ?: base
    val copies = downloads.filter { it.complete && it.book.id == opened.id && sameRecording(it.book, recording) }
    val offline = if (played != null) copies.firstOrNull { it.source.id == played.id } else copies.firstOrNull { it.source.format == "M4B" } ?: copies.firstOrNull()
    val preparing = entry?.state == "preparing" && preparingRecording(entry, pending)?.let { sameRecording(it, recording) } == true
    return CurrentRecording(recording, played, played?.let { AudioCursor(it.id, entry.partId, entry.positionMs) }, offline, preparing)
}

/**
 * [current] with what a fresh search says about the same release: whether it's ready now and its formats. The played
 * audio and its place stay with [CurrentRecording.source]; only an explicit pick plays another of these.
 */
fun refreshed(current: CurrentRecording, search: SourceSearchState?): CurrentRecording {
    val fresh = search?.results?.firstOrNull { sameRecording(it, current.recording) } ?: return current
    val recording = current.recording
    return current.copy(recording = recording.copy(cacheState = fresh.cacheState, cachedFormats = fresh.cachedFormats, seeders = fresh.seeders,
        filesVerified = recording.filesVerified || fresh.filesVerified, releaseSizeBytes = fresh.releaseSizeBytes.takeIf { it > 0 } ?: recording.releaseSizeBytes,
        sources = (fresh.sources + recording.sources).distinctBy { it.format }))
}

/**
 * The phone copy the page's offline row stands for: a download of [target] still saving, else the copy Listen plays,
 * else any finished copy of it. Other downloads of the book keep their own cards.
 */
fun offlineDownload(target: Audiobook, current: CurrentRecording?, downloads: List<OfflineBook>): OfflineBook? {
    val mine = downloads.filter { sameRecording(it.book, target) }
    return mine.firstOrNull { !it.complete } ?: current?.offline?.takeIf { sameRecording(current.recording, target) } ?: mine.firstOrNull { it.complete }
}

/** The recording TorBox is getting ready for this book when it isn't the one being listened to. */
fun pendingRecording(entry: ShelfEntry?, current: CurrentRecording?, pending: PreparationRecord? = null): Audiobook? =
    preparingRecording(entry, pending)?.takeIf { current == null || !sameRecording(it, current.recording) }

/** Whole-book time left from [cursor], when every part's length is known (the player's own [bookTime]). */
fun timeLeftMs(cursor: AudioCursor, source: AudioSource?): Long? =
    source?.parts?.let { parts -> bookTime(parts, resumeIndex(parts, cursor.partId), cursor.positionMs)?.remainingMs }

/** The time left on Resume, in the player's words: "9 h 41 m left", "1 m left". */
fun resumeTimeLeft(ms: Long): String = "${timeLeftLabel(ms)} left"

/** The primary action on the listener's own recording: "Resume · 9 h 41 m left", "Play offline", or "Listen". */
fun resumeLabel(current: CurrentRecording): String {
    val left = current.place?.takeIf { current.started }?.let { timeLeftMs(it, current.source) }?.let(::resumeTimeLeft)
    return when {
        current.offline != null -> listOfNotNull("Play offline", left).joinToString(" · ")
        current.started -> listOfNotNull("Resume", left).joinToString(" · ")
        else -> "Listen"
    }
}

/** True when this recording can play right now: a phone copy, a free public recording, or ready in TorBox. */
fun playableNow(recording: Audiobook, onPhone: Boolean): Boolean = onPhone || SourceQuality.ready(recording)

/** How a recording plays, in the listener's words. */
fun readiness(recording: Audiobook, connected: Boolean, onPhone: Boolean = false): String = when {
    onPhone || onThisPhone(recording) -> "On this phone"
    recording.provider == "archive" -> "Free public recording"
    !connected -> "Needs TorBox"
    SourceQuality.ready(recording) -> "Ready now"
    else -> "Needs time to get ready"
}

/** The narrator this recording names, or blank when it doesn't name one. A catalog's narrator doesn't count. */
fun recordingNarrator(recording: Audiobook): String = SourceQuality.edition(recording).narrator.ifBlank {
    recording.narrator.takeUnless { recording.narratorFromCatalog || BookMetadata.unknown(it) || it.startsWith("Narrator not") || it.contains("depends on source") }.orEmpty()
}

/** Unabridged, Abridged, or Dramatized, and the language when it isn't the book's. Unabridged only when the release says so. */
fun recordingKind(recording: Audiobook, book: Audiobook): List<String> {
    val edition = SourceQuality.edition(recording)
    val kind = edition.kind.ifBlank { if (Regex("(?i)\\bunabridged\\b").containsMatchIn(recording.releaseTitle.ifBlank { recording.title })) "Unabridged" else "" }
    val language = edition.language.takeUnless { it.isBlank() || it.equals(book.language, true) || BookMetadata.unknown(book.language) }.orEmpty()
    return listOf(kind, language).filter(String::isNotBlank)
}

/** The one line under Listen: "Ray Porter · Unabridged · Ready now". */
fun recordingLine(recording: Audiobook, book: Audiobook, connected: Boolean, onPhone: Boolean = false): String =
    (listOf(recordingNarrator(recording).ifBlank { "Narrator not confirmed" }) + recordingKind(recording, book) + readiness(recording, connected, onPhone)).joinToString(" · ")

/** True when this recording plays through TorBox, so it needs a connected account. */
fun needsTorBox(recording: Audiobook): Boolean = recording.provider != "archive" && !onThisPhone(recording)

/** Audio files added from this phone: they play offline and never need TorBox or a download. */
fun onThisPhone(recording: Audiobook): Boolean = recording.provider == app.narrio.data.LocalAudio.PROVIDER

/**
 * The chooser's rows: the listener's recording first, then the distinct versions found (or every match when
 * [showAll]), never listing the listener's recording twice.
 */
fun chooserRows(current: Audiobook?, search: SourceSearchState?, showAll: Boolean): List<Audiobook> {
    val found = if (search == null) emptyList() else if (showAll || search.versions.isEmpty()) search.results else search.versions
    return listOfNotNull(current) + found.filter { current == null || !sameRecording(it, current) }
}

/** How many rows "Show all" would add. */
fun hiddenMatches(current: Audiobook?, search: SourceSearchState?): Int =
    chooserRows(current, search, showAll = true).size - chooserRows(current, search, showAll = false).size

/** Formats [recording] can play in right now; the chooser asks only when there are several. */
fun readyFormats(recording: Audiobook, phoneFormats: List<String> = emptyList()): List<String> =
    recording.sources.map { it.format }.filter { it in phoneFormats || recording.provider == "archive" || it in recording.cachedFormats }.distinct()

/** The format a recording plays in unless the listener picks: the remembered one, then a ready M4B, then any ready one. */
fun defaultFormat(recording: Audiobook, remembered: String, phoneFormats: List<String> = emptyList()): String? {
    val ready = readyFormats(recording, phoneFormats)
    val offered = recording.sources.map { it.format }
    return remembered.takeIf { it in ready } ?: ready.firstOrNull { it == "M4B" } ?: ready.firstOrNull()
        ?: remembered.takeIf { it in offered } ?: offered.firstOrNull { it == "M4B" } ?: offered.firstOrNull()
}

/** Bytes the phone needs for [recording] in [format]: its files, else the release's size. */
fun downloadBytes(recording: Audiobook, format: String?): Long =
    recording.sources.firstOrNull { it.format == format }?.parts?.sumOf { it.sizeBytes }?.takeIf { it > 0 } ?: recording.releaseSizeBytes

/**
 * Switching from the recording with a saved place to another: [place] is where the listener is in [from]. The old
 * place stays saved with its recording; the new one resumes [resumesAt], its own saved place, else starts at its
 * beginning unless a start choice says otherwise.
 */
data class RecordingSwitch(val from: Audiobook, val place: PlaceSummary?, val to: Audiobook, val resumesAt: PlaceSummary? = null)

/** What switching does to each place: the new recording resumes its own saved place when it has one. */
fun switchNotice(switch: RecordingSwitch): String {
    val narrator = recordingNarrator(switch.from)
    val yours = if (narrator.isBlank()) "your current recording" else "$narrator's recording"
    val start = switch.resumesAt?.let { "This one picks up where you left it, at ${it.label}." } ?: "This one starts from the beginning."
    return "$start Your place in $yours stays saved."
}
