package app.narrio.data

import app.narrio.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Active request time is bounded; declared add-on rate-limit waits do not spend this budget. */
class SourceSearchBudget(private var remainingMs: Long = 15_000, private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    suspend fun <T> run(block: suspend () -> T): T {
        val started = now()
        return try { withTimeout(remainingMs.coerceAtLeast(0)) { block() } }
        finally { remainingMs -= (now() - started).coerceAtLeast(0) }
    }
}

interface SourceLookup {
    val titleVariants: Boolean get() = true
    suspend fun search(book: Audiobook, title: String, budget: SourceSearchBudget, status: suspend (SourceGroupStatus) -> Unit): List<Audiobook>
    suspend fun hydrate(recording: Audiobook): Audiobook = recording
}

/** Independent provider work, shared matching rules, and a final optional uncached-file pass. */
class ProviderSourceSearch(
    private val settings: SourceProviderSettings,
    private val lookup: (SourceProvider) -> SourceLookup?,
    private val checkCached: suspend (List<Audiobook>) -> List<Audiobook>,
    private val loadFiles: suspend (Audiobook) -> Audiobook? = { null },
    private val recordStatus: (String, String) -> Unit = { _, _ -> },
    private val onPhone: () -> Set<String> = { emptySet() },
    private val phoneRecordings: () -> List<Audiobook> = { emptyList() },
    private val rankingChanges: Flow<Unit> = emptyFlow(),
    private val preferredFormat: (Audiobook) -> String = { "M4B" },
    private val timeoutMs: Long = 15_000,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Ids of the recording the listener already uses for a book; it stays the best match. */
    private val listening: (Audiobook) -> Set<String> = { emptySet() },
) : StreamingSourceSearch {
    override fun start(book: Audiobook, connected: Boolean, scope: CoroutineScope): SourceSearchSession = Session(book, connected, scope)

    private inner class Session(val book: Audiobook, val connected: Boolean, val scope: CoroutineScope) : SourceSearchSession {
        val providers = settings.providers.value.sortedBy { it.order }
        val groups = providers.associate { provider -> provider.id to SourceGroup(provider.id, provider.name,
            if (!provider.enabled || provider.requiresTorBox && !connected) SourceGroupStatus.SKIPPED else SourceGroupStatus.SEARCHING,
            message = when { !provider.enabled -> "Disabled"; provider.requiresTorBox && !connected -> "Connect TorBox"; else -> null }) }.toMutableMap()
        val raw = mutableMapOf<String, List<Audiobook>>()
        val mutex = Mutex()
        val jobs = mutableMapOf<String, Job>()
        var finishing: Job? = null
        var generation = 0
        var inspected = false
        var inspecting = false
        private val mutable = MutableStateFlow(StreamedSourceSearch(book, providers.map { groups.getValue(it.id) }))
        override val state = mutable.asStateFlow()

        init {
            providers.filter { groups.getValue(it.id).status != SourceGroupStatus.SKIPPED }.forEach { launch(it) }
            scope.launch { mutex.withLock { publish(); finishIfReported() } }
            scope.launch { rankingChanges.collect { mutex.withLock { publish() } } }
        }

        override fun retry(providerId: String) {
            scope.launch {
                mutex.withLock {
                    val provider = providers.firstOrNull { it.id == providerId } ?: return@withLock
                    if (groups.getValue(providerId).status != SourceGroupStatus.FAILED || jobs[providerId]?.isActive == true) return@withLock
                    finishing?.cancel(); finishing = null; generation++; inspected = false; inspecting = false
                    groups.replaceAll { _, group -> if (group.status == SourceGroupStatus.CHECKING) group.copy(status = SourceGroupStatus.DONE) else group }
                    raw.remove(providerId)
                    groups[providerId] = SourceGroup(providerId, provider.name, SourceGroupStatus.SEARCHING)
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
            val collected = mutableListOf<Audiobook>()
            var failure: String? = null
            suspend fun status(status: SourceGroupStatus) = mutex.withLock {
                groups[provider.id] = groups.getValue(provider.id).copy(status = status, elapsedMs = now() - started)
                publish()
            }
            try {
                val source = lookup(provider) ?: error("Provider no longer installed")
                val budget = SourceSearchBudget(timeoutMs, now)
                val titles = if (source.titleVariants) SourceQuality.searchTitles(book) else listOf("")
                for (title in titles) {
                    try {
                        val found = source.search(book, title, budget, ::status).filter { SourceQuality.isCandidate(book, it) }
                        status(SourceGroupStatus.CHECKING)
                        val checked = withTimeout(timeoutMs) {
                            val public = found.filter { it.provider == "archive" }.take(12).chunked(4).flatMap { batch ->
                                coroutineScope { batch.map { recording -> async {
                                    try { if (recording.detailsLoaded) recording else source.hydrate(recording) }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { failure = "Some recording details could not be checked. Retry."; null }
                                } }.awaitAll().filterNotNull() }
                            }
                            val indexed = found.filter { it.provider == "knaben" }
                            val cloud = if (indexed.isEmpty()) emptyList() else try { checkCached(indexed) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) {
                                failure = "TorBox availability could not be checked. Retry to check which releases have audio."
                                indexed.map { it.copy(cacheState = "unchecked", cachedFormats = emptyList(), sources = emptyList(), filesVerified = false) }
                            }
                            public + cloud + found.filter { it.provider != "archive" && it.provider != "knaben" }
                        }
                        collected += checked
                        mutex.withLock { raw[provider.id] = collected.distinctBy { it.id }; publish() }
                        if (collected.any { SourceQuality.matches(book, it) } || SourceQuality.filter(book, collected).isNotEmpty()) break
                    } catch (timedOut: TimeoutCancellationException) { failure = "Source lookup timed out. Retry."; break }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { failure = if (error is java.io.InterruptedIOException) "Source lookup timed out. Retry." else "Source lookup failed. Retry." }
                    status(SourceGroupStatus.SEARCHING)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failure = "Source lookup failed. Retry." }
            mutex.withLock {
                raw[provider.id] = collected.distinctBy { it.id }
                groups[provider.id] = groups.getValue(provider.id).copy(status = if (failure == null) SourceGroupStatus.DONE else SourceGroupStatus.FAILED,
                    message = failure, elapsedMs = now() - started)
                recordStatus(provider.id, failure ?: "Done · ${SourceQuality.filter(book, collected).size} matches")
                publish(); finishIfReported()
            }
        }

        private fun reported() = groups.values.all { it.status in setOf(SourceGroupStatus.DONE, SourceGroupStatus.FAILED, SourceGroupStatus.SKIPPED) }

        /** Called under the mutex, once every provider has finished discovery and ready checks. */
        private fun finishIfReported() {
            if (!reported() || inspected || finishing?.isActive == true) return
            inspected = true
            if (mutable.value.best?.reasons?.contains(BestMatchReason.ON_PHONE) == true ||
                mutable.value.groups.any { it.recordings.any(SourceQuality::ready) }) { publish(); return }
            val candidates = raw.entries.flatMap { (id, recordings) -> recordings.map { id to it } }
                .filter { (_, recording) -> recording.provider == "knaben" && recording.cacheState == "uncached" && recording.seeders > 0 }
                .sortedWith(compareBy<Pair<String, Audiobook>> { !SourceQuality.matches(book, it.second) }.thenByDescending { it.second.seeders })
                .distinctBy { identity(it.second) }.take(8)
            if (candidates.isEmpty()) { publish(); return }
            val epoch = generation
            inspecting = true
            val affected = candidates.map { it.first }.toSet()
            affected.forEach { id -> if (groups.getValue(id).status != SourceGroupStatus.FAILED) groups[id] = groups.getValue(id).copy(status = SourceGroupStatus.CHECKING) }
            publish()
            finishing = scope.launch {
                val verified = coroutineScope { candidates.map { (id, recording) -> async {
                    try { identity(recording) to withTimeout(timeoutMs) { loadFiles(recording) } }
                    catch (timedOut: TimeoutCancellationException) { inspectionFailure(id, "File lookup timed out. Retry.", epoch); identity(recording) to null }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { inspectionFailure(id, "Audio files could not be checked. Retry.", epoch); identity(recording) to null }
                } }.awaitAll().toMap() }
                mutex.withLock {
                    if (generation != epoch) return@withLock
                    raw.replaceAll { _, recordings -> recordings.map { verified[identity(it)] ?: it } }
                    affected.forEach { id ->
                        if (groups.getValue(id).status == SourceGroupStatus.CHECKING) {
                            groups[id] = groups.getValue(id).copy(status = SourceGroupStatus.DONE)
                            recordStatus(id, "Done · ${SourceQuality.filter(book, raw[id].orEmpty()).size} matches")
                        }
                    }
                    inspecting = false
                    publish()
                }
            }
        }

        private suspend fun inspectionFailure(id: String, message: String, epoch: Int) = mutex.withLock {
            if (generation == epoch) {
                groups[id] = groups.getValue(id).copy(status = SourceGroupStatus.FAILED, message = message)
                recordStatus(id, message)
            }
        }

        /** Ownership follows priority; better verified delivery evidence can come from another section. */
        private fun publish() {
            val phone = phoneRecordings()
            val phoneSources = phone.flatMap { it.sources }.map { it.id }.toSet()
            val matchedPhone = SourceQuality.filter(book, phone, phoneSources)
            val phoneIds = onPhone() + matchedPhone.flatMap { listOf(it.id, it.recordingId, identity(it)) }.filter(String::isNotBlank)
            val combined = raw.toMutableMap()
            matchedPhone.forEach { recording ->
                val id = if (recording.provider == "archive") DeviceSourceProviderSettings.ARCHIVE else DeviceSourceProviderSettings.LIBRARY
                if (id in groups && SourceQuality.isCandidate(book, recording)) combined[id] = listOf(recording) + combined[id].orEmpty()
            }
            val qualified = combined.mapValues { SourceQuality.filter(book, it.value, phoneSources) }
            val allQualified = qualified.values.flatten().map(::identity).toSet()
            val possible = combined.mapValues { (_, recordings) -> recordings.filter {
                identity(it) !in allQualified && SourceQuality.confidence(book, it) != MatchConfidence.NONE &&
                    (it.provider != "knaben" || it.cacheState == "cached" || it.seeders > 0)
            }.distinctBy(::identity).sortedWith(compareBy<Audiobook> { SourceQuality.confidence(book, it) != MatchConfidence.STRONG }
                .thenBy { !SourceQuality.ready(it) }.thenByDescending { it.seeders }).take(20) }
            val visible = (qualified.values.flatten() + possible.values.flatten()).groupBy(::identity)
            val output = providers.associate { it.id to groups.getValue(it.id).copy(recordings = emptyList(), possible = emptyList(), alsoFoundBy = emptyMap()) }.toMutableMap()
            for ((key, releases) in visible) {
                val origins = providers.filter { provider -> combined[provider.id].orEmpty().any { identity(it) == key } }
                val owner = origins.firstOrNull() ?: continue
                val confirmed = key in allQualified
                val owned = (if (confirmed) qualified else possible)[owner.id].orEmpty().firstOrNull { identity(it) == key }
                val recording = releases.firstOrNull { it.sources.any { source -> source.id in phoneSources } }
                    ?: owned?.takeIf { !confirmed || SourceQuality.ready(it) } ?: releases.firstOrNull { SourceQuality.ready(it) } ?: owned ?: releases.first()
                val group = output.getValue(owner.id)
                output[owner.id] = group.copy(recordings = group.recordings + if (confirmed) listOf(recording) else emptyList(),
                    possible = group.possible + if (!confirmed) listOf(recording) else emptyList(),
                    alsoFoundBy = group.alsoFoundBy + (recording.id to origins.drop(1).map { it.name }))
            }
            val ordered = providers.map { output.getValue(it.id) }
            mutable.value = StreamedSourceSearch(book, ordered, BestMatchRanking.choose(book, ordered, phoneIds, preferredFormat(book), listening(book)), reported() && inspected && !inspecting)
        }
    }

    companion object {
        internal fun identity(recording: Audiobook) = recording.torrentHash.takeIf(String::isNotBlank)?.lowercase() ?: recording.recordingId.ifBlank { recording.id }
    }
}
