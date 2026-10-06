package app.narrio.domain

/** A consistent, edition-scoped view. Implemented by A's repository after integration. */
data class MappingSnapshot(val document: BookText, val source: AudioSource, val bindings: List<TextBinding>)

interface PositionMappingRepository {
    suspend fun snapshot(bookId: String, sourceId: String): MappingSnapshot?
}

/** Never uses progression/Locator JSON as identity or borrows anchors from another pair. */
class NarrationPositionMapper(private val repository: PositionMappingRepository) : PositionMapper {
    override suspend fun audioFor(bookId: String, cursor: ContentCursor, sourceId: String): MappedAudio? =
        repository.snapshot(bookId, sourceId)?.let { MappingEngine.audioFor(it, cursor) }

    override suspend fun textFor(bookId: String, audio: AudioCursor): MappedText? =
        repository.snapshot(bookId, audio.sourceId)?.let { MappingEngine.textFor(it, audio) }
}

object MappingEngine {
    private data class Point(val offset: Int, val time: Long)
    private data class PartMap(val binding: TextBinding, val part: AudioPart, val lines: List<TextPassage>, val timeline: FollowTimeline, val points: List<Point>)

    private fun maps(snapshot: MappingSnapshot): List<PartMap> = snapshot.source.parts.mapIndexedNotNull { index, part ->
        val doc = snapshot.document
        if (doc.chapters.isEmpty()) return@mapIndexedNotNull null
        val binding = snapshot.bindings.firstOrNull { it.documentId == doc.id && it.sourceId == snapshot.source.id && it.partId == part.id }
            ?: defaultTextBinding(doc, snapshot.source, index) ?: return@mapIndexedNotNull null
        val boundLines = FollowAlongTiming.passages(doc, binding.chapterId)
        if (binding.anchors.any { anchor ->
            val line = boundLines.firstOrNull { it.id == anchor.passageId }
            line == null || anchor.word !in 0 until line.weight || anchor.positionMs < 0
        }) return@mapIndexedNotNull null
        val timeline = FollowAlongTiming.timeline(doc, binding, part.durationMs) ?: return@mapIndexedNotNull null
        val lines = timeline.passages
        val bases = lines.runningFold(0) { count, line -> count + line.text.length + 1 }
        val points = binding.anchors.mapNotNull { anchor ->
            val at = lines.indexOfFirst { it.id == anchor.passageId }.takeIf { it >= 0 } ?: return@mapNotNull null
            val words = Regex("\\S+").findAll(lines[at].text).toList()
            if (anchor.word !in words.indices) return@mapNotNull null
            Point(bases[at] + words[anchor.word].range.first, anchor.positionMs)
        }.sortedBy { it.offset }
        PartMap(binding, part, lines, timeline, points)
    }

    private fun offset(map: PartMap, cursor: ContentCursor): Int? {
        var base = 0
        for (line in map.lines) {
            if (line.resource == cursor.resource && cursor.offset in line.offset..line.offset + line.text.length)
                return base + cursor.offset - line.offset
            base += line.text.length + 1
        }
        return null
    }

    private fun cursor(doc: BookText, map: PartMap, offset: Int): ContentCursor {
        var base = 0
        val index = map.lines.indexOfFirst { line ->
            val contains = offset <= base + line.text.length
            if (!contains) base += line.text.length + 1
            contains
        }.takeIf { it >= 0 } ?: map.lines.lastIndex
        val line = map.lines[index]
        val all = doc.chapters.flatMap { it.passages }
        val before = all.takeWhile { it.id != line.id }.sumOf { it.text.length + 1 }
        val inside = (offset - base).coerceIn(0, line.text.length)
        return ContentCursor(doc.id, line.resource, line.offset + inside, doc.normalizationVersion,
            (before + inside).toDouble() / all.sumOf { it.text.length + 1 }.coerceAtLeast(1))
    }

    fun audioFor(snapshot: MappingSnapshot, cursor: ContentCursor): MappedAudio? {
        val doc = snapshot.document
        if (cursor.editionId != doc.id || cursor.normalizationVersion != doc.normalizationVersion || cursor.offset < 0) return null
        val candidates = maps(snapshot).mapNotNull { map -> offset(map, cursor)?.let { Triple(map, it, map.points.any { p -> p.offset == it }) } }
        // First prefer exact anchors, then ranges bounded by anchors. Overlapping evidence is ambiguous.
        val exact = candidates.filter { it.third }
        val bounded = candidates.filter { (map, at, _) -> map.points.size >= 2 && at in map.points.first().offset..map.points.last().offset }
        val fallback = candidates.filter { (map, _, _) ->
            // An auto WHOLE_BOOK binding in a multipart recording only proves its anchored slice.
            snapshot.source.parts.size == 1 || map.binding.chapterId != WHOLE_BOOK || doc.timedSourceId.isNotBlank()
        }
        val (map, at, _) = (exact.ifEmpty { bounded }.ifEmpty { fallback }).singleOrNull() ?: return null
        val anchor = map.points.firstOrNull { it.offset == at }
        // Between anchors interpolate; beyond them use the timeline's measured pace, as textFor does, so a place
        // mapped to audio and back lands on the same passage.
        val bracketed = map.points.size >= 2 && at in map.points.first().offset..map.points.last().offset
        val time = anchor?.time ?: (if (bracketed) interpolate(map.points, at) else null) ?: run {
            val line = map.lines.indexOfFirst { it.resource == cursor.resource && cursor.offset in it.offset..it.offset + it.text.length }
            if (line < 0) return null
            val fraction = (cursor.offset - map.lines[line].offset).toDouble() / map.lines[line].text.length.coerceAtLeast(1)
            map.timeline.starts[line] + ((map.timeline.ends[line] - map.timeline.starts[line]) * fraction).toLong()
        }
        if (time < 0 || (map.part.durationMs > 0 && time >= map.part.durationMs)) return null
        // Supplied timing proves passage start, not a word-level interpolation within it.
        val suppliedStart = map.timeline.timed && map.lines.any { it.resource == cursor.resource && it.offset == cursor.offset }
        return MappedAudio(AudioCursor(snapshot.source.id, map.part.id, time),
            if (anchor != null || suppliedStart) MappingConfidence.EXACT else MappingConfidence.ESTIMATED)
    }

    private fun interpolate(points: List<Point>, at: Int): Long? {
        if (points.size < 2) return null
        val right = points.indexOfFirst { it.offset > at }.let { if (it < 0) points.lastIndex else it.coerceAtLeast(1) }
        val a = points[right - 1]; val b = points[right]
        return a.time + ((at - a.offset).toDouble() / (b.offset - a.offset).coerceAtLeast(1) * (b.time - a.time)).toLong()
    }

    fun textFor(snapshot: MappingSnapshot, audio: AudioCursor): MappedText? {
        if (snapshot.source.id != audio.sourceId || audio.positionMs < 0) return null
        val map = maps(snapshot).firstOrNull { it.part.id == audio.partId } ?: return null
        if (map.part.durationMs > 0 && audio.positionMs >= map.part.durationMs) return null
        val points = map.points
        points.firstOrNull { it.time == audio.positionMs }?.let {
            return MappedText(cursor(snapshot.document, map, it.offset), MappingConfidence.EXACT)
        }
        val right = points.indexOfFirst { it.time > audio.positionMs }
        if (right > 0) {
            val a = points[right - 1]; val b = points[right]
            val at = a.offset + ((audio.positionMs - a.time).toDouble() / (b.time - a.time) * (b.offset - a.offset)).toInt()
            return MappedText(cursor(snapshot.document, map, at),
                if (b.time - a.time <= 180_000) MappingConfidence.EXACT else MappingConfidence.ESTIMATED)
        }
        val index = map.timeline.activeIndex(audio.positionMs) ?: return null
        val line = map.lines[index]
        val base = map.lines.take(index).sumOf { it.text.length + 1 }
        // A supplied cue establishes its passage, not words inferred inside the cue.
        val at = if (map.timeline.timed) base else base + (((audio.positionMs - map.timeline.starts[index]).toDouble() /
            (map.timeline.ends[index] - map.timeline.starts[index]).coerceAtLeast(1)) * line.text.length).toInt()
        return MappedText(cursor(snapshot.document, map, at), if (map.timeline.timed) MappingConfidence.EXACT else MappingConfidence.ESTIMATED)
    }
}
