package app.narrio

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.narrio.data.*
import app.narrio.domain.*
import app.narrio.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Advanced sourcing in the recording chooser, with controlled providers and fixture files: searching with the
 * listener's own words, Not this book and Unhide, a mistyped link, phone audio without TorBox, choosing files, and
 * starting near your place when switching recordings. No network, account, or audio is used.
 */
@RunWith(AndroidJUnit4::class)
class AdvancedSourcingExperienceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val vm get() = ViewModelProvider(compose.activity)[NarrioViewModel::class.java]
    private val graph get() = (compose.activity.application as NarrioApplication).graph
    private val store = ViewModelStore()
    private var originalAppearance: AppearanceSettings? = null

    private val book = Audiobook("catalog:advanced-hp", "Harry Potter and the Sorcerer's Stone", "J. K. Rowling", provider = "catalog", detailsLoaded = true,
        description = "A boy learns he is a wizard.", metadataSource = "Test catalog", metadataUpdatedAtMs = System.currentTimeMillis())
    private fun release(id: String, title: String, hash: Char) = Audiobook(id, title, "Author not verified", provider = "knaben", detailsLoaded = true,
        releaseTitle = title, cacheState = "cached", cachedFormats = listOf("M4B"), seeders = 12, filesVerified = true,
        torrentHash = hash.toString().repeat(40), magnetUri = "magnet:?xt=urn:btih:${hash.toString().repeat(40)}",
        sources = listOf(AudioSource("$id-src", "Whole-book audio", "M4B", listOf(AudioPart("$id-p", "book.m4b", "Whole book", sizeBytes = 300_000_000)), delivery = "torbox")))
    private val us = release("fixture-us", "Harry Potter and the Sorcerer's Stone - J.K. Rowling (Jim Dale)", 'd')
    private val uk = release("fixture-uk", "Harry Potter and the Philosopher's Stone - J.K. Rowling (Stephen Fry)", 'e')
    /** Hour-long phone files; their content URIs are never opened by these tests. */
    private val phoneFiles = listOf("Disc 1/01.mp3", "Disc 1/02.mp3", "Disc 2/01.mp3", "Extras/interview.mp3")
        .map { LocalAudioFile("content://app.narrio.test/${it.hashCode()}", it, 1_000, 3_600_000) }

    private fun fixture(): NarrioViewModel {
        val settings = object : SourceProviderSettings {
            override val providers = MutableStateFlow(listOf(SourceProvider("torbox-search", "TorBox search", SourceProviderKind.BUILT_IN, true, 0, true, false)))
            override fun setEnabled(id: String, enabled: Boolean) = Unit
            override fun move(id: String, index: Int) = Unit
        }
        val lookup = object : SourceLookup {
            override suspend fun search(book: Audiobook, title: String, budget: SourceSearchBudget, status: suspend (SourceGroupStatus) -> Unit) = listOf(us, uk)
        }
        val engine = ProviderSourceSearch(settings, { lookup }, { it }, timeoutMs = 60_000, hidden = graph.hiddenReleases::keys,
            rankingChanges = graph.hiddenReleases.hidden.map { })
        lateinit var fixture: NarrioViewModel
        compose.runOnIdle {
            originalAppearance = originalAppearance ?: vm.appearance.value
            graph.hiddenReleases.forget(book.id); graph.sourceWords.forget(book.id)
            fixture = NarrioViewModel(compose.activity.application, engine)
            store.put("advanced-${System.nanoTime()}", fixture)
            fixture.connected.value = true
            compose.activity.setContent { NarrioApp(compose.activity, fixture) }
        }
        return fixture
    }

    @After fun cleanUp() {
        runBlocking { graph.library.remove(book.id) }
        graph.listeningRecordings.remove(book.id)
        compose.runOnIdle {
            graph.hiddenReleases.forget(book.id); graph.sourceWords.forget(book.id); graph.releaseFiles.forget(book.id)
            originalAppearance?.let { vm.updateAppearance(it) }; store.clear()
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle(); runBlocking { delay(600) }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val folder = File(compose.activity.filesDir, "qa-captures").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    private fun advanced() = compose.onNodeWithTag("advanced-sources")
    private fun reveal(matcher: SemanticsMatcher) = advanced().performScrollToNode(matcher)
    private fun has(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    /** The book page's Recordings & sources row opens the chooser. */
    private fun openChooser() {
        compose.waitUntil(5_000) { has("book-details") }
        compose.onNodeWithTag("book-details").performScrollToNode(hasTestTag("recordings-row"))
        compose.onNodeWithTag("recordings-row").performClick()
        compose.waitUntil(5_000) { has("recording-chooser") }
    }
    /** The chooser's last row opens Advanced. */
    private fun toAdvanced() {
        compose.onNodeWithTag("recording-chooser").performScrollToNode(hasTestTag("advanced-entry"))
        compose.onNodeWithTag("advanced-entry").performClick()
        compose.waitUntil(5_000) { has("advanced-sources") }
    }

    @Test fun ownWordsFindTheUkTitleAndNotThisBookHidesAWrongRelease() {
        val fixture = fixture()
        compose.runOnIdle { fixture.open(book) }
        compose.waitUntil(5_000) { fixture.sourceSearch.value.streamed?.complete == true }
        assertEquals(listOf(us.id), fixture.sourceSearch.value.recordings.map { it.id })
        assertTrue("The UK title isn't offered by the automatic search", fixture.sourceSearch.value.possible.none { it.id == uk.id })

        openChooser(); toAdvanced()
        reveal(hasTestTag("custom-search-words"))
        capture("advanced-own-words")
        compose.onNodeWithTag("custom-words-field").performTextInput("Philosopher's Stone Rowling")
        compose.onNodeWithTag("custom-words-field").performImeAction()
        compose.waitUntil(5_000) { fixture.sourceSearch.value.words != null && fixture.sourceSearch.value.streamed?.complete == true }
        assertEquals("Philosopher's Stone Rowling", graph.sourceWords.get(book.id))
        assertTrue(fixture.sourceSearch.value.possible.any { it.id == uk.id })
        assertEquals("Possible matches never become the best match", us.id, fixture.sourceSearch.value.streamed!!.best!!.recording.id)

        // The US release is wrong for this listener: hide it from its release row, and it leaves the best match at once.
        reveal(hasContentDescription("Not this book: hide ${us.releaseTitle}"))
        compose.onNodeWithContentDescription("Not this book: hide ${us.releaseTitle}").performClick()
        compose.waitUntil(5_000) { fixture.sourceSearch.value.streamed?.best == null }
        reveal(hasTestTag("hidden-releases"))
        compose.onNodeWithText("Hidden · 1").assertExists().performClick()
        capture("advanced-hidden-releases")
        reveal(hasContentDescription("Unhide ${us.releaseTitle}"))
        compose.onNodeWithContentDescription("Unhide ${us.releaseTitle}").performClick()
        compose.waitUntil(5_000) { fixture.sourceSearch.value.streamed?.best?.recording?.id == us.id }
        assertTrue(graph.hiddenReleases.keys(book.id).isEmpty())

        // Text that isn't a link says what to paste, without any network request.
        reveal(hasTestTag("link-field"))
        compose.onNodeWithTag("link-field").performTextInput("not a link")
        compose.onNodeWithTag("link-field").performImeAction()
        compose.waitUntil(5_000) { has("link-error") }
        compose.onNodeWithTag("link-error").assertTextContains("magnet link", substring = true)
    }

    @Test fun phoneAudioNeedsNoTorBoxAndItsChosenFilesPlayInTheChosenOrder() {
        val fixture = fixture()
        val recording = LocalAudio.recording(book, phoneFiles, "Harry Potter").forBook(book)
        compose.runOnIdle { fixture.connected.value = false; fixture.open(recording) }
        openChooser()
        // Phone audio plays without TorBox and is already on the phone, so there's nothing to connect or download.
        compose.onNodeWithTag("recording-chooser").performScrollToNode(hasTestTag("chooser-listen"))
        val listen = compose.onNodeWithTag("chooser-listen", useUnmergedTree = false).fetchSemanticsNode()
        assertFalse(listen.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString().contains("Connect TorBox"))
        assertFalse(has("chooser-download"))
        compose.onAllNodesWithText("On this phone", substring = true).onFirst().assertExists()

        toAdvanced()
        reveal(hasTestTag("choose-files:MP3"))
        compose.onNodeWithTag("choose-files:MP3").performClick()
        compose.waitUntil(5_000) { has("files-count") }
        compose.onNodeWithTag("files-count").assertTextEquals("4 of 4 files chosen")
        compose.onNodeWithContentDescription("Play Extras/interview.mp3").performClick()
        compose.onNodeWithContentDescription("Move Disc 1/01.mp3 up").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Move Disc 1/02.mp3 up").performClick()
        compose.onNodeWithTag("files-count").assertTextEquals("3 of 4 files chosen")
        capture("advanced-choose-files")
        compose.onNodeWithTag("release-files").performScrollToNode(hasTestTag("save-files"))
        compose.onNodeWithTag("save-files").performClick()
        val source = recording.sources.single()
        compose.waitUntil(5_000) { graph.releaseFiles.chosen(book.id, recording, source) != null }
        // Playback gets the chosen files in the chosen order; the layout and its part IDs are unchanged.
        val chosen = runBlocking { graph.releaseFiles.apply(recording, source) }
        assertEquals(listOf("Disc 1/02.mp3", "Disc 1/01.mp3", "Disc 2/01.mp3"), chosen.parts.map { it.name })
        assertEquals(source.id, chosen.id)
        assertTrue(chosen.parts.all { part -> part in source.parts })
    }

    @Test fun switchingRecordingsOffersToStartNearYourPlace() {
        val fixture = fixture()
        // The listener is 30 minutes into the second hour-long file of their phone recording.
        val mine = LocalAudio.recording(book, phoneFiles.take(3), "Harry Potter").forBook(book)
        val played = mine.sources.single()
        runBlocking {
            graph.library.save(mine)
            graph.library.progress(book.id, NarrioJson.encodeToString(AudioSource.serializer(), played), played.parts[1].id, 30 * 60_000L, System.currentTimeMillis())
        }
        graph.listeningRecordings.played(mine, played.id)
        compose.runOnIdle { fixture.open(book) }
        openChooser()
        compose.waitUntil(5_000) { has("recording:${us.id}") }
        compose.onNodeWithTag("recording:${us.id}").performClick()
        compose.onNodeWithTag("recording-chooser").performScrollToNode(hasTestTag("start-near"))
        // One whole-book file of unknown length: the same elapsed time, 1:30:00, labelled approximate.
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Start near 1:30:00 (≈)").fetchSemanticsNodes().isNotEmpty() }
        capture("advanced-start-near")
        assertEquals(StartChoice.NEAR, fixture.advanced.startChoice.value)
        compose.onNodeWithText("Start from the beginning").performClick()
        compose.runOnIdle { assertEquals(StartChoice.BEGINNING, fixture.advanced.startChoice.value) }
    }
}
