package app.narrio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.media3.exoplayer.offline.Download
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.narrio.data.*
import app.narrio.domain.*
import app.narrio.ui.NarrioViewModel
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class OfflineListeningTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = (compose.activity.application as NarrioApplication).graph
    private fun shell(command: String) = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { fd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() } }

    @Before fun waitForNetworkAfterPreviousOfflineTest() {
        if (!android.os.Build.MODEL.contains("sdk_gphone")) return
        shell("svc wifi enable"); shell("svc data enable")
        val connectivity = compose.activity.getSystemService(android.net.ConnectivityManager::class.java)
        compose.waitUntil(20_000) { connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true }
        runBlocking { delay(3000) }
    }

    @Test fun explicitWholeM4bDownloadPlaysAndSeeksWithoutNetwork() {
        org.junit.Assume.assumeTrue(android.os.Build.MODEL.contains("sdk_gphone"))
        compose.waitUntil(30_000) { graph.playback.service?.initialized == true }
        val book = runBlocking { graph.catalog.recording("raven") }
        val source = book.sources.first { it.format == "M4B" }
        val vm = ViewModelProvider(compose.activity)[NarrioViewModel::class.java]
        compose.runOnIdle { vm.open(book) }
        compose.onNodeWithTag("download-offline").performScrollTo().performClick()
        compose.waitUntil(180_000) { graph.offline.books.value.any { it.book.id == book.id && it.complete } }
        val downloaded = graph.offline.books.value.first { it.book.id == book.id }
        assertTrue(downloaded.bytesDownloaded >= source.parts.sumOf { it.sizeBytes })
        assertEquals(1, downloaded.completedFiles)
        shell("svc wifi disable"); shell("svc data disable")
        try {
            compose.onNodeWithTag("listen-action").performScrollTo().assertTextContains("Play offline").performClick()
            compose.waitUntil(30_000) { graph.playback.state.value.playing && graph.playback.state.value.durationMs > 120_000 }
            compose.runOnIdle { graph.playback.service!!.seek(300_000) }
            compose.waitUntil(15_000) { graph.playback.state.value.playing && graph.playback.state.value.positionMs >= 300_000 }
            val before = graph.playback.state.value.positionMs
            compose.runOnIdle { compose.activity.moveTaskToBack(true) }
            compose.waitUntil(15_000) { graph.playback.state.value.positionMs > before + 2000 }
            compose.runOnIdle { graph.playback.service!!.toggle() }
            println("Offline full M4B: bytes=${downloaded.bytesDownloaded}, duration=${graph.playback.state.value.durationMs}, position=${graph.playback.state.value.positionMs}, Wi-Fi/data disabled")
        } finally { shell("svc wifi enable"); shell("svc data enable") }
    }

    @Test fun streamingDoesNotWriteOfflineAudioAndMultipartDownloadsPauseResume() {
        org.junit.Assume.assumeTrue(android.os.Build.MODEL.contains("sdk_gphone"))
        compose.waitUntil(30_000) { graph.playback.service?.initialized == true }
        val original = runBlocking { graph.catalog.recording("secret_garden_1105_librivox") }
        val source = original.sources.first { it.format == "MP3" }.let { it.copy(id = it.id + ":two-part-qa", parts = it.parts.take(2)) }
        val book = original.copy(id = original.id + ":offline-qa", title = "The Secret Garden · Two-part QA sample", sources = listOf(source))
        val before = graph.offline.cache.cacheSpace
        compose.runOnIdle { runBlocking { graph.playback.service!!.load(book, source) } }
        compose.waitUntil(60_000) { graph.playback.state.value.playing && graph.playback.state.value.positionMs > 2000 }
        assertEquals("Normal streaming must not save audio", before, graph.offline.cache.cacheSpace)
        compose.runOnIdle { graph.playback.service!!.toggle(); graph.offline.queue(book, source); graph.offline.pause(source) }
        compose.waitUntil(20_000) { source.parts.all { graph.offline.manager.downloadIndex.getDownload(stableAudioUri(it))?.stopReason == 1 } }
        compose.runOnIdle { graph.offline.resume(book, source) }
        compose.waitUntil(180_000) { graph.offline.books.value.any { it.book.id == book.id && it.complete } }
        shell("svc wifi disable"); shell("svc data disable")
        try {
            compose.runOnIdle { runBlocking { graph.playback.service!!.load(book, source) }; graph.playback.service!!.part(1, 120_000) }
            compose.waitUntil(30_000) { graph.playback.state.value.playing && graph.playback.state.value.partIndex == 1 && graph.playback.state.value.positionMs > 120_000 }
            assertNull(graph.playback.state.value.error)
            compose.runOnIdle { graph.playback.service!!.toggle() }
            println("Offline multipart: files=${source.parts.size}, second-part position=${graph.playback.state.value.positionMs}, normal streaming cache delta=0, pause/resume verified")
        } finally { shell("svc wifi enable"); shell("svc data enable") }
    }

    @Test fun indexedSearchFindsRealDistinctAudiobookReleases() {
        val releases = runBlocking { graph.indexedCatalog.search("The Secret Garden") }
        assertTrue(releases.size >= 3)
        assertEquals(releases.size, releases.map { it.torrentHash }.distinct().size)
        assertTrue(releases.all { it.provider == "knaben" && it.magnetUri.startsWith("magnet:?") })
        println("Live Knaben Android search: ${releases.size} distinct audiobook hashes; narration is explicitly unverified")
    }
}
