package app.narrio

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
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
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Native fixtures exercise actual Media3 seeking without requiring an account or network. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class FollowAlongTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = (compose.activity.application as NarrioApplication).graph
    private val vm get() = ViewModelProvider(compose.activity)[NarrioViewModel::class.java]
    private val fixture = """CHAPTER I

The morning light fell softly across the garden.

Mary opened the small wooden gate and stepped inside.

A robin waited on the branch above her head.

She stopped to listen to the leaves stirring in the breeze.

For the first time, the quiet place felt like home.

There were new green shoots beside the old stone wall.

She knelt down and carefully cleared the earth around them.

Somewhere beyond the trees, a bell began to ring."""

    private fun audio(id: String, multipart: Boolean = false): Pair<Audiobook, File> {
        val wave = File(compose.activity.filesDir, "$id.wav")
        val size = 120 * 8000 * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + size); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16); put("data".toByteArray()); putInt(size)
        }.array()
        wave.outputStream().use { stream -> stream.write(header); stream.write(ByteArray(size)) }
        val parts = (0 until if (multipart) 2 else 1).map { AudioPart("$id-part-$it", "$id.wav", "Chapter ${it + 1}", durationMs = 120_000, archiveUrl = Uri.fromFile(wave).toString()) }
        val source = AudioSource("$id-source", "Native fixture audio", "WAV", parts)
        return Audiobook(id, "A Garden Walk", "Native test fixture", "Synthetic silence", sources = listOf(source), detailsLoaded = true) to wave
    }

    private fun file(content: String, extension: String): Uri {
        val resolver = compose.activity.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Narrio-follow-along-${System.nanoTime()}.$extension")
            put(MediaStore.MediaColumns.MIME_TYPE, if (extension == "vtt") "text/vtt" else "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/NarrioTest")
        })!!
        resolver.openOutputStream(uri)!!.use { it.write(content.toByteArray()) }
        return uri
    }

    private fun open(book: Audiobook) {
        compose.waitUntil(15_000) { graph.playback.service?.initialized == true }
        runBlocking { withContext(Dispatchers.Main) { graph.playback.service!!.load(book, book.sources.single(), false, book.sources.single().parts.first().id, 0) } }
        compose.runOnIdle { graph.preferences.edit().putBoolean("notificationAsked", true).apply(); vm.theme.value = "Night"; vm.playerOpen.value = true }
        compose.onNodeWithText("Follow along").performScrollTo().performClick()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        // System bar appearance and touch ripples settle outside Compose's idle detection.
        runBlocking { delay(500) }
        // A transient success snackbar must not hide the transport in review evidence.
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("Book text saved for follow along") or hasText("Timing matched to this line.", substring = true)).fetchSemanticsNodes().isEmpty()
        }
        compose.waitForIdle()
        val config = compose.activity.resources.configuration
        val suffix = if (config.screenWidthDp >= 600) "fold" else if (config.fontScale > 1.2f) "phone-large-text" else "phone"
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val folder = File(compose.activity.filesDir, "qa-captures").apply { mkdirs() }
        File(folder, "$name-$suffix.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun localTextHighlightsSeeksAndRestoresTimingMatches() {
        val (book, wave) = audio("follow-native-text")
        val uri = file(fixture, "txt")
        try {
            runBlocking { graph.followAlong.remove(book.id) }
            open(book)
            compose.onNodeWithText("Read as you listen").assertIsDisplayed()
            capture("follow-empty")
            compose.runOnIdle { vm.beginTextImport(); vm.importBookText(uri) }
            compose.waitUntil(10_000) { vm.bookText.value.document != null }
            val document = vm.bookText.value.document!!
            val binding = defaultTextBinding(document, book.sources.single(), 0)!!
            val timeline = FollowAlongTiming.timeline(document, binding, 120_000)!!
            compose.onNodeWithText("Estimated timing").assertIsDisplayed()
            val line = timeline.passages[2]
            compose.onNodeWithText(line.text).performScrollTo().performClick()
            compose.waitUntil(5000) { graph.playback.state.value.positionMs == timeline.starts[2] }
            capture("follow-estimated")
            compose.runOnIdle { graph.playback.service!!.speed(1.5f); graph.playback.service!!.toggle() }
            compose.waitUntil(10_000) { graph.playback.state.value.playing && graph.playback.state.value.positionMs > timeline.starts[2] + 1000 }
            compose.runOnIdle { graph.playback.service!!.toggle() }
            compose.onNodeWithContentDescription("Adjust timing").performClick()
            compose.onNodeWithText(line.text).performScrollTo().performClick()
            capture("follow-adjust")
            compose.onNodeWithText("Match at", substring = true).performClick()
            compose.waitUntil(5000) { vm.bookText.value.bindings.any { it.anchors.isNotEmpty() } }
            val matched = vm.bookText.value.bindings.single()
            assertEquals(line.id, matched.anchors.single().passageId)
            compose.activityRule.scenario.recreate()
            compose.waitUntil(10_000) { vm.bookText.value.bindings.any { it.anchors.isNotEmpty() } }
            assertEquals(matched, vm.bookText.value.bindings.single())
            compose.onNodeWithText("Follow along").assertIsSelected()
            compose.runOnIdle { vm.theme.value = "Day" }
            capture("follow-day")
            compose.runOnIdle { vm.theme.value = "Night" }
            compose.onNodeWithContentDescription("Manage book text").performClick()
            compose.onNodeWithText("Choose EPUB, text, or VTT").assertIsDisplayed()
            capture("follow-sources")
            assertEquals(document.id, runBlocking { graph.followAlong.load(graph.library.bookText(book.id)!!).id })
        } finally {
            compose.activity.contentResolver.delete(uri, null, null)
            runBlocking { withContext(Dispatchers.Main) { graph.playback.service?.forget() }; graph.followAlong.remove(book.id); graph.library.remove(book.id) }
            wave.delete()
        }
    }

    @Test fun suppliedTimestampsNeverCarryOverToAnotherAudioPart() {
        val (book, wave) = audio("follow-native-vtt", true)
        val uri = file("WEBVTT\n\n00:01.000 --> 00:08.000\nThe first timed passage.\n\n00:09.000 --> 00:20.000\nThe second timed passage.\n", "vtt")
        try {
            open(book)
            compose.runOnIdle { vm.beginTextImport(); vm.importBookText(uri) }
            compose.waitUntil(5000) { vm.bookText.value.document?.format == "VTT" }
            compose.runOnIdle { graph.playback.service!!.seek(2000) }
            compose.onNodeWithText("Supplied timestamps · this audio part").assertIsDisplayed()
            compose.onNodeWithText("The second timed passage.").performScrollTo().performClick()
            compose.waitUntil(5000) { graph.playback.state.value.positionMs == 9000L }
            capture("follow-timed")
            compose.runOnIdle { graph.playback.service!!.part(1) }
            compose.waitUntil(10_000) { graph.playback.state.value.partIndex == 1 && graph.playback.state.value.playing }
            compose.runOnIdle { graph.playback.service!!.toggle() }
            compose.onNodeWithText("This timing track belongs to another audio part").assertIsDisplayed()
            compose.onNodeWithText("The first timed passage.").assertDoesNotExist()
            capture("follow-other-part")
            compose.onNodeWithContentDescription("Manage book text").performClick()
            compose.onNodeWithText("Remove book text").performScrollTo().performClick()
            compose.onNodeWithText("Remove text").performClick()
            compose.waitUntil(5000) { vm.bookText.value.document == null }
            assertNotNull(runBlocking { graph.library.find(book.id) })
            assertEquals(1, graph.playback.state.value.partIndex)
        } finally {
            compose.activity.contentResolver.delete(uri, null, null)
            runBlocking { withContext(Dispatchers.Main) { graph.playback.service?.forget() }; graph.followAlong.remove(book.id); graph.library.remove(book.id) }
            wave.delete()
        }
    }

    @Test fun publicLookupDownloadsARealEpubWithChapters() = runBlocking {
        val book = NarrioViewModel.curated.first()
        val result = graph.textDiscovery.search("Secret Garden").first { it.title.equals("The Secret Garden", true) }
        val document = graph.followAlong.fetch(result, book, "public-fixture-source", "public-fixture-part")
        try {
            assertEquals("EPUB", document.format)
            assertTrue(document.chapters.size >= 27)
            assertTrue(document.chapters.flatMap { it.passages }.any { it.text.contains("Mary Lennox") })
            assertEquals(document, graph.followAlong.load(graph.library.bookText(book.id)!!))
            assertTrue(document.attribution.contains("Project Gutenberg"))
        } finally { graph.followAlong.remove(book.id) }
    }

    @Test fun wholeBookChapterNavigationKeepsTheWholeRecordingTimeline() {
        val (book, wave) = audio("follow-native-chapters")
        val uri = file("CHAPTER I\n\nFirst chapter begins here.\n\nMore words to listen to.\n\nCHAPTER II\n\nSecond chapter begins here.\n\nThe final line.", "txt")
        try {
            open(book)
            compose.runOnIdle { vm.beginTextImport(); vm.importBookText(uri) }
            compose.waitUntil(5000) { vm.bookText.value.document?.chapters?.size == 2 }
            val document = vm.bookText.value.document!!
            val binding = defaultTextBinding(document, book.sources.single(), 0)!!
            val timeline = FollowAlongTiming.timeline(document, binding, 120_000)!!
            val first = document.chapters[1].passages.first()
            val index = timeline.passages.indexOfFirst { it.id == first.id }
            compose.onNodeWithText("Whole book").performClick()
            compose.onNodeWithText("Jump to a text chapter").assertIsDisplayed()
            capture("follow-chapters")
            compose.onNodeWithText("CHAPTER II").performScrollTo().performClick()
            compose.waitUntil(5000) { graph.playback.state.value.positionMs == timeline.starts[index] }
            assertTrue(vm.bookText.value.bindings.all { it.chapterId == WHOLE_BOOK })
            compose.onNodeWithText("Whole book").assertIsDisplayed()
        } finally {
            compose.activity.contentResolver.delete(uri, null, null)
            runBlocking { withContext(Dispatchers.Main) { graph.playback.service?.forget() }; graph.followAlong.remove(book.id); graph.library.remove(book.id) }
            wave.delete()
        }
    }
}
