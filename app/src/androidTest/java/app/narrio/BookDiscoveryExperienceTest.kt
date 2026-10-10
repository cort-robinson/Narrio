package app.narrio

import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.narrio.domain.*
import app.narrio.data.*
import app.narrio.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Controlled catalog/source fixtures. Display tests inject selection/results; lookup regressions exercise
 * the ViewModel and matcher with a synthetic public provider, without live audio-provider or delivery calls.
 */
@RunWith(AndroidJUnit4::class)
class BookDiscoveryExperienceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val vm get() = ViewModelProvider(compose.activity)[NarrioViewModel::class.java]
    private val book = Audiobook("catalog:fixture", "Project Hail Mary", "Andy Weir", provider = "catalog", detailsLoaded = true,
        description = "A lone astronaut must save the Earth.", metadataSource = "Test catalog", metadataUrl = "https://example.com/book", metadataUpdatedAtMs = System.currentTimeMillis())
    private fun recording(id: String, title: String, narrator: String) = Audiobook(id, title, "Andy Weir", narrator = narrator, detailsLoaded = true,
        sources = listOf(AudioSource("$id-layout", "Whole-book audio", "M4B", listOf(AudioPart("$id-part", "book.m4b", "Whole book", archiveUrl = "https://example.com/$id.m4b")))))
    /** Lazy rows below the fold aren't composed until the details list scrolls to them. */
    private fun reveal(matcher: SemanticsMatcher) = compose.onNode(hasScrollToNodeAction()).performScrollToNode(matcher)
    private fun show(state: SourceSearchState) = compose.runOnIdle { vm.connected.value = false; vm.selection.value = SelectionState(book); vm.sourceSearch.value = state }
    /** An engine snapshot: one public section holding [found] and [possible], with [best] leading. */
    private fun snapshot(target: Audiobook, found: List<Audiobook>, possible: List<Audiobook> = emptyList(), best: Audiobook? = found.firstOrNull(),
                         reasons: List<BestMatchReason> = listOf(BestMatchReason.FREE_PUBLIC_RECORDING, BestMatchReason.NARRATOR_KNOWN), provider: String = "archive", complete: Boolean = true) =
        SourceSearchState(book = target).withSnapshot(StreamedSourceSearch(target, listOf(SourceGroup(provider, "Internet Archive / LibriVox", SourceGroupStatus.DONE, found, possible)),
            best?.let { BestMatch(it, reasons, provider) }, complete))

    private val fixtureStore = ViewModelStore()
    private val fixtureBooks = mutableSetOf<String>()
    @After fun cleanLookupFixtures() = runBlocking {
        compose.runOnIdle { fixtureStore.clear() }
        fixtureBooks.forEach { vm.graph.followAlong.remove(it); vm.graph.library.remove(it); vm.graph.listeningRecordings.remove(it) }
    }

    /** Runs the real matcher against one public fixture recording; no live audio provider or delivery call. */
    private fun lookupFixture(calls: AtomicInteger): NarrioViewModel {
        val audio = recording("lookup-fixture", book.title, "Fixture Reader")
        val archive = object : RecordingDiscovery {
            override suspend fun search(query: String, category: String): List<Audiobook> {
                calls.incrementAndGet()
                return listOf(audio)
            }
            override suspend fun recording(id: String): Audiobook = error("The fixture is already hydrated")
        }
        val settings = object : SourceProviderSettings {
            override val providers = MutableStateFlow(listOf(SourceProvider("archive", "Fixture", SourceProviderKind.BUILT_IN, true, 0, false, false)))
            override fun setEnabled(id: String, enabled: Boolean) = Unit
            override fun move(id: String, index: Int) = Unit
        }
        val discovery = ProviderSourceSearch(settings, { RecordingSourceLookup(archive) }, { error("No torrents") })
        lateinit var fixture: NarrioViewModel
        compose.runOnIdle {
            fixture = NarrioViewModel(compose.activity.application, discovery)
            fixtureStore.put("lookup", fixture)
            fixture.connected.value = false
            compose.activity.setContent { NarrioApp(compose.activity, fixture) }
        }
        return fixture
    }

    /** A saved catalog book could have follow-along text before ever choosing an audio source. */
    private fun migrateCatalogText(): Audiobook = runBlocking {
        val context = compose.activity
        val name = "catalog-lookup-v4.db"
        val catalogBook = book.copy(id = "catalog:lookup-migrated")
        fixtureBooks += catalogBook.id
        context.deleteDatabase(name)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val schema = NarrioJson.parseToJsonElement(instrumentation.context.assets.open("app.narrio.data.LibraryDatabase/4.json")
            .bufferedReader().use { it.readText() }).jsonObject["database"]!!.jsonObject
        val bytes = "Chapter One\n\nA saved follow-along edition.".toByteArray()
        val document = BookTextParser.parse(bytes, "TXT", catalogBook.title, catalogBook.author)
        val folder = File(context.filesDir, "follow-along/${BookTextParser.fingerprint(catalogBook.id.toByteArray())}").apply { mkdirs() }
        File(folder, "${document.id}.txt").writeBytes(bytes)
        File(folder, "${document.id}.json").writeText(NarrioJson.encodeToString(document))
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            schema.objects("entities").forEach { table ->
                old.execSQL(table.text("createSql").replace("\${TABLE_NAME}", table.text("tableName")))
                table.objects("indices").forEach { old.execSQL(it.text("createSql").replace("\${TABLE_NAME}", table.text("tableName"))) }
            }
            old.execSQL("INSERT INTO shelf VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any>(catalogBook.id, NarrioJson.encodeToString(catalogBook), "", "", 0, 1, 0, "saved", 0, ""))
            old.execSQL("INSERT INTO book_text VALUES (?, ?)", arrayOf(catalogBook.id, document.id))
            old.version = 4
        }
        val migrated = Room.databaseBuilder(context, LibraryDatabase::class.java, name).addMigrations(LibraryMigration4To5, LibraryMigration5To6).build()
        try {
            val dao = migrated.library()
            assertFalse(dao.find(catalogBook.id)!!.hasAudio)
            assertEquals("TXT", dao.editions(catalogBook.id).single().format)
            // Copy the migrated rows into the app's isolated test library to exercise RoomReadingLibrary + open.
            vm.graph.library.save(dao.find(catalogBook.id)!!.book())
            dao.editions(catalogBook.id).forEach { vm.graph.library.putEdition(it) }
            vm.graph.library.putBookText(dao.bookText(catalogBook.id)!!)
        } finally { migrated.close(); context.deleteDatabase(name) }
        catalogBook
    }

    @Test fun openingMigratedCatalogWithTextFindsAudioAndReusesResults() {
        val migrated = migrateCatalogText()
        val calls = AtomicInteger()
        val fixture = lookupFixture(calls)
        val formats = runBlocking { fixture.readingLibrary.value.observeBook(migrated).first() }
        assertTrue(formats.ebook)
        assertFalse(formats.audio)
        compose.runOnIdle { fixture.open(migrated) }
        compose.waitUntil(5_000) { fixture.sourceSearch.value.searched && !fixture.sourceSearch.value.loading }
        assertEquals("lookup-fixture", fixture.sourceSearch.value.choice?.id)
        compose.onNodeWithText("Listen").performScrollTo().assertIsEnabled()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.filesDir, "audio-lookup-regression.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        assertEquals(1, calls.get())
        compose.runOnIdle { fixture.open(migrated) }
        compose.waitUntil(5_000) { fixture.sourceSearch.value.choice != null }
        assertEquals("Returning to the parent book reuses its lookup", 1, calls.get())
        // The chooser on the same page offers those results without looking again.
        compose.onNodeWithTag("change-recording").performScrollTo().performClick()
        compose.onNodeWithTag("recording:lookup-fixture").assertIsDisplayed()
        compose.onNodeWithTag("chooser-listen").assertTextContains("Listen to this recording")
        assertEquals(1, calls.get())
        assertEquals(migrated.id, fixture.selection.value.book?.id)
    }

    @Test fun catalogLookupStartsWithoutWaitingForReadingLibrary() {
        val calls = AtomicInteger()
        val fixture = lookupFixture(calls)
        compose.runOnIdle {
            fixture.readingLibrary.value = object : ReadingLibrary by fixture.readingLibrary.value {
                override fun observeBook(book: Audiobook): Flow<BookFormats> = flow { awaitCancellation() }
            }
            fixture.open(book)
        }
        compose.waitUntil(5_000) { fixture.sourceSearch.value.choice != null }
        assertEquals(1, calls.get())
    }

    @Test fun findAudiobookButtonRunsLookupForBookWithEbook() {
        val migrated = migrateCatalogText()
        val calls = AtomicInteger()
        val fixture = lookupFixture(calls)
        // An unsearched details state still exposes the explicit action (e.g. restored/injected selection).
        compose.runOnIdle { fixture.selection.value = SelectionState(migrated); fixture.sourceSearch.value = SourceSearchState(migrated) }
        compose.waitUntil(5_000) { fixture.detailFormats.value?.ebook == true }
        compose.onNodeWithTag("find-audiobook-action").performScrollTo().performClick()
        compose.waitUntil(5_000) { fixture.sourceSearch.value.choice != null }
        assertEquals(1, calls.get())
        compose.onNodeWithText("Listen").performScrollTo().assertIsEnabled()
    }

    @Test fun automaticStreamDoesNotWaitForEbookFormatsAndSettingsInvalidateCachedResults() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val recording = recording("automatic-stream", "Project Hail Mary", "Fixture Reader")
        val provider = SourceProvider("archive", "Fixture", SourceProviderKind.BUILT_IN, true, 0, false, false)
        val settings = object : SourceProviderSettings {
            override val providers = MutableStateFlow(listOf(provider))
            override fun setEnabled(id: String, enabled: Boolean) = Unit
            override fun move(id: String, index: Int) = Unit
        }
        val discovery = object : RecordingDiscovery {
            override suspend fun search(query: String, category: String): List<Audiobook> { calls.incrementAndGet(); return listOf(recording) }
            override suspend fun recording(id: String) = recording
        }
        val store = androidx.lifecycle.ViewModelStore()
        lateinit var fixture: NarrioViewModel
        val graph = (compose.activity.application as NarrioApplication).graph
        val original = graph.sourceProviderSettings.providers.value.first { it.id == "archive" }.enabled
        try {
            compose.runOnIdle {
                fixture = NarrioViewModel(compose.activity.application, ProviderSourceSearch(settings, { RecordingSourceLookup(discovery) }, { it }))
                store.put("stream-fixture", fixture)
                fixture.connected.value = false
                fixture.readingLibrary.value = object : ReadingLibrary by fixture.readingLibrary.value {
                    override fun observeBook(book: Audiobook): Flow<BookFormats> = flow { awaitCancellation() }
                }
                fixture.open(book)
            }
            compose.waitUntil(5_000) { fixture.sourceSearch.value.streamed?.complete == true }
            compose.runOnIdle { assertEquals(recording, fixture.sourceSearch.value.choice); fixture.open(book) }
            compose.waitUntil(5_000) { fixture.sourceSearch.value.streamed?.complete == true }
            assertEquals(1, calls.get())
            compose.runOnIdle { graph.sourceProviderSettings.setEnabled("archive", !original) }
            compose.waitUntil(5_000) { calls.get() == 2 && fixture.sourceSearch.value.streamed?.complete == true }
            assertEquals(recording, fixture.sourceSearch.value.choice)
        } finally {
            compose.runOnIdle { store.clear(); graph.sourceProviderSettings.setEnabled("archive", original) }
        }
    }

    @Test fun streamedBestKeepsDetailsAndExplicitVersionChoiceCompatible() {
        val fast = recording("streamed-fast", "Project Hail Mary", "Fixture Reader")
        val better = recording("streamed-better", "Project Hail Mary (version 2)", "Second Reader")
        fun snapshot(best: Audiobook, complete: Boolean) = StreamedSourceSearch(book,
            listOf(SourceGroup("archive", "LibriVox", SourceGroupStatus.DONE, if (best == fast) listOf(fast) else listOf(better, fast))),
            BestMatch(best, listOf(BestMatchReason.READY_TO_STREAM, BestMatchReason.FREE_PUBLIC_RECORDING), "archive"), complete)
        show(SourceSearchState(book = book).withSnapshot(snapshot(fast, false)))
        compose.runOnIdle { assertEquals(fast, vm.sourceSearch.value.choice); vm.chooseVersion(fast) }
        compose.runOnIdle { vm.sourceSearch.value = vm.sourceSearch.value.withSnapshot(snapshot(better, true)) }
        compose.onNodeWithText("Listen").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Read by Fixture Reader", substring = true).performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(fast, vm.sourceSearch.value.choice)
            assertEquals(better, vm.sourceSearch.value.streamed!!.best!!.recording)
        }
        val bitmap = compose.onRoot().captureToImage()
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        java.io.File(context.filesDir, "streamed-sources.png").outputStream().use {
            bitmap.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun verifiedUncachedSourceIsLabelledAndGetsReadyInOneTap() {
        val eragon = book.copy(title = "Eragon", author = "Christopher Paolini")
        val recording = Audiobook("uncached-fixture", "Christopher Paolini - Eragon", "Author not verified", provider = "knaben", detailsLoaded = true,
            torrentHash = "a".repeat(40), magnetUri = "magnet:?xt=urn:btih:${"a".repeat(40)}", cacheState = "uncached", seeders = 10, filesVerified = true,
            sources = listOf(AudioSource("manifest-fixture", "Ordered audio parts", "MP3", listOf(AudioPart("part", "Eragon.mp3", "Eragon")), delivery = "torbox")))
        compose.runOnIdle { vm.connected.value = true; vm.selection.value = SelectionState(eragon)
            vm.sourceSearch.value = snapshot(eragon, listOf(recording), reasons = listOf(BestMatchReason.NEEDS_PREPARING, BestMatchReason.WELL_SEEDED), provider = "torbox-search") }
        // The page says it needs time in plain words, and getting it ready is one tap.
        compose.onNodeWithTag("recording-summary").performScrollTo().assertTextEquals("Narrator not confirmed · Needs time to get ready")
        compose.onNodeWithTag("listen-action").assertTextContains("Get it ready").assertIsEnabled()
        compose.onNodeWithText("Getting a recording ready can take", substring = true).assertExists()
        // The chooser offers the same one tap; Advanced keeps the release's technical details.
        compose.onNodeWithTag("change-recording").performClick()
        compose.onNodeWithTag("chooser-listen").assertTextContains("Get it ready").assertIsEnabled()
        compose.onNodeWithTag("recording-chooser").performScrollToNode(hasTestTag("advanced-entry"))
        compose.onNodeWithTag("advanced-entry").performClick()
        compose.onNodeWithTag("advanced-sources").performScrollToNode(hasTestTag("release:${recording.id}"))
        compose.onAllNodesWithText("Needs TorBox preparation", substring = true).onFirst().assertExists()
        compose.onAllNodesWithText("10 seeders", substring = true).onFirst().assertExists()
    }

    @Test fun bookDetailsLeadWithOneRecordingAndTheChooserOffersVersions() {
        show(SourceSearchState(book, loading = true, searched = true))
        // One page: no "The book" or "The recording" title, whichever way it opened.
        compose.onNodeWithText("The book").assertDoesNotExist()
        // Fallback cover art also draws the author; the final node is the details text below it.
        compose.onAllNodesWithText("Andy Weir").onLast().assertIsDisplayed()
        compose.onNodeWithText("Finding audio…").assertIsNotEnabled()

        val chosen = recording("fixture-recording", "Project Hail Mary (version 2)", "Fixture Reader")
        compose.runOnIdle { vm.sourceSearch.value = snapshot(book, listOf(chosen)) }
        compose.onNodeWithText("Listen").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("recording-summary").assertTextEquals("Fixture Reader · Free public recording")
        // About this book comes before the sources, which the page sums up in one row.
        reveal(hasText(book.description)); compose.onNodeWithText(book.description).assertIsDisplayed()
        reveal(hasTestTag("recordings-row")); compose.onNodeWithTag("recordings-row").assertTextContains("1 found")
        compose.onNodeWithTag("listening-sources").assertDoesNotExist()

        // One chooser: distinct versions, the best match tagged, and Listen in one tap.
        compose.onNodeWithTag("book-details").performScrollToIndex(0)
        compose.onNodeWithTag("change-recording").performScrollTo().performClick()
        compose.onNodeWithTag("recording:${chosen.id}").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithText("Best match").assertIsDisplayed()
        compose.onNodeWithTag("chooser-listen").assertTextContains("Listen to this recording").assertIsEnabled()
        // Advanced keeps the per-source sections, the release, its files, and how it plays.
        compose.onNodeWithTag("recording-chooser").performScrollToNode(hasTestTag("advanced-entry"))
        compose.onNodeWithTag("advanced-entry").performClick()
        compose.onNodeWithTag("listening-sources").assertExists()
        compose.onNodeWithTag("advanced-sources").performScrollToNode(hasTestTag("about-recording"))
        compose.onNodeWithText("book.m4b").assertExists()
        compose.onNodeWithTag("advanced-sources").performScrollToIndex(0)
        compose.onNodeWithTag("advanced-back").performClick()
        compose.onNodeWithTag("recording-chooser").assertIsDisplayed()

        val other = recording("fixture-other", "Project Hail Mary (version 3)", "Second Reader")
        compose.runOnIdle { vm.sourceSearch.value = vm.sourceSearch.value.withSnapshot(snapshot(book, listOf(chosen, other)).streamed!!) }
        compose.onNodeWithTag("recording-chooser").performScrollToNode(hasTestTag("recording:${other.id}"))
        compose.onNodeWithTag("recording:${other.id}").performClick()
        compose.runOnIdle { assertEquals(other.id, vm.sourceSearch.value.choice?.id) }
        // The page behind the sheet follows the pick.
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Second Reader · Free public recording").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun uncertainMatchesWaitForTheListenerToChooseFromSearchResults() {
        val possible = recording("fixture-possible", "Project Hail Mary audiobook", "Narrator not listed")
        show(snapshot(book, emptyList(), possible = listOf(possible)))
        compose.onNodeWithText("Listen").assertDoesNotExist()
        compose.onNodeWithText("No sure match").performScrollTo().assertIsDisplayed()
        // Reviewing opens the chooser with every match, each marked as one that might be this book.
        compose.onNodeWithText("Review possible matches").performScrollTo().performClick()
        compose.onNodeWithTag("recording:${possible.id}").assertIsDisplayed()
        compose.onNodeWithText("Might be this book").assertIsDisplayed()
        compose.onNodeWithTag("recording:${possible.id}").performClick()
        compose.runOnIdle { assertEquals(possible.id, vm.sourceSearch.value.choice?.id) }
        compose.onNodeWithTag("recording-chooser").performScrollToNode(hasTestTag("chooser-listen"))
        compose.onNodeWithTag("chooser-listen").assertTextContains("Listen to this recording")

        // Disconnected and nothing found: the slot says so and offers TorBox for the sources that need it.
        compose.runOnIdle { vm.back() }
        show(SourceSearchState(book = book).withSnapshot(StreamedSourceSearch(book, listOf(
            SourceGroup("archive", "Internet Archive / LibriVox", SourceGroupStatus.DONE),
            SourceGroup("torbox-search", "TorBox search", SourceGroupStatus.SKIPPED, message = "Connect TorBox")), complete = true)))
        compose.onNodeWithText("No free public recording found").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Connect TorBox").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Search again").performScrollTo().assertIsEnabled()
    }

    /** A book on the shelf with its own recording and a place a quarter of the way in, as listening leaves it. */
    private fun shelved(fixture: NarrioViewModel): Audiobook = runBlocking {
        val parent = book.copy(id = "catalog:shelf-fixture")
        fixtureBooks += parent.id
        val source = AudioSource("shelf-own-layout", "Whole-book audio", "M4B", listOf(AudioPart("shelf-own-part", "book.m4b", "Whole book", durationMs = 36_000_000,
            archiveUrl = "https://example.com/own.m4b")))
        val mine = recording("shelf-own", book.title, "Ray Porter").copy(sources = listOf(source)).forBook(parent)
        fixture.graph.library.save(mine)
        fixture.graph.library.progress(parent.id, NarrioJson.encodeToString(source), source.parts.single().id, 9_000_000, System.currentTimeMillis())
        fixture.graph.listeningRecordings.played(mine, source.id)
        mine
    }

    /** A QA screenshot in the app's private files, like the other experience tests. */
    private fun capture(name: String) {
        compose.waitForIdle(); Thread.sleep(600)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val folder = File(compose.activity.filesDir, "qa-captures").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }

    @Test fun aShelfBookResumesItsOwnRecordingAndCanChangeIt() {
        val calls = AtomicInteger()
        val fixture = lookupFixture(calls)
        val mine = shelved(fixture)
        val appearance = fixture.appearance.value
        compose.runOnIdle { fixture.updateAppearance(appearance.copy(mode = ThemeMode.NIGHT)); fixture.open(mine) }
        try { shelfBookResumesAndChanges(fixture, mine, calls, appearance) } finally { compose.runOnIdle { fixture.updateAppearance(appearance) } }
    }

    private fun shelfBookResumesAndChanges(fixture: NarrioViewModel, mine: Audiobook, calls: AtomicInteger, appearance: AppearanceSettings) {
        // Its own recording leads with the time left; nothing is searched until the listener asks.
        compose.onNodeWithTag("listen-action").assertTextContains("Resume · 7 h 30 m left").assertIsEnabled()
        compose.onNodeWithTag("recording-summary").assertTextEquals("Ray Porter · Free public recording")
        assertEquals(0, calls.get())
        capture("shelf-book-resume-night")
        compose.onNodeWithTag("change-recording").performScrollTo().performClick()
        compose.waitUntil(5_000) { fixture.sourceSearch.value.streamed?.complete == true }
        assertEquals(1, calls.get())
        // Another recording is the search's best match, yet the listener's own leads the chooser and the page.
        compose.runOnIdle { assertEquals("lookup-fixture", fixture.sourceSearch.value.streamed!!.best!!.recording.id) }
        compose.onNodeWithTag("recording:${mine.id}").assertIsSelected().assertTextContains("Listening now · 25%")
        compose.onNodeWithTag("chooser-listen").assertTextContains("Resume · 7 h 30 m left")
        // Picking another says its place starts over and the old one stays saved.
        compose.onNodeWithTag("recording:lookup-fixture").performClick()
        compose.onNodeWithTag("switch-notice").assertTextEquals("This one starts from the beginning. Your place in Ray Porter's recording stays saved.")
        compose.onNodeWithTag("chooser-listen").assertTextContains("Listen to this recording").assertIsEnabled()
        capture("chooser-switch-night")
        compose.runOnIdle { fixture.updateAppearance(appearance.copy(mode = ThemeMode.DAY)) }
        capture("chooser-switch-day")
        compose.runOnIdle { assertEquals("lookup-fixture", fixture.sourceSearch.value.choice?.id) }
        // Picking isn't listening: the page still resumes the listener's recording.
        compose.onNodeWithTag("listen-action").assertTextContains("Resume · 7 h 30 m left")
        compose.onNodeWithTag("recording-summary").assertTextEquals("Ray Porter · Free public recording")
    }

    @Test fun aDownloadedRecordingPlaysOffline() {
        val fixture = lookupFixture(AtomicInteger())
        val wave = File(compose.activity.filesDir, "offline-details-fixture.wav")
        val size = 8000 * 2 * 5
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + size); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16); put("data".toByteArray()); putInt(size)
        }.array()
        wave.outputStream().use { it.write(header); it.write(ByteArray(size)) }
        val parent = book.copy(id = "catalog:offline-fixture")
        fixtureBooks += parent.id
        val source = AudioSource("offline-details-layout", "Native fixture audio", "WAV",
            listOf(AudioPart("offline-details-part", wave.name, "Chapter 1", durationMs = 5_000, archiveUrl = android.net.Uri.fromFile(wave).toString())))
        val mine = Audiobook("offline-details", book.title, book.author, "Fixture Reader", detailsLoaded = true, sources = listOf(source)).forBook(parent)
        val wifiOnly = fixture.wifiOnly.value
        try {
            compose.runOnIdle { fixture.setWifiOnly(false) }
            runBlocking { fixture.graph.library.save(mine) }
            compose.runOnIdle { fixture.open(mine) }
            compose.onNodeWithTag("listen-action").assertTextContains("Listen")
            compose.onNodeWithTag("download-offline").performScrollTo().performClick()
            compose.waitUntil(30_000) { fixture.graph.offline.books.value.any { it.book.id == parent.id && it.complete } }
            // Listen now plays the phone copy, and the row says it's on this phone.
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("listen-action") and hasText("Play offline")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("on-this-phone").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("download-offline").assertDoesNotExist()
            // Play offline plays that phone copy, as this book's recording.
            compose.waitUntil(15_000) { fixture.graph.playback.service?.initialized == true }
            compose.onNodeWithTag("book-details").performScrollToIndex(0)
            compose.onNodeWithTag("listen-action").performScrollTo().performClick()
            compose.waitUntil(15_000) { fixture.graph.playback.state.value.let { it.source?.id == source.id && it.playing } }
            assertTrue(fixture.graph.offline.complete(source))
            assertTrue(sameRecording(fixture.graph.listeningRecordings[parent.id]!!, mine))
        } finally {
            runBlocking { withContext(Dispatchers.Main) { fixture.graph.playback.service?.forget() } }
            compose.runOnIdle { fixture.graph.offline.remove(source); fixture.setWifiOnly(wifiOnly) }
            wave.delete()
        }
    }
}
