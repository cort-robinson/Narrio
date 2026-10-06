package app.narrio.domain

import kotlinx.coroutines.flow.StateFlow

/*
 * Shared contract for per-source listening search. See docs/SOURCE_SEARCH.md.
 * Results stream in by provider; a single best match leads for listeners who don't want to compare releases.
 */

enum class SourceProviderKind { BUILT_IN, ADDON }

/**
 * Anything that can contribute listening sources: a built-in source (Internet Archive/LibriVox, the TorBox
 * account library, TorBox search) or an installed audiobook add-on. [order] is the listener's priority: it sets
 * section order and breaks ties when the same release comes from several providers.
 */
data class SourceProvider(
    val id: String,
    val name: String,
    val kind: SourceProviderKind,
    val enabled: Boolean,
    val order: Int,
    val requiresTorBox: Boolean,
    val removable: Boolean,
    /** Last lookup outcome for this provider, for settings; not a health check. */
    val lastStatus: String? = null,
)

/** Listener-controlled provider selection and priority, persisted on the device. */
interface SourceProviderSettings {
    val providers: StateFlow<List<SourceProvider>>
    fun setEnabled(id: String, enabled: Boolean)
    /** Moves [id] to [index] in priority order. */
    fun move(id: String, index: Int)
}

enum class SourceGroupStatus {
    /** Queued behind a per-provider rate limit. */
    WAITING,
    SEARCHING,
    /** Found releases; checking TorBox cache or files. */
    CHECKING,
    DONE,
    FAILED,
    /** Disabled, or needs TorBox while disconnected. */
    SKIPPED,
}

/**
 * One provider's section. A release found by several providers appears once, in the highest-priority group that
 * found it, with the others listed in [alsoFoundBy].
 */
data class SourceGroup(
    val providerId: String,
    val name: String,
    val status: SourceGroupStatus,
    val recordings: List<Audiobook> = emptyList(),
    val possible: List<Audiobook> = emptyList(),
    val alsoFoundBy: Map<String, List<String>> = emptyMap(),
    val message: String? = null,
    val elapsedMs: Long = 0,
)

/** Why the best match was chosen; the UI words these for less technical listeners. */
enum class BestMatchReason {
    ON_PHONE,
    READY_TO_STREAM,
    FREE_PUBLIC_RECORDING,
    NEEDS_PREPARING,
    STRONG_MATCH,
    PREFERRED_FORMAT,
    LANGUAGE_MATCH,
    UNABRIDGED,
    NARRATOR_KNOWN,
    WELL_SEEDED,
}

data class BestMatch(val recording: Audiobook, val reasons: List<BestMatchReason>, val providerId: String)

/**
 * A snapshot of an in-progress or finished search. Emitted repeatedly as providers report. [best] may change while
 * [complete] is false; the UI should avoid moving it under the listener's finger.
 */
data class StreamedSourceSearch(
    val book: Audiobook,
    val groups: List<SourceGroup>,
    val best: BestMatch? = null,
    val complete: Boolean = false,
)

/** One running search. Cancelling [scope] passed to [StreamingSourceSearch.start] stops every provider. */
interface SourceSearchSession {
    val state: StateFlow<StreamedSourceSearch>
    /** Searches one provider again within this session, keeping other groups. */
    fun retry(providerId: String)
}

interface StreamingSourceSearch {
    fun start(book: Audiobook, connected: Boolean, scope: kotlinx.coroutines.CoroutineScope): SourceSearchSession
}
