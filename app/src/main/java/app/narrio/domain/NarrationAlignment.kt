package app.narrio.domain

import java.text.Normalizer
import java.util.Locale

/** A recognized word with its time in the audio part. */
data class SpokenWord(val word: String, val startMs: Long, val endMs: Long)

/**
 * Locates recognized narration in book text. Speech recognition misses and substitutes words, so a
 * match needs a run of shared three-word phrases in the same order and one clear location; repeated
 * phrases elsewhere in the book make a window ambiguous and it is skipped rather than guessed.
 */
class NarrationAlignment(val passages: List<TextPassage>) {
    // Word positions count whitespace-separated tokens, matching TextPassage.weight and anchor offsets.
    private val words: List<String>
    private val passageAt: IntArray
    private val wordInPassage: IntArray
    private val trigrams = HashMap<String, MutableList<Int>>()
    val size: Int get() = words.size
    private val starts = mutableListOf<Int>()

    init {
        val tokens = mutableListOf<String>()
        val owners = mutableListOf<Int>()
        val offsets = mutableListOf<Int>()
        passages.forEachIndexed { index, passage ->
            val parts = passage.text.split(WHITESPACE)
            starts += tokens.size
            // Same count as TextPassage.weight, without recompiling its pattern per passage.
            repeat(parts.size.coerceAtLeast(1)) { word ->
                tokens += normalize(parts.getOrElse(word) { "" }); owners += index; offsets += word
            }
        }
        words = tokens
        passageAt = owners.toIntArray()
        wordInPassage = offsets.toIntArray()
        for (i in 0..words.size - 3) key(words, i)?.let { trigrams.getOrPut(it) { mutableListOf() } += i }
    }

    /** First word position of each passage, for converting a timeline estimate into a search range. */
    fun passageStart(index: Int): Int = starts[index]

    /**
     * Returns anchors for the window, or empty when the narration can't be placed confidently.
     * [range] narrows the search around an expected position; the whole text is used otherwise.
     */
    fun match(spoken: List<SpokenWord>, range: IntRange = 0 until size): List<TextAnchor> {
        val heard = spoken.map { it.copy(word = normalize(it.word)) }.filter { it.word.isNotEmpty() }
        val said = heard.map { it.word }
        class Hit(val heard: Int, val text: Int)
        val hits = mutableListOf<Hit>()
        for (i in 0..said.size - 3) {
            val positions = key(said, i)?.let(trigrams::get) ?: continue
            val local = positions.filter { it in range }
            // Phrases this common identify nothing.
            if (local.size in 1..MAX_OCCURRENCES) local.forEach { hits += Hit(i, it) }
        }
        if (hits.isEmpty()) return emptyList()
        // Longest chain in which text and speech advance together, allowing recognition slips.
        val score = IntArray(hits.size) { 1 }
        val previous = IntArray(hits.size) { -1 }
        for (k in hits.indices) for (j in 0 until k) {
            val spokenStep = hits[k].heard - hits[j].heard
            val textStep = hits[k].text - hits[j].text
            if (spokenStep > 0 && textStep > 0 && kotlin.math.abs(textStep - spokenStep) <= 3 + spokenStep / 4 && score[j] + 1 > score[k]) {
                score[k] = score[j] + 1; previous[k] = j
            }
        }
        val best = score.indices.maxBy { score[it] }
        val chain = generateSequence(best) { previous[it].takeIf { p -> p >= 0 } }.map { hits[it] }.toList().asReversed()
        val first = chain.first().text
        val last = chain.last().text
        val rival = hits.indices.filter { hits[it].text !in first - REGION..last + REGION }.maxOfOrNull { score[it] } ?: 0
        if (chain.size < MIN_CHAIN || rival * 2 > chain.size) return emptyList()
        // A few well-separated anchors per window; their words, not passage starts, carry the timing.
        val anchors = mutableListOf<TextAnchor>()
        var lastMs: Long? = null
        for (hit in chain) {
            val ms = heard[hit.heard].startMs
            if (lastMs == null || hit === chain.last() || ms - lastMs >= ANCHOR_SPACING_MS) {
                anchors += TextAnchor(passages[passageAt[hit.text]].id, ms, wordInPassage[hit.text], auto = true)
                lastMs = ms
            }
        }
        return anchors
    }

    /** The word position a passage-level timeline expects at [positionMs], for a narrowed search. */
    fun expected(timeline: FollowTimeline, positionMs: Long): Int? {
        val index = timeline.starts.indexOfLast { it <= positionMs }.takeIf { it >= 0 } ?: return null
        return passageStart(index)
    }

    companion object {
        private const val MAX_OCCURRENCES = 6
        private const val MIN_CHAIN = 4
        private const val REGION = 15
        private const val ANCHOR_SPACING_MS = 4000L

        private val WHITESPACE = Regex("\\s+")
        private val MARKS = Regex("\\p{M}")
        private val SYMBOLS = Regex("[^\\p{L}\\p{N}]")

        fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD).replace(MARKS, "")
            .lowercase(Locale.ROOT).replace(SYMBOLS, "")

        private fun key(words: List<String>, at: Int): String? {
            val a = words[at]; val b = words[at + 1]; val c = words[at + 2]
            if (a.isEmpty() || b.isEmpty() || c.isEmpty()) return null
            return "$a $b $c"
        }
    }
}
