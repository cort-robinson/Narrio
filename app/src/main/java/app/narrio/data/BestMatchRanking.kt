package app.narrio.data

import app.narrio.domain.*

/**
 * Pure ranking over qualified recordings; possible matches require an explicit listener choice. The recording the
 * listener already uses ([listening] ids) stays the choice; another match is only ever offered beside it.
 */
object BestMatchRanking {
    fun choose(book: Audiobook, groups: List<SourceGroup>, onPhone: Set<String> = emptySet(), preferredFormat: String = "M4B",
               listening: Set<String> = emptySet()): BestMatch? {
        val candidates = groups.flatMap { group -> group.recordings.map { it to group.providerId } }
        fun carries(recording: Audiobook, ids: Set<String>) = recording.id in ids || recording.recordingId in ids ||
            recording.torrentHash.lowercase().takeIf(String::isNotBlank) in ids
        fun local(recording: Audiobook) = carries(recording, onPhone)
        fun current(recording: Audiobook) = listening.isNotEmpty() && carries(recording, listening)
        fun ready(recording: Audiobook) = local(recording) || SourceQuality.ready(recording)
        fun preferred(recording: Audiobook) = recording.sources.any {
            it.format.equals(preferredFormat.ifBlank { "M4B" }, true) &&
                (local(recording) || recording.provider == "archive" || !SourceQuality.ready(recording) || it.format in recording.cachedFormats)
        }
        fun language(recording: Audiobook): Boolean {
            val language = SourceQuality.edition(recording).language.ifBlank { recording.language.takeUnless(BookMetadata::unknown).orEmpty() }
            fun canonical(value: String) = when (value.lowercase()) { "en", "eng", "english" -> "english"; else -> value.lowercase() }
            return language.isNotBlank() && !BookMetadata.unknown(book.language) && canonical(language) == canonical(book.language)
        }
        fun unabridged(recording: Audiobook) = Regex("(?i)\\bunabridged\\b").containsMatchIn(recording.releaseTitle.ifBlank { recording.title })
        fun narrator(recording: Audiobook) = SourceQuality.edition(recording).narrator.isNotBlank() ||
            !recording.narratorFromCatalog && !BookMetadata.unknown(recording.narrator)
        // A verified collection projection is a strong match too: its selected filenames supplied identity.
        fun strong(recording: Audiobook) = recording.bookFilesSelected || SourceQuality.matches(book, recording)
        val pool = candidates.filter { current(it.first) || ready(it.first) }.ifEmpty { candidates }
        val chosen = pool.sortedWith(compareByDescending<Pair<Audiobook, String>> { current(it.first) }.thenByDescending { local(it.first) }
            .thenByDescending { ready(it.first) }.thenByDescending { strong(it.first) }
            .thenByDescending { preferred(it.first) }.thenByDescending { language(it.first) }
            .thenByDescending { unabridged(it.first) }.thenByDescending { narrator(it.first) }
            .thenByDescending { it.first.seeders }).firstOrNull() ?: return null
        val recording = chosen.first
        val reasons = buildList {
            if (current(recording)) add(BestMatchReason.LISTENING_NOW)
            if (local(recording)) add(BestMatchReason.ON_PHONE)
            if (ready(recording)) {
                add(BestMatchReason.READY_TO_STREAM)
                if (recording.provider == "archive") add(BestMatchReason.FREE_PUBLIC_RECORDING)
            } else add(BestMatchReason.NEEDS_PREPARING)
            if (strong(recording)) add(BestMatchReason.STRONG_MATCH)
            if (preferred(recording)) add(BestMatchReason.PREFERRED_FORMAT)
            if (language(recording)) add(BestMatchReason.LANGUAGE_MATCH)
            if (unabridged(recording)) add(BestMatchReason.UNABRIDGED)
            if (narrator(recording)) add(BestMatchReason.NARRATOR_KNOWN)
            if (recording.seeders > 0) add(BestMatchReason.WELL_SEEDED)
        }
        return BestMatch(recording, reasons, chosen.second)
    }
}
