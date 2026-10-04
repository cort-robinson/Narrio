package app.narrio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.narrio.domain.*
import app.narrio.ui.NarrioViewModel
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Live provider check, not part of required CI: a real LibriVox recording finds its Project Gutenberg
 * text automatically and on-device recognition anchors the text to the narration. Needs network and
 * downloads the pinned speech model once.
 */
@RunWith(AndroidJUnit4::class)
class NarrationSyncLiveTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = (compose.activity.application as NarrioApplication).graph
    private val vm get() = ViewModelProvider(compose.activity)[NarrioViewModel::class.java]

    private fun capture(name: String) {
        compose.waitForIdle()
        runBlocking { delay(800) }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.filesDir, "qa-captures").apply { mkdirs() }.resolve("$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun realRecordingFindsItsEbookAndSyncsToTheNarration() {
        val book = runBlocking { graph.catalog.recording(NarrioViewModel.curated.first().id) }
        val source = book.sources.first { it.format == "MP3" }
        try {
            runBlocking { graph.followAlong.remove(book.id) }
            compose.waitUntil(15_000) { graph.playback.service?.initialized == true }
            // Chapter 2, past the LibriVox introduction.
            runBlocking { withContext(Dispatchers.Main) { graph.playback.service!!.load(book, source, false, source.parts[1].id, 90_000) } }
            compose.runOnIdle {
                graph.preferences.edit().putBoolean("notificationAsked", true).apply()
                vm.setFollowAlongAuto(true); vm.allowSyncModelDownload(); vm.playerOpen.value = true
            }
            compose.onNodeWithText("Follow along").performClick()
            compose.waitUntil(120_000) { vm.bookText.value.document != null }
            val document = vm.bookText.value.document!!
            assertTrue(document.title.contains("Secret Garden", ignoreCase = true))
            assertTrue(app.narrio.data.BookTextFinder.plausible(document, book, source))
            compose.waitUntil(15_000) { graph.playback.state.value.durationMs > 0 }
            compose.waitUntil(600_000) { vm.bookText.value.bindings.any { binding -> binding.partId == source.parts[1].id && binding.anchors.count { it.auto } >= 2 } }
            val binding = vm.bookText.value.bindings.first { it.partId == source.parts[1].id }
            val state = graph.playback.state.value
            val timeline = FollowAlongTiming.timeline(document, binding, state.durationMs)!!
            assertTrue(timeline.aligned)
            // Chapter 2 audio must land in chapter 2's text, not merely somewhere in the book.
            val heard = timeline.passages[timeline.activeIndex(binding.anchors.first { it.auto }.positionMs)!!]
            val chapter = document.chapters.first { chapter -> chapter.passages.any { it.id == heard.id } }
            InstrumentationRegistry.getInstrumentation().run {
                val report = "chapter=${chapter.title}\npassage=${heard.text}\nanchors=${binding.anchors.joinToString { "${it.passageId}+${it.word}@${it.positionMs}" }}"
                File(targetContext.filesDir, "qa-captures").apply { mkdirs() }.resolve("narration-sync.txt").writeText(report)
            }
            assertTrue(chapter.title, Regex("(?i)\\b(ii|2|two)\\b").containsMatchIn(chapter.title))
            compose.waitUntil(30_000) { compose.onAllNodesWithText("Synced with narration").fetchSemanticsNodes().isNotEmpty() }
            // Further windows extend coverage; across several minutes the anchors must imply a human narration pace.
            compose.waitUntil(300_000) {
                val auto = vm.bookText.value.bindings.first { it.partId == source.parts[1].id }.anchors.filter { it.auto }
                auto.size >= 2 && auto.last().positionMs - auto.first().positionMs >= 240_000
            }
            val anchors = vm.bookText.value.bindings.first { it.partId == source.parts[1].id }.anchors.filter { it.auto }
            val passages = FollowAlongTiming.passages(document, WHOLE_BOOK)
            fun word(anchor: TextAnchor) = passages.takeWhile { it.id != anchor.passageId }.sumOf { it.weight } + anchor.word
            assertTrue(anchors.zipWithNext().all { (a, b) -> word(b) > word(a) && b.positionMs > a.positionMs })
            val wordsPerSecond = (word(anchors.last()) - word(anchors.first())) * 1000.0 / (anchors.last().positionMs - anchors.first().positionMs)
            android.util.Log.i("NarrationSyncLiveTest", "anchors=${anchors.size} span=${anchors.last().positionMs - anchors.first().positionMs}ms pace=$wordsPerSecond words/s")
            assertTrue("pace $wordsPerSecond", wordsPerSecond in 1.5..4.0)
            capture("narration-synced")
            compose.onNodeWithContentDescription("Manage book text").performClick()
            compose.onNodeWithText("Find and sync automatically").performScrollTo()
            capture("narration-sync-setting")
        } finally {
            runBlocking { withContext(Dispatchers.Main) { graph.playback.service?.forget() }; graph.followAlong.remove(book.id); graph.library.remove(book.id) }
        }
    }
}
