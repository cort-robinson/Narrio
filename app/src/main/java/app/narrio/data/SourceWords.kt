package app.narrio.data

import app.narrio.domain.*
import kotlinx.coroutines.CoroutineScope

/**
 * Searching sources with the listener's own words: a UK or US title, a series name and number, or another spelling of
 * the author. Matching against the book is unchanged; a release that names every word but not the book's own title and
 * author is offered as a possible match, so it never becomes the best match by itself.
 */
interface CustomWordsSourceSearch : StreamingSourceSearch {
    /** [words] replaces the automatic title variants for every provider; null searches as [start] does. */
    fun start(book: Audiobook, connected: Boolean, scope: CoroutineScope, words: String?): SourceSearchSession
}

/** A ready-made set of search words, labelled for the listener. */
data class WordsVariant(val label: String, val words: String)

object SourceWords {
    private const val MAX_LENGTH = 200

    /** Trimmed and collapsed; null when nothing searchable is left. */
    fun clean(words: String?): String? = words?.replace(Regex("\\s+"), " ")?.trim()?.take(MAX_LENGTH)
        ?.takeIf { BookIdentity.normalize(it).isNotBlank() }

    /** Every word appears in the release name, its author, or a file name. Punctuation and accents are ignored. */
    fun matches(words: String, recording: Audiobook): Boolean {
        // "Philosopher's" and "Philosophers" are the same word to a listener.
        fun normalize(value: String) = BookIdentity.normalize(value.replace(Regex("['’]"), ""))
        val wanted = normalize(words).split(' ').filter(String::isNotBlank)
        if (wanted.isEmpty()) return false
        val names = listOf(recording.releaseTitle, recording.title, recording.author) +
            recording.sources.flatMap { source -> source.parts.map { it.name } }
        val evidence = " ${normalize(names.joinToString(" "))} "
        return wanted.all { " $it " in evidence }
    }

    /** The book's own title without a subtitle, edition label, or series label. */
    fun mainTitle(book: Audiobook): String = BookIdentity.title(book.title).substringBefore(':')
        .replace(Regex("\\s*\\([^)]*\\)\\s*"), " ").replace(Regex("\\s+"), " ").trim()

    private val numbered = "(?:Book|Bk\\.?|Volume|Vol\\.?|Part|Novel|#)\\s*(?:\\d{1,3}|[IVX]{1,5}|One|Two|Three|Four|Five|Six|Seven|Eight|Nine|Ten)\\b"
    private val bracketed = Regex("\\(([^()]+?),?\\s+$numbered\\)", RegexOption.IGNORE_CASE)
    private val trailing = Regex("^(.+?),?\\s+$numbered\\s*$", RegexOption.IGNORE_CASE)
    private val leading = Regex("^(.+?),?\\s+$numbered\\s*[:\\-–—]\\s*\\S", RegexOption.IGNORE_CASE)

    /**
     * The series a catalog title names, such as "Dungeon Crawler Carl" in "The Gate of the Feral Gods: Dungeon
     * Crawler Carl, Book 4" or "(Mistborn, Book 1)". Null when the title doesn't say.
     */
    fun series(book: Audiobook): String? {
        val title = book.title.trim()
        fun clean(value: String) = value.trim().trim(',', '-', '–', '—', ':').trim().takeIf { series ->
            series.length in 3..80 && BookIdentity.normalize(series) != BookIdentity.normalize(mainTitle(book))
        }
        bracketed.find(title)?.groupValues?.get(1)?.let(::clean)?.let { return it }
        val subtitle = title.substringAfter(':', "").trim()
        if (subtitle.isNotBlank()) trailing.find(subtitle)?.groupValues?.get(1)?.let(::clean)?.let { return it }
        return leading.find(title)?.groupValues?.get(1)?.let(::clean)
    }

    /** The surname of the first credited author, or null when the author isn't known. */
    fun lastName(author: String): String? {
        if (BookMetadata.unknown(author)) return null
        val first = author.split(Regex("\\s*(?:,|&|;|\\band\\b)\\s*")).firstOrNull { it.isNotBlank() }?.trim() ?: return null
        val names = first.split(Regex("\\s+")).map { it.trim('.', ',') }
            .filter { it.isNotBlank() && !it.matches(Regex("(?i)jr|sr|ii|iii|iv|phd|md")) }
        return names.lastOrNull()?.takeIf { names.size >= 2 && it.length >= 2 }
    }

    /**
     * Quick variants: the title alone, the title with its series name when the catalog names one, and the title with
     * only the author's surname. Each differs from the others; none repeats the automatic search exactly.
     */
    fun variants(book: Audiobook): List<WordsVariant> {
        val title = mainTitle(book).ifBlank { return emptyList() }
        return listOfNotNull(
            WordsVariant("Title only", title),
            series(book)?.let { WordsVariant("Include series name", "$it $title") },
            lastName(book.author)?.let { WordsVariant("Author last name only", "$title $it") },
        ).distinctBy { BookIdentity.normalize(it.words) }
    }
}
