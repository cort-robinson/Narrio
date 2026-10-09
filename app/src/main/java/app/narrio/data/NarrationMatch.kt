package app.narrio.data

import app.narrio.domain.Audiobook
import app.narrio.domain.BookTextSource
import app.narrio.domain.NarrationContext
import java.util.Locale

/** One piece of evidence about whether an ebook's text follows a recording, read from names and metadata. */
enum class NarrationSignal(val caution: Boolean = false) {
    /** Shipped in the recording's own files: the strongest evidence short of alignment. */
    WITH_RECORDING,
    SAME_LANGUAGE,
    /** Every translator named on either side is the same person. */
    SAME_TRANSLATOR,
    /** Says it's unabridged or the complete text, for a recording that isn't abridged. */
    COMPLETE,
    OTHER_LANGUAGE(caution = true),
    /** Both name translators and none of them is the same person. */
    OTHER_TRANSLATOR(caution = true),
    /** An abridgment, excerpt, sample, or preview. */
    SHORTENED(caution = true),
    /** The recording is abridged, so even a complete ebook won't follow it closely. */
    RECORDING_ABRIDGED(caution = true),
    /** A retelling, adaptation, simplified, or graphic edition. */
    ADAPTED(caution = true),
    /** A volume or part other than the one the recording names. */
    ONE_PART(caution = true),
    /** The file is far too small to hold the text of a recording this long. */
    TOO_SHORT(caution = true),
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

/** A translator as named: given names (possibly initials) and surname, normalized. */
internal data class Person(val given: List<String>, val surname: String)

/** Cheap, honest signals for ranking ebooks against a recording; see docs/EREADER.md#finding-an-ebook. */
object NarrationMatch {
    private val shortened = Regex("\\b(?:abridged|abridgement|abridgment|condensed|shortened|excerpts?|extracts?|samples?|preview|selections?)\\b")
    private val abridged = Regex("\\b(?:abridged|abridgement|abridgment|condensed)\\b")
    private val adapted = Regex("\\b(?:retold|retelling|adapted|adaptation|simplified|young readers?|graphic novel|easy reader|comic|manga|screenplay)\\b")
    private val complete = Regex("\\b(?:unabridged|complete text|complete and unabridged)\\b")
    private val part = Regex("\\b(?:vol|volume|part|tome)\\s+(\\d+|one|two|three|four|five|six|i{1,3}|iv|v|vi)\\b")
    private val NUMBERS = mapOf("one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5", "six" to "6",
        "i" to "1", "ii" to "2", "iii" to "3", "iv" to "4", "v" to "5", "vi" to "6")

    // Names are capitalized words or initials; a full stop after a word, a lowercase word, or a credit for another role ends them.
    private const val ROLE = "(?:Narrated|Read|Performed|Edited|Illustrated|Introduction|Foreword|With|Unabridged|Abridged|Translated)\\b"
    private const val NAME = "(?!$ROLE)\\p{Lu}(?:\\.|[\\p{L}'\\-]+)(?:\\s+(?!$ROLE)\\p{Lu}(?:\\.|[\\p{L}'\\-]+)){0,3}"
    private val translatorBy = Regex("\\b(?:[Tt]ranslated|[Tt]ranslation|[Tt]rans\\.)\\s+(?:from (?:the )?\\p{L}+\\s+)?by\\s+($NAME(?:\\s*(?:,|\\band\\b|&)\\s*$NAME)*)")
    private val translatorRole = Regex("(?i)((?:[\\p{L}.'\\-]+,?\\s+){0,2}[\\p{L}.'\\-]+)\\s*\\((?:translator|trans\\.?|tr\\.)\\)")

    /**
     * [book] carries the recording's metadata (its language, release name, description, length). [confirmed] means the
     * ebook named the exact title and author. Recording files outrank everything; another language or translator, an
     * abridgment on either side, an adaptation, a different volume, or a file too small for the recording count against
     * an ebook. Unknown values never count either way.
     */
    fun judge(book: Audiobook, candidate: BookTextSource, providerId: String, confirmed: Boolean): NarrationFit {
        val signals = mutableSetOf<NarrationSignal>()
        val name = BookIdentity.normalize("${candidate.title} ${candidate.fileName}")
        val recording = BookIdentity.normalize("${book.title} ${book.releaseTitle}")
        val shipped = providerId == DeviceSourceProviderSettings.RECORDING_FILES
        if (shipped) signals += NarrationSignal.WITH_RECORDING
        val ours = language(book.language)
        val theirs = language(candidate.language)
        if (ours != null && theirs != null) signals += if (ours == theirs) NarrationSignal.SAME_LANGUAGE else NarrationSignal.OTHER_LANGUAGE
        // Each field is read alone, so one field's words never run into another's credit.
        translatorFit(listOf(book.title, book.releaseTitle, book.description).flatMap(::translators).distinct(),
            listOf(candidate.title, candidate.author).flatMap(::translators).distinct())?.let { signals += it }
        when {
            // An abridged recording follows only its own cut; its own file is the one exception.
            abridged.containsMatchIn(recording) -> if (!shipped) signals += NarrationSignal.RECORDING_ABRIDGED
            shortened.containsMatchIn(name) -> signals += NarrationSignal.SHORTENED
            complete.containsMatchIn(name) -> signals += NarrationSignal.COMPLETE
        }
        // Even a matching retelling is a different text, so any adaptation stays cautious.
        if (adapted.containsMatchIn(name)) signals += NarrationSignal.ADAPTED
        // A complete ebook holds a recording's single part; a different or narrower part doesn't.
        partOf(name)?.let { if (it != partOf(recording)) signals += NarrationSignal.ONE_PART }
        if (tooShort(book, candidate)) signals += NarrationSignal.TOO_SHORT
        val score = (if (confirmed) 10 else 0) + signals.sumOf { WEIGHTS.getValue(it) }
        return NarrationFit(score, signals)
    }

    /**
     * The book as its recording describes it, for judging ebooks: every known recording, and the saved recording's
     * language when the book's own is a catalog placeholder such as "Language depends on source".
     */
    fun recordingBook(book: Audiobook, saved: Audiobook?, narration: NarrationContext? = null): Audiobook {
        if (narration != null) return narrationBook(book, narration)
        val sources = (book.sources + saved?.sources.orEmpty()).distinctBy { it.id }
        val language = if (language(book.language) == null && saved != null && language(saved.language) != null) saved.language else book.language
        return book.copy(sources = sources, language = language)
    }

    /**
     * The book's identity (id, title, author, used to name matches) with only the given recording's narration details
     * and chosen files. An unknown recording language stays unknown rather than borrowing the catalog's.
     */
    fun narrationBook(book: Audiobook, narration: NarrationContext): Audiobook {
        val recording = narration.recording
        return book.copy(language = recording.language, narrator = recording.narrator, releaseTitle = recording.releaseTitle.ifBlank { recording.title },
            description = recording.description, durationMs = recording.durationMs, sources = listOfNotNull(narration.source).ifEmpty { recording.sources })
    }

    private val WEIGHTS = mapOf(
        NarrationSignal.WITH_RECORDING to 100, NarrationSignal.SAME_LANGUAGE to 20, NarrationSignal.SAME_TRANSLATOR to 30, NarrationSignal.COMPLETE to 5,
        NarrationSignal.OTHER_LANGUAGE to -100, NarrationSignal.OTHER_TRANSLATOR to -60, NarrationSignal.SHORTENED to -60,
        NarrationSignal.RECORDING_ABRIDGED to -20, NarrationSignal.ADAPTED to -60, NarrationSignal.ONE_PART to -30, NarrationSignal.TOO_SHORT to -60,
    )

    private fun partOf(text: String) = part.find(text)?.groupValues?.get(1)?.let { NUMBERS[it] ?: it.trimStart('0') }

    /** Narration runs about 2.6 words a second; the bound is generous, catching only files that can't hold the text. */
    private const val WORDS_PER_MS = 2.6 / 1000

    /**
     * True only when the size and the recording's length are both known and the file couldn't hold a quarter of the
     * narrated words, even compressed: a sample or a stub rather than the book.
     */
    internal fun tooShort(book: Audiobook, candidate: BookTextSource): Boolean {
        if (candidate.sizeBytes <= 0) return false
        val duration = book.sources.maxOfOrNull { source -> source.parts.sumOf { it.durationMs } }?.takeIf { it > 0 } ?: book.durationMs
        if (duration <= 0) return false
        val bytesPerWord = when (candidate.format) { "EPUB" -> 1.0; "TXT" -> 4.0; else -> return false }
        return candidate.sizeBytes < duration * WORDS_PER_MS / 4 * bytesPerWord
    }

    private val CODES = mapOf("fre" to "fr", "ger" to "de", "dut" to "nl", "chi" to "zh", "gre" to "el", "cze" to "cs", "per" to "fa", "rum" to "ro", "slo" to "sk", "wel" to "cy")

    /**
     * A comparable language code from a name or ISO code ("English", "en", "eng", "English [en]", "en-US"); null when
     * the value is a placeholder ("Language depends on source", "Language not verified") or isn't a language at all.
     */
    fun language(value: String): String? {
        val first = value.substringBefore('[').substringBefore(',').substringBefore(';').trim().lowercase(Locale.ROOT)
            .let { if (Regex("^[a-z]{2,3}[-_].*").matches(it)) it.substring(0, it.indexOfFirst { c -> c == '-' || c == '_' }) else it }
        if (first.isBlank()) return null
        return when (first.length) {
            2 -> first.takeIf { it in LANGUAGES.values }
            3 -> CODES[first] ?: ISO3[first]
            else -> LANGUAGES[first]
        }
    }

    private val LANGUAGES by lazy {
        Locale.getAvailableLocales().filter { it.language.length == 2 }.associate { it.getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT) to it.language }
    }
    private val ISO3 by lazy {
        Locale.getAvailableLocales().filter { it.language.length == 2 }.mapNotNull { locale -> runCatching { locale.isO3Language to locale.language }.getOrNull() }.toMap()
    }

    /**
     * Translators named in [text]: "translated by Richard Pevear and Larissa Volokhonsky", or catalog credits such as
     * "Garnett, Constance (Translator)". Empty when none is named.
     */
    internal fun translators(text: String): List<Person> {
        translatorBy.find(text)?.let { match ->
            return match.groupValues[1].split(Regex("\\s*(?:,|\\band\\b|&)\\s*")).mapNotNull(::person)
        }
        return translatorRole.findAll(text).mapNotNull { match ->
            val role = match.groupValues[1].trim()
            // Catalog order "Surname, Given" puts the surname first; "Author, Given Surname" ends with the translator.
            val given = role.substringAfterLast(',', "").trim()
            if (given.isNotEmpty() && ' ' !in given) person("$given ${role.substringBeforeLast(',').substringAfterLast(',')}")
            else person(role.substringAfterLast(','))
        }.toList()
    }

    private fun person(name: String): Person? {
        val words = BookIdentity.normalize(name).split(' ').filter(String::isNotBlank)
        return if (words.isEmpty()) null else Person(words.dropLast(1), words.last())
    }

    /**
     * [SAME_TRANSLATOR] when every translator on each side is the same person, with compatible given names;
     * [OTHER_TRANSLATOR] when no one could be the same person. A shared surname without given names, or a partial
     * overlap, is uncertain and says nothing.
     */
    private fun translatorFit(narrated: List<Person>, written: List<Person>): NarrationSignal? {
        if (narrated.isEmpty() || written.isEmpty()) return null
        fun could(a: Person, b: Person) = a.surname == b.surname && (a.given.isEmpty() || b.given.isEmpty() || compatible(a.given.first(), b.given.first()))
        fun same(a: Person, b: Person) = could(a, b) && a.given.isNotEmpty() && b.given.isNotEmpty()
        return when {
            narrated.all { a -> written.any { same(a, it) } } && written.all { b -> narrated.any { same(it, b) } } -> NarrationSignal.SAME_TRANSLATOR
            narrated.none { a -> written.any { could(a, it) } } -> NarrationSignal.OTHER_TRANSLATOR
            else -> null
        }
    }

    /** "constance" and "c" can be the same given name; "constance" and "david" can't. */
    private fun compatible(a: String, b: String) = a == b || (a.length == 1 && b.startsWith(a)) || (b.length == 1 && a.startsWith(b))
}
