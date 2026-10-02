package app.narrio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.narrio.data.*
import app.narrio.domain.*
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

    @Test fun realRecordingSupportsLaterPartControlsAndBackgroundResume() {
        compose.waitUntil(60_000) { compose.onAllNodesWithText("Explore recording").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Explore recording").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Read by Ashleighjane").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Listen").performScrollTo().performClick()
        compose.onNodeWithText("Start listening").performScrollTo().performClick()
        compose.waitUntil(90_000) { graph.playback.state.value.playing && graph.playback.state.value.positionMs > 1000 }
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
            graph.playback.service!!.sleep(0, true)
            graph.playback.service!!.seek(secondPart.durationMs - 1500)
        }
        compose.waitUntil(30_000) { graph.playback.state.value.partIndex == 2 && !graph.playback.state.value.playing }
        assertFalse(graph.playback.state.value.sleepAtEnd)
        compose.runOnIdle { graph.playback.service!!.part(1, 120_000) }
        compose.waitUntil(30_000) { graph.playback.state.value.partIndex == 1 && graph.playback.state.value.positionMs >= 120_000 && graph.playback.state.value.playing }
        compose.onNodeWithContentDescription("Bookmark this moment").performScrollTo().performClick()
        val bookId = graph.playback.state.value.book!!.id
        compose.waitUntil(5000) { runBlocking { graph.library.bookmarks(bookId).first().isNotEmpty() } }
        val bookmark = runBlocking { graph.library.bookmarks(bookId).first().first() }
        assertEquals(graph.playback.state.value.part!!.id, bookmark.partId)
        assertTrue(bookmark.positionMs >= 120_000)
        compose.onNodeWithText("Sleep").performScrollTo().performClick()
        compose.onNodeWithText("In 15 minutes").performScrollTo().performClick()
        assertTrue(graph.playback.state.value.sleepUntil > System.currentTimeMillis())
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
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("am start --activity-reorder-to-front --activity-single-top -n app.narrio/.MainActivity").use { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
        }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { graph.playback.service != null }
        assertEquals(1, graph.playback.state.value.partIndex)
        assertTrue(graph.playback.state.value.positionMs >= 120_000)
    }

    @Test fun wholeBookM4bSupportsLongDistanceSeeking() {
        compose.waitUntil(60_000) { compose.onAllNodesWithText("Explore recording").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Explore recording").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Read by Ashleighjane").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Listen").performScrollTo().performClick()
        compose.onNodeWithText("Whole-book audio").performClick()
        compose.onNodeWithText("Start listening").performScrollTo().performClick()
        compose.waitUntil(90_000) { graph.playback.state.value.playing && graph.playback.state.value.source?.format == "M4B" && graph.playback.state.value.durationMs > 3_600_000 }
        compose.runOnIdle { graph.playback.service!!.seek(5_400_000) }
        compose.waitUntil(90_000) { graph.playback.state.value.playing && graph.playback.state.value.positionMs >= 5_400_000 }
        assertEquals(1, graph.playback.state.value.source?.parts?.size)
        println("M4B native evidence: duration=${graph.playback.state.value.durationMs}, position=${graph.playback.state.value.positionMs}, chapters=${graph.playback.state.value.chapters.size}")
        compose.runOnIdle { graph.playback.service!!.toggle() }
    }

    @Test fun searchFindsDistinctNarratedRecordings() {
        compose.waitUntil(60_000) { compose.onAllNodesWithText("Search books or authors").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextInput("pride prejudice")
        compose.waitUntil(60_000) { compose.onAllNodesWithText("recordings", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Pride and Prejudice (version 3)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Karen Savage").assertExists()
    }
}
