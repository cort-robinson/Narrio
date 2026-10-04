package app.narrio.domain

import org.junit.Assert.*
import org.junit.Test

class NarrationAlignmentTest {
    private val story = listOf(
        "Mary Lennox was sent to live at Misselthwaite Manor with her uncle.",
        "Nobody seemed to want her, and she was a disagreeable child.",
        "The robin showed her the buried key beside the old wall.",
        "She found the door hidden behind the hanging ivy one windy morning.",
        "Inside the garden every rose bush looked grey and dead.",
        "Dickon told her the roses were wick and would bloom again.",
    ).mapIndexed { index, text -> TextPassage("p$index", text, "garden.xhtml", index * 100) }

    /** Speech as a recognizer hears it: lowercase, one word missed, one misheard, from [startMs]. */
    private fun heard(text: String, startMs: Long, msPerWord: Long = 400) = text.lowercase().replace(Regex("[^a-z ]"), "")
        .split(' ').filter(String::isNotBlank).mapIndexed { i, word -> SpokenWord(word, startMs + i * msPerWord, startMs + i * msPerWord + 300) }

    @Test fun placesRecognizedNarrationAtTheWordsActuallySpoken() {
        val alignment = NarrationAlignment(story)
        val spoken = heard("the robin showed her the buried key beside the old wall she found the door hidden behind hanging ivy one windy morning", 61_000)
            .toMutableList().apply { set(4, SpokenWord("a", this[4].startMs, this[4].endMs)) }
        val anchors = alignment.match(spoken)
        assertTrue(anchors.isNotEmpty())
        assertTrue(anchors.all { it.auto })
        val first = anchors.first()
        assertEquals("p2", first.passageId)
        // "the robin showed" starts the passage, heard at 61 s.
        assertEquals(0, first.word)
        assertEquals(61_000, first.positionMs)
        assertEquals("p3", anchors.last().passageId)
        assertTrue(anchors.zipWithNext().all { (a, b) -> b.positionMs > a.positionMs })
    }

    @Test fun repeatedPassagesAreAmbiguousAndAreNotGuessed() {
        val refrain = "Row row row your boat gently down the stream merrily merrily"
        val song = (0..3).map { TextPassage("verse$it", refrain, "song.xhtml", it * 100) }
        assertTrue(NarrationAlignment(song).match(heard(refrain, 0)).isEmpty())
        // A narrowed range around the expected verse makes the same words unambiguous.
        val alignment = NarrationAlignment(song)
        val third = alignment.passageStart(2)
        val anchors = alignment.match(heard(refrain, 0), third until third + song[2].weight)
        assertEquals("verse2", anchors.first().passageId)
    }

    @Test fun unrelatedOrTooShortSpeechProducesNoAnchors() {
        val alignment = NarrationAlignment(story)
        assertTrue(alignment.match(heard("this chapter is brought to you by a sponsor of the recording", 0)).isEmpty())
        assertTrue(alignment.match(heard("the robin showed", 0)).isEmpty())
    }
}
