package app.narrio

import android.net.Uri
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.narrio.data.*
import app.narrio.domain.*
import app.narrio.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Choosing among a book's recordings with real playback of local silent audio: which recording plays, each one's own
 * place, a pending TorBox preparation surviving Continue, and the choice surviving a restart. A fixed search stands
 * in for providers, and TorBox stays disconnected, so nothing here reaches the network.
 */
@RunWith(AndroidJUnit4::class)
class RecordingChoiceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = (compose.activity.application as NarrioApplication).graph
    private val service get() = graph.playback.service!!
    private val store = ViewModelStore()
    private val files = mutableListOf<File>()
    private val parent = Audiobook("catalog:choice-fixture", "Project Hail Mary", "Andy Weir", provider = "catalog", detailsLoaded = true,
        description = "A lone astronaut must save the Earth.")

    /** A minute of silence as a public recording read by [narrator]. */
    private fun recording(id: String, narrator: String): Audiobook {
        val wave = File(compose.activity.filesDir, "$id.wav").also(files::add)
        val size = 60 * 8000 * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + size); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16); put("data".toByteArray()); putInt(size)
        }.array()
        wave.outputStream().use { it.write(header); it.write(ByteArray(size)) }
        val part = AudioPart("$id-part", wave.name, "Chapter 1", durationMs = 60_000, archiveUrl = Uri.fromFile(wave).toString())
        return Audiobook(id, parent.title, parent.author, narrator, provider = "archive", detailsLoaded = true,
            sources = listOf(AudioSource("$id-layout", "Native fixture audio", "WAV", listOf(part))))
    }

    /** A search that always finds [found], the first leading. */
    private fun fixedSearch(found: List<Audiobook>) = object : StreamingSourceSearch {
        override fun start(book: Audiobook, connected: Boolean, scope: CoroutineScope) = object : SourceSearchSession {
            override val state = MutableStateFlow(StreamedSourceSearch(book, listOf(SourceGroup("archive", "Fixture", SourceGroupStatus.DONE, found)),
                BestMatch(found.first(), listOf(BestMatchReason.READY_TO_STREAM, BestMatchReason.FREE_PUBLIC_RECORDING), "archive"), complete = true))
            override fun retry(providerId: String) = Unit
        }
    }

    private fun app(found: List<Audiobook>): NarrioViewModel {
        lateinit var vm: NarrioViewModel
        compose.runOnIdle {
            vm = NarrioViewModel(compose.activity.application, fixedSearch(found))
            store.put("choice", vm)
            vm.connected.value = false
            graph.preferences.edit().putBoolean("notificationAsked", true).apply()
            compose.activity.setContent { NarrioApp(compose.activity, vm) }
        }
        compose.waitUntil(15_000) { graph.playback.service?.initialized == true }
        return vm
    }

    private fun <T> main(block: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun playing(source: AudioSource) = graph.playback.state.value.let { it.source?.id == source.id && it.playing }

    @After fun clean() {
        main { graph.playback.service?.forget() }
        compose.runOnIdle { store.clear() }
        runBlocking { graph.library.remove(parent.id) }
        graph.listeningRecordings.remove(parent.id); runBlocking { graph.preparations.forget(parent.id) }
        files.forEach(File::delete)
    }

    @Test fun switchingRecordingsPlaysTheChosenOneAndEachKeepsItsPlace() {
        val ray = recording("choice-ray", "Ray Porter")
        val kim = recording("choice-kim", "Kim Doe")
        val vm = app(listOf(ray, kim))
        val mine = ray.forBook(parent)
        runBlocking {
            graph.library.save(mine)
            graph.library.progress(parent.id, NarrioJson.encodeToString(ray.sources.single()), "choice-ray-part", 20_000, System.currentTimeMillis())
        }
        graph.listeningRecordings.played(mine, ray.sources.single().id)
        compose.runOnIdle { vm.open(mine) }
        compose.onNodeWithTag("listen-action").assertTextContains("Resume · 1 m left")

        // Kim's recording plays from its start; Ray's place stays saved with Ray's audio.
        compose.onNodeWithTag("change-recording").performScrollTo().performClick()
        compose.onNodeWithTag("recording:${kim.id}").performClick()
        compose.onNodeWithTag("switch-notice").assertTextEquals("This one starts from the beginning. Your place in Ray Porter's recording stays saved.")
        compose.onNodeWithTag("recording-chooser").performScrollToNode(hasTestTag("chooser-listen"))
        compose.onNodeWithTag("chooser-listen").performClick()
        compose.waitUntil(15_000) { playing(kim.sources.single()) }
        assertTrue(graph.playback.state.value.positionMs < 10_000)
        main { service.toggle() }
        runBlocking { assertEquals(20_000L, graph.library.position(parent.id, ray.sources.single().id)!!.positionMs) }
        assertTrue(sameRecording(graph.listeningRecordings[parent.id]!!, kim))

        // Back on the page Kim's recording is the one Resume plays; choosing Ray again picks up at Ray's place.
        compose.runOnIdle { vm.playerOpen.value = false }
        compose.onNodeWithTag("recording-summary").assertTextEquals("Kim Doe · Free public recording")
        compose.onNodeWithTag("change-recording").performScrollTo().performClick()
        compose.onNodeWithTag("recording:${ray.id}").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("switch-notice") and hasText("picks up where you left it", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("recording-chooser").performScrollToNode(hasTestTag("chooser-listen"))
        compose.onNodeWithTag("chooser-listen").performClick()
        compose.waitUntil(15_000) { playing(ray.sources.single()) }
        assertTrue("Ray resumes near 0:20, not the start", graph.playback.state.value.positionMs >= 19_000)
        main { service.toggle() }

        // After a restart the book still knows which recording is the listener's, and every recording's audio.
        val restarted = ListeningRecordings(graph.preferences)
        assertTrue(sameRecording(restarted[parent.id]!!, ray))
        assertEquals(listOf(kim.sources.single().id), restarted.playedSources(parent.id, kim))
    }

    @Test fun continueResumesTheListenedRecordingAndKeepsAnotherOnesPreparation() {
        val ray = recording("choice-ray", "Ray Porter")
        val vm = app(listOf(ray))
        val mine = ray.forBook(parent)
        // Kim's release is being got ready in TorBox: the row's book and preparation are Kim's; Ray's audio played.
        val kim = Audiobook("choice-kim-release", parent.title, parent.author, provider = "knaben", detailsLoaded = true, releaseTitle = "Project Hail Mary (read by Kim Doe)",
            torrentHash = "d".repeat(40), magnetUri = "magnet:?xt=urn:btih:${"d".repeat(40)}", cacheState = "uncached", seeders = 3, filesVerified = true,
            sources = listOf(AudioSource("kim-m4b", "Whole book", "M4B", listOf(AudioPart("kim-1", "book.m4b", "Whole book")), delivery = "torbox"))).forBook(parent)
        runBlocking {
            graph.library.save(mine)
            graph.library.progress(parent.id, NarrioJson.encodeToString(ray.sources.single()), "choice-ray-part", 30_000, System.currentTimeMillis())
            // Getting Kim's ready holds the row for TorBox item 9 without replacing Ray, the recording that played.
            graph.preparations.begin(kim, Preparation(9, false, 0f, "Getting ready in TorBox"), "M4B")
        }
        graph.listeningRecordings.played(mine, ray.sources.single().id)

        val shelved = runBlocking { graph.library.find(parent.id)!! }
        compose.runOnIdle { vm.resume(shelved) }
        compose.waitUntil(15_000) { playing(ray.sources.single()) }
        assertTrue(graph.playback.state.value.positionMs >= 29_000)
        main { service.toggle() }
        val entry = runBlocking { graph.library.find(parent.id)!! }
        assertEquals("preparing", entry.state)
        assertEquals(9L, entry.preparationId)
        assertEquals("M4B", entry.pendingFormat)
        assertTrue(sameRecording(runBlocking { graph.preparations.current(parent.id) }!!.recording, kim))
        assertTrue(sameRecording(graph.listeningRecordings[parent.id]!!, ray))

        // The page resumes Ray and shows Kim's recording still getting ready.
        compose.runOnIdle { vm.playerOpen.value = false; vm.open(entry.book()) }
        compose.onNodeWithTag("listen-action").assertTextContains("Resume", substring = true)
        compose.onNodeWithTag("recording-summary").assertTextEquals("Ray Porter · Free public recording")
        compose.onNodeWithTag("book-details").performScrollToNode(hasTestTag("preparation"))
        compose.onNodeWithText("Getting ready in TorBox").assertExists()
        compose.onNodeWithText("Kim Doe's recording").assertExists()
    }

    @Test fun anExplicitFormatNeverFallsBackAndAnUnreadyRecordingGetsReady() {
        val vm = app(listOf(recording("choice-ray", "Ray Porter")))
        // Saved but never played; M4B is ready in TorBox and MP3 isn't.
        val release = Audiobook("choice-release", parent.title, parent.author, provider = "knaben", detailsLoaded = true, releaseTitle = "Project Hail Mary (read by Ray Porter)",
            torrentHash = "e".repeat(40), cacheState = "cached", cachedFormats = listOf("M4B"), filesVerified = true, seeders = 4,
            sources = listOf(AudioSource("rel-m4b", "Whole book", "M4B", listOf(AudioPart("m4b-1", "book.m4b", "Whole book")), delivery = "torbox"),
                AudioSource("rel-mp3", "Parts", "MP3", listOf(AudioPart("mp3-1", "01.mp3", "Part 1")), delivery = "torbox"))).forBook(parent)
        runBlocking { graph.library.save(release) }
        compose.runOnIdle { vm.connected.value = true; vm.selection.value = SelectionState(release); vm.sourceSearch.value = SourceSearchState(parent, searched = true) }
        compose.onNodeWithTag("listen-action").assertTextContains("Listen")
        compose.onNodeWithTag("change-recording").performClick()
        compose.onNodeWithTag("recording-chooser").performScrollToNode(hasTestTag("advanced-entry"))
        compose.onNodeWithTag("advanced-entry").performClick()
        compose.onNodeWithTag("advanced-sources").performScrollToNode(hasTestTag("format:MP3"))
        compose.onNodeWithTag("format:MP3").performClick()
        compose.onNodeWithTag("advanced-sources").performScrollToIndex(0)
        compose.onNodeWithTag("advanced-back").performClick()
        compose.onNodeWithTag("recording-chooser").performScrollToNode(hasTestTag("chooser-listen"))
        // MP3 isn't ready, so the action gets MP3 ready instead of playing M4B under the listener's choice.
        compose.onNodeWithTag("chooser-listen").assertTextContains("Get MP3 ready")
        assertNotEquals("MP3", vm.savedFormat(parent.id))

        // The same recording with nothing ready offers Get it ready on the page.
        val uncached = release.copy(cacheState = "uncached", cachedFormats = emptyList())
        compose.runOnIdle { vm.selection.value = SelectionState(uncached) }
        runBlocking { graph.library.save(uncached) }
        InstrumentationRegistryBack.press()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("recording-chooser").fetchSemanticsNodes().isEmpty() }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("listen-action") and hasText("Get it ready")).fetchSemanticsNodes().isNotEmpty() }
    }
}

/** A real Back key press, which reaches a sheet's own window. */
private object InstrumentationRegistryBack {
    fun press() = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
}
