package app.narrio.data

import app.narrio.domain.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HiddenReleasesTest {
    private val book = Audiobook("catalog:phm", "Project Hail Mary", "Andy Weir", provider = "catalog")
    private fun release(id: String, hash: Char, seeders: Long, title: String = "Project Hail Mary - Andy Weir") = Audiobook(id, title, "Author not verified",
        provider = "knaben", releaseTitle = title, torrentHash = hash.toString().repeat(40), magnetUri = "magnet:?xt=urn:btih:${hash.toString().repeat(40)}",
        cacheState = "cached", cachedFormats = listOf("M4B"), seeders = seeders, filesVerified = true, detailsLoaded = true,
        sources = listOf(AudioSource("$id-s", "", "M4B", listOf(AudioPart("$id-p", "Project Hail Mary.m4b", "Book")), delivery = "torbox")))
    private val wrong = release("knaben:wrong", 'a', 500, "Project Hail Mary - Andy Weir [Abridged]")
    private val right = release("knaben:right", 'b', 20)

    @Test fun hiddenReleasesLeaveTheBestMatchAndEverySection() {
        val groups = listOf(SourceGroup("search", "Search", SourceGroupStatus.DONE, recordings = listOf(wrong, right), alsoFoundBy = mapOf(wrong.id to listOf("Other"))))
        assertEquals(wrong.id, BestMatchRanking.choose(book, groups)!!.recording.id)
        val keys = setOf(HiddenReleases.key(wrong))
        val left = HiddenReleases.exclude(groups, keys)
        assertEquals(listOf(right.id), left.single().recordings.map { it.id })
        assertTrue(left.single().alsoFoundBy.isEmpty())
        assertEquals(right.id, BestMatchRanking.choose(book, left)!!.recording.id)
        // A book-specific projection of the same torrent is the same release.
        assertTrue(HiddenReleases.hidden(wrong.copy(id = "knaben:wrong:book:x", recordingId = "knaben:wrong:book:x"), keys))
        assertSame(groups, HiddenReleases.exclude(groups, emptySet()))
    }

    @Test fun storeSurvivesRestartAndForgetsTheBook() {
        val values = MemoryValues()
        val store = HiddenReleaseStore(values) { 42 }
        store.hide(book.id, wrong, "Knaben")
        store.hide(book.id, wrong, "Knaben")
        store.hide("other", right)
        val restarted = HiddenReleaseStore(values)
        assertEquals(listOf(HiddenRelease("a".repeat(40), wrong.releaseTitle, "Knaben", 42)), restarted.hidden.value[book.id])
        restarted.unhide(book.id, "a".repeat(40))
        assertNull(restarted.hidden.value[book.id])
        restarted.forget("other")
        assertTrue(HiddenReleaseStore(values).hidden.value.isEmpty())
        assertNull(values.get("hiddenReleases.v1"))
    }

    @Test fun runningSearchDropsAReleaseAsSoonAsItIsHidden() = runTest {
        val store = HiddenReleaseStore(MemoryValues())
        val settings = object : SourceProviderSettings {
            override val providers = MutableStateFlow(listOf(SourceProvider("search", "Search", SourceProviderKind.BUILT_IN, true, 0, false, false)))
            override fun setEnabled(id: String, enabled: Boolean) = Unit
            override fun move(id: String, index: Int) = Unit
        }
        val engine = ProviderSourceSearch(settings, { object : SourceLookup {
            override suspend fun search(book: Audiobook, title: String, budget: SourceSearchBudget, status: suspend (SourceGroupStatus) -> Unit) = listOf(wrong, right)
        } }, { it }, rankingChanges = store.hidden.drop(1).map { }, now = { currentTime }, hidden = store::keys)
        val session = engine.start(book, true, backgroundScope); runCurrent()
        assertEquals(wrong.id, session.state.value.best!!.recording.id)
        store.hide(book.id, session.state.value.best!!.recording); runCurrent()
        assertEquals(right.id, session.state.value.best!!.recording.id)
        assertTrue(session.state.value.groups.flatMap { it.recordings + it.possible }.none { it.torrentHash == wrong.torrentHash })
    }
}
