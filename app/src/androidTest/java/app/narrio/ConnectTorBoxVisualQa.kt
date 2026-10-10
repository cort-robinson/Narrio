package app.narrio

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.narrio.domain.*
import app.narrio.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Captures Connect TorBox surfaces in Night and Day for visual review; not a CI test. Writes to files/qa-captures. */
@RunWith(AndroidJUnit4::class)
class ConnectTorBoxVisualQa {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val vm get() = ViewModelProvider(compose.activity)[NarrioViewModel::class.java]
    private val book = Audiobook("catalog:connect-qa", "Project Hail Mary", "Andy Weir", provider = "catalog", detailsLoaded = true,
        description = "A lone astronaut must save the Earth.", metadataSource = "Test catalog", metadataUpdatedAtMs = System.currentTimeMillis())

    private fun capture(name: String) {
        compose.waitForIdle(); runBlocking { delay(900) }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val folder = File(compose.activity.filesDir, "qa-captures").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }

    @Test fun captureConnectSurfaces() {
        val original = vm.appearance.value
        val connected = vm.connected.value
        try {
            for (mode in listOf(ThemeMode.NIGHT, ThemeMode.DAY)) {
                val label = mode.name.lowercase()
                compose.runOnIdle {
                    vm.updateAppearance(original.copy(mode = mode)); vm.connected.value = false
                    vm.graph.preferences.edit().remove(TorBoxNudge.KEY).commit(); vm.navigate(0); vm.search("")
                }
                compose.onNodeWithTag("torbox-nudge").assertIsDisplayed(); capture("connect-discover-nudge-$label")

                compose.runOnIdle { vm.selection.value = SelectionState(book); vm.sourceSearch.value = SourceSearchState(book = book).withSnapshot(StreamedSourceSearch(book, listOf(
                    SourceGroup("archive", "Internet Archive / LibriVox", SourceGroupStatus.DONE),
                    SourceGroup("torbox-search", "TorBox search", SourceGroupStatus.SKIPPED, message = "Connect TorBox")), complete = true)) }
                compose.onNodeWithText("Connect TorBox").performScrollTo().performClick()
                compose.onNodeWithTag("connect-torbox-sheet").assertIsDisplayed(); capture("connect-sheet-$label")
                compose.onNodeWithTag("torbox-key").performTextInput("synthetic-key")
                compose.runOnIdle { vm.torBox.update { it.copy(error = "TorBox didn't accept this API key. Copy the whole key from your TorBox settings and check that your plan includes API access.") } }
                capture("connect-sheet-error-$label")
                compose.runOnIdle { vm.dismissTorBoxConnect() }
                compose.waitUntil(5_000) { compose.onAllNodesWithTag("connect-torbox-sheet").fetchSemanticsNodes().isEmpty() }

                compose.runOnIdle { vm.navigate(2) }
                compose.onNodeWithTag("torbox-key").assertIsDisplayed(); capture("connect-settings-disconnected-$label")
                compose.runOnIdle { vm.connected.value = true }
                compose.onNodeWithTag("torbox-connected").assertIsDisplayed(); capture("connect-settings-connected-$label")
            }
        } finally {
            compose.runOnIdle { vm.updateAppearance(original); vm.connected.value = connected; vm.graph.preferences.edit().remove(TorBoxNudge.KEY).commit() }
        }
    }
}
