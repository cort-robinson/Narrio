package app.narrio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.narrio.data.*
import app.narrio.domain.*
import app.narrio.ui.NarrioViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NarrioListeningTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = (compose.activity.application as NarrioApplication).graph
    private val vm get() = ViewModelProvider(compose.activity)[NarrioViewModel::class.java]

    /** Opens the bundled LibriVox recording directly; book-first search is covered separately below. */
    private fun openSecretGarden() {
        // The one-time notification prompt would cover the player; this test exercises playback, not that prompt.
        graph.preferences.edit().putBoolean("notificationAsked", true).commit()
        compose.runOnIdle { vm.open(NarrioViewModel.curated.first()) }
        compose.waitUntil(30_000) { vm.selection.value.book?.sources?.isNotEmpty() == true && !vm.selection.value.loading }
        compose.onNodeWithText("Read by Ashleighjane").assertExists()
    }

    /** Plays the open recording in [format], chosen in the recording chooser's Advanced view. */
    private fun listenIn(format: String) {
        compose.onNodeWithTag("change-recording").performScrollTo().performClick()
        compose.onNodeWithTag("recording-chooser").performScrollToNode(hasTestTag("advanced-entry"))
        compose.onNodeWithTag("advanced-entry").performClick()
        compose.onNodeWithTag("advanced-sources").performScrollToNode(hasTestTag("format:$format"))
        compose.onNodeWithTag("format:$format").performClick()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("recording-chooser").performScrollToNode(hasTestTag("chooser-listen"))
        compose.onNodeWithTag("chooser-listen").performClick()
    }

    @Test fun realRecordingSupportsLaterPartControlsAndBackgroundResume() {
        openSecretGarden()
        listenIn("MP3")
        compose.waitUntil(90_000) { graph.playback.state.value.playing && graph.playback.state.value.source?.format == "MP3" && graph.playback.state.value.positionMs > 1000 }
        assertEquals(27, graph.playback.state.value.source?.parts?.size)
        compose.runOnIdle { graph.playback.service!!.speed(1f) }
        compose.onNodeWithText("1×").performScrollTo().performClick()
        compose.onNodeWithText("1.5× speed").performScrollTo().performClick()
        compose.waitUntil(3000) { graph.playback.state.value.speed == 1.5f }
        compose.runOnIdle { graph.playback.service!!.part(1, 120_000) }
        compose.waitUntil(90_000) { graph.playback.state.value.partIndex == 1 && graph.playback.state.value.positionMs >= 120_000 && graph.playback.state.value.playing }
        val secondPart = graph.playback.state.value.source!!.parts[1]
        assertTrue(secondPart.durationMs > 2000)
        compose.runOnIdle {
            graph.playback.service!!.sleep(app.narrio.playback.SleepMode.END_OF_PART)
            graph.playback.service!!.seek(secondPart.durationMs - 1500)
        }
        compose.waitUntil(30_000) { graph.playback.state.value.partIndex == 2 && !graph.playback.state.value.playing }
        assertFalse(graph.playback.state.value.sleep.active)
        compose.runOnIdle { graph.playback.service!!.part(1, 120_000) }
        compose.waitUntil(30_000) { graph.playback.state.value.partIndex == 1 && graph.playback.state.value.positionMs >= 120_000 && graph.playback.state.value.playing }
        compose.onNodeWithContentDescription("Bookmark this moment").performClick()
        val bookId = graph.playback.state.value.book!!.id
        compose.waitUntil(5000) { runBlocking { graph.library.bookmarks(bookId).first().isNotEmpty() } }
        val bookmark = runBlocking { graph.library.bookmarks(bookId).first().first() }
        assertEquals(graph.playback.state.value.part!!.id, bookmark.partId)
        assertTrue(bookmark.positionMs >= 120_000)
        // The confirmation snackbar briefly covers the tool tray; wait for it like a listener would.
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Bookmark added").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Sleep").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("In 15 minutes").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("In 15 minutes").performScrollTo().performClick()
        assertTrue(graph.playback.state.value.sleep.untilMs > System.currentTimeMillis())
        val before = graph.playback.state.value.positionMs
        compose.runOnIdle { compose.activity.moveTaskToBack(true) }
        compose.waitUntil(20_000) { graph.playback.state.value.positionMs > before + 1500 }
        assertTrue(graph.playback.state.value.playing)
        compose.runOnIdle { graph.playback.service!!.toggle() }
        val saved = runBlocking {
            delay(500)
            graph.library.find(bookId)!!
        }
        assertEquals(bookmark.partId, saved.partId)
        assertTrue(saved.positionMs >= 120_000)
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("am start --activity-reorder-to-front --activity-single-top -n ${compose.activity.packageName}/app.narrio.MainActivity").use { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
        }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { graph.playback.service != null }
        assertEquals(1, graph.playback.state.value.partIndex)
        assertTrue(graph.playback.state.value.positionMs >= 120_000)
    }

    @Test fun wholeBookM4bSupportsLongDistanceSeeking() {
        openSecretGarden()
        listenIn("M4B")
        compose.waitUntil(90_000) { graph.playback.state.value.playing && graph.playback.state.value.source?.format == "M4B" && graph.playback.state.value.durationMs > 3_600_000 }
        compose.runOnIdle { graph.playback.service!!.seek(5_400_000) }
        compose.waitUntil(90_000) { graph.playback.state.value.playing && graph.playback.state.value.positionMs >= 5_400_000 }
        assertEquals(1, graph.playback.state.value.source?.parts?.size)
        println("M4B native evidence: duration=${graph.playback.state.value.durationMs}, position=${graph.playback.state.value.positionMs}, chapters=${graph.playback.state.value.chapters.size}")
        compose.runOnIdle { graph.playback.service!!.toggle() }
    }

    @Test fun bookSearchFindsItsNarratedLibriVoxRecording() {
        compose.waitUntil(60_000) { compose.onAllNodesWithText("Search books or authors").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextInput("pride and prejudice jane austen")
        compose.waitUntil(60_000) { vm.catalog.value.books.any { it.title.startsWith("Pride and Prejudice") } }
        val book = vm.catalog.value.books.first { it.title.startsWith("Pride and Prejudice") }
        compose.runOnIdle { vm.open(book) }
        compose.waitUntil(90_000) { !vm.sourceSearch.value.loading && vm.sourceSearch.value.recordings.any { it.provider == "archive" } }
        compose.onNodeWithText("Listen").performScrollTo().assertIsEnabled()
        // Choosing it in the chooser keeps the book's one page and leads Listen with it.
        val recording = vm.sourceSearch.value.recordings.first { it.provider == "archive" }
        compose.runOnIdle { vm.chooseVersion(recording) }
        compose.onNodeWithTag("recording-summary").assertTextContains("Free public recording", substring = true)
        compose.onNodeWithText("Listen").performScrollTo().assertIsEnabled()
    }
}
