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
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real public metadata and artwork; audio availability in the UI case is a synthetic fixture. */
@RunWith(AndroidJUnit4::class)
class MetadataExperienceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = (compose.activity.application as NarrioApplication).graph
    private val vm get() = ViewModelProvider(compose.activity)[NarrioViewModel::class.java]
    private fun book() = Audiobook("metadata-experience", "Andy Weir - Project Hail Mary [M4B, Unabridged]", "Author not verified",
        "Narrator not verified", "Language not verified", provider = "knaben", detailsLoaded = true,
        sources = listOf(AudioSource("metadata-layout", "Whole-book audio", "M4B", listOf(AudioPart("metadata-part", "book.m4b", "Book")))),
        cacheState = "cached", cachedFormats = listOf("M4B"))

    private fun capture(name: String) {
        compose.waitForIdle(); runBlocking { delay(1000) }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val folder = File(compose.activity.filesDir, "qa-captures").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun liveCatalogMetadataPersistsWithoutChangingSavedPosition() = runBlocking {
        val original = book()
        try {
            graph.library.save(original)
            graph.library.progress(original.id, NarrioJson.encodeToString(original.sources.single()), "metadata-part", 123_000, 99)
            val enriched = graph.metadata.enrich(original)
            assertEquals("Project Hail Mary", enriched.title); assertEquals("Andy Weir", enriched.author)
            assertEquals("Ray Porter", enriched.narrator); assertEquals("Audible", enriched.metadataSource)
            assertTrue(enriched.description.length > 500); assertTrue(enriched.coverUrl.startsWith("https://"))
            graph.library.updateBookDetails(enriched.copy(sources = emptyList(), cacheState = "uncached"))
            val saved = graph.library.find(original.id)!!
            assertEquals(123_000, saved.positionMs); assertEquals("metadata-part", saved.partId)
            assertEquals(original.sources.single(), saved.source())
            assertEquals(enriched, saved.book())
            assertEquals(123_000, graph.library.position(original.id, "metadata-layout")!!.positionMs)
        } finally { graph.library.remove(original.id) }
    }

    @Test fun recordingDisplaysRealArtCatalogAttributionAndNonBlockingMetadataState() {
        val enriched = runBlocking { graph.metadata.enrich(book()) }
        assertEquals("Audible", enriched.metadataSource)
        val appearance = InstrumentationRegistry.getArguments().getString("reviewTheme", "Night")
        compose.runOnIdle { vm.connected.value = false; vm.updateAppearance(vm.appearance.value.copy(mode = ThemeMode.entries.firstOrNull { it.label == appearance } ?: ThemeMode.NIGHT)); vm.open(enriched) }
        compose.waitUntil(20_000) { vm.selection.value.book?.metadataSource == "Audible" && !vm.selection.value.loading && !vm.selection.value.metadataLoading }
        compose.onNodeWithText("Catalog narrator: Ray Porter").assertIsDisplayed()
        compose.onNodeWithContentDescription("Cover of Project Hail Mary").assertExists()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Project Hail Mary").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("Listen").performScrollTo().assertIsEnabled()
        val window = compose.activity.resources.configuration
        val suffix = (if (window.screenWidthDp >= 600) "expanded" else if (window.fontScale > 1.2f) "large-text" else "phone") + if (appearance == "Day") "-day" else ""
        capture("metadata-recording-$suffix")
        compose.onNodeWithText("Details from Audible").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Refresh book details").performScrollTo().assertIsEnabled()
        capture("metadata-description-$suffix")
        compose.runOnIdle { vm.selection.value = SelectionState(enriched, metadataLoading = true) }
        compose.onNodeWithText("Fetching book details\u2026").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Listen").performScrollTo().assertIsEnabled()
        compose.runOnIdle { vm.selection.value = SelectionState(enriched.copy(coverUrl = "https://127.0.0.1:1/unavailable.jpg", metadataSource = "", metadataUrl = "")) }
        capture("metadata-cover-fallback-$suffix")
    }
}
