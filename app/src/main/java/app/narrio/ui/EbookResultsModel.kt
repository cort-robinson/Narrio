package app.narrio.ui

import app.narrio.data.BookMetadata
import app.narrio.data.BookIdentity
import app.narrio.data.DeviceSourceProviderSettings
import app.narrio.data.NarrationFit
import app.narrio.data.NarrationMatch
import app.narrio.data.NarrationSignal
import app.narrio.domain.*

/*
 * What the ebook sheet says about a per-source ebook search, worded like listening sources: the best ebook and why,
 * how far the search has got, and what to do when nothing turned up. Pure functions, tested without a device.
 */

typealias PinnedEbook = Pinned<BestEbook>

fun PinnedEbook.next(latest: BestEbook?, interacted: Boolean, stillListed: (String) -> Boolean): PinnedEbook =
    next(latest, interacted, { it.edition.id }, stillListed)

/**
 * "Free public-domain ebook · EPUB", then the quieter reasons. With a [recording], an ebook whose name points away from
 * the narration says so first.
 */
fun bestEbookCopy(best: BestEbook, book: Audiobook, recording: Boolean = false): BestMatchCopy {
    val reasons = best.reasons.toSet()
    val availability = when {
        EbookMatchReason.WITH_RECORDING in reasons -> "Comes with this recording"
        EbookMatchReason.IN_YOUR_TORBOX in reasons -> "In your TorBox"
        EbookMatchReason.CACHED_IN_TORBOX in reasons -> "Ready in TorBox"
        EbookMatchReason.PUBLIC_DOMAIN in reasons -> "Free public-domain ebook"
        else -> null
    }
    val caution = if (recording) cautionLabel(NarrationMatch.judge(book, best.edition, best.providerId, confirmed = true)) else null
    val detail = buildList {
        if (EbookMatchReason.WITH_RECORDING in reasons) add("Most likely the narrated edition")
        if (EbookMatchReason.LIKELY_NARRATION in reasons) add("Likely matches the narration")
        caution?.let { add("May not follow the narration: ${it.replaceFirstChar(Char::lowercase)}") }
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
            "${tally.failed} of ${tally.active} sources couldn't be checked. Search again, or retry ${if (tally.failed == 1) "it" else "them"} under Advanced.")
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

/** One ebook in the flat Other choices list; [confirmed] ebooks named the exact title and author. */
data class EbookChoice(val edition: BookTextSource, val providerId: String, val source: String, val confirmed: Boolean, val fit: NarrationFit)

/**
 * Every found ebook once, without [shownId] (the best match card's own): the recording's own files first, as in the
 * best match, then confirmed before possible, then the likeliest to follow the narration, keeping source order for ties.
 */
fun ebookChoices(search: StreamedEbookSearch, book: Audiobook, shownId: String?): List<EbookChoice> = search.groups.flatMap { group ->
    val source = if (group.providerId == DeviceSourceProviderSettings.RECORDING_FILES) "In this recording's files" else group.name
    group.editions.map { EbookChoice(it, group.providerId, source, true, NarrationMatch.judge(book, it, group.providerId, confirmed = true)) } +
        group.possible.map { EbookChoice(it, group.providerId, source, false, NarrationMatch.judge(book, it, group.providerId, confirmed = false)) }
}.distinctBy { it.edition.id }.filter { it.edition.id != shownId }
    .sortedWith(compareByDescending<EbookChoice> { it.providerId == DeviceSourceProviderSettings.RECORDING_FILES }.thenByDescending { it.confirmed }
        .thenByDescending { it.fit.score })

/** "Jane Austen · French · EPUB": the language only when it differs from the book's. */
fun ebookChoiceDetail(choice: EbookChoice, book: Audiobook): String {
    val theirs = NarrationMatch.language(choice.edition.language)
    val ours = NarrationMatch.language(book.language)
    val language = theirs?.takeIf { it != ours }?.let(::languageName)
    return listOfNotNull(choice.edition.author.ifBlank { null }, language, choice.edition.format.ifBlank { null }).joinToString(" · ")
}

private fun languageName(code: String) = if (code.length == 2) java.util.Locale.forLanguageTag(code).getDisplayLanguage(java.util.Locale.ENGLISH).ifBlank { code } else code.replaceFirstChar(Char::uppercase)

enum class ChoiceNoteKind { LIKELY, CAUTION, CHECK }
data class ChoiceNote(val kind: ChoiceNoteKind, val text: String)

/**
 * What a choice says about itself, in order: a possible match asks for a check of its title and author; then, with a
 * [recording], a likely fit, or the reason it may not follow the narration (shown even beside the check, since a known
 * difference matters either way). Never a verified claim: the pairing status decides after adding.
 */
fun ebookChoiceNotes(choice: EbookChoice, recording: Boolean): List<ChoiceNote> {
    val caution = cautionLabel(choice.fit)
    val shipped = NarrationSignal.WITH_RECORDING in choice.fit.signals
    return listOfNotNull(
        ChoiceNote(ChoiceNoteKind.CHECK, "Check the title and author").takeIf { !choice.confirmed },
        when {
            recording && choice.fit.likely && shipped -> ChoiceNote(ChoiceNoteKind.LIKELY, "Comes with this recording")
            recording && choice.fit.likely && choice.confirmed -> ChoiceNote(ChoiceNoteKind.LIKELY, "Likely matches the narration")
            caution != null -> ChoiceNote(ChoiceNoteKind.CAUTION, if (recording) "May not follow the narration: ${caution.replaceFirstChar(Char::lowercase)}" else caution)
            else -> null
        },
    )
}

private fun cautionLabel(fit: NarrationFit): String? = when {
    NarrationSignal.OTHER_LANGUAGE in fit.signals -> "In another language"
    NarrationSignal.OTHER_TRANSLATOR in fit.signals -> "A different translation"
    NarrationSignal.RECORDING_ABRIDGED in fit.signals -> "The recording is abridged"
    NarrationSignal.SHORTENED in fit.signals -> "Abridged or a sample"
    NarrationSignal.TOO_SHORT in fit.signals -> "Much shorter than the recording"
    NarrationSignal.ADAPTED in fit.signals -> "A retelling or adaptation"
    NarrationSignal.ONE_PART in fit.signals -> "A different volume or part"
    else -> null
}

/** A quick alternative to the book's own search words. */
data class EbookWordChoice(val label: String, val words: String)
/** The search words a book's details make, and quick alternatives for UK/US titles, subtitles, and original titles. */
data class EbookWordSuggestions(val bookDetails: String, val choices: List<EbookWordChoice>)

private val originalTitle = Regex("(?i)\\b(?:original(?:ly)? (?:titled|title|published as)|first published (?:in [\\p{L} ]+? )?as|published in the (?:UK|US|United Kingdom|United States) as)\\s*:?\\s*[\"“]?([^\"”.;,()\\n]{2,80})")

fun ebookWordSuggestions(book: Audiobook): EbookWordSuggestions {
    val title = BookIdentity.title(book.title)
    val author = book.author.takeUnless(BookMetadata::unknown).orEmpty()
    fun with(words: String) = "$words $author".trim()
    val details = with(title)
    val short = title.substringBefore(':').replace(Regex("\\s*\\([^)]*\\)\\s*$"), "").trim()
    val original = originalTitle.find(book.description)?.groupValues?.get(1)?.trim()?.trimEnd('\'', '’')
        ?.takeIf { it.isNotBlank() && BookIdentity.normalize(it) != BookIdentity.normalize(title) }
    val choices = listOfNotNull(
        EbookWordChoice("Title only", title).takeIf { title.isNotBlank() && it.words != details },
        EbookWordChoice("Without subtitle", with(short)).takeIf { short.isNotBlank() && short != title },
        original?.let { EbookWordChoice("Original title", with(it)) },
    )
    return EbookWordSuggestions(details, choices)
}

/** The words to keep for a search: blank when they're just the book's own details, so the default search runs. */
fun customEbookWords(typed: String, book: Audiobook): String =
    typed.trim().takeUnless { BookIdentity.normalize(it) == BookIdentity.normalize(ebookWordSuggestions(book).bookDetails) }.orEmpty()
