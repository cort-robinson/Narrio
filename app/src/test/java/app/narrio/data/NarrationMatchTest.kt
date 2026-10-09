package app.narrio.data

import app.narrio.domain.*
import org.junit.Assert.*
import org.junit.Test

class NarrationMatchTest {
    private val recording = AudioSource("rec", "Parts", "MP3", emptyList())
    private val book = Audiobook("crime", "Crime and Punishment", "Fyodor Dostoevsky", language = "English", sources = listOf(recording),
        description = "A new recording of the classic novel, translated by Constance Garnett.")
    private fun ebook(id: String, title: String = "Crime and Punishment - Fyodor Dostoevsky", author: String = "", language: String = "", provider: String = "gutenberg",
                      format: String = "EPUB", size: Long = 0) =
        BookTextSource(id, title, author, format, provider, language = language, sizeBytes = size)
    private fun judge(edition: BookTextSource, providerId: String = "gutenberg", confirmed: Boolean = true, recorded: Audiobook = book) =
        NarrationMatch.judge(recorded, edition, providerId, confirmed)

    @Test fun onlyPositiveEvidenceWithoutACautionReadsAsLikely() {
        assertTrue(judge(ebook("same", language = "en")).likely)
        // No language on the file: nothing points either way, so no claim is made.
        assertFalse(judge(ebook("unknown")).likely)
        assertFalse(judge(ebook("unknown")).caution)
        assertTrue(judge(ebook("shipped"), DeviceSourceProviderSettings.RECORDING_FILES, confirmed = false).likely)
        val french = judge(ebook("fr", language = "French"))
        assertFalse(french.likely)
        assertEquals(setOf(NarrationSignal.OTHER_LANGUAGE), french.signals)
    }

    /** Before, a catalog placeholder language read as a foreign one and flagged every English ebook. */
    @Test fun placeholderLanguagesAreUnknownAndASavedRecordingSuppliesItsOwn() {
        val catalog = Audiobook("catalog:crime", "Crime and Punishment", "Fyodor Dostoevsky", language = "Language depends on source", provider = "catalog")
        val english = ebook("en", language = "English")
        assertEquals(emptySet<NarrationSignal>(), judge(english, recorded = catalog).signals)
        assertFalse(NarrationSignal.OTHER_LANGUAGE in judge(english, recorded = catalog.copy(language = "Language not verified")).signals)
        val saved = catalog.copy(language = "English", sources = listOf(recording))
        val narrated = NarrationMatch.recordingBook(catalog, saved)
        assertEquals("English", narrated.language)
        assertEquals(listOf(recording), narrated.sources)
        assertTrue(judge(english, recorded = narrated).likely)
        // A book that knows its own language keeps it.
        assertEquals("French", NarrationMatch.recordingBook(catalog.copy(language = "French"), saved).language)
        listOf("Language depends on source", "Language not verified", "Your TorBox library", "xx", "any", "").forEach { assertNull(it, NarrationMatch.language(it)) }
    }

    @Test fun shortenedAdaptedAndOtherPartEditionsCountAgainstTheNarration() {
        assertTrue(NarrationSignal.SHORTENED in judge(ebook("abridged", "Crime and Punishment (Abridged) - Fyodor Dostoevsky", language = "en")).signals)
        val sample = judge(ebook("sample", "Crime and Punishment - Fyodor Dostoevsky - Free Sample", language = "en"))
        assertTrue(NarrationSignal.SHORTENED in sample.signals)
        assertFalse(sample.likely)
        assertTrue(NarrationSignal.ADAPTED in judge(ebook("kids", "Crime and Punishment: Retold for Young Readers")).signals)
        assertTrue(NarrationSignal.ONE_PART in judge(ebook("vol", "Crime and Punishment Volume 2 - Fyodor Dostoevsky")).signals)
        val unabridged = judge(ebook("full", "Crime and Punishment (Unabridged) - Fyodor Dostoevsky", language = "en"))
        assertEquals(setOf(NarrationSignal.SAME_LANGUAGE, NarrationSignal.COMPLETE), unabridged.signals)
    }

    /** An abridged recording follows only its own cut: neither a full text nor another abridgment is a likely match. */
    @Test fun anAbridgedRecordingKeepsEveryOtherEditionCautious() {
        val abridged = book.copy(releaseTitle = "Crime and Punishment (Abridged) [MP3]")
        val full = judge(ebook("full", "Crime and Punishment (Unabridged) - Fyodor Dostoevsky", language = "en"), recorded = abridged)
        assertEquals(setOf(NarrationSignal.SAME_LANGUAGE, NarrationSignal.RECORDING_ABRIDGED), full.signals)
        assertFalse(full.likely)
        val sample = judge(ebook("sample", "Crime and Punishment - abridged sample", language = "en"), recorded = abridged)
        assertTrue(sample.caution)
        assertFalse(sample.likely)
        // The recording's own text is its own cut.
        assertTrue(judge(ebook("shipped"), DeviceSourceProviderSettings.RECORDING_FILES, recorded = abridged).likely)
    }

    @Test fun partsAreComparedInBothDirections() {
        val partOne = book.copy(releaseTitle = "Crime and Punishment Part 1 [MP3]")
        assertTrue(NarrationSignal.ONE_PART in judge(ebook("p2", "Crime and Punishment Part 2", language = "en"), recorded = partOne).signals)
        assertTrue(NarrationSignal.ONE_PART in judge(ebook("p2", "Crime and Punishment Part Two", language = "en"), recorded = partOne).signals)
        assertTrue(judge(ebook("p1", "Crime and Punishment Part One", language = "en"), recorded = partOne).likely)
        // The complete book holds the recording's part.
        assertTrue(judge(ebook("whole", language = "en"), recorded = partOne).likely)
    }

    @Test fun translatorsAreComparedAsWholePeople() {
        val garnett = ebook("garnett", author = "Dostoyevsky, Fyodor; Garnett, Constance (Translator)", language = "en")
        assertEquals(setOf(NarrationSignal.SAME_LANGUAGE, NarrationSignal.SAME_TRANSLATOR), judge(garnett).signals)
        val pevear = judge(ebook("pevear", "Crime and Punishment, translated by Richard Pevear and Larissa Volokhonsky", language = "en"))
        assertTrue(NarrationSignal.OTHER_TRANSLATOR in pevear.signals)
        assertFalse(pevear.likely)
        // The same surname isn't the same translator.
        assertTrue(NarrationSignal.OTHER_TRANSLATOR in judge(ebook("david", "Crime and Punishment, translated by David Garnett")).signals)
        // A surname alone, or co-translators only partly named, is uncertain either way.
        val surname = judge(ebook("surname", author = "Garnett (Translator)"))
        assertFalse(NarrationSignal.SAME_TRANSLATOR in surname.signals || NarrationSignal.OTHER_TRANSLATOR in surname.signals)
        val pair = book.copy(description = "Translated by Richard Pevear and Larissa Volokhonsky.")
        val one = judge(ebook("one", "Crime and Punishment, translated by Richard Pevear"), recorded = pair)
        assertFalse(NarrationSignal.SAME_TRANSLATOR in one.signals || NarrationSignal.OTHER_TRANSLATOR in one.signals)
        assertTrue(NarrationSignal.SAME_TRANSLATOR in judge(ebook("both", "Crime and Punishment, translated by R. Pevear & L. Volokhonsky"), recorded = pair).signals)
    }

    /** Before, the name ran on into the next sentence and the translator read as "narrated". */
    @Test fun translatorNamesStopAtSentencesAndOtherCredits() {
        fun named(text: String) = NarrationMatch.translators(text).map { (it.given + it.surname).joinToString(" ") }
        assertEquals(listOf("constance garnett"), named("Translated by Constance Garnett. Narrated by a full cast."))
        assertEquals(listOf("constance garnett"), named("Crime and Punishment translated by Constance Garnett Narrated by Simon Vance"))
        assertEquals(listOf("richard pevear", "larissa volokhonsky"), named("translated by Richard Pevear and Larissa Volokhonsky, with an introduction"))
        assertEquals(listOf("j k smith"), named("Translated from the Russian by J. K. Smith."))
        assertEquals(listOf("louise maude"), named("War and Peace by Leo Tolstoy, Louise Maude (translator)"))
        assertEquals(listOf("constance garnett"), named("Dostoyevsky, Fyodor; Garnett, Constance (Translator)"))
        assertEquals(emptyList<String>(), named("Crime and Punishment - Fyodor Dostoevsky"))
        val cast = book.copy(description = "Translated by Constance Garnett. Narrated by a full cast.")
        assertTrue(NarrationSignal.SAME_TRANSLATOR in judge(ebook("garnett", author = "Garnett, Constance (Translator)"), recorded = cast).signals)
    }

    /** A file far too small for a ten-hour recording is a stub; an ordinary EPUB, or one of unknown size, isn't judged. */
    @Test fun aFileTooSmallForTheRecordingIsFlagged() {
        val long = book.copy(sources = listOf(recording.copy(parts = List(10) { AudioPart("p$it", "p$it.mp3", "Part $it", durationMs = 3_600_000) })))
        assertTrue(NarrationSignal.TOO_SHORT in judge(ebook("stub", language = "en", size = 15_000), recorded = long).signals)
        assertFalse(NarrationSignal.TOO_SHORT in judge(ebook("novel", language = "en", size = 400_000), recorded = long).signals)
        assertFalse(NarrationSignal.TOO_SHORT in judge(ebook("unknown", language = "en"), recorded = long).signals)
        assertFalse(NarrationSignal.TOO_SHORT in judge(ebook("stub", language = "en", size = 15_000)).signals)
        assertTrue(NarrationSignal.TOO_SHORT in judge(ebook("txt", language = "en", format = "TXT", size = 60_000), recorded = long).signals)
    }

    @Test fun languagesCompareByCodeOrName() {
        assertEquals("en", NarrationMatch.language("English"))
        assertEquals("en", NarrationMatch.language("eng"))
        assertEquals("en", NarrationMatch.language("English [en]"))
        assertEquals("en", NarrationMatch.language("en-US"))
        assertEquals("fr", NarrationMatch.language("French"))
        assertEquals("fr", NarrationMatch.language("fre"))
        assertNull(NarrationMatch.language(" "))
    }

    /** Before, the first same-language EPUB led even when it was a sample; now the likeliest fit with the narration does. */
    @Test fun theBestMatchIsTheLikeliestFitAndOnlyARecordingEarnsTheLabel() {
        val sample = ebook("sample", "Crime and Punishment - Fyodor Dostoevsky (sample)", language = "en", provider = "torbox-cache")
        val full = ebook("full", language = "en")
        val french = ebook("fr", language = "fr")
        val groups = listOf(EbookGroup("cache", "Cache", SourceGroupStatus.DONE, editions = listOf(sample, french)),
            EbookGroup("gutenberg", "Project Gutenberg", SourceGroupStatus.DONE, editions = listOf(full)))
        val best = EbookRanking.choose(book, groups, recording = true)!!
        assertEquals("full", best.edition.id)
        assertEquals(listOf(EbookMatchReason.PUBLIC_DOMAIN, EbookMatchReason.LIKELY_NARRATION, EbookMatchReason.STRONG_MATCH, EbookMatchReason.EPUB,
            EbookMatchReason.LANGUAGE_MATCH), best.reasons)
        assertFalse(EbookMatchReason.LIKELY_NARRATION in EbookRanking.choose(book, groups, recording = false)!!.reasons)
    }
}
