package app.narrio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.narrio.domain.*
import app.narrio.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Controlled catalog/source states; this test makes no recording or delivery lookup. */
@RunWith(AndroidJUnit4::class)
class BookDiscoveryExperienceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val vm get() = ViewModelProvider(compose.activity)[NarrioViewModel::class.java]
    private val book = Audiobook("catalog:fixture", "Project Hail Mary", "Andy Weir", provider = "catalog", detailsLoaded = true,
        description = "A lone astronaut must save the Earth.", metadataSource = "Test catalog", metadataUrl = "https://example.com/book", metadataUpdatedAtMs = System.currentTimeMillis())

    @Test fun bookDetailsWaitForExplicitSourceDiscoveryAndRecordingSelectionRetainsIdentity() {
        compose.runOnIdle { vm.connected.value = false; vm.open(book) }
        compose.waitUntil { vm.selection.value.book?.id == book.id && !vm.selection.value.loading }
        compose.onNodeWithText("The book").assertIsDisplayed()
        // Fallback cover art also draws the author; the final node is the details text below it.
        compose.onAllNodesWithText("Andy Weir").onLast().assertIsDisplayed()
        compose.onNodeWithText("Find sources").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Listen").assertDoesNotExist()
        compose.runOnIdle { assertFalse(vm.sourceSearch.value.searched); assertTrue(vm.sourceSearch.value.recordings.isEmpty()) }
        compose.onNodeWithText(book.description).performScrollTo().assertIsDisplayed()

        val recording = Audiobook("fixture-recording", "Project Hail Mary (version 2)", "Andy Weir", narrator = "Fixture Reader", detailsLoaded = true,
            sources = listOf(AudioSource("fixture-layout", "Whole-book audio", "M4B", listOf(AudioPart("fixture-part", "book.m4b", "Whole book", archiveUrl = "https://example.com/audio.m4b")))))
        compose.runOnIdle { vm.sourceSearch.value = SourceSearchState(book, listOf(recording), searched = true) }
        compose.onNodeWithText(recording.title).performScrollTo().performClick()
        compose.waitUntil { vm.selection.value.book?.id == recording.id && !vm.selection.value.loading }
        compose.onNodeWithText("The recording").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Read by Fixture Reader").assertIsDisplayed()
        compose.onNodeWithText("Listen").performScrollTo().assertIsEnabled()
        compose.runOnIdle {
            assertEquals(recording.sources, vm.selection.value.book!!.sources)
            assertEquals(recording.narrator, vm.selection.value.book!!.narrator)
            assertEquals(book.title, vm.selection.value.book!!.title)
            vm.back()
        }
        compose.onNodeWithText("The book").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(recording.title).performScrollTo().assertIsDisplayed()
    }
}
