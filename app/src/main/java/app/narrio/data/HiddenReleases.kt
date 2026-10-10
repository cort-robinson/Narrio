package app.narrio.data

import app.narrio.domain.*

/** Pure rules for "Not this book". */
object HiddenReleases {
    /** One key per release: its torrent hash, so every indexed copy and book-specific projection is hidden together. */
    fun key(recording: Audiobook): String = ProviderSourceSearch.identity(recording)

    fun hidden(recording: Audiobook, keys: Set<String>): Boolean = keys.isNotEmpty() &&
        (key(recording) in keys || recording.id in keys || recording.recordingId.takeIf(String::isNotBlank) in keys)

    /** Removes hidden releases from every section, so they can't be the best match, a version, or a possible match. */
    fun exclude(groups: List<SourceGroup>, keys: Set<String>): List<SourceGroup> = if (keys.isEmpty()) groups else groups.map { group ->
        group.copy(recordings = group.recordings.filterNot { hidden(it, keys) }, possible = group.possible.filterNot { hidden(it, keys) },
            alsoFoundBy = group.alsoFoundBy.filterKeys { id -> (group.recordings + group.possible).none { it.id == id && hidden(it, keys) } })
    }
}
