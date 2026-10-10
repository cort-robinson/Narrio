package app.narrio

import android.view.KeyEvent
import androidx.activity.compose.setContent
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Connecting TorBox from where it's needed, with controlled sources and no TorBox account: the sheet opens over the
 * book, the book stays selected and looks again once connected, source settings open on Add-ons, and Discover's
 * hint shows only while disconnected and stays dismissed.
 */
@RunWith(AndroidJUnit4::class)
class ConnectTorBoxTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val vm get() = ViewModelProvider(compose.activity)[NarrioViewModel::class.java]
    private val store = ViewModelStore()
    private var restoreConnected: Boolean? = null

    private val book = Audiobook("catalog:connect-torbox", "Project Hail Mary", "Andy Weir", provider = "catalog", detailsLoaded = true,
        description = "A lone astronaut must save the Earth.", metadataSource = "Test catalog", metadataUpdatedAtMs = System.currentTimeMillis())
    private val cached = Audiobook("fixture-connect-cached", "Project Hail Mary - Andy Weir (read by Ray Porter) [Unabridged]", "Andy Weir", provider = "knaben",
        detailsLoaded = true, releaseTitle = "Project Hail Mary - Andy Weir (read by Ray Porter) [Unabridged]", cacheState = "cached", cachedFormats = listOf("M4B"),
        seeders = 24, filesVerified = true, torrentHash = "c".repeat(40), magnetUri = "magnet:?xt=urn:btih:${"c".repeat(40)}",
        sources = listOf(AudioSource("fixture-connect-src", "Whole-book audio", "M4B", listOf(AudioPart("fixture-connect-p", "book.m4b", "Whole book", sizeBytes = 412_000_000)), delivery = "torbox")))

    private fun public(id: String, title: String) = Audiobook(id, title, "Andy Weir", provider = "archive", detailsLoaded = true, releaseTitle = title, filesVerified = true,
        sources = listOf(AudioSource("$id-src", "Whole-book audio", "MP3", listOf(AudioPart("$id-p", "book.mp3", "Whole book", sizeBytes = 300_000_000, archiveUrl = "https://example.com/$id.mp3")), delivery = "archive")))

    /** Stands in for TorBox: checks nothing, saves nothing, and answers when [answer] completes. */
    private class FakeAccount {
        val saved = mutableListOf<String>()
        val answer = CompletableDeferred<Unit>()
        suspend fun save(key: String) { answer.await(); saved += key }
    }

    /** Answers once released, with [answer]. */
    private class Controlled(private val answer: List<Audiobook>) : SourceLookup {
        val gate = CompletableDeferred<Unit>()
        @Volatile var calls = 0
        override suspend fun search(book: Audiobook, title: String, budget: SourceSearchBudget, status: suspend (SourceGroupStatus) -> Unit): List<Audiobook> {
            calls++; gate.await(); status(SourceGroupStatus.SEARCHING); return answer
        }
    }

    private fun fixture(lookups: Map<String, SourceLookup>, enabled: Boolean = true, account: FakeAccount = FakeAccount(), connected: Boolean = false): NarrioViewModel {
        val settings = object : SourceProviderSettings {
            override val providers = MutableStateFlow(listOf(
                SourceProvider("archive", "Internet Archive / LibriVox", SourceProviderKind.BUILT_IN, enabled, 0, false, false),
                SourceProvider("torbox-search", "TorBox search", SourceProviderKind.BUILT_IN, enabled, 1, true, false)))
            override fun setEnabled(id: String, enabled: Boolean) = Unit
            override fun move(id: String, index: Int) = Unit
        }
        lateinit var fixture: NarrioViewModel
        compose.runOnIdle {
            fixture = NarrioViewModel(compose.activity.application, ProviderSourceSearch(settings, { lookups[it.id] }, { it }, timeoutMs = 60_000), account::save)
            store.put("connect-${System.nanoTime()}", fixture)
            fixture.connected.value = connected
            compose.activity.setContent { NarrioApp(compose.activity, fixture) }
        }
        compose.runOnIdle { fixture.open(book) }
        return fixture
    }

    @After fun cleanUp() {
        compose.runOnIdle {
            vm.graph.preferences.edit().remove(TorBoxNudge.KEY).commit()
            restoreConnected?.let { vm.connected.value = it }
            store.clear()
        }
    }

    @Test fun connectingFromABookKeepsItOpenAndLooksAgainWithTorBox() {
        val archive = Controlled(emptyList()).also { it.gate.complete(Unit) }
        val torbox = Controlled(listOf(cached))
        val account = FakeAccount()
        val fixture = fixture(mapOf("archive" to archive, "torbox-search" to torbox), account = account)
        compose.waitUntil(10_000) { fixture.sourceSearch.value.streamed?.complete == true }
        assertEquals(0, torbox.calls)

        compose.onNodeWithText("Connect TorBox").performScrollTo().performClick()
        compose.onNodeWithTag("connect-torbox-sheet").assertIsDisplayed()
        compose.runOnIdle { assertEquals(book.id, fixture.selection.value.book?.id); assertEquals(0, fixture.destination.value) }
        compose.onNodeWithTag("torbox-connect").assertIsNotEnabled()
        compose.onNodeWithTag("torbox-key").performTextInput(" synthetic-key\n")
        compose.onNodeWithTag("torbox-connect").assertIsEnabled()

        // A failed attempt explains itself under the key; editing the key clears it.
        compose.runOnIdle { fixture.torBox.update { it.copy(error = "TorBox didn't accept this API key.") } }
        compose.onNodeWithTag("torbox-error", useUnmergedTree = true).assertTextEquals("TorBox didn't accept this API key.")
        compose.onNodeWithTag("torbox-key").performTextInput("2")
        compose.onNodeWithTag("torbox-error", useUnmergedTree = true).assertDoesNotExist()

        // Back closes the sheet (after the keyboard, when one is open) and leaves the book as it was.
        repeat(2) { if (fixture.torBox.value.prompt) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); compose.waitForIdle() } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("connect-torbox-sheet").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { assertFalse(fixture.torBox.value.prompt); assertEquals(book.id, fixture.selection.value.book?.id) }
        compose.onNodeWithText("The book").assertIsDisplayed()

        // Connecting through the form: it shows progress, then closes and the page searches TorBox in place.
        compose.onNodeWithText("Connect TorBox").performScrollTo().performClick()
        compose.onNodeWithTag("connect-torbox-sheet").assertIsDisplayed()
        compose.onNodeWithTag("torbox-key").performTextInput("synthetic-key")
        compose.onNodeWithTag("torbox-connect").performClick()
        compose.onNodeWithText("Connecting…", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("torbox-connect").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(fixture.torBox.value.connecting); account.answer.complete(Unit) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("connect-torbox-sheet").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { assertEquals(listOf("synthetic-key"), account.saved); assertTrue(fixture.connected.value) }
        compose.waitUntil(5_000) { torbox.calls > 0 }
        compose.runOnIdle { torbox.gate.complete(Unit) }
        compose.waitUntil(10_000) { fixture.sourceSearch.value.streamed?.complete == true }
        compose.runOnIdle { assertEquals(book.id, fixture.selection.value.book?.id); assertEquals(cached.id, fixture.sourceSearch.value.choice?.id) }
        compose.onNodeWithTag("book-details").performScrollToIndex(0)
        compose.onNodeWithTag("best-match-reasons").assertTextContains("Ready to stream · M4B", substring = true)
    }

    @Test fun reconnectingSearchesAfreshAndKeepsTheListenersRecording() {
        val first = public("fixture-connect-first", "Project Hail Mary")
        val second = public("fixture-connect-second", "Project Hail Mary (solo reading)")
        val archive = Controlled(listOf(first, second)).also { it.gate.complete(Unit) }
        val torbox = Controlled(listOf(cached)).also { it.gate.complete(Unit) }
        val account = FakeAccount().also { it.answer.complete(Unit) }
        // Found while connected to one account; the listener picks a recording other than the best match.
        val fixture = fixture(mapOf("archive" to archive, "torbox-search" to torbox), account = account, connected = true)
        compose.waitUntil(10_000) { fixture.sourceSearch.value.streamed?.complete == true }
        val pick = compose.runOnIdle { fixture.sourceSearch.value.results.first { it.id != fixture.sourceSearch.value.choice?.id }.also(fixture::chooseVersion) }
        compose.runOnIdle { assertEquals(pick.id, fixture.sourceSearch.value.choice?.id) }
        val searched = torbox.calls

        // Signed out, then connected again with another key: the old account's results are never reused.
        compose.runOnIdle { fixture.connected.value = false; fixture.requestTorBoxConnect() }
        compose.onNodeWithTag("torbox-key").performTextInput("another-account")
        compose.onNodeWithTag("torbox-connect").performClick()
        compose.waitUntil(5_000) { fixture.connected.value && torbox.calls > searched }
        compose.waitUntil(10_000) { fixture.sourceSearch.value.streamed?.complete == true }
        compose.runOnIdle {
            assertEquals(listOf("another-account"), account.saved)
            assertEquals(pick.id, fixture.sourceSearch.value.choice?.id)
            assertEquals(book.id, fixture.selection.value.book?.id)
        }
    }

    @Test fun openSourceSettingsLandsOnAddonsAndSettingsLeadWithTorBox() {
        val fixture = fixture(emptyMap(), enabled = false)
        compose.waitUntil(10_000) { fixture.sourceSearch.value.streamed?.complete == true }
        compose.onNodeWithText("Open source settings").performScrollTo().performClick()
        compose.onNodeWithTag("addon-options").assertExists()
        compose.onNodeWithText("Sources & add-ons").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, fixture.destination.value); assertNull(fixture.selection.value.book) }

        // Its back arrow returns to Settings, which opens on TorBox; Add-ons' own Connect TorBox opens the sheet.
        compose.onNodeWithContentDescription("Back to settings").performClick()
        compose.onNodeWithTag("torbox-key").assertIsDisplayed()
        compose.onNodeWithText("Sources & add-ons · Audiobooks, ebooks & book info").assertIsDisplayed()
        compose.runOnIdle { fixture.connected.value = true }
        compose.onNodeWithTag("torbox-connected").assertIsDisplayed()
        compose.onNodeWithText("Disconnect").performClick()
        compose.onNodeWithText("Disconnect TorBox?").assertIsDisplayed()
        compose.onNodeWithText("Keep connected").performClick()
        compose.runOnIdle { assertTrue(fixture.connected.value) }
    }

    @Test fun discoverHintShowsOnlyWhileDisconnectedAndStaysDismissed() {
        compose.runOnIdle {
            restoreConnected = vm.connected.value
            vm.graph.preferences.edit().remove(TorBoxNudge.KEY).commit()
            vm.connected.value = false; vm.navigate(0); vm.search("")
        }
        compose.onNodeWithTag("torbox-nudge").assertIsDisplayed()
        compose.onNodeWithText("Most of these books need TorBox to listen.").assertIsDisplayed()
        compose.runOnIdle { vm.connected.value = true }
        compose.onNodeWithTag("torbox-nudge").assertDoesNotExist()
        compose.runOnIdle { vm.connected.value = false }

        // Its Connect TorBox opens the sheet over Discover.
        compose.onNodeWithTag("torbox-nudge").assertIsDisplayed()
        compose.onNode(hasText("Connect TorBox") and hasAnyAncestor(hasTestTag("torbox-nudge"))).performClick()
        compose.onNodeWithTag("connect-torbox-sheet").assertIsDisplayed()
        compose.runOnIdle { vm.dismissTorBoxConnect() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("connect-torbox-sheet").fetchSemanticsNodes().isEmpty() }

        // Searching hides it; Not now hides it for good, across a restart of the screen.
        compose.runOnIdle { vm.search("hail mary") }
        compose.onNodeWithTag("torbox-nudge").assertDoesNotExist()
        compose.runOnIdle { vm.search("") }
        compose.onNodeWithText("Not now").performClick()
        compose.onNodeWithTag("torbox-nudge").assertDoesNotExist()
        compose.runOnIdle { assertTrue(vm.graph.preferences.getLong(TorBoxNudge.KEY, 0) > 0) }
        compose.activityRule.scenario.recreate()
        compose.runOnIdle { ViewModelProvider(compose.activity)[NarrioViewModel::class.java].navigate(0) }
        compose.onNodeWithTag("torbox-nudge").assertDoesNotExist()
    }
}
