package app.narrio.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/*
 * Per-source ebook search, the reading counterpart of SourceSearch.kt. See docs/SOURCE_SEARCH.md.
 * Each ebook source fills its own section as it answers; one best match leads for readers who don't want to compare.
 * Providers, their settings, and section statuses are shared with listening search.
 */

/**
 * One ebook source's section. An ebook found by several sources appears once, in the highest-priority group that
 * found it, with the others listed in [alsoFoundBy]. [possible] ebooks need the reader's own check.
 */
data class EbookGroup(
    val providerId: String,
    val name: String,
    val status: SourceGroupStatus,
    val editions: List<BookTextSource> = emptyList(),
    val possible: List<BookTextSource> = emptyList(),
    val alsoFoundBy: Map<String, List<String>> = emptyMap(),
    val message: String? = null,
    val elapsedMs: Long = 0,
    /** A website page where the reader can pass the browser check this source couldn't pass by itself. */
    val checkUrl: String? = null,
)

/** Why the best ebook was chosen; the UI words these for readers. */
enum class EbookMatchReason {
    /** Shipped with the recording, so it's most likely the narrated edition. */
    WITH_RECORDING,
    IN_YOUR_TORBOX,
    CACHED_IN_TORBOX,
    PUBLIC_DOMAIN,
    STRONG_MATCH,
    EPUB,
    LANGUAGE_MATCH,
    /** Named signals point to the narrated text (same language or translator, nothing shortened); alignment still decides. */
    LIKELY_NARRATION,
}

data class BestEbook(val edition: BookTextSource, val reasons: List<EbookMatchReason>, val providerId: String)

/**
 * The recording an ebook should follow, when the caller knows it: [recording] carries its own metadata (language,
 * narrator, release name, description with any translator, abridgment or part, length) and [source] the audio files
 * chosen to play. With it, narration hints and "In this recording's files" use only this recording, never a catalog
 * book's placeholders or another saved recording's files. Hints stay hints: the pairing status decides after adding.
 */
data class NarrationContext(val recording: Audiobook, val source: AudioSource? = null) {
    /** Identifies the recording and file choice, so results for another one are never reused. */
    val key: String get() = "${recording.recordingId.ifBlank { recording.id }}/${source?.id ?: recording.sources.joinToString(",") { it.id }}"
}

/** A snapshot of an in-progress or finished ebook search, emitted as sources report. */
data class StreamedEbookSearch(
    val book: Audiobook,
    val groups: List<EbookGroup>,
    val best: BestEbook? = null,
    val complete: Boolean = false,
)

/** One running ebook search. Cancelling the scope passed to [StreamingEbookSearch.start] stops every source. */
interface EbookSearchSession {
    val state: StateFlow<StreamedEbookSearch>
    /** Searches one source again within this session, keeping the other groups. */
    fun retry(providerId: String)
}

interface StreamingEbookSearch {
    /**
     * [recordings] are the book's audio sources, whose companion files are checked first. [words] are the reader's own
     * search words, used by every source and its retries; blank searches by the book's title and author.
     */
    fun start(book: Audiobook, recordings: List<AudioSource>, connected: Boolean, scope: CoroutineScope, words: String = ""): EbookSearchSession
}
