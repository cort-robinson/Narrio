package app.narrio.data

import app.narrio.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderSourceSearchTest {
    private val book = Audiobook("book", "Project Hail Mary", "Andy Weir", provider = "catalog")
    private fun recording(id: String = "fast", provider: String = "archive", hash: String = "") = book.copy(id = id, provider = provider,
        torrentHash = hash, detailsLoaded = true, cacheState = "cached", cachedFormats = listOf("M4B"), seeders = 3,
        sources = listOf(AudioSource(id, "Audio", "M4B", listOf(AudioPart(id, "book.m4b", "Audio", archiveUrl = "https://example.com/audio")))))
    private fun provider(id: String, order: Int = 0, enabled: Boolean = true, needsTorBox: Boolean = false) =
        SourceProvider(id, id, SourceProviderKind.BUILT_IN, enabled, order, needsTorBox, false)
    private fun settings(vararg providers: SourceProvider) = object : SourceProviderSettings {
        override val providers = MutableStateFlow(providers.toList())
        override fun setEnabled(id: String, enabled: Boolean) = Unit
        override fun move(id: String, index: Int) = Unit
    }
    private fun lookup(block: suspend (String) -> List<Audiobook>) = object : SourceLookup {
        override suspend fun search(book: Audiobook, title: String, budget: SourceSearchBudget, status: suspend (SourceGroupStatus) -> Unit) = budget.run { block(title) }
    }

    @Test fun fastResultsAppearBeforeSlowTimeoutAndFailedProviderDoesNotBlock() = runTest {
        val fast = recording()
        fun legacy(delayMs: Long, releases: List<Audiobook>, fail: Boolean = false) = object : RecordingDiscovery {
            override suspend fun search(query: String, category: String): List<Audiobook> { delay(delayMs); if (fail) error("fixture failure"); return releases }
            override suspend fun recording(id: String) = releases.first()
        }
        val beforeStart = currentTime
        val before = BookSourceDiscovery(legacy(250, listOf(fast)), listOf(legacy(30_000, emptyList()), legacy(1_000, emptyList(), true)),
            { emptyList() }, { it }).search(book, true)
        val beforeMs = currentTime - beforeStart
        assertEquals(fast.id, before.recordings.single().id)
        val start = currentTime
        val engine = ProviderSourceSearch(settings(provider("fast"), provider("slow", 1), provider("failed", 2)), { source ->
            when (source.id) {
                "fast" -> lookup { delay(250); listOf(fast) }
                "slow" -> lookup { delay(30_000); emptyList() }
                else -> lookup { delay(1_000); error("fixture failure") }
            }
        }, { it }, now = { currentTime })
        val session = engine.start(book, true, backgroundScope)
        advanceTimeBy(250); runCurrent()
        assertEquals(fast.id, session.state.value.best!!.recording.id)
        assertFalse(session.state.value.complete)
        assertEquals(SourceGroupStatus.SEARCHING, session.state.value.groups[1].status)
        val firstMs = currentTime - start
        advanceTimeBy(14_750); runCurrent()
        assertTrue(session.state.value.complete)
        assertEquals(SourceGroupStatus.FAILED, session.state.value.groups[1].status)
        assertTrue(session.state.value.groups[1].message!!.contains("timed out"))
        assertEquals(SourceGroupStatus.FAILED, session.state.value.groups[2].status)
        assertEquals(30_000L, beforeMs); assertEquals(250L, firstMs); assertEquals(15_000L, currentTime - start)
        println("Controlled timing: old first/complete=${beforeMs}ms; streamed first=${firstMs}ms, complete=${currentTime - start}ms")
    }

    @Test fun retrySearchesOnlyFailedProviderAndRetainsOtherGroups() = runTest {
        var attempts = 0
        var fastCalls = 0
        val engine = ProviderSourceSearch(settings(provider("good"), provider("retry", 1)), { source ->
            lookup { if (source.id == "good") { fastCalls++; listOf(recording()) } else { attempts++; if (attempts == 1) error("failure"); listOf(recording("retried")) } }
        }, { it }, now = { currentTime })
        val session = engine.start(book, true, backgroundScope)
        runCurrent()
        assertTrue(session.state.value.complete)
        session.retry("good"); session.retry("retry"); session.retry("retry"); runCurrent()
        assertEquals(1, fastCalls); assertEquals(2, attempts)
        assertEquals(listOf("fast", "retried"), session.state.value.groups.flatMap { it.recordings }.map { it.id })
        assertTrue(session.state.value.complete)
        assertEquals(SourceGroupStatus.DONE, session.state.value.groups[1].status)
    }

    @Test fun priorityOwnsDuplicatesRegardlessOfArrivalOrderAndKeepsProvenance() = runTest {
        val hash = "a".repeat(40)
        val engine = ProviderSourceSearch(settings(provider("first"), provider("second", 1)), { source ->
            lookup { if (source.id == "first") delay(1_000); listOf(recording(source.id, "knaben", hash)) }
        }, { it }, now = { currentTime })
        val session = engine.start(book, true, backgroundScope)
        runCurrent()
        assertEquals("second", session.state.value.best!!.providerId)
        advanceTimeBy(1_000); runCurrent()
        val snapshot = session.state.value
        assertEquals("first", snapshot.best!!.providerId)
        assertEquals(1, snapshot.groups.sumOf { it.recordings.size })
        assertEquals(listOf("second"), snapshot.groups[0].alsoFoundBy["first"])
        assertTrue(snapshot.groups[1].recordings.isEmpty())
    }

    @Test fun higherPriorityDiscoveryKeepsReadyEvidenceFoundByLowerPriorityProvider() = runTest {
        val hash = "a".repeat(40)
        val engine = ProviderSourceSearch(settings(provider("first"), provider("second", 1)), { source -> lookup {
            listOf(recording(source.id, "knaben", hash).let { if (source.id == "first") it.copy(cacheState = "uncached", sources = emptyList()) else it })
        } }, { it }, now = { currentTime })
        val session = engine.start(book, true, backgroundScope); runCurrent()
        assertEquals("first", session.state.value.best!!.providerId)
        assertEquals("cached", session.state.value.best!!.recording.cacheState)
        assertTrue(session.state.value.groups[1].recordings.isEmpty())
    }

    @Test fun disabledAndDisconnectedProvidersAreSkippedWithoutAnyLookup() = runTest {
        val engine = ProviderSourceSearch(settings(provider("disabled", enabled = false), provider("account", 1, needsTorBox = true)),
            { error("Must not call skipped sources") }, { error("No cache lookup") }, now = { currentTime })
        val session = engine.start(book, false, backgroundScope); runCurrent()
        assertTrue(session.state.value.complete)
        assertEquals(listOf("Disabled", "Connect TorBox"), session.state.value.groups.map { it.message })
        assertTrue(session.state.value.groups.all { it.status == SourceGroupStatus.SKIPPED })
    }

    @Test fun completedPhoneFilesLeadWhileDisconnectedWithoutClaimingTorBoxCache() = runTest {
        val downloaded = recording("local", "knaben", "a".repeat(40)).copy(cacheState = "unchecked", cachedFormats = emptyList())
        val engine = ProviderSourceSearch(settings(provider("torbox-library", needsTorBox = true), provider("archive", 1)),
            { lookup { listOf(recording("public")) } }, { error("Disconnected") }, { error("Local audio needs no file lookup") },
            phoneRecordings = { listOf(downloaded) }, now = { currentTime })
        val session = engine.start(book, false, backgroundScope); runCurrent()
        assertEquals(downloaded.id, session.state.value.best!!.recording.id)
        assertTrue(BestMatchReason.ON_PHONE in session.state.value.best!!.reasons)
        assertEquals("unchecked", session.state.value.best!!.recording.cacheState)
        assertTrue(session.state.value.best!!.recording.cachedFormats.isEmpty())
        assertEquals(SourceGroupStatus.SKIPPED, session.state.value.groups[0].status)
        assertTrue(session.state.value.complete)
        assertNull(BestMatchRanking.choose(book, listOf(SourceGroup("p", "p", SourceGroupStatus.DONE,
            SourceQuality.filter(book, listOf(downloaded.copy(author = "Someone Else")), downloaded.sources.map { it.id }.toSet())))))
    }

    @Test fun siblingBooksInOneDownloadedTorrentDoNotClaimThisBookIsOnThePhone() = runTest {
        val eragon = book.copy(title = "Eragon", author = "Christopher Paolini")
        val hash = "a".repeat(40)
        val downloaded = recording("download", "knaben", hash).copy(title = "Eragon, Eldest, Brisingr - Christopher Paolini", author = "Author not verified",
            sources = listOf(AudioSource("local-source", "Audio", "M4B", listOf(AudioPart("eldest", "Eldest/Eldest.m4b", "Eldest")), delivery = "torbox")))
        val wanted = downloaded.copy(id = "remote", sources = emptyList(), cacheState = "uncached", cachedFormats = emptyList())
        val engine = ProviderSourceSearch(settings(provider("torbox-library", needsTorBox = true)), { lookup { listOf(wanted) } }, { it },
            phoneRecordings = { listOf(downloaded) }, now = { currentTime })
        val session = engine.start(eragon, true, backgroundScope); runCurrent()
        assertNull(session.state.value.best)
        assertTrue(session.state.value.groups.flatMap { it.recordings }.isEmpty())
    }

    @Test fun newPhoneDownloadReranksFinishedSessionWithoutRepeatingProviderLookups() = runTest {
        var calls = 0
        var phone = emptyList<Audiobook>()
        val updates = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val downloaded = recording("local", "knaben", "a".repeat(40)).copy(cacheState = "unchecked", cachedFormats = emptyList())
        val engine = ProviderSourceSearch(settings(provider("archive"), provider("torbox-library", 1, needsTorBox = true)),
            { lookup { calls++; listOf(recording()) } }, { it }, phoneRecordings = { phone }, rankingChanges = updates, now = { currentTime })
        val session = engine.start(book, false, backgroundScope); runCurrent()
        assertTrue(session.state.value.complete); assertEquals("fast", session.state.value.best!!.recording.id)
        phone = listOf(downloaded); updates.tryEmit(Unit); runCurrent()
        assertEquals("local", session.state.value.best!!.recording.id); assertEquals(1, calls)
        assertTrue(session.state.value.complete)
    }

    @Test fun confidentProviderStopsTitleVariantsButEmptyProviderTriesFallback() = runTest {
        val queries = mutableMapOf<String, MutableList<String>>()
        val detailed = book.copy(title = "Project Hail Mary: A Novel")
        val engine = ProviderSourceSearch(settings(provider("confident"), provider("empty", 1)), { source -> lookup { title ->
            queries.getOrPut(source.id) { mutableListOf() } += title
            if (source.id == "confident") listOf(recording()) else emptyList()
        } }, { it }, now = { currentTime })
        engine.start(detailed, true, backgroundScope); runCurrent()
        assertEquals(listOf(detailed.title), queries["confident"])
        assertEquals(listOf(detailed.title, book.title), queries["empty"])
    }

    @Test fun fileInspectionWaitsForAllSectionsAndIsSkippedWhenReadyAudioArrives() = runTest {
        var fileCalls = 0
        val cloud = recording("cloud", "knaben", "a".repeat(40)).copy(cacheState = "uncached", sources = emptyList())
        val engine = ProviderSourceSearch(settings(provider("cloud"), provider("public", 1)), { source -> lookup {
            if (source.id == "cloud") listOf(cloud) else { delay(1_000); listOf(recording()) }
        } }, { it }, { fileCalls++; error("A ready match exists") }, now = { currentTime })
        val session = engine.start(book, true, backgroundScope); runCurrent()
        assertEquals(0, fileCalls); assertFalse(session.state.value.complete)
        advanceTimeBy(1_000); runCurrent()
        assertTrue(session.state.value.complete); assertEquals(0, fileCalls)
    }

    @Test fun verifiedUncachedFilesProduceNeedsPreparingBestAfterSectionsReport() = runTest {
        val cloud = recording("cloud", "knaben", "a".repeat(40)).copy(cacheState = "uncached", sources = emptyList(),
            magnetUri = "magnet:?xt=urn:btih:${"a".repeat(40)}")
        var loaded = 0
        val engine = ProviderSourceSearch(settings(provider("cloud")), { lookup { listOf(cloud) } }, { it }, {
            loaded++; delay(1_000); it.copy(filesVerified = true, sources = recording().sources)
        }, now = { currentTime })
        val session = engine.start(book, true, backgroundScope); runCurrent()
        assertFalse(session.state.value.complete); assertEquals(SourceGroupStatus.CHECKING, session.state.value.groups.single().status)
        advanceTimeBy(1_000); runCurrent()
        assertTrue(session.state.value.complete); assertEquals(1, loaded)
        assertTrue(BestMatchReason.NEEDS_PREPARING in session.state.value.best!!.reasons)
    }

    @Test fun cancellationStopsEveryProviderAndCheckingWork() = runTest {
        var cancelled = 0
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val engine = ProviderSourceSearch(settings(provider("a"), provider("b", 1)), { lookup {
            try { awaitCancellation() } finally { cancelled++ }
        } }, { it }, now = { currentTime })
        val session = engine.start(book, true, scope); runCurrent(); scope.cancel(); runCurrent()
        assertEquals(2, cancelled)
        advanceTimeBy(30_000); runCurrent()
        assertFalse(session.state.value.complete)
        assertTrue(session.state.value.groups.none { it.status == SourceGroupStatus.FAILED })
    }

    @Test fun rateLimitWaitingDoesNotSpendSearchBudgetAndCanBeCancelled() = runTest {
        val source = object : SourceLookup {
            override suspend fun search(book: Audiobook, title: String, budget: SourceSearchBudget, status: suspend (SourceGroupStatus) -> Unit): List<Audiobook> {
                status(SourceGroupStatus.WAITING); delay(60_000); status(SourceGroupStatus.SEARCHING)
                return budget.run { delay(250); listOf(recording()) }
            }
        }
        val engine = ProviderSourceSearch(settings(provider("limited")), { source }, { it }, now = { currentTime })
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val session = engine.start(book, true, scope); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(SourceGroupStatus.SEARCHING, session.state.value.groups.single().status)
        advanceTimeBy(250); runCurrent(); assertTrue(session.state.value.complete)
        val waiting = engine.start(book, true, scope); runCurrent(); scope.cancel(); runCurrent()
        advanceTimeBy(60_000); runCurrent(); assertEquals(SourceGroupStatus.WAITING, waiting.state.value.groups.single().status)
    }
}
