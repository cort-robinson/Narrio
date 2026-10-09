package app.narrio.data

import app.narrio.domain.*
import org.junit.Assert.*
import org.junit.Test

class NarrationMatchTest {
    private val recording = AudioSource("rec", "Parts", "MP3", emptyList())
    private val book = Audiobook("crime", "Crime and Punishment", "Fyodor Dostoevsky", language = "English", sources = listOf(recording),
        description = "A new recording of the classic novel, translated by Constance Garnett.")
    private fun ebook(id: String, title: String = "Crime and Punishment - Fyodor Dostoevsky", author: String = "", language: String = "", provider: String = "gutenberg") =
        BookTextSource(id, title, author, "EPUB", provider, language = language)
    private fun judge(edition: BookTextSource, providerId: String = "gutenberg", confirmed: Boolean = true) = NarrationMatch.judge(book, edition, providerId, confirmed)

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

    @Test fun shortenedAdaptedAndSingleVolumeEditionsCountAgainstTheNarration() {
        assertTrue(NarrationSignal.SHORTENED in judge(ebook("abridged", "Crime and Punishment (Abridged) - Fyodor Dostoevsky", language = "en")).signals)
        assertTrue(NarrationSignal.SHORTENED in judge(ebook("sample", "Crime and Punishment - Fyodor Dostoevsky - Free Sample", language = "en")).signals)
        assertFalse(judge(ebook("sample", "Crime and Punishment - Fyodor Dostoevsky - Free Sample", language = "en")).likely)
        assertTrue(NarrationSignal.ADAPTED in judge(ebook("kids", "Crime and Punishment: Retold for Young Readers")).signals)
        assertTrue(NarrationSignal.ONE_PART in judge(ebook("vol", "Crime and Punishment Volume 2 - Fyodor Dostoevsky")).signals)
        val unabridged = judge(ebook("full", "Crime and Punishment (Unabridged) - Fyodor Dostoevsky", language = "en"))
        assertEquals(setOf(NarrationSignal.SAME_LANGUAGE, NarrationSignal.COMPLETE), unabridged.signals)
        // An abridged recording isn't a reason to warn about an abridged ebook.
        val abridged = book.copy(releaseTitle = "Crime and Punishment (Abridged) [MP3]")
        assertTrue(NarrationSignal.SHORTENED !in NarrationMatch.judge(abridged, ebook("a", "Crime and Punishment - abridged"), "gutenberg", true).signals)
    }

    @Test fun translatorsAreComparedWhenBothNameOne() {
        assertEquals(NarrationSignal.SAME_TRANSLATOR in judge(ebook("garnett", author = "Dostoyevsky, Fyodor; Garnett, Constance (Translator)")).signals, true)
        assertTrue(judge(ebook("garnett", author = "Dostoyevsky, Fyodor; Garnett, Constance (Translator)")).likely)
        val other = judge(ebook("pevear", "Crime and Punishment, translated by Richard Pevear and Larissa Volokhonsky", language = "en"))
        assertTrue(NarrationSignal.OTHER_TRANSLATOR in other.signals)
        assertFalse(other.likely)
        assertEquals("maude", NarrationMatch.translator("War and Peace by Leo Tolstoy, Louise Maude (translator)"))
        assertNull(NarrationMatch.translator("Crime and Punishment - Fyodor Dostoevsky"))
    }

    @Test fun languagesCompareByCodeOrName() {
        assertEquals("en", NarrationMatch.language("English"))
        assertEquals("en", NarrationMatch.language("eng"))
        assertEquals("en", NarrationMatch.language("English [en]"))
        assertEquals("en", NarrationMatch.language("en-US"))
        assertEquals("fr", NarrationMatch.language("French"))
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
