package app.narrio.ui

import app.narrio.data.BookMetadata
import app.narrio.data.DeviceSourceProviderSettings
import app.narrio.domain.*

/*
 * What the ebook sheet says about a per-source ebook search, worded like listening sources: the best ebook and why,
 * how far the search has got, and what to do when nothing turned up. Pure functions, tested without a device.
 */

typealias PinnedEbook = Pinned<BestEbook>

fun PinnedEbook.next(latest: BestEbook?, interacted: Boolean, stillListed: (String) -> Boolean): PinnedEbook =
    next(latest, interacted, { it.edition.id }, stillListed)

/** "Free public-domain ebook · EPUB", then the quieter reasons. */
fun bestEbookCopy(best: BestEbook, book: Audiobook): BestMatchCopy {
    val reasons = best.reasons.toSet()
    val availability = when {
        EbookMatchReason.WITH_RECORDING in reasons -> "Comes with this recording"
        EbookMatchReason.IN_YOUR_TORBOX in reasons -> "In your TorBox"
        EbookMatchReason.CACHED_IN_TORBOX in reasons -> "Ready in TorBox"
        EbookMatchReason.PUBLIC_DOMAIN in reasons -> "Free public-domain ebook"
        else -> null
    }
    val detail = buildList {
        if (EbookMatchReason.WITH_RECORDING in reasons) add("Most likely the narrated edition")
        if (EbookMatchReason.STRONG_MATCH in reasons) add("Matches this title and author")
        if (EbookMatchReason.LANGUAGE_MATCH in reasons && !BookMetadata.unknown(book.language)) add(book.language)
    }
    return BestMatchCopy(listOfNotNull(availability, best.edition.format.ifBlank { null }).joinToString(" · "), detail.joinToString(" · "))
}

/**
 * Sections before the first snapshot arrives, or when the lookup itself threw: every enabled source is searching
 * (or failed with that error); skipped sources say why, in the engine's own words.
 */
fun pendingEbookSearch(book: Audiobook, providers: List<SourceProvider>, connected: Boolean, recording: Boolean, searching: Boolean, error: String?): StreamedEbookSearch {
    val failed = !searching && error != null
    return StreamedEbookSearch(book, providers.sortedBy { it.order }.map { provider ->
        val reason = when {
            !provider.enabled -> "Disabled"
            provider.requiresTorBox && !connected -> "Connect TorBox"
            provider.id == DeviceSourceProviderSettings.RECORDING_FILES && !recording -> "No recording yet"
            else -> null
        }
        EbookGroup(provider.id, provider.name, when { reason != null -> SourceGroupStatus.SKIPPED; failed -> SourceGroupStatus.FAILED; else -> SourceGroupStatus.SEARCHING },
            message = reason ?: error.takeIf { failed })
    }, complete = failed)
}

/** Totals across ebook sections; an ebook found by several sources counts once. */
fun ebookTally(search: StreamedEbookSearch, providers: Map<String, SourceProvider>, connected: Boolean): SearchTally {
    val active = search.groups.filter { it.status != SourceGroupStatus.SKIPPED }
    val skipped = search.groups.filter { it.status == SourceGroupStatus.SKIPPED }.map { skippedReason(it.message, providers[it.providerId], connected) }
    return SearchTally(
        active = active.size,
        answered = active.count { it.status == SourceGroupStatus.DONE || it.status == SourceGroupStatus.FAILED },
        failed = active.count { it.status == SourceGroupStatus.FAILED },
        needTorBox = skipped.count { it == "Needs TorBox" },
        off = skipped.count { it == "Off in settings" },
        found = search.groups.sumOf { it.editions.size },
        possible = search.groups.sumOf { it.possible.size },
        names = active.map { it.name },
    )
}

fun ebookGroupStatusLabel(group: EbookGroup, provider: SourceProvider?, connected: Boolean): String =
    groupStatusLabel(group.status, group.editions.size, group.possible.size, group.message, provider, connected)

/** What the best-match slot says when there's no ebook to offer. */
fun ebookNoMatchCopy(search: StreamedEbookSearch, tally: SearchTally): NoMatchCopy {
    val searched = sourceList(tally.names)
    return when {
        tally.active == 0 && tally.needTorBox > 0 && tally.off == 0 -> NoMatchCopy(NoMatchKind.NEEDS_TORBOX, "Connect TorBox to search",
            "Your ebook sources need TorBox. Connect it to look for this book in ${tally.needTorBox} ${plural(tally.needTorBox, "source")}.")
        tally.active == 0 -> NoMatchCopy(NoMatchKind.ALL_OFF, "No ebook source is searching", "Turn one on in Settings → Sources & add-ons, or add your own file below.")
        !search.complete -> NoMatchCopy(NoMatchKind.SEARCHING, "Finding the best ebook",
            "Checking ${tally.active} ${plural(tally.active, "source")} for this title and author.")
        tally.possible > 0 && tally.found == 0 -> NoMatchCopy(NoMatchKind.POSSIBLE_ONLY, "No sure match",
            "Nothing named this title and author closely enough to choose for you. Check the possible ${plural(tally.possible, "match", "matches")} below before adding one.")
        tally.failed == tally.active -> NoMatchCopy(NoMatchKind.ALL_FAILED, "Couldn't reach any source",
            if (tally.active == 1) "$searched didn't answer. Check your connection, then try again."
            else "None of your ${tally.active} ebook sources answered. Check your connection, then try again.")
        tally.failed > 0 -> NoMatchCopy(NoMatchKind.PARTLY_FAILED, "No ebook found yet",
            "${tally.failed} of ${tally.active} sources couldn't be checked. Retry ${if (tally.failed == 1) "it" else "them"} below.")
        tally.needTorBox > 0 -> NoMatchCopy(NoMatchKind.NEEDS_TORBOX, "No free ebook found",
            "$searched ${if (tally.active == 1) "has" else "have"} no ebook of this book. Connect TorBox to also check ${tally.needTorBox} more ${plural(tally.needTorBox, "source")}.")
        else -> NoMatchCopy(NoMatchKind.NOTHING_FOUND, "No ebook found", "Searched $searched. Nothing matched this title and author.")
    }
}

/** Screen-reader milestones: searching, the first best ebook, and the end of the search. */
fun ebookAnnouncement(search: StreamedEbookSearch, shown: BestEbook?, tally: SearchTally, book: Audiobook): String = when {
    search.complete && shown != null -> "Search finished. ${tally.found} found. Best match: ${bestEbookCopy(shown, book).headline}."
    search.complete -> "Search finished. ${ebookNoMatchCopy(search, tally).title}."
    shown != null -> "Best match found: ${bestEbookCopy(shown, book).headline}. Still checking."
    else -> "Searching ${tally.active} ${plural(tally.active, "source")}."
}

/** Provider names for an ebook's other finders. */
internal fun ebookAlsoFoundBy(search: StreamedEbookSearch, editionId: String): List<String> =
    search.groups.firstNotNullOfOrNull { it.alsoFoundBy[editionId] }.orEmpty().distinct()
