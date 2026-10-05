package app.narrio.domain

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PositionMappingTest {
    private val lines = (0..2).map { TextPassage("p$it", "one two three four five six seven eight nine ten", "c$it.xhtml", 100) }
    private val doc = BookText("edition", "Book", "Author", "EPUB", "", lines.mapIndexed { i, p -> TextChapter("c$i", "Chapter ${i + 1}", listOf(p)) })
    private val single = AudioSource("recording", "", "MP3", listOf(AudioPart("part", "part.mp3", "Book", 120_000)))
    private fun cursor(index: Int = 0, inside: Int = 0) = ContentCursor(doc.id, lines[index].resource, 100 + inside)
    private fun snapshot(source: AudioSource = single, anchors: List<TextAnchor> = emptyList()) = MappingSnapshot(doc, source,
        if (anchors.isEmpty()) emptyList() else listOf(TextBinding(doc.id, source.id, source.parts.first().id, WHOLE_BOOK, anchors)))
    private fun audio(ms: Long) = AudioCursor(single.id, single.parts.single().id, ms)

    @Test fun noAnchorsEstimatesInBothDirections() {
        assertEquals(MappingConfidence.ESTIMATED, MappingEngine.audioFor(snapshot(), cursor(1))!!.confidence)
        assertEquals(MappingConfidence.ESTIMATED, MappingEngine.textFor(snapshot(), audio(45_000))!!.confidence)
    }
    @Test fun exactWordAnchorUsesCanonicalOffset() {
        val map = snapshot(anchors = listOf(TextAnchor("p0", 10_000, word = 2)))
        assertEquals(MappedAudio(audio(10_000), MappingConfidence.EXACT), MappingEngine.audioFor(map, cursor(inside = 8)))
        assertEquals(108, MappingEngine.textFor(map, audio(10_000))!!.text.offset)
        assertEquals(MappingConfidence.EXACT, MappingEngine.textFor(map, audio(10_000))!!.confidence)
    }
    @Test fun textInterpolationRemainsEstimatedButBracketedNarrationMapsExactly() {
        val map = snapshot(anchors = listOf(TextAnchor("p0", 10_000), TextAnchor("p2", 90_000)))
        assertEquals(50_000, MappingEngine.audioFor(map, cursor(1))!!.audio.positionMs)
        assertEquals(MappingConfidence.ESTIMATED, MappingEngine.audioFor(map, cursor(1))!!.confidence)
        assertEquals(MappingConfidence.EXACT, MappingEngine.textFor(map, audio(50_000))!!.confidence)
    }
    @Test fun extrapolatedNarrationStaysEstimated() {
        val map = snapshot(anchors = listOf(TextAnchor("p1", 40_000, auto = true), TextAnchor("p2", 60_000, auto = true)))
        assertEquals(MappingConfidence.ESTIMATED, MappingEngine.textFor(map, audio(35_000))!!.confidence)
    }
    @Test fun editionNormalizationAndRecordingNeverBorrowEvidence() {
        val map = snapshot()
        assertNull(MappingEngine.audioFor(map, cursor().copy(editionId = "replacement")))
        assertNull(MappingEngine.audioFor(map, cursor().copy(normalizationVersion = 2)))
        assertNull(MappingEngine.audioFor(map, cursor().copy(resource = "missing")))
        assertNull(MappingEngine.textFor(map, audio(500).copy(sourceId = "other")))
        assertNull(MappingEngine.textFor(map, audio(500).copy(partId = "other")))
    }
    @Test fun progressionIsNeverMappingAuthority() {
        assertEquals(MappingEngine.audioFor(snapshot(), cursor(1)), MappingEngine.audioFor(snapshot(), cursor(1).copy(progression = .99, locatorJson = "ignored")))
    }
    @Test fun missingDurationAndInvalidAnchorsAreUnmapped() {
        assertNull(MappingEngine.audioFor(snapshot(single.copy(parts = single.parts.map { it.copy(durationMs = 0) })), cursor()))
        assertNull(MappingEngine.textFor(snapshot(anchors = listOf(TextAnchor("p0", 100_000), TextAnchor("p1", 20_000))), audio(30_000)))
    }
    @Test fun multipartMatchesChapterOrder() {
        val source = single.copy(parts = (0..2).map { AudioPart("part$it", "$it.mp3", "Chapter ${it + 1}", 60_000) })
        assertEquals("part2", MappingEngine.audioFor(snapshot(source), cursor(2))!!.audio.partId)
        assertEquals("c1.xhtml", MappingEngine.textFor(snapshot(source), AudioCursor(source.id, "part1", 15_000))!!.text.resource)
    }
    @Test fun ambiguousMultipartRequiresEvidence() {
        val source = single.copy(parts = (0..1).map { AudioPart("part$it", "$it.mp3", "Track $it", 60_000) })
        assertNull(MappingEngine.audioFor(snapshot(source), cursor(1)))
        assertNull(MappingEngine.textFor(snapshot(source), AudioCursor(source.id, "part1", 15_000)))
        val anchored = MappingSnapshot(doc, source, listOf(TextBinding(doc.id, source.id, "part1", WHOLE_BOOK,
            listOf(TextAnchor("p1", 10_000, auto = true), TextAnchor("p2", 30_000, auto = true)))))
        assertEquals("part1", MappingEngine.audioFor(anchored, cursor(1, 5))!!.audio.partId)
        assertNull(MappingEngine.audioFor(anchored, cursor()))
    }
    @Test fun overlappingMultipartAnchorsAreUnmapped() {
        val source = single.copy(parts = (0..1).map { AudioPart("part$it", "$it.mp3", "Track $it", 60_000) })
        val bindings = source.parts.map { TextBinding(doc.id, source.id, it.id, WHOLE_BOOK, listOf(TextAnchor("p1", 10_000), TextAnchor("p2", 30_000))) }
        assertNull(MappingEngine.audioFor(MappingSnapshot(doc, source, bindings), cursor(1)))
    }
    @Test fun foreignBindingsAreIgnored() {
        val foreign = TextBinding("other-edition", single.id, "part", WHOLE_BOOK, listOf(TextAnchor("p0", 50_000)))
        assertEquals(MappingConfidence.ESTIMATED, MappingEngine.audioFor(MappingSnapshot(doc, single, listOf(foreign)), cursor())!!.confidence)
    }
    @Test fun suppliedCuesRespectPartBindingAndGaps() {
        val timed = doc.copy(timedSourceId = single.id, timedPartId = "part", chapters = listOf(TextChapter("c", "", listOf(
            lines[0].copy(startMs = 10_000, endMs = 20_000), lines[1].copy(startMs = 30_000, endMs = 40_000)))))
        val map = MappingSnapshot(timed, single, emptyList())
        assertEquals(MappingConfidence.EXACT, MappingEngine.textFor(map, audio(15_000))!!.confidence)
        assertEquals(MappingConfidence.EXACT, MappingEngine.audioFor(map, cursor())!!.confidence)
        assertNull(MappingEngine.textFor(map, audio(25_000)))
        assertNull(MappingEngine.textFor(map.copy(source = single.copy(id = "other")), audio(15_000).copy(sourceId = "other")))
    }
    @Test fun mapperUsesRepositoryActiveEdition() = runTest {
        val mapper = NarrationPositionMapper(object : PositionMappingRepository {
            override suspend fun snapshot(bookId: String, sourceId: String) = if (bookId == "book" && sourceId == single.id) snapshot() else null
        })
        assertNotNull(mapper.audioFor("book", cursor(), single.id))
        assertNull(mapper.audioFor("unknown-book", cursor(), single.id))
    }
}
