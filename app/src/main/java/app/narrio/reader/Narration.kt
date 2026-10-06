package app.narrio.reader

import app.narrio.domain.*
import app.narrio.playback.SyncPhase
import kotlin.math.abs

/**
 * Where narration is in the text: the narrated sentence (a parser passage), the narrated word when narration
 * anchors make it exact, and the mapped character [cursor] itself, which auto-follow keeps on screen.
 */
data class NarratedPlace(val passageId: String, val sentence: CursorRange, val word: CursorRange?, val confidence: MappingConfidence, val cursor: ContentCursor)

/** Read along's mapping from the media clock to the page. Pure, so the rules are testable without a navigator. */
object Narration {
    private const val LEAD_MS = 60L
    private val WORD = Regex("\\S+")

    /**
     * The narrated place for [mapped] in [document]. [words] asks for the word mark, which appears only where the
     * place is EXACT (narration anchors) and the text isn't a supplied timing track, whose cues prove passages only.
     */
    fun place(document: BookText, mapped: MappedText?, words: Boolean): NarratedPlace? {
        val text = mapped?.text ?: return null
        if (mapped.confidence == MappingConfidence.UNMAPPED || text.editionId != document.id) return null
        val passage = passageAt(document, text) ?: return null
        val start = ContentCursor(document.id, passage.resource, passage.offset, document.normalizationVersion)
        val word = if (words && mapped.confidence == MappingConfidence.EXACT && document.timedSourceId.isBlank())
            wordAt(passage.text, text.offset - passage.offset)?.let { range ->
                CursorRange(start.copy(offset = passage.offset + range.first), start.copy(offset = passage.offset + range.last + 1))
            } else null
        return NarratedPlace(passage.id, CursorRange(start, start.copy(offset = passage.offset + passage.text.length)), word, mapped.confidence, text)
    }

    /** The passage holding [cursor], or the next one when the cursor sits between passages of the same resource. */
    fun passageAt(document: BookText, cursor: ContentCursor): TextPassage? {
        var next: TextPassage? = null
        for (chapter in document.chapters) for (passage in chapter.passages) {
            if (passage.resource != cursor.resource) continue
            if (cursor.offset >= passage.offset && cursor.offset < passage.offset + passage.text.length.coerceAtLeast(1)) return passage
            if (passage.offset > cursor.offset && (next == null || passage.offset < next.offset)) next = passage
        }
        return next
    }

    /** Where a tap on [cursor] plays from: the start of its sentence. */
    fun sentenceStart(document: BookText, cursor: ContentCursor): ContentCursor? =
        passageAt(document, cursor)?.let { ContentCursor(document.id, it.resource, it.offset, document.normalizationVersion) }

    /** The word at [index] in [text], or the next word when [index] falls on whitespace. */
    fun wordAt(text: String, index: Int): IntRange? {
        if (index < 0) return null
        return WORD.findAll(text).map { it.range }.firstOrNull { index <= it.last }
    }

    /**
     * Narration's text place for the media clock. Only the playing part is mapped, with its binding resolved
     * against the whole recording first, so an unambiguous default chapter is kept and other parts cost nothing.
     */
    fun mapped(document: BookText, source: AudioSource, partIndex: Int, durationMs: Long, bindings: List<TextBinding>, positionMs: Long): MappedText? {
        val part = source.parts.getOrNull(partIndex) ?: return null
        val binding = bindings.firstOrNull { it.documentId == document.id && it.sourceId == source.id && it.partId == part.id }
            ?: defaultTextBinding(document, source, partIndex) ?: return null
        val measured = part.copy(durationMs = durationMs.takeIf { it > 0 } ?: part.durationMs)
        // Text to audio and back both truncate, so a seek to a sentence's start can map one character short of it.
        // Looking a frame ahead keeps that seek on its own sentence; 60 ms is far below what a listener can hear.
        val ahead = (positionMs + LEAD_MS).coerceAtMost((measured.durationMs - 1).coerceAtLeast(positionMs))
        return MappingEngine.textFor(MappingSnapshot(document, source.copy(parts = listOf(measured)), listOf(binding)), AudioCursor(source.id, part.id, ahead))
    }
}

/** Who leads when reading along starts, and when a move deserves Undo. */
object ReadAlongDecisions {
    /** About one page of characters; within this, starting read along moves the page silently. */
    const val PAGE_CHARS = 1_500

    /** Arriving from the Listening room, or while the book plays, audio leads; otherwise the page on screen does. */
    fun audioLeads(fromListening: Boolean, playing: Boolean): Boolean = fromListening || playing

    /** Moving the reader from [previous] to narration at [narrated] offers Undo when it's more than about a page. */
    fun offersUndo(previous: ContentCursor?, narrated: ContentCursor): Boolean =
        previous != null && (previous.editionId != narrated.editionId || previous.resource != narrated.resource ||
            abs(previous.offset - narrated.offset) > PAGE_CHARS)
}

/** What read along can say about narration right now. [estimated] status lines carry the "≈" treatment. */
data class ReadAlongStatus(val text: String, val estimated: Boolean = false, val highlight: Boolean = true)

object ReadAlongStatuses {
    fun of(pairing: PairingStatus, sameEdition: Boolean, place: NarratedPlace?, phase: SyncPhase, correcting: Boolean): ReadAlongStatus = when {
        !sameEdition -> ReadAlongStatus("Narration follows another edition", highlight = false)
        pairing == PairingStatus.MISMATCH -> ReadAlongStatus("This edition doesn't match the narration", highlight = false)
        place == null && phase == SyncPhase.LISTENING -> ReadAlongStatus("Finding this part in the book…")
        place == null && phase == SyncPhase.DOWNLOADING_MODEL -> ReadAlongStatus("Preparing narration sync…")
        place == null && phase == SyncPhase.NEEDS_MODEL -> ReadAlongStatus("Narration sync needs a download")
        place == null -> ReadAlongStatus("This part isn't placed in the book yet")
        place.confidence == MappingConfidence.EXACT -> ReadAlongStatus("Synced with narration")
        correcting || phase == SyncPhase.LISTENING -> ReadAlongStatus("Syncing with narration…", estimated = true)
        phase == SyncPhase.UNSUPPORTED -> ReadAlongStatus("Estimated · sync supports English", estimated = true)
        else -> ReadAlongStatus("Estimated place", estimated = true)
    }
}
