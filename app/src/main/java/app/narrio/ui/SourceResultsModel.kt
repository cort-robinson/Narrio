package app.narrio.ui

import app.narrio.data.BookMetadata
import app.narrio.data.SourceQuality
import app.narrio.domain.*

/*
 * What book details say about a per-source search: the best match the listener sees, what each reason means in
 * plain words, and how the whole search turned out. Pure functions, so the wording and the "never swap under the
 * listener's finger" rule are tested without a device.
 */

/**
 * The best match on screen and a better one waiting to be shown. A new best replaces the shown one only before the
 * listener has touched the page; afterwards it waits in [pending] behind a quiet "Better match found".
 */
data class PinnedBest(val shown: BestMatch? = null, val pending: BestMatch? = null) {
    fun next(latest: BestMatch?, interacted: Boolean, stillListed: (String) -> Boolean): PinnedBest {
        val current = shown?.takeIf { stillListed(it.recording.id) }
        return when {
            latest == null -> PinnedBest(current, null)
            current == null -> PinnedBest(latest, null)
            // The same release can gain detail (a finished cache check); that never moves anything.
            latest.recording.id == current.recording.id -> PinnedBest(latest, null)
            !interacted -> PinnedBest(latest, null)
            else -> PinnedBest(current, latest)
        }
    }

    fun accept(): PinnedBest = if (pending == null) this else PinnedBest(pending, null)
}

/** The audio format the best match would play: the preferred one when offered, otherwise the first. */
fun bestFormat(recording: Audiobook, preferred: String = "M4B"): String? =
    recording.sources.firstOrNull { it.format.equals(preferred, true) }?.format ?: recording.sources.firstOrNull()?.format

/**
 * The headline names how it plays, the format, and the narrator ("Ready to stream · M4B · Read by Ray Porter");
 * [BestMatchCopy.detail] adds the quieter reasons.
 */
data class BestMatchCopy(val headline: String, val detail: String)

fun bestMatchCopy(best: BestMatch, book: Audiobook): BestMatchCopy {
    val recording = best.recording
    val reasons = best.reasons.toSet()
    val narrator = SourceQuality.edition(recording).narrator.ifBlank { recording.narrator.takeUnless { BookMetadata.unknown(it) || it.contains("depends on source") }.orEmpty() }
    val availability = when {
        BestMatchReason.ON_PHONE in reasons -> "On this phone"
        // A public recording is also ready to stream; "free" is the more useful thing to say.
        BestMatchReason.FREE_PUBLIC_RECORDING in reasons -> "Free public recording"
        BestMatchReason.READY_TO_STREAM in reasons -> "Ready to stream"
        BestMatchReason.NEEDS_PREPARING in reasons -> "Needs preparing in TorBox"
        else -> availabilityLabel(recording)
    }
    val headline = listOfNotNull(availability, bestFormat(recording),
        narrator.takeIf { it.isNotBlank() && (BestMatchReason.NARRATOR_KNOWN in reasons || reasons.isEmpty()) }?.let { "Read by $it" })
    val language = SourceQuality.edition(recording).language.ifBlank { book.language.takeUnless(BookMetadata::unknown).orEmpty() }
    val detail = buildList {
        if (BestMatchReason.STRONG_MATCH in reasons) add("Matches this title and author")
        if (BestMatchReason.UNABRIDGED in reasons) add("Unabridged")
        if (BestMatchReason.LANGUAGE_MATCH in reasons && language.isNotBlank()) add(language)
        // Seeders only matter while TorBox still has to fetch the release.
        if (BestMatchReason.WELL_SEEDED in reasons && BestMatchReason.NEEDS_PREPARING in reasons && recording.seeders > 0) add("${recording.seeders} ${if (recording.seeders == 1L) "seeder" else "seeders"}")
    }
    return BestMatchCopy(headline.joinToString(" · "), detail.joinToString(" · "))
}

/** True when listening needs TorBox to prepare the release first. */
fun needsPreparing(best: BestMatch): Boolean = BestMatchReason.NEEDS_PREPARING in best.reasons ||
    best.reasons.none { it == BestMatchReason.ON_PHONE || it == BestMatchReason.READY_TO_STREAM || it == BestMatchReason.FREE_PUBLIC_RECORDING } && !SourceQuality.ready(best.recording)

/** Why a section was skipped, in the listener's terms. */
fun skippedReason(group: SourceGroup, provider: SourceProvider?, connected: Boolean): String = when {
    provider?.enabled == false || group.message.equals("Disabled", true) -> "Off in settings"
    provider?.requiresTorBox == true && !connected || group.message.orEmpty().contains("TorBox", true) -> "Needs TorBox"
    !group.message.isNullOrBlank() -> group.message
    else -> "Skipped"
}

/**
 * Sections before the engine's first snapshot arrives, or when the lookup itself threw: every source is
 * searching (or skipped, with why), and a thrown lookup fails each active source with its message.
 */
fun pendingSourceSearch(book: Audiobook, providers: List<SourceProvider>, connected: Boolean, searching: Boolean, error: String?): StreamedSourceSearch {
    val failed = !searching && error != null
    return StreamedSourceSearch(book, providers.sortedBy { it.order }.map { provider ->
        val skipped = !provider.enabled || provider.requiresTorBox && !connected
        SourceGroup(provider.id, provider.name, when { skipped -> SourceGroupStatus.SKIPPED; failed -> SourceGroupStatus.FAILED; else -> SourceGroupStatus.SEARCHING },
            // Skipped sections carry the engine's own reasons, so they read the same before and after its first snapshot.
            message = when { !provider.enabled -> "Disabled"; skipped -> "Connect TorBox"; failed -> error; else -> null })
    }, complete = failed)
}

/** Totals across sections; a release found by several providers counts once. */
data class SearchTally(val active: Int, val answered: Int, val failed: Int, val needTorBox: Int, val off: Int, val found: Int, val possible: Int, val names: List<String>)

fun tally(search: StreamedSourceSearch, providers: Map<String, SourceProvider>, connected: Boolean): SearchTally {
    val active = search.groups.filter { it.status != SourceGroupStatus.SKIPPED }
    val skipped = search.groups.filter { it.status == SourceGroupStatus.SKIPPED }
    return SearchTally(
        active = active.size,
        answered = active.count { it.status == SourceGroupStatus.DONE || it.status == SourceGroupStatus.FAILED },
        failed = active.count { it.status == SourceGroupStatus.FAILED },
        needTorBox = skipped.count { skippedReason(it, providers[it.providerId], connected) == "Needs TorBox" },
        off = skipped.count { skippedReason(it, providers[it.providerId], connected) == "Off in settings" },
        found = search.groups.sumOf { it.recordings.size },
        possible = search.groups.sumOf { it.possible.size },
        names = active.map { it.name },
    )
}

/** One line under "Listening sources": how far the search has got and what it found. */
fun searchSummary(search: StreamedSourceSearch, tally: SearchTally): String {
    val found = "${tally.found} found"
    val torbox = if (tally.needTorBox > 0) " · ${tally.needTorBox} need TorBox" else ""
    return when {
        tally.active == 0 -> "No sources are searching$torbox"
        !search.complete -> "${tally.answered} of ${tally.active} ${plural(tally.active, "source")} answered · $found$torbox"
        tally.failed == tally.active -> "${tally.failed} of ${tally.active} ${plural(tally.active, "source")} couldn't be checked$torbox"
        tally.failed > 0 -> "$found · ${tally.failed} of ${tally.active} ${plural(tally.active, "source")} couldn't be checked$torbox"
        tally.active == 1 -> "1 source searched · $found$torbox"
        else -> "All ${tally.active} sources answered · $found$torbox"
    }
}

/** What the best-match slot says when there is no best match to offer. */
enum class NoMatchKind { SEARCHING, POSSIBLE_ONLY, ALL_FAILED, PARTLY_FAILED, NEEDS_TORBOX, NOTHING_FOUND, ALL_OFF }

data class NoMatchCopy(val kind: NoMatchKind, val title: String, val message: String)

fun noMatchCopy(search: StreamedSourceSearch, tally: SearchTally): NoMatchCopy {
    val searched = sourceList(tally.names)
    return when {
        tally.active == 0 && tally.needTorBox > 0 -> NoMatchCopy(NoMatchKind.NEEDS_TORBOX, "Connect TorBox to search",
            "Your audiobook sources need TorBox. Connect it to look for this book in ${tally.needTorBox} ${plural(tally.needTorBox, "source")}.")
        tally.active == 0 -> NoMatchCopy(NoMatchKind.ALL_OFF, "Every audiobook source is off", "Turn one on in Settings → Sources & add-ons to look for a recording.")
        !search.complete -> NoMatchCopy(NoMatchKind.SEARCHING, "Finding the best recording",
            "Checking ${tally.active} ${plural(tally.active, "source")} for this title and author.")
        tally.possible > 0 && tally.found == 0 -> NoMatchCopy(NoMatchKind.POSSIBLE_ONLY, "No sure match",
            "Nothing matched this title and author closely enough to choose for you. Check the release name and narrator of the possible ${plural(tally.possible, "match", "matches")} below.")
        tally.failed == tally.active -> NoMatchCopy(NoMatchKind.ALL_FAILED, "Couldn't reach any source",
            if (tally.active == 1) "$searched didn't answer. Check your connection, then try again."
            else "None of your ${tally.active} sources answered. Check your connection, then try again.")
        tally.failed > 0 -> NoMatchCopy(NoMatchKind.PARTLY_FAILED, "No recording found yet",
            "${tally.failed} of ${tally.active} sources couldn't be checked. Retry ${if (tally.failed == 1) "it" else "them"} below, or search again.")
        tally.needTorBox > 0 -> NoMatchCopy(NoMatchKind.NEEDS_TORBOX, "No free public recording found",
            "$searched ${if (tally.active == 1) "has" else "have"} no recording of this book. Connect TorBox to also search ${tally.needTorBox} more ${plural(tally.needTorBox, "source")}.")
        else -> NoMatchCopy(NoMatchKind.NOTHING_FOUND, "No recording found", "Searched $searched. Nothing matched this title and author.")
    }
}

/** "LibriVox", "LibriVox and TorBox search", "LibriVox, TorBox search, and 2 more". */
internal fun sourceList(names: List<String>): String = when (names.size) {
    0 -> "no sources"
    1 -> names[0]
    2 -> "${names[0]} and ${names[1]}"
    3 -> "${names[0]}, ${names[1]}, and ${names[2]}"
    else -> "${names[0]}, ${names[1]}, and ${names.size - 2} more"
}

internal fun plural(count: Int, one: String, many: String = one + "s") = if (count == 1) one else many

/** A section's status in a few words: "Searching", "4 found", "Timed out". */
fun groupStatusLabel(group: SourceGroup, provider: SourceProvider?, connected: Boolean): String {
    val count = group.recordings.size + group.possible.size
    return when (group.status) {
        SourceGroupStatus.WAITING -> "Waiting its turn"
        SourceGroupStatus.SEARCHING -> if (count > 0) "Searching · $count found" else "Searching"
        SourceGroupStatus.CHECKING -> if (count > 0) "Checking TorBox · $count found" else "Checking TorBox"
        SourceGroupStatus.DONE -> when {
            group.recordings.isNotEmpty() -> "${group.recordings.size} found"
            group.possible.isNotEmpty() -> "${group.possible.size} possible"
            else -> "Nothing for this book"
        }
        SourceGroupStatus.FAILED -> if (group.message.orEmpty().contains("timed out", true)) "Timed out" else "Couldn't search"
        SourceGroupStatus.SKIPPED -> skippedReason(group, provider, connected)
    }
}

/** Milestones worth announcing to a screen reader: the first best match and the end of the search, not every section. */
fun searchAnnouncement(search: StreamedSourceSearch, shown: BestMatch?, tally: SearchTally, book: Audiobook): String = when {
    search.complete && shown != null -> "Search finished. ${tally.found} found. Best match: ${bestMatchCopy(shown, book).headline}."
    search.complete -> "Search finished. ${noMatchCopy(search, tally).title}."
    shown != null -> "Best match found: ${bestMatchCopy(shown, book).headline}. Still checking."
    else -> "Searching ${tally.active} ${plural(tally.active, "source")}."
}
