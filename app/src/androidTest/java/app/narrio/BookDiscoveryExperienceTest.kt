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

    private val fixtureStore = ViewModelStore()
    private val fixtureBooks = mutableSetOf<String>()
    @After fun cleanLookupFixtures() = runBlocking {
        compose.runOnIdle { fixtureStore.clear() }
        fixtureBooks.forEach { vm.graph.followAlong.remove(it); vm.graph.library.remove(it) }
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
        val migrated = Room.databaseBuilder(context, LibraryDatabase::class.java, name).addMigrations(LibraryMigration4To5).build()
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
        compose.runOnIdle { fixture.chooseRecording(fixture.sourceSearch.value.choice!!) }
        compose.waitUntil(5_000) { fixture.selection.value.book?.recordingId == "lookup-fixture" }
        assertEquals(migrated.id, fixture.selection.value.book?.id)
        compose.runOnIdle { fixture.back() }
        compose.waitUntil(5_000) { fixture.selection.value.book?.provider == "catalog" }
        assertEquals(1, calls.get())
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

    @Test fun verifiedUncachedSourceIsLabelledAndRequiresExplicitPreparation() {
        val eragon = book.copy(title = "Eragon", author = "Christopher Paolini")
        val recording = Audiobook("uncached-fixture", "Christopher Paolini - Eragon", "Author not verified", provider = "knaben", detailsLoaded = true,
            torrentHash = "a".repeat(40), magnetUri = "magnet:?xt=urn:btih:${"a".repeat(40)}", cacheState = "uncached", seeders = 10, filesVerified = true,
            sources = listOf(AudioSource("manifest-fixture", "Ordered audio parts", "MP3", listOf(AudioPart("part", "Eragon.mp3", "Eragon")), delivery = "torbox")))
        compose.runOnIdle { vm.connected.value = true; vm.selection.value = SelectionState(eragon); vm.sourceSearch.value = SourceSearchState(eragon, listOf(recording), searched = true) }
        compose.onNodeWithText("Review and prepare").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Needs TorBox preparation", substring = true).performScrollTo().assertIsDisplayed()
        // Inject the selected recording state to keep native CI free of live cache/metadata requests.
        compose.runOnIdle { vm.selection.value = SelectionState(SourceQuality.describe(recording, eragon)) }
        compose.onNodeWithText("Listen").performScrollTo().performClick()
        compose.onNodeWithText("Stream now").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Prepare in TorBox").performScrollTo().assertIsEnabled()
    }

    @Test fun bookDetailsChooseOneRecordingAndOfferVersionsOnlyWhenNarrationDiffers() {
        show(SourceSearchState(book, loading = true, searched = true))
        compose.onNodeWithText("The book").assertIsDisplayed()
        // Fallback cover art also draws the author; the final node is the details text below it.
        compose.onAllNodesWithText("Andy Weir").onLast().assertIsDisplayed()
        compose.onNodeWithText("Finding audio…").assertIsNotEnabled()
        compose.onNodeWithText("Find sources").assertDoesNotExist()

        val chosen = recording("fixture-recording", "Project Hail Mary (version 2)", "Fixture Reader")
        compose.runOnIdle { vm.sourceSearch.value = SourceSearchState(book, listOf(chosen), searched = true) }
        compose.onNodeWithText("Listen").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Read by Fixture Reader", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("versions", substring = true).assertDoesNotExist()
        compose.onNodeWithText(book.description).performScrollTo().assertIsDisplayed()

        // Listening options is one sheet: the recording, its format, and the recording's own page for its files.
        compose.onNodeWithTag("listening-options-action").performScrollTo().performClick()
        compose.onNodeWithTag("listening-options").assertIsDisplayed()
        compose.onNodeWithText("Whole-book audio").assertIsDisplayed()
        compose.onNodeWithTag("listen-choice").assertIsEnabled()
        compose.onNodeWithText("About this recording").performClick()
        compose.waitUntil(10_000) { vm.selection.value.book?.id == book.id && vm.selection.value.book?.recordingId == chosen.id && !vm.selection.value.loading }
        compose.onNodeWithText("The recording").assertIsDisplayed()
        compose.onNodeWithText("Read by Fixture Reader").assertIsDisplayed()
        compose.onNodeWithText("Listen").performScrollTo().assertIsEnabled()
        compose.runOnIdle {
            assertEquals(chosen.sources, vm.selection.value.book!!.sources)
            assertEquals(chosen.narrator, vm.selection.value.book!!.narrator)
            assertEquals(book.title, vm.selection.value.book!!.title)
            vm.back()
        }
        compose.onNodeWithText("The book").assertIsDisplayed()
        compose.runOnIdle { assertFalse(vm.sourceSearch.value.loading); assertEquals(chosen.id, vm.sourceSearch.value.choice?.id) }

        val other = recording("fixture-other", "Project Hail Mary (version 3)", "Second Reader")
        compose.runOnIdle { vm.sourceSearch.value = SourceSearchState(book, listOf(chosen, other), searched = true) }
        compose.onNodeWithText("Change recording or format · 2 found").performScrollTo().performClick()
        compose.onNodeWithTag("listening-options").performScrollToNode(hasText(other.title))
        compose.onNodeWithText(other.title).performClick()
        compose.runOnIdle { assertEquals(other.id, vm.sourceSearch.value.choice?.id) }
        // The page behind the sheet follows the pick: its status line now names the second reader too.
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Read by Second Reader", substring = true).fetchSemanticsNodes().size >= 2 }
    }

    @Test fun uncertainMatchesWaitForTheListenerToChooseFromSearchResults() {
        val possible = recording("fixture-possible", "Project Hail Mary audiobook", "Narrator not listed")
        show(SourceSearchState(book, searched = true, possible = listOf(possible)))
        compose.onNodeWithText("Listen").assertDoesNotExist()
        compose.onNodeWithText("Review 1 search result", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Choose a recording").performScrollTo().performClick()
        compose.onNodeWithTag("listening-options").performScrollToNode(hasText("Possible match", substring = true))
        compose.onNodeWithText("Possible match", substring = true).assertIsDisplayed()
        compose.onNodeWithText(possible.title).performClick()
        compose.runOnIdle { assertEquals(possible.id, vm.sourceSearch.value.choice?.id) }
        compose.onNodeWithTag("listen-choice").assertIsEnabled()
        compose.onNodeWithText("About this recording").performClick()
        compose.waitUntil(10_000) { vm.selection.value.book?.id == book.id && vm.selection.value.book?.recordingId == possible.id && !vm.selection.value.loading }
        compose.onNodeWithText("The recording").assertIsDisplayed()

        show(SourceSearchState(book, searched = true))
        compose.onNodeWithText("Search again").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Connect TorBox for more sources").performScrollTo().assertIsDisplayed()
    }
}
