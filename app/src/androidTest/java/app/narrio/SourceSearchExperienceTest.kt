package app.narrio

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.narrio.data.*
import app.narrio.domain.*
import app.narrio.ui.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

/**
 * Book details listening by source on the real streaming engine with controlled providers: a public source, the
 * TorBox library, a slow TorBox search, a failing add-on that succeeds on Retry, and one waiting behind its rate
 * limit. Each provider answers only when the test releases it. No network, account, or delivery calls.
 */
@RunWith(AndroidJUnit4::class)
class SourceSearchExperienceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val vm get() = ViewModelProvider(compose.activity)[NarrioViewModel::class.java]
    private val store = ViewModelStore()
    private var originalAppearance: AppearanceSettings? = null

    private val book = Audiobook("catalog:phm-sources", "Project Hail Mary", "Andy Weir", provider = "catalog", detailsLoaded = true,
        description = "A lone astronaut must save the Earth.", metadataSource = "Test catalog", metadataUpdatedAtMs = System.currentTimeMillis())
    private fun release(id: String, title: String, hash: Char, provider: String = "knaben", cached: Boolean = true, format: String = "M4B", author: String = "Andy Weir") = Audiobook(
        id, title, author, provider = provider, detailsLoaded = true, releaseTitle = title, cacheState = if (cached) "cached" else "uncached",
        cachedFormats = if (cached) listOf(format) else emptyList(), seeders = 24, filesVerified = true,
        torrentHash = if (provider == "knaben") hash.toString().repeat(40) else "", magnetUri = if (provider == "knaben") "magnet:?xt=urn:btih:${hash.toString().repeat(40)}" else "",
        sources = listOf(AudioSource("$id-src", "Whole-book audio", format, listOf(AudioPart("$id-p", "book.${format.lowercase()}", "Whole book", sizeBytes = 412_000_000,
            archiveUrl = if (provider == "archive") "https://example.com/$id.mp3" else "")), delivery = if (provider == "archive") "archive" else "torbox")))
    private val public = release("fixture-librivox", "Project Hail Mary (solo reading)", '0', provider = "archive", cached = false, format = "MP3")
    private val cached = release("fixture-cached", "Project Hail Mary - Andy Weir (read by Ray Porter) [Unabridged]", '1')
    private val other = release("fixture-other", "Project Hail Mary - Andy Weir 2021 MP3", '2', format = "MP3")
    private val maybe = release("fixture-maybe", "Project Hail Mary audiobook", '3', author = "Author not verified")
    private val fromAddon = release("fixture-abb", "Andy Weir - Project Hail Mary (Ray Porter)", '4', cached = false)

    /** A provider that answers when released; [answers] give successive results, and an exception answer fails. */
    private class Controlled(private val waiting: Boolean = false, private val variants: Boolean = true, vararg answers: () -> List<Audiobook>) : SourceLookup {
        private val queue = ArrayDeque(answers.toList())
        @Volatile var gate = CompletableDeferred<Unit>()
        override val titleVariants get() = variants
        fun answer() { gate.complete(Unit) }
        override suspend fun search(book: Audiobook, title: String, budget: SourceSearchBudget, status: suspend (SourceGroupStatus) -> Unit): List<Audiobook> {
            if (waiting) status(SourceGroupStatus.WAITING)
            gate.await()
            status(SourceGroupStatus.SEARCHING)
            val next = if (queue.size > 1) queue.removeFirst() else queue.first()
            gate = CompletableDeferred()
            return next()
        }
    }

    private val providers = listOf(
        SourceProvider("archive", "Internet Archive / LibriVox", SourceProviderKind.BUILT_IN, true, 0, false, false),
        SourceProvider("torbox-library", "My TorBox library", SourceProviderKind.BUILT_IN, true, 1, true, false),
        SourceProvider("torbox-search", "TorBox search", SourceProviderKind.BUILT_IN, true, 2, true, false),
        SourceProvider("addon:audiobookbay", "AudiobookBay", SourceProviderKind.ADDON, true, 3, true, true),
        SourceProvider("addon:knaben", "Knaben audiobooks", SourceProviderKind.ADDON, true, 4, true, true),
    )

    private fun fixture(lookups: Map<String, SourceLookup>, connected: Boolean = true, mode: ThemeMode = ThemeMode.NIGHT): NarrioViewModel {
        val settings = object : SourceProviderSettings {
            override val providers = MutableStateFlow(this@SourceSearchExperienceTest.providers)
            override fun setEnabled(id: String, enabled: Boolean) = Unit
            override fun move(id: String, index: Int) = Unit
        }
        val engine = ProviderSourceSearch(settings, { lookups[it.id] }, { it }, timeoutMs = 60_000)
        lateinit var fixture: NarrioViewModel
        compose.runOnIdle {
            originalAppearance = originalAppearance ?: vm.appearance.value
            fixture = NarrioViewModel(compose.activity.application, engine)
            store.put("sources-${System.nanoTime()}", fixture)
            fixture.connected.value = connected
            fixture.updateAppearance(fixture.appearance.value.copy(mode = mode))
            compose.activity.setContent { NarrioApp(compose.activity, fixture) }
        }
        compose.runOnIdle { fixture.open(book) }
        return fixture
    }

    @After fun cleanUp() {
        compose.runOnIdle { originalAppearance?.let { vm.updateAppearance(it) }; store.clear() }
    }

    private fun capture(name: String) {
        compose.waitForIdle(); runBlocking { delay(600) }
        val window = compose.activity.resources.configuration
        val suffix = if (window.screenWidthDp >= 600) "unfolded" else if (window.fontScale > 1.2f) "phone-large-text" else "phone"
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val folder = File(compose.activity.filesDir, "qa-captures").apply { mkdirs() }
        File(folder, "$name-$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    private fun reveal(matcher: SemanticsMatcher) = compose.onNodeWithTag("book-details").performScrollToNode(matcher)
    private fun top() = compose.onNodeWithTag("book-details").performScrollToIndex(0)

    @Test fun sectionsFillAsEachSourceAnswersAndTheBestMatchNeverMovesUnderTheListener() {
        val archive = Controlled(answers = arrayOf({ listOf(public) }))
        val library = Controlled(variants = false, answers = arrayOf({ emptyList() }))
        val search = Controlled(answers = arrayOf({ listOf(cached, other, maybe) }))
        val addon = Controlled(answers = arrayOf({ throw IOException("offline") }, { listOf(cached, fromAddon) }))
        val waiting = Controlled(waiting = true, answers = arrayOf({ emptyList() }))
        val fixture = fixture(mapOf("archive" to archive, "torbox-library" to library, "torbox-search" to search, "addon:audiobookbay" to addon, "addon:knaben" to waiting))

        // Every source has a section at once, each with its own state; nothing is found yet.
        compose.onNodeWithText("Finding audio…").assertIsNotEnabled()
        reveal(hasTestTag("source-section:addon:knaben"))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Waiting its turn").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodesWithText("Searching").fetchSemanticsNodes().size >= 3)

        // The public source answers first and becomes the best match; the failing add-on reports alone.
        compose.runOnIdle { archive.answer(); library.answer(); addon.answer() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("best-match-reasons").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("best-match-reasons").assertTextContains("Free public recording", substring = true)
        compose.onNodeWithTag("listen-action").assertIsEnabled()
        compose.waitUntil(5_000) { fixture.sourceSearch.value.streamed?.groups?.first { it.providerId == "addon:audiobookbay" }?.status == SourceGroupStatus.FAILED }
        reveal(hasTestTag("retry:addon:audiobookbay"))
        compose.onNodeWithText("Nothing for this book").assertExists()
        assertFalse(fixture.sourceSearch.value.streamed!!.complete)
        top(); capture("sources-in-progress-night")

        // The listener is on the page now; the slow source's better release must not replace the card.
        compose.onNodeWithTag("book-details").performTouchInput { down(Offset(4f, 4f)); up() }
        compose.runOnIdle { search.answer() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("better-match").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("best-match-reasons").assertTextContains("Free public recording", substring = true)
        capture("sources-better-match-night")
        compose.onNodeWithTag("better-match").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("better-match").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("best-match-reasons").assertTextContains("Ready to stream · M4B", substring = true)
        compose.runOnIdle { assertEquals(cached.id, fixture.sourceSearch.value.choice?.id) }

        // A failed source retries on its own; the rest keep their results.
        reveal(hasTestTag("retry:addon:audiobookbay"))
        compose.onNodeWithTag("retry:addon:audiobookbay").performClick()
        compose.waitUntil(5_000) { fixture.sourceSearch.value.streamed?.groups?.first { it.providerId == "addon:audiobookbay" }?.status == SourceGroupStatus.SEARCHING }
        compose.runOnIdle { addon.answer(); waiting.answer() }
        compose.waitUntil(10_000) { fixture.sourceSearch.value.streamed?.complete == true }
        compose.onNodeWithTag("sources-summary").assertTextEquals("All 5 sources answered · 4 found")
        // The release both sources found stays in the higher-priority section, credited to the other.
        reveal(hasText("Also found by AudiobookBay"))
        compose.onNodeWithText("Also found by AudiobookBay").assertIsDisplayed()

        // Possible matches stay folded until asked for.
        reveal(hasTestTag("possible:torbox-search"))
        compose.onNodeWithTag("release:${maybe.id}").assertDoesNotExist()
        compose.onNodeWithTag("possible:torbox-search").performClick()
        compose.onNodeWithTag("release:${maybe.id}").assertExists()

        compose.runOnIdle { fixture.updateAppearance(fixture.appearance.value.copy(mode = ThemeMode.DAY)) }
        top(); capture("sources-finished-day")
        reveal(hasTestTag("source-section:torbox-search")); capture("sources-sections-day")

        // A release row opens its own page, as before.
        reveal(hasTestTag("release:${other.id}"))
        compose.onNodeWithTag("release:${other.id}").performClick()
        compose.waitUntil(10_000) { fixture.selection.value.book?.recordingId == other.id && !fixture.selection.value.loading }
        compose.onNodeWithText("The recording").assertIsDisplayed()
    }

    @Test fun failedAndDisconnectedSearchesSayWhatToDoNext() {
        val down = { Controlled(answers = arrayOf({ throw IOException("offline") })).also { it.answer() } }
        val failing = fixture(mapOf("archive" to down(), "torbox-library" to down(), "torbox-search" to down(), "addon:audiobookbay" to down(), "addon:knaben" to down()), mode = ThemeMode.DAY)
        compose.waitUntil(10_000) { failing.sourceSearch.value.streamed?.complete == true }
        compose.onNodeWithText("Couldn't reach any source").assertIsDisplayed()
        compose.onNodeWithText("None of your 5 sources answered. Check your connection, then try again.").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsEnabled()
        reveal(hasTestTag("retry:torbox-search")); compose.onNodeWithTag("retry:torbox-search").assertIsDisplayed()
        top(); capture("sources-all-failed-day")

        val empty = Controlled(answers = arrayOf({ emptyList() })).also { it.answer() }
        val offline = fixture(mapOf("archive" to empty), connected = false)
        compose.waitUntil(10_000) { offline.sourceSearch.value.streamed?.complete == true }
        compose.onNodeWithText("No free public recording found").assertIsDisplayed()
        compose.onNodeWithText("Connect TorBox").assertIsDisplayed()
        reveal(hasTestTag("source-section:addon:knaben"))
        assertEquals(4, compose.onAllNodesWithText("Needs TorBox").fetchSemanticsNodes().size)
        top(); capture("sources-disconnected-night")
    }

    @Test fun sourceSettingsToggleAndReorderWithTouchAndTalkBack() {
        val settings = vm.sourceProviderSettings
        val original = settings.providers.value.map { it.id to it.enabled }
        try {
            compose.runOnIdle { originalAppearance = vm.appearance.value; vm.connected.value = false; vm.updateAppearance(vm.appearance.value.copy(mode = ThemeMode.DAY)); vm.navigate(2) }
            compose.onNodeWithText("Sources & add-ons · Audiobooks, ebooks & book info").performScrollTo().performClick()
            compose.onNodeWithText("TorBox isn't connected").assertIsDisplayed()
            compose.onNodeWithTag("addon-options").performScrollToNode(hasTestTag("source-row:torbox-search"))
            capture("source-settings-day")

            // Built-ins switch off, but offer no Remove.
            compose.onNodeWithTag("source-toggle:torbox-search").performClick()
            compose.waitUntil(5_000) { !settings.providers.value.first { it.id == "torbox-search" }.enabled }
            compose.onNodeWithContentDescription("More for TorBox search").performClick()
            compose.onNodeWithText("Remove TorBox search").assertDoesNotExist()
            val before = settings.providers.value.indexOfFirst { it.id == "torbox-search" }
            compose.onNodeWithText("Move up").performClick()
            compose.waitUntil(5_000) { settings.providers.value.indexOfFirst { it.id == "torbox-search" } == before - 1 }

            // TalkBack's Move down action, without dragging.
            val archive = settings.providers.value.indexOfFirst { it.id == "archive" }
            compose.onNodeWithTag("source-row:archive").fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == "Move down" }.let { action -> compose.runOnIdle { action.action() } }
            compose.waitUntil(5_000) { settings.providers.value.indexOfFirst { it.id == "archive" } == archive + 1 }
            compose.onNodeWithTag("addon-message").assertTextEquals("Internet Archive / LibriVox moved to ${archive + 2} of ${settings.providers.value.size}.")

            // Drag by the handle: My TorBox library moves below the next source.
            val start = settings.providers.value.indexOfFirst { it.id == "torbox-library" }
            compose.onNodeWithTag("addon-options").performScrollToNode(hasTestTag("source-row:torbox-library"))
            compose.onNodeWithTag("source-row:torbox-library").performTouchInput {
                down(Offset(24f * density, centerY))
                repeat(12) { moveBy(Offset(0f, height * 0.1f)) }
                up()
            }
            compose.waitUntil(5_000) { settings.providers.value.indexOfFirst { it.id == "torbox-library" } == start + 1 }
        } finally {
            compose.runOnIdle { original.forEachIndexed { index, (id, enabled) -> settings.move(id, index); settings.setEnabled(id, enabled) } }
        }
    }
}
