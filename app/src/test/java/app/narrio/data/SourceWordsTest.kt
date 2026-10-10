package app.narrio.data

import app.narrio.domain.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SourceWordsTest {
    private val carl = Audiobook("catalog:carl", "The Gate of the Feral Gods: Dungeon Crawler Carl, Book 4", "Matt Dinniman", provider = "catalog")

    @Test fun variantsOfferTitleOnlySeriesAndSurname() {
        assertEquals(listOf(
            WordsVariant("Title only", "The Gate of the Feral Gods"),
            WordsVariant("Include series name", "Dungeon Crawler Carl The Gate of the Feral Gods"),
            WordsVariant("Author last name only", "The Gate of the Feral Gods Dinniman"),
        ), SourceWords.variants(carl))
    }

    @Test fun seriesComesOnlyFromTheTitlesOwnSeriesLabels() {
        assertEquals("Mistborn", SourceWords.series(carl.copy(title = "The Final Empire (Mistborn, Book 1)")))
        assertEquals("The Expanse", SourceWords.series(carl.copy(title = "The Expanse Book 3: Abaddon's Gate")))
        assertEquals("Wheel of Time", SourceWords.series(carl.copy(title = "The Eye of the World: Wheel of Time #1")))
        // A real subtitle isn't a series, and a book with no series offers no series variant.
        assertNull(SourceWords.series(carl.copy(title = "Sapiens: A Brief History of Humankind")))
        assertEquals(listOf("Title only", "Author last name only"), SourceWords.variants(carl.copy(title = "Project Hail Mary", author = "Andy Weir")).map { it.label })
    }

    @Test fun surnameSkipsSuffixesAndUnknownAuthors() {
        assertEquals("King", SourceWords.lastName("Martin Luther King Jr."))
        assertEquals("Pratchett", SourceWords.lastName("Terry Pratchett & Neil Gaiman"))
        assertNull(SourceWords.lastName("Author not verified"))
        assertNull(SourceWords.lastName("Homer"))
        // An unknown author leaves only the title variant.
        assertEquals(listOf("Title only"), SourceWords.variants(carl.copy(title = "Beowulf", author = "Author not listed")).map { it.label })
    }

    @Test fun wordsMatchReleaseNamesAndFilesIgnoringPunctuationAndCase() {
        val release = Audiobook("knaben:x", "Harry Potter and the Philosopher's Stone - J.K. Rowling (Stephen Fry)", "Author not verified",
            sources = listOf(AudioSource("s", "", "MP3", listOf(AudioPart("p", "HP1/01 - The Boy Who Lived.mp3", "01")))))
        assertTrue(SourceWords.matches("philosophers stone rowling", release))
        assertTrue(SourceWords.matches("Boy who LIVED", release))
        assertFalse(SourceWords.matches("sorcerer's stone", release))
        assertFalse(SourceWords.matches("   ", release))
        assertNull(SourceWords.clean(" !! "))
        assertEquals("Philosopher's Stone", SourceWords.clean("  Philosopher's   Stone "))
    }

    @Test fun customWordsSearchEveryProviderWithoutTheAuthorAndOfferUnmatchedTitlesOnlyAsPossible() = runTest {
        val book = Audiobook("catalog:hp", "Harry Potter and the Sorcerer's Stone", "J. K. Rowling", provider = "catalog")
        fun release(id: String, title: String, hash: Char) = Audiobook(id, title, "Author not verified", provider = "knaben", releaseTitle = title,
            torrentHash = hash.toString().repeat(40), magnetUri = "magnet:?xt=urn:btih:${hash.toString().repeat(40)}", cacheState = "cached",
            cachedFormats = listOf("M4B"), seeders = 9, filesVerified = true, detailsLoaded = true,
            sources = listOf(AudioSource("$id-s", "", "M4B", listOf(AudioPart("$id-p", "book.m4b", "Book")), delivery = "torbox")))
        val uk = release("knaben:uk", "Harry Potter and the Philosopher's Stone - J.K. Rowling - Stephen Fry", 'a')
        val asked = mutableListOf<Pair<String, String>>()
        val settings = object : SourceProviderSettings {
            override val providers = MutableStateFlow(listOf(SourceProvider("search", "Search", SourceProviderKind.BUILT_IN, true, 0, false, false)))
            override fun setEnabled(id: String, enabled: Boolean) = Unit
            override fun move(id: String, index: Int) = Unit
        }
        val engine = ProviderSourceSearch(settings, { object : SourceLookup {
            override suspend fun search(book: Audiobook, title: String, budget: SourceSearchBudget, status: suspend (SourceGroupStatus) -> Unit): List<Audiobook> {
                asked += title to book.author; return listOf(uk)
            }
        } }, { it }, now = { currentTime })

        val automatic = engine.start(book, true, backgroundScope); runCurrent()
        // The automatic search doesn't accept another title.
        assertTrue(automatic.state.value.groups.single().let { it.recordings.isEmpty() && it.possible.isEmpty() })
        asked.clear()

        val custom = engine.start(book, true, backgroundScope, "Philosopher's Stone Rowling"); runCurrent()
        assertEquals(listOf("Philosopher's Stone Rowling" to "Author not listed"), asked)
        val group = custom.state.value.groups.single()
        assertEquals(listOf(uk.id), group.possible.map { it.id })
        assertNull("A release that doesn't name the book is never chosen automatically", custom.state.value.best)
    }
}
