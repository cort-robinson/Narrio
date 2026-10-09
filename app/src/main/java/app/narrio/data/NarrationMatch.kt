package app.narrio.data

import app.narrio.domain.Audiobook
import app.narrio.domain.BookTextSource
import java.util.Locale

/** One piece of evidence about whether an ebook's text follows a recording, read from names and metadata. */
enum class NarrationSignal(val caution: Boolean = false) {
    /** Shipped in the recording's own files: the strongest evidence short of alignment. */
    WITH_RECORDING,
    SAME_LANGUAGE,
    SAME_TRANSLATOR,
    /** Says it's unabridged or the complete text. */
    COMPLETE,
    OTHER_LANGUAGE(caution = true),
    OTHER_TRANSLATOR(caution = true),
    /** An abridgment, excerpt, sample, or preview. */
    SHORTENED(caution = true),
    /** A retelling, adaptation, simplified, or graphic edition. */
    ADAPTED(caution = true),
    /** One volume or part of the book. */
    ONE_PART(caution = true),
}

/**
 * How likely an ebook's text follows the narration, before any alignment. [likely] needs positive evidence and no
 * caution; names can't prove the same edition, so alignment (the pairing status) stays authoritative after adding.
 */
data class NarrationFit(val score: Int, val signals: Set<NarrationSignal>) {
    val likely: Boolean get() = signals.none { it.caution } && (NarrationSignal.WITH_RECORDING in signals ||
        NarrationSignal.SAME_LANGUAGE in signals || NarrationSignal.SAME_TRANSLATOR in signals)
    val caution: Boolean get() = signals.any { it.caution }
}

/** Cheap, honest signals for ranking ebooks against a recording; see docs/EREADER.md#finding-an-ebook. */
object NarrationMatch {
    private val shortened = Regex("\\b(?:abridged|abridgement|abridgment|condensed|shortened|excerpts?|extracts?|samples?|preview|selections?)\\b")
    private val adapted = Regex("\\b(?:retold|retelling|adapted|adaptation|simplified|young readers?|graphic novel|easy reader|comic|manga|screenplay)\\b")
    private val complete = Regex("\\b(?:unabridged|complete text|complete and unabridged)\\b")
    private val part = Regex("\\b(?:vol|volume|part|tome)\\s+(?:\\d+|one|two|three|four|five|i{1,3}|iv|v)\\b")
    // Case-sensitive so the capitalized name stops at "and" or the next sentence.
    private val translatorBy = Regex("\\b(?:[Tt]ranslated|[Tt]ranslation|[Tt]rans\\.?)\\s+(?:from \\p{L}+ )?by\\s+(\\p{Lu}[\\p{L}'\\-]*(?:\\s+\\p{Lu}[\\p{L}.'\\-]*){0,3})")
    private val translatorRole = Regex("(?i)((?:[\\p{L}.'\\-]+,?\\s+){0,3}[\\p{L}.'\\-]+)\\s*\\((?:translator|trans\\.?|tr\\.)\\)")

    /**
     * [book] carries the recording's metadata (its language, release name, description). [confirmed] means the ebook
     * named the exact title and author. Recording files outrank everything; other language, translator, abridgment,
     * adaptation, or a single volume count against an ebook.
     */
    fun judge(book: Audiobook, candidate: BookTextSource, providerId: String, confirmed: Boolean): NarrationFit {
        val signals = mutableSetOf<NarrationSignal>()
        val name = BookIdentity.normalize("${candidate.title} ${candidate.fileName}")
        val recording = BookIdentity.normalize("${book.title} ${book.releaseTitle}")
        if (providerId == DeviceSourceProviderSettings.RECORDING_FILES) signals += NarrationSignal.WITH_RECORDING
        val ours = language(book.language.takeUnless(BookMetadata::unknown).orEmpty())
        val theirs = language(candidate.language)
        if (ours != null && theirs != null) signals += if (ours == theirs) NarrationSignal.SAME_LANGUAGE else NarrationSignal.OTHER_LANGUAGE
        val narrated = translator("${book.title} ${book.releaseTitle} ${book.description}")
        val written = translator("${candidate.title} ${candidate.author}")
        if (narrated != null && written != null) signals += if (narrated == written) NarrationSignal.SAME_TRANSLATOR else NarrationSignal.OTHER_TRANSLATOR
        // Both abridged is still a different cut, so it's neutral rather than a match.
        if (shortened.containsMatchIn(name) && !shortened.containsMatchIn(recording)) signals += NarrationSignal.SHORTENED
        if (adapted.containsMatchIn(name) && !adapted.containsMatchIn(recording)) signals += NarrationSignal.ADAPTED
        if (part.containsMatchIn(name) && !part.containsMatchIn(recording)) signals += NarrationSignal.ONE_PART
        if (complete.containsMatchIn(name)) signals += NarrationSignal.COMPLETE
        val score = (if (confirmed) 10 else 0) + signals.sumOf { WEIGHTS.getValue(it) }
        return NarrationFit(score, signals)
    }

    private val WEIGHTS = mapOf(
        NarrationSignal.WITH_RECORDING to 100, NarrationSignal.SAME_LANGUAGE to 20, NarrationSignal.SAME_TRANSLATOR to 30, NarrationSignal.COMPLETE to 5,
        NarrationSignal.OTHER_LANGUAGE to -100, NarrationSignal.OTHER_TRANSLATOR to -60, NarrationSignal.SHORTENED to -60, NarrationSignal.ADAPTED to -60,
        NarrationSignal.ONE_PART to -30,
    )

    private val CODES = mapOf("eng" to "en", "fre" to "fr", "fra" to "fr", "ger" to "de", "deu" to "de", "spa" to "es", "ita" to "it", "por" to "pt",
        "dut" to "nl", "nld" to "nl", "rus" to "ru", "jpn" to "ja", "chi" to "zh", "zho" to "zh", "swe" to "sv", "pol" to "pl", "lat" to "la", "gre" to "el", "ell" to "el")

    /** A comparable language code from a name or ISO code ("English", "en", "eng", "English [en]"); null when unknown. */
    fun language(value: String): String? {
        val first = value.substringBefore('[').substringBefore(',').substringBefore(';').trim().lowercase(Locale.ROOT)
            .let { if (Regex("^[a-z]{2,3}[-_].*").matches(it)) it.substring(0, it.indexOfFirst { c -> c == '-' || c == '_' }) else it }
        if (first.isBlank()) return null
        if (first.length == 2 && first.all(Char::isLetter)) return first
        return CODES[first] ?: NAMES[first] ?: first
    }

    private val NAMES by lazy {
        Locale.getAvailableLocales().filter { it.language.length == 2 }.associate { it.getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT) to it.language }
    }

    /** The translator's surname, from "translated by Constance Garnett" or "Garnett, Constance (Translator)". */
    internal fun translator(text: String): String? {
        val name = translatorBy.find(text)?.groupValues?.get(1)
            ?: translatorRole.find(text)?.groupValues?.get(1)?.let { role ->
                // Catalog order "Surname, Given" puts the surname first; "Author, Given Surname" ends with it.
                val given = role.substringAfterLast(',', "").trim()
                if (given.isNotEmpty() && ' ' !in given) role.substringBeforeLast(',').substringAfterLast(',') else role
            } ?: return null
        return BookIdentity.normalize(name).split(' ').lastOrNull(String::isNotBlank)
    }
}
