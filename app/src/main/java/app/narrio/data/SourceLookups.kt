package app.narrio.data

import app.narrio.domain.*

class RecordingSourceLookup(private val discovery: RecordingDiscovery) : SourceLookup {
    override suspend fun search(book: Audiobook, title: String, budget: SourceSearchBudget, status: suspend (SourceGroupStatus) -> Unit) =
        budget.run { discovery.searchBook(book, title) }
    override suspend fun hydrate(recording: Audiobook) = discovery.recording(recording.id)
}

class AccountSourceLookup(private val account: suspend (String) -> List<Audiobook>) : SourceLookup {
    override val titleVariants = false
    override suspend fun search(book: Audiobook, title: String, budget: SourceSearchBudget, status: suspend (SourceGroupStatus) -> Unit) =
        budget.run { account("") }
}

class AddonSourceLookup(private val addons: AddonManager, private val id: String) : SourceLookup {
    override suspend fun search(book: Audiobook, title: String, budget: SourceSearchBudget, status: suspend (SourceGroupStatus) -> Unit) =
        addons.searchAddon(id, book, title, budget, status)
}
