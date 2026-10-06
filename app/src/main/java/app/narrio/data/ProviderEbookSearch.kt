package app.narrio.data

import app.narrio.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One ebook source. [found] keeps what it reached even when part of the lookup failed with [failure]. */
data class EbookLookupResult(val found: List<EbookCandidate>, val failure: String? = null)

interface EbookLookup {
    suspend fun search(book: Audiobook, recordings: List<AudioSource>, budget: SourceSearchBudget, status: suspend (SourceGroupStatus) -> Unit): EbookLookupResult
}

/** Companion files in the book's recordings; one shipped with the recording is at least a possible match. */
object RecordingEbookLookup : EbookLookup {
    override suspend fun search(book: Audiobook, recordings: List<AudioSource>, budget: SourceSearchBudget, status: suspend (SourceGroupStatus) -> Unit) =
        EbookLookupResult(recordings.flatMap { BookTextFinder.companions(it) }.distinctBy { it.id }
            .map { EbookCandidate(it, maxOf(MatchConfidence.POSSIBLE, EbookMatch.confidence(book, it.title))) })
}

/** Ebooks already in the TorBox account: torrent files and web downloads, each reported separately. */
class AccountEbookLookup(private val finder: BookTextFinder) : EbookLookup {
    override suspend fun search(book: Audiobook, recordings: List<AudioSource>, budget: SourceSearchBudget, status: suspend (SourceGroupStatus) -> Unit): EbookLookupResult {
        var failure: String? = null
        suspend fun read(block: suspend () -> List<EbookCandidate>) = try { budget.run { block() } }
            catch (timedOut: TimeoutCancellationException) { failure = "Ebook lookup timed out. Retry."; emptyList() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failure = failure ?: "Some of your TorBox ebooks could not be checked. Retry."; emptyList() }
        val found = read { finder.accountMatches(book) } + read { finder.webAccountMatches(book) }
        return EbookLookupResult(found, failure)
    }
}

/** An ebook add-on's releases, kept only when TorBox already has their ebook files. Title variants stop early. */
class AddonEbookLookup(private val finder: BookTextFinder, private val addons: AddonManager, private val id: String) : EbookLookup {
    override suspend fun search(book: Audiobook, recordings: List<AudioSource>, budget: SourceSearchBudget, status: suspend (SourceGroupStatus) -> Unit): EbookLookupResult {
        val found = mutableListOf<EbookCandidate>()
        for (title in SourceQuality.searchTitles(book)) {
            val releases = addons.searchEbookAddon(id, book, title, budget, status)
            status(SourceGroupStatus.CHECKING)
            found += budget.run { finder.cachedFiles(book, releases) }
            if (found.any { it.confidence == MatchConfidence.STRONG }) break
            status(SourceGroupStatus.SEARCHING)
        }
        return EbookLookupResult(found)
    }
}

class GutenbergEbookLookup(private val finder: BookTextFinder) : EbookLookup {
    override suspend fun search(book: Audiobook, recordings: List<AudioSource>, budget: SourceSearchBudget, status: suspend (SourceGroupStatus) -> Unit) =
        EbookLookupResult(budget.run { finder.publicMatches(book) })
}

/** Independent ebook sources with shared matching: confirmed ebooks can lead, possible ones wait for the reader. */
class ProviderEbookSearch(
    private val settings: SourceProviderSettings,
    private val lookup: (SourceProvider) -> EbookLookup?,
    private val recordStatus: (String, String) -> Unit = { _, _ -> },
    private val timeoutMs: Long = 15_000,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) : StreamingEbookSearch {
    override fun start(book: Audiobook, recordings: List<AudioSource>, connected: Boolean, scope: CoroutineScope): EbookSearchSession =
        Session(book, recordings, connected, scope)

    private inner class Session(val book: Audiobook, val recordings: List<AudioSource>, connected: Boolean, val scope: CoroutineScope) : EbookSearchSession {
        val providers = settings.providers.value.sortedBy { it.order }
        val groups = providers.associate { provider ->
            val reason = when {
                !provider.enabled -> "Disabled"
                provider.requiresTorBox && !connected -> "Connect TorBox"
                provider.id == DeviceSourceProviderSettings.RECORDING_FILES && recordings.isEmpty() -> "No recording yet"
                else -> null
            }
            provider.id to EbookGroup(provider.id, provider.name, if (reason == null) SourceGroupStatus.SEARCHING else SourceGroupStatus.SKIPPED, message = reason)
        }.toMutableMap()
        val raw = mutableMapOf<String, List<EbookCandidate>>()
        val mutex = Mutex()
        val jobs = mutableMapOf<String, Job>()
        private val mutable = MutableStateFlow(StreamedEbookSearch(book, providers.map { groups.getValue(it.id) }))
        override val state = mutable.asStateFlow()

        init {
            providers.filter { groups.getValue(it.id).status != SourceGroupStatus.SKIPPED }.forEach(::launch)
            scope.launch { mutex.withLock { publish() } }
        }

        override fun retry(providerId: String) {
            scope.launch {
                mutex.withLock {
                    val provider = providers.firstOrNull { it.id == providerId } ?: return@withLock
                    if (groups.getValue(providerId).status != SourceGroupStatus.FAILED || jobs[providerId]?.isActive == true) return@withLock
                    raw.remove(providerId)
                    groups[providerId] = EbookGroup(providerId, provider.name, SourceGroupStatus.SEARCHING)
                    publish(); launch(provider)
                }
            }
        }

        private fun launch(provider: SourceProvider) {
            val job = scope.launch(start = CoroutineStart.LAZY) { runProvider(provider) }
            jobs[provider.id] = job; job.start()
        }

        private suspend fun runProvider(provider: SourceProvider) {
            val started = now()
            suspend fun status(status: SourceGroupStatus) = mutex.withLock {
                groups[provider.id] = groups.getValue(provider.id).copy(status = status, elapsedMs = now() - started)
                publish()
            }
            val result = try {
                val source = lookup(provider) ?: error("Source no longer installed")
                source.search(book, recordings, SourceSearchBudget(timeoutMs, now), ::status)
            } catch (timedOut: TimeoutCancellationException) { EbookLookupResult(emptyList(), "Ebook lookup timed out. Retry.") }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                EbookLookupResult(emptyList(), if (error is java.io.InterruptedIOException) "Ebook lookup timed out. Retry." else "Ebook lookup failed. Retry.")
            }
            mutex.withLock {
                raw[provider.id] = result.found.distinctBy { identity(it.source) }
                groups[provider.id] = groups.getValue(provider.id).copy(status = if (result.failure == null) SourceGroupStatus.DONE else SourceGroupStatus.FAILED,
                    message = result.failure, elapsedMs = now() - started)
                recordStatus(provider.id, result.failure ?: "Done · ${result.found.count { it.confidence == MatchConfidence.STRONG }} matches")
                publish()
            }
        }

        private fun reported() = groups.values.all { it.status in setOf(SourceGroupStatus.DONE, SourceGroupStatus.FAILED, SourceGroupStatus.SKIPPED) }

        /** Called under the mutex. An ebook belongs to the highest-priority source that found it. */
        private fun publish() {
            val confirmed = raw.values.flatten().filter { it.confidence == MatchConfidence.STRONG }.map { identity(it.source) }.toSet()
            val output = providers.associate { it.id to groups.getValue(it.id).copy(editions = emptyList(), possible = emptyList(), alsoFoundBy = emptyMap()) }.toMutableMap()
            val seen = mutableSetOf<String>()
            for (provider in providers) for (candidate in raw[provider.id].orEmpty()) {
                val key = identity(candidate.source)
                if (!seen.add(key)) continue
                val strong = key in confirmed
                // A possible match elsewhere never hides a confirmed one: the confirmed copy is shown.
                val shown = if (strong) raw.getValue(provider.id).firstOrNull { identity(it.source) == key && it.confidence == MatchConfidence.STRONG }?.source
                    ?: raw.values.flatten().first { identity(it.source) == key && it.confidence == MatchConfidence.STRONG }.source else candidate.source
                val others = providers.filter { it.id != provider.id && raw[it.id].orEmpty().any { other -> identity(other.source) == key } }.map { it.name }
                val group = output.getValue(provider.id)
                output[provider.id] = group.copy(editions = group.editions + if (strong) listOf(shown) else emptyList(),
                    possible = group.possible + if (!strong) listOf(shown) else emptyList(),
                    alsoFoundBy = if (others.isEmpty()) group.alsoFoundBy else group.alsoFoundBy + (shown.id to others))
            }
            val ordered = providers.map { output.getValue(it.id) }
            mutable.value = StreamedEbookSearch(book, ordered, EbookRanking.choose(book, ordered), reported())
        }
    }

    companion object {
        /** A cached release's file is the same ebook whichever add-on indexed it. */
        internal fun identity(source: BookTextSource) =
            source.torrentHash.takeIf(String::isNotBlank)?.let { "${it.lowercase()}:${source.fileName}" } ?: source.id
    }
}

/** Pure ranking over confirmed ebooks; possible matches need the reader's own choice. Ties keep source priority. */
object EbookRanking {
    fun choose(book: Audiobook, groups: List<EbookGroup>): BestEbook? {
        fun canonical(value: String) = when (value.lowercase().trim()) { "en", "eng", "english" -> "english"; else -> value.lowercase().trim() }
        fun language(edition: BookTextSource) = when {
            edition.language.isBlank() || BookMetadata.unknown(book.language) -> 1
            canonical(edition.language) == canonical(book.language) -> 2
            else -> 0
        }
        val chosen = groups.flatMap { group -> group.editions.map { it to group.providerId } }
            .sortedWith(compareByDescending<Pair<BookTextSource, String>> { it.second == DeviceSourceProviderSettings.RECORDING_FILES }
                .thenByDescending { language(it.first) }.thenByDescending { it.first.format == "EPUB" }).firstOrNull() ?: return null
        val (edition, providerId) = chosen
        val reasons = buildList {
            when {
                providerId == DeviceSourceProviderSettings.RECORDING_FILES -> add(EbookMatchReason.WITH_RECORDING)
                edition.provider == "torbox-cache" -> add(EbookMatchReason.CACHED_IN_TORBOX)
                edition.provider == "gutenberg" -> add(EbookMatchReason.PUBLIC_DOMAIN)
                edition.provider == "torbox" || edition.provider == "torbox-web" -> add(EbookMatchReason.IN_YOUR_TORBOX)
            }
            add(EbookMatchReason.STRONG_MATCH)
            if (edition.format == "EPUB") add(EbookMatchReason.EPUB)
            if (language(edition) == 2) add(EbookMatchReason.LANGUAGE_MATCH)
        }
        return BestEbook(edition, reasons, providerId)
    }
}
