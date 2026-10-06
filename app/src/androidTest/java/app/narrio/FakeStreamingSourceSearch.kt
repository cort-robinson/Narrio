package app.narrio

import app.narrio.domain.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A controlled per-source search for UI tests (workstream U): every provider reports only when the test calls
 * [FakeStreamingSourceSearch.answer], so slow, failing, and waiting sources are deterministic. No network.
 */
class FakeStreamingSourceSearch(private val script: List<Provider>) : StreamingSourceSearch {
    /** One provider's scripted outcome. Lower [rank] wins best match. [failure] fails it; [retried] answers a retry. */
    data class Provider(
        val id: String, val name: String, val recordings: List<Audiobook> = emptyList(), val possible: List<Audiobook> = emptyList(),
        val failure: String? = null, val retried: List<Audiobook> = emptyList(), val skipped: Boolean = false, val waiting: Boolean = false,
        val rank: Int = 0, val reasons: List<BestMatchReason> = listOf(BestMatchReason.READY_TO_STREAM, BestMatchReason.PREFERRED_FORMAT),
        val alsoFoundBy: Map<String, List<String>> = emptyMap(),
    )

    private val gates = script.associate { it.id to CompletableDeferred<Unit>() }.toMutableMap()
    lateinit var session: Session; private set

    fun answer(id: String) { gates[id]?.complete(Unit) }

    override fun start(book: Audiobook, connected: Boolean, scope: CoroutineScope): SourceSearchSession = Session(book, scope).also { session = it }

    inner class Session(book: Audiobook, private val scope: CoroutineScope) : SourceSearchSession {
        private val flow = MutableStateFlow(StreamedSourceSearch(book, script.map { provider ->
            SourceGroup(provider.id, provider.name, when { provider.skipped -> SourceGroupStatus.SKIPPED; provider.waiting -> SourceGroupStatus.WAITING; else -> SourceGroupStatus.SEARCHING })
        }))
        override val state: StateFlow<StreamedSourceSearch> = flow.asStateFlow()
        private val retries = mutableSetOf<String>()

        init { script.filterNot { it.skipped }.forEach { provider -> scope.launch { run(provider) } } }

        private suspend fun run(provider: Provider) {
            gates.getValue(provider.id).await()
            val retry = provider.id in retries
            val failed = provider.failure != null && !retry
            report(provider.id) {
                if (failed) it.copy(status = SourceGroupStatus.FAILED, message = provider.failure)
                else it.copy(status = SourceGroupStatus.DONE, recordings = if (retry) provider.retried else provider.recordings, possible = provider.possible, alsoFoundBy = provider.alsoFoundBy)
            }
        }

        override fun retry(providerId: String) {
            val provider = script.firstOrNull { it.id == providerId } ?: return
            retries += providerId
            gates[providerId] = CompletableDeferred()
            report(providerId) { it.copy(status = SourceGroupStatus.SEARCHING, message = null) }
            scope.launch { run(provider) }
        }

        private fun report(id: String, change: (SourceGroup) -> SourceGroup) = flow.update { search ->
            val groups = search.groups.map { if (it.providerId == id) change(it) else it }
            val best = script.filter { provider -> groups.first { it.providerId == provider.id }.recordings.isNotEmpty() }.minByOrNull { it.rank }?.let { provider ->
                BestMatch(groups.first { it.providerId == provider.id }.recordings.first(), provider.reasons, provider.id)
            }
            search.copy(groups = groups, best = best, complete = groups.none { it.status in setOf(SourceGroupStatus.SEARCHING, SourceGroupStatus.WAITING, SourceGroupStatus.CHECKING) })
        }
    }
}
