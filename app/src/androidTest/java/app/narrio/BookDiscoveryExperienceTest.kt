package app.narrio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.narrio.domain.*
import app.narrio.data.SourceQuality
import app.narrio.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Controlled catalog/source states. Opening a catalog book looks up sources automatically, so these tests
 * inject the book's selection and lookup results directly; they make no recording or delivery lookup.
 */
@RunWith(AndroidJUnit4::class)
class BookDiscoveryExperienceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val vm get() = ViewModelProvider(compose.activity)[NarrioViewModel::class.java]
    private val book = Audiobook("catalog:fixture", "Project Hail Mary", "Andy Weir", provider = "catalog", detailsLoaded = true,
        description = "A lone astronaut must save the Earth.", metadataSource = "Test catalog", metadataUrl = "https://example.com/book", metadataUpdatedAtMs = System.currentTimeMillis())
    private fun recording(id: String, title: String, narrator: String) = Audiobook(id, title, "Andy Weir", narrator = narrator, detailsLoaded = true,
        sources = listOf(AudioSource("$id-layout", "Whole-book audio", "M4B", listOf(AudioPart("$id-part", "book.m4b", "Whole book", archiveUrl = "https://example.com/$id.m4b")))))
    /** Lazy rows below the fold aren't composed until the details list scrolls to them. */
    private fun reveal(matcher: SemanticsMatcher) = compose.onNode(hasScrollToNodeAction()).performScrollToNode(matcher)
    private fun show(state: SourceSearchState) = compose.runOnIdle { vm.connected.value = false; vm.selection.value = SelectionState(book); vm.sourceSearch.value = state }

    @Test fun verifiedUncachedSourceIsLabelledAndRequiresExplicitPreparation() {
        val eragon = book.copy(title = "Eragon", author = "Christopher Paolini")
        val recording = Audiobook("uncached-fixture", "Christopher Paolini - Eragon", "Author not verified", provider = "knaben", detailsLoaded = true,
            torrentHash = "a".repeat(40), magnetUri = "magnet:?xt=urn:btih:${"a".repeat(40)}", cacheState = "uncached", seeders = 10, filesVerified = true,
            sources = listOf(AudioSource("manifest-fixture", "Ordered audio parts", "MP3", listOf(AudioPart("part", "Eragon.mp3", "Eragon")), delivery = "torbox")))
        compose.runOnIdle { vm.connected.value = true; vm.selection.value = SelectionState(eragon); vm.sourceSearch.value = SourceSearchState(eragon, listOf(recording), searched = true) }
        compose.onNodeWithText("Review and prepare").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Needs TorBox preparation", substring = true).performScrollTo().assertIsDisplayed()
        // Inject the selected recording state to keep native CI free of live cache/metadata requests.
        compose.runOnIdle { vm.selection.value = SelectionState(SourceQuality.describe(recording, eragon)) }
        compose.onNodeWithText("Listen").performScrollTo().performClick()
        compose.onNodeWithText("Stream now").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Prepare in TorBox").performScrollTo().assertIsEnabled()
    }

    @Test fun bookDetailsChooseOneRecordingAndOfferVersionsOnlyWhenNarrationDiffers() {
        show(SourceSearchState(book, loading = true, searched = true))
        compose.onNodeWithText("The book").assertIsDisplayed()
        // Fallback cover art also draws the author; the final node is the details text below it.
        compose.onAllNodesWithText("Andy Weir").onLast().assertIsDisplayed()
        compose.onNodeWithText("Finding audio…").assertIsNotEnabled()
        compose.onNodeWithText("Find sources").assertDoesNotExist()

        val chosen = recording("fixture-recording", "Project Hail Mary (version 2)", "Fixture Reader")
        compose.runOnIdle { vm.sourceSearch.value = SourceSearchState(book, listOf(chosen), searched = true) }
        compose.onNodeWithText("Listen").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Read by Fixture Reader", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("versions", substring = true).assertDoesNotExist()
        compose.onNodeWithText(book.description).performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("Listening options").performScrollTo().performClick()
        compose.waitUntil { vm.selection.value.book?.id == chosen.id && !vm.selection.value.loading }
        compose.onNodeWithText("The recording").assertIsDisplayed()
        compose.onNodeWithText("Read by Fixture Reader").assertIsDisplayed()
        compose.onNodeWithText("Listen").performScrollTo().assertIsEnabled()
        compose.runOnIdle {
            assertEquals(chosen.sources, vm.selection.value.book!!.sources)
            assertEquals(chosen.narrator, vm.selection.value.book!!.narrator)
            assertEquals(book.title, vm.selection.value.book!!.title)
            vm.back()
        }
        compose.onNodeWithText("The book").assertIsDisplayed()
        compose.runOnIdle { assertFalse(vm.sourceSearch.value.loading); assertEquals(chosen.id, vm.sourceSearch.value.choice?.id) }

        val other = recording("fixture-other", "Project Hail Mary (version 3)", "Second Reader")
        compose.runOnIdle { vm.sourceSearch.value = SourceSearchState(book, listOf(chosen, other), searched = true) }
        compose.onNodeWithText("2 versions").performScrollTo().performClick()
        reveal(hasText(other.title))
        compose.onNodeWithText(other.title).performClick()
        compose.runOnIdle { assertEquals(other.id, vm.sourceSearch.value.choice?.id) }
        compose.onNodeWithText("Read by Second Reader", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun uncertainMatchesWaitForTheListenerToChooseFromSearchResults() {
        val possible = recording("fixture-possible", "Project Hail Mary audiobook", "Narrator not listed")
        show(SourceSearchState(book, searched = true, possible = listOf(possible)))
        compose.onNodeWithText("Listen").assertDoesNotExist()
        compose.onNodeWithText("Review 1 search result", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Choose a recording").performScrollTo().performClick()
        reveal(hasText("Possible match", substring = true))
        compose.onNodeWithText("Possible match", substring = true).assertIsDisplayed()
        compose.onNodeWithText(possible.title).performClick()
        compose.waitUntil { vm.selection.value.book?.id == possible.id && !vm.selection.value.loading }
        compose.onNodeWithText("The recording").assertIsDisplayed()

        show(SourceSearchState(book, searched = true))
        compose.onNodeWithText("Search again").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Connect TorBox for more sources").performScrollTo().assertIsDisplayed()
    }
}
