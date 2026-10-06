package app.narrio

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.narrio.domain.*
import app.narrio.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Book details listening by source, driven by [FakeStreamingSourceSearch]: a fast public source, a slow TorBox
 * search, a failing add-on, and one waiting behind its rate limit. Controlled fixtures only; no provider requests.
 */
@RunWith(AndroidJUnit4::class)
class SourceSearchExperienceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val vm get() = ViewModelProvider(compose.activity)[NarrioViewModel::class.java]
    private val book = Audiobook("catalog:phm-sources", "Project Hail Mary", "Andy Weir", provider = "catalog", detailsLoaded = true,
        description = "A lone astronaut must save the Earth.", metadataSource = "Test catalog", metadataUpdatedAtMs = System.currentTimeMillis())
    private fun release(id: String, title: String, provider: String = "knaben", cached: Boolean = true, format: String = "M4B") = Audiobook(id, title, "Andy Weir",
        provider = provider, detailsLoaded = true, releaseTitle = title, cacheState = if (cached) "cached" else "uncached", cachedFormats = if (cached) listOf(format) else emptyList(),
        seeders = 24, torrentHash = if (provider == "knaben") id.padEnd(40, 'a').take(40) else "",
        sources = listOf(AudioSource("$id-src", "Whole-book audio", format, listOf(AudioPart("$id-p", "book.${format.lowercase()}", "Whole book", sizeBytes = 412_000_000,
            archiveUrl = if (provider == "archive") "https://example.com/$id.mp3" else "")), delivery = if (provider == "archive") "archive" else "torbox")))
    private val public = release("fixture-librivox", "Project Hail Mary (solo reading)", provider = "archive", cached = false, format = "MP3")
    private val cached = release("fixture-cached", "Project Hail Mary - Andy Weir (read by Ray Porter) [Unabridged]")
    private val other = release("fixture-other", "Project Hail Mary - Andy Weir 2021 MP3", format = "MP3")

    private fun capture(name: String) {
        compose.waitForIdle(); runBlocking { delay(600) }
        val window = compose.activity.resources.configuration
        val suffix = if (window.screenWidthDp >= 600) "unfolded" else if (window.fontScale > 1.2f) "phone-large-text" else "phone"
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val folder = File(compose.activity.filesDir, "qa-captures").apply { mkdirs() }
        File(folder, "$name-$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    private fun reveal(matcher: SemanticsMatcher) = compose.onNodeWithTag("book-details").performScrollToNode(matcher)
    private fun show(fake: FakeStreamingSourceSearch, connected: Boolean = true, mode: ThemeMode = ThemeMode.NIGHT) = compose.runOnIdle {
        vm.connected.value = connected
        vm.updateAppearance(vm.appearance.value.copy(mode = mode))
        vm.selection.value = SelectionState(book)
        vm.attachSourceSession(fake.start(book, connected, vm.viewModelScope))
    }

    @After fun detach() { compose.runOnIdle { vm.attachSourceSession(null); vm.navigate(0) } }

    @Test fun sectionsFillAsEachSourceAnswersAndTheBestMatchNeverMovesUnderTheListener() {
        val fake = FakeStreamingSourceSearch(listOf(
            FakeStreamingSourceSearch.Provider("archive", "Internet Archive / LibriVox", listOf(public), rank = 2,
                reasons = listOf(BestMatchReason.FREE_PUBLIC_RECORDING, BestMatchReason.STRONG_MATCH)),
            FakeStreamingSourceSearch.Provider("torbox-library", "My TorBox library"),
            FakeStreamingSourceSearch.Provider("torbox-search", "TorBox search", listOf(cached, other), possible = listOf(release("fixture-maybe", "Hail Mary audiobook")), rank = 0,
                reasons = listOf(BestMatchReason.READY_TO_STREAM, BestMatchReason.PREFERRED_FORMAT, BestMatchReason.NARRATOR_KNOWN, BestMatchReason.STRONG_MATCH),
                alsoFoundBy = mapOf(cached.id to listOf("addon:audiobookbay"))),
            FakeStreamingSourceSearch.Provider("addon:audiobookbay", "AudiobookBay", failure = "Didn't answer within 15 seconds; it timed out.",
                retried = listOf(release("fixture-abb", "Andy Weir - Project Hail Mary (Ray Porter)", cached = false))),
            FakeStreamingSourceSearch.Provider("addon:knaben", "Knaben audiobooks", waiting = true),
        ))
        show(fake)
        // Every source has a section at once, with its own state; nothing is found yet.
        compose.onNodeWithText("Finding audio…").assertIsNotEnabled()
        reveal(hasTestTag("source-section:addon:knaben"))
        compose.onNodeWithTag("source-section:addon:knaben").assertExists()
        compose.onAllNodesWithText("Searching", substring = true).fetchSemanticsNodes().let { assertTrue(it.size >= 3) }
        compose.onNodeWithText("Waiting its turn").assertExists()

        // The quick public source answers first and becomes the best match.
        compose.runOnIdle { fake.answer("archive"); fake.answer("torbox-library"); fake.answer("addon:audiobookbay") }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("best-match-reasons").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("best-match-reasons").assertTextContains("Free public recording", substring = true)
        compose.onNodeWithTag("listen-action").assertIsEnabled()
        reveal(hasText("Timed out"))
        compose.onNodeWithText("Nothing for this book").assertExists()
        compose.onNodeWithTag("book-details").performScrollToIndex(0)
        capture("sources-in-progress-night")

        // The listener is on the page now; the slow source's better release must not replace the card.
        compose.onNodeWithTag("book-details").performTouchInput { down(Offset(4f, 4f)); up() }
        compose.runOnIdle { fake.answer("torbox-search") }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("better-match").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("best-match-reasons").assertTextContains("Free public recording", substring = true)
        capture("sources-better-match-night")
        compose.onNodeWithTag("better-match").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("better-match").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("best-match-reasons").assertTextEquals("Ready to stream · M4B · Read by Ray Porter")

        // A failed source retries on its own; the rest stay put.
        reveal(hasTestTag("retry:addon:audiobookbay"))
        compose.onNodeWithTag("retry:addon:audiobookbay").performClick()
        compose.runOnIdle { fake.answer("addon:audiobookbay"); fake.answer("addon:knaben") }
        compose.waitUntil(5_000) { vm.sourceSession.value!!.state.value.complete }
        compose.onNodeWithTag("sources-summary").assertTextEquals("All 5 sources answered · 4 found")
        reveal(hasText("Also found by AudiobookBay"))
        compose.onNodeWithText("Also found by AudiobookBay").assertIsDisplayed()

        // Possible matches stay folded until asked for.
        reveal(hasTestTag("possible:torbox-search"))
        compose.onNodeWithText("Hail Mary audiobook").assertDoesNotExist()
        compose.onNodeWithTag("possible:torbox-search").performClick()
        compose.onNodeWithText("Hail Mary audiobook").assertExists()

        compose.runOnIdle { vm.updateAppearance(vm.appearance.value.copy(mode = ThemeMode.DAY)) }
        compose.onNodeWithTag("book-details").performScrollToIndex(0)
        capture("sources-finished-day")
        reveal(hasTestTag("source-section:torbox-search"))
        capture("sources-sections-day")

        // Listen plays the best match: it becomes the book's choice.
        compose.onNodeWithTag("book-details").performScrollToIndex(0)
        compose.runOnIdle { vm.chooseVersion(other) }
        compose.onNodeWithTag("listening-options-action").performScrollTo().performClick()
        compose.onNodeWithTag("listening-options").assertIsDisplayed()
        compose.runOnIdle { assertEquals(cached.id, vm.sourceSearch.value.choice?.id) }
    }

    @Test fun choosingAReleaseOpensItsPage() {
        val fake = FakeStreamingSourceSearch(listOf(FakeStreamingSourceSearch.Provider("torbox-search", "TorBox search", listOf(cached, other))))
        show(fake, mode = ThemeMode.DAY)
        compose.runOnIdle { fake.answer("torbox-search") }
        reveal(hasTestTag("release:${other.id}"))
        compose.onNodeWithTag("release:${other.id}").performClick()
        compose.waitUntil(10_000) { vm.selection.value.book?.recordingId == other.id && !vm.selection.value.loading }
        compose.onNodeWithText("The recording").assertIsDisplayed()
    }

    @Test fun failedAndDisconnectedSearchesSayWhatToDoNext() {
        val failing = FakeStreamingSourceSearch(listOf(
            FakeStreamingSourceSearch.Provider("archive", "Internet Archive / LibriVox", failure = "Couldn't connect."),
            FakeStreamingSourceSearch.Provider("torbox-search", "TorBox search", failure = "TorBox didn't answer."),
        ))
        show(failing, mode = ThemeMode.DAY)
        compose.runOnIdle { failing.answer("archive"); failing.answer("torbox-search") }
        compose.onNodeWithText("Couldn't reach any source").assertIsDisplayed()
        compose.onNodeWithText("None of your 2 sources answered. Check your connection, then try again.").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsEnabled()
        reveal(hasTestTag("retry:torbox-search")); compose.onNodeWithTag("retry:torbox-search").assertIsDisplayed()
        compose.onNodeWithTag("book-details").performScrollToIndex(0)
        capture("sources-all-failed-day")

        val offline = FakeStreamingSourceSearch(listOf(
            FakeStreamingSourceSearch.Provider("archive", "Internet Archive / LibriVox"),
            FakeStreamingSourceSearch.Provider("torbox-library", "My TorBox library", skipped = true),
            FakeStreamingSourceSearch.Provider("torbox-search", "TorBox search", skipped = true),
        ))
        show(offline, connected = false, mode = ThemeMode.NIGHT)
        compose.runOnIdle { offline.answer("archive") }
        compose.onNodeWithText("No free public recording found").assertIsDisplayed()
        compose.onNodeWithText("Connect TorBox").assertIsDisplayed()
        reveal(hasTestTag("source-section:torbox-search"))
        compose.onAllNodesWithText("Needs TorBox").fetchSemanticsNodes().let { assertEquals(2, it.size) }
        compose.onNodeWithTag("book-details").performScrollToIndex(0)
        capture("sources-disconnected-night")
    }

    @Test fun sourceSettingsToggleAndReorderWithTouchAndTalkBack() {
        val settings = vm.sourceProviderSettings
        val original = settings.providers.value.map { it.id to it.enabled }
        try {
            compose.runOnIdle { vm.connected.value = false; vm.updateAppearance(vm.appearance.value.copy(mode = ThemeMode.DAY)); vm.navigate(2) }
            compose.onNodeWithText("Sources & add-ons · Audiobooks, ebooks & book info").performScrollTo().performClick()
            compose.onNodeWithText("TorBox isn't connected").assertIsDisplayed()
            compose.onNodeWithTag("addon-options").performScrollToNode(hasTestTag("source-row:torbox-search"))
            capture("source-settings-day")

            // Built-ins switch off, but offer no Remove.
            compose.onNodeWithTag("source-toggle:torbox-search").performClick()
            compose.waitUntil(5_000) { !settings.providers.value.first { it.id == "torbox-search" }.enabled }
            compose.onNodeWithContentDescription("More for TorBox search").performClick()
            compose.onNodeWithText("Remove TorBox search").assertDoesNotExist()
            compose.onNodeWithText("Move up").performClick()
            compose.waitUntil(5_000) { settings.providers.value.indexOfFirst { it.id == "torbox-search" } == 1 }

            // TalkBack's Move down action, without dragging.
            compose.onNodeWithTag("source-row:archive").performSemanticsAction(SemanticsActions.CustomActions) { actions -> actions.first { it.label == "Move down" }.action() }
            compose.waitUntil(5_000) { settings.providers.value.indexOfFirst { it.id == "archive" } == 1 }
            compose.onNodeWithTag("addon-message").assertTextEquals("Internet Archive / LibriVox moved to 2 of ${settings.providers.value.size}.")

            // Drag by the handle: My TorBox library moves below the next source.
            val start = settings.providers.value.indexOfFirst { it.id == "torbox-library" }
            compose.onNodeWithTag("source-row:torbox-library").performTouchInput {
                down(Offset(24.dp.toPx(), centerY)); moveBy(Offset(0f, height * 1.2f), 400); up()
            }
            compose.waitUntil(5_000) { settings.providers.value.indexOfFirst { it.id == "torbox-library" } == start + 1 }
        } finally {
            compose.runOnIdle {
                original.forEachIndexed { index, (id, enabled) -> settings.move(id, index); settings.setEnabled(id, enabled) }
            }
        }
    }
}

private val Int.dp get() = androidx.compose.ui.unit.Dp(this.toFloat())
