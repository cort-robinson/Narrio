package app.narrio.data

import app.narrio.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Port of the old discovery fixtures through actual streamed sessions, retaining legacy list ordering. */
internal class StreamingSourceFixture(
    archive: RecordingDiscovery,
    indexed: List<RecordingDiscovery>,
    account: suspend (String) -> List<Audiobook>,
    checkCached: suspend (List<Audiobook>) -> List<Audiobook>,
    loadFiles: suspend (Audiobook) -> Audiobook? = { null },
) {
    private val lookups = listOf(RecordingSourceLookup(archive)) + indexed.map(::RecordingSourceLookup) + AccountSourceLookup(account)
    private val providers = lookups.mapIndexed { i, _ -> SourceProvider("p$i", "Provider $i", SourceProviderKind.BUILT_IN, true, i, i != 0, false) }
    private val settings = object : SourceProviderSettings {
        override val providers = MutableStateFlow(this@StreamingSourceFixture.providers)
        override fun setEnabled(id: String, enabled: Boolean) = Unit
        override fun move(id: String, index: Int) = Unit
    }
    private val engine = ProviderSourceSearch(settings, { lookups[it.order] }, checkCached, loadFiles)
    suspend fun search(book: Audiobook, connected: Boolean): BookSourceResults = coroutineScope {
        val job = SupervisorJob(coroutineContext[Job])
        try {
            val session = engine.start(book, connected, CoroutineScope(coroutineContext + job))
            val result = session.state.first { it.complete }
            BookSourceResults(SourceQuality.filter(book, result.groups.flatMap { it.recordings }),
                result.groups.mapNotNull { it.message.takeIf { _ -> it.status == SourceGroupStatus.FAILED } }.distinct().joinToString(" ").ifBlank { null },
                result.groups.flatMap { it.possible }.sortedWith(compareBy<Audiobook> { SourceQuality.confidence(book, it) != MatchConfidence.STRONG }
                    .thenBy { !SourceQuality.ready(it) }.thenByDescending { it.seeders }))
        } finally { job.cancel() }
    }
}
