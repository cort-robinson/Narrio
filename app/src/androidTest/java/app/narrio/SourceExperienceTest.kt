package app.narrio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.narrio.data.*
import app.narrio.domain.*
import app.narrio.ui.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Native UI states use synthetic cache/progress snapshots; no authenticated account is implied. */
@RunWith(AndroidJUnit4::class)
class SourceExperienceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = (compose.activity.application as NarrioApplication).graph
    private val vm get() = ViewModelProvider(compose.activity)[NarrioViewModel::class.java]
    private val sources = listOf(AudioSource("review-mp3", "Ordered audio parts", "MP3", listOf(AudioPart("review-1", "Chapter_01.mp3", "Chapter 1", sizeBytes = 30_000_000))),
        AudioSource("review-m4b", "Whole-book audio", "M4B", listOf(AudioPart("review-m", "TheSecretGarden.m4b", "Whole book", sizeBytes = 500_000_000))))
    private fun book() = Audiobook("review-state", "The Secret Garden · An unabridged audiobook release with a long filename", "Author not verified", "Narrator not verified", "Language not verified", provider = "knaben", sources = sources, detailsLoaded = true, torrentHash = "a".repeat(40), magnetUri = "magnet:?xt=urn:btih:${"a".repeat(40)}", cacheState = "cached", cachedFormats = listOf("M4B"))
    private fun capture(name: String) {
        compose.waitForIdle(); runBlocking { delay(500) }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val folder = File(compose.activity.filesDir, "qa-captures").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }

    @Test fun cachedChoicesPreparationAndOfflineSettingsRemainReadable() {
        val window = compose.activity.resources.configuration
        val suffix = if (window.screenWidthDp >= 600) "fold" else if (window.fontScale > 1.2f) "phone-large-text" else "phone"
        // A recording's page with its other recordings already looked for, so the chooser makes no provider calls.
        compose.runOnIdle { vm.connected.value = true; vm.updateAppearance(vm.appearance.value.copy(mode = ThemeMode.NIGHT)); vm.selection.value = SelectionState(book())
            vm.sourceSearch.value = SourceSearchState(book().catalogIdentity(), searched = true); vm.chooseFormat(book(), "M4B") }
        compose.onNodeWithTag("listen-action").assertTextContains("Listen").assertIsEnabled()
        compose.onNodeWithTag("recording-summary").assertTextEquals("Narrator not confirmed · Unabridged · Ready now")
        compose.onNodeWithTag("download-offline").performScrollTo().assertTextContains("Download for offline · 500 MB")
        capture("cached-source-$suffix")
        // Advanced lets the listener override the automatic format and see the files.
        compose.onNodeWithTag("book-details").performScrollToIndex(0)
        compose.onNodeWithTag("change-recording").performScrollTo().performClick()
        compose.onNodeWithTag("recording-chooser").performScrollToNode(hasTestTag("advanced-entry"))
        compose.onNodeWithTag("advanced-entry").performClick()
        compose.onNodeWithTag("advanced-sources").performScrollToNode(hasTestTag("format:MP3"))
        compose.onNodeWithTag("format:MP3").assertTextContains("MP3 · 1 file · Not cached in TorBox").performClick()
        compose.onNodeWithTag("advanced-sources").performScrollToNode(hasText("Chapter_01.mp3"))
        capture("uncached-source-$suffix")
        // Back leaves Advanced first, then closes the chooser.
        compose.pressBackInSheet("advanced-sources")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("recording-chooser").fetchSemanticsNodes().isNotEmpty() }
        compose.pressBackInSheet("recording-chooser")
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("recording-chooser").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { vm.chooseFormat(book(), "M4B"); vm.preparation.value = Preparation(7, false, .35f, "Preparing in TorBox", 1_000_000, 3600, 2); vm.selection.value = SelectionState(book().copy(cacheState = "uncached", cachedFormats = emptyList())) }
        compose.onNodeWithTag("book-details").performScrollToNode(hasTestTag("preparation"))
        compose.onNodeWithText("35%", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Check again").performScrollTo().assertIsDisplayed()
        capture("preparation-$suffix")
        compose.runOnIdle { vm.connected.value = false; vm.preparation.value = null; vm.navigate(2); vm.updateAppearance(vm.appearance.value.copy(mode = ThemeMode.DAY)) }
        compose.onNodeWithTag("settings-options").performScrollToNode(hasText("Download only on Wi-Fi"))
        compose.onNodeWithText("Download only on Wi-Fi").performScrollTo().assertIsDisplayed()
        capture("offline-settings-$suffix")
    }

    @Test fun oldPlaybackCannotOverwritePendingM4bPreparation() = runBlocking {
        val book = book().copy(id = "pending-layout-test")
        graph.library.save(book); graph.library.preparing(book.id, 7, "M4B")
        graph.library.progress(book.id, NarrioJson.encodeToString(AudioSource.serializer(), sources.first()), "review-1", 120_000, System.currentTimeMillis())
        val saved = graph.library.find(book.id)!!
        assertEquals("M4B", saved.pendingFormat); assertEquals("preparing", saved.state); assertEquals(7, saved.preparationId)
        assertEquals(120_000, saved.positionMs)
        graph.library.remove(book.id)
    }
}
