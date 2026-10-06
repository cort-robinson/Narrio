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
}

data class BestEbook(val edition: BookTextSource, val reasons: List<EbookMatchReason>, val providerId: String)

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
    /** [recordings] are the book's audio sources, whose companion files are checked first. */
    fun start(book: Audiobook, recordings: List<AudioSource>, connected: Boolean, scope: CoroutineScope): EbookSearchSession
}
