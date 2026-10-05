package app.narrio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.window.layout.FoldingFeature
import androidx.window.testing.layout.FoldingFeature as TestFold
import androidx.window.testing.layout.TestWindowLayoutInfo
import androidx.window.testing.layout.WindowLayoutInfoPublisherRule
import app.narrio.domain.AppearanceSettings
import app.narrio.domain.ThemeMode
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Captures read along's planned states for visual review (not part of CI): phone, unfolded, and tabletop, in Night
 * and Day. Screenshots go to the app's external files under `read-along-qa/`. Window sizes are temporary `wm`
 * overrides restored afterwards; postures are injected, not physical hinge evidence.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class ReadAlongVisualQa {
    @get:Rule(order = 0) val postures = WindowLayoutInfoPublisherRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private lateinit var fixture: ReadAlongFixture
    private val output by lazy { File(compose.activity.getExternalFilesDir(null), "read-along-qa").apply { mkdirs() } }
    private var originalSize: String? = null
    private var originalDensity: String? = null

    private fun command(command: String): String = automation.executeShellCommand(command).use { java.io.FileInputStream(it.fileDescriptor).bufferedReader().readText() }
    private fun shell(command: String) { automation.executeShellCommand(command).close(); Thread.sleep(2_000) }

    @Before fun setUp() {
        originalSize = Regex("Override size: (\\S+)").find(command("wm size"))?.groupValues?.get(1)
        originalDensity = Regex("Override density: (\\d+)").find(command("wm density"))?.groupValues?.get(1)
        fixture = ReadAlongFixture.create(compose, "read-along-qa")
    }

    @After fun restore() {
        postures.overrideWindowLayoutInfo(TestWindowLayoutInfo(emptyList()))
        shell("wm size ${originalSize ?: "reset"}"); shell("wm density ${originalDensity ?: "reset"}")
        fixture.remove()
    }

    private fun capture(name: String) {
        compose.waitForIdle(); Thread.sleep(1_200)
        val bitmap = requireNotNull(automation.takeScreenshot()) { "Couldn't capture the display" }
        File(output, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun appearance(mode: ThemeMode) = compose.runOnIdle { fixture.vm.updateAppearance(AppearanceSettings(mode = mode)) }

    /** Opens read along from the Listening room at [positionMs] and waits for the page to follow narration there. */
    private fun readAlongAt(positionMs: Long) {
        fixture.seek(positionMs)
        compose.runOnIdle { fixture.vm.closeReader(); fixture.vm.playerOpen.value = true }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("read-along").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag("read-along")[0].performClick()
        val controller = fixture.controller()
        val place = fixture.place(positionMs)!!
        compose.waitUntil(20_000) { controller.following.value && controller.visible.value?.contains(place.cursor) == true }
    }

    @Test fun phone() {
        appearance(ThemeMode.NIGHT)
        compose.runOnIdle { fixture.vm.playerOpen.value = true }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("read-along").fetchSemanticsNodes().isNotEmpty() }
        capture("phone-listening-room-entry")
        readAlongAt(200_000)
        capture("phone-night")
        compose.runOnIdle { fixture.reader().update { copy(wordHighlight = true) } }
        capture("phone-night-word")
        compose.runOnIdle { fixture.reader().update { copy(wordHighlight = false) } }
        val controller = fixture.controller()
        compose.runOnIdle { controller.next(animated = false) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("back-to-narration").fetchSemanticsNodes().isNotEmpty() }
        capture("phone-night-back-to-narration")
        compose.onNodeWithTag("back-to-narration").performClick()
        appearance(ThemeMode.DAY)
        capture("phone-day")
        compose.onNodeWithTag("read-along-status").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("read-along-sheet").fetchSemanticsNodes().isNotEmpty() }
        capture("phone-day-options")
        automation.executeShellCommand("input keyevent KEYCODE_BACK").close(); Thread.sleep(800)
        fixture.seek(fixture.anchoredUntilMs + 40_000)
        Thread.sleep(2_500)
        capture("phone-day-estimated")
        appearance(ThemeMode.NIGHT)
        capture("phone-night-estimated")
    }

    @Test fun unfolded() {
        shell("wm size 1848x2448"); shell("wm density 360")
        for (mode in listOf(ThemeMode.NIGHT, ThemeMode.DAY)) {
            appearance(mode)
            readAlongAt(200_000)
            compose.onNodeWithTag("read-along-panel").assertIsDisplayed()
            capture("unfolded-${mode.name.lowercase()}")
        }
        val vertical = TestFold(compose.activity, size = 24, orientation = FoldingFeature.Orientation.VERTICAL)
        postures.overrideWindowLayoutInfo(TestWindowLayoutInfo(listOf(vertical)))
        Thread.sleep(1_500)
        Thread.sleep(4_000)
        capture("unfolded-hinge-day")
    }

    @Test fun tabletop() {
        shell("wm size 2448x1848"); shell("wm density 360")
        val horizontal = TestFold(compose.activity, size = 24, state = FoldingFeature.State.HALF_OPENED, orientation = FoldingFeature.Orientation.HORIZONTAL)
        for (mode in listOf(ThemeMode.NIGHT, ThemeMode.DAY)) {
            appearance(mode)
            postures.overrideWindowLayoutInfo(TestWindowLayoutInfo(emptyList()))
            readAlongAt(200_000)
            postures.overrideWindowLayoutInfo(TestWindowLayoutInfo(listOf(horizontal)))
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("read-along-deck").fetchSemanticsNodes().isNotEmpty() }
            // Moving into the tabletop shell reopens the reader; wait for the live page to follow the narration again.
            val place = fixture.place(200_000)!!
            fun live() = (fixture.reader().state.value as? app.narrio.ui.ReaderState.Ready)?.session?.controller
            try { compose.waitUntil(30_000) { live()?.let { it.ready.value && it.following.value && it.visible.value?.contains(place.cursor) == true } == true } }
            catch (timeout: Throwable) { capture("tabletop-${mode.name.lowercase()}-failed"); throw AssertionError("state=${fixture.reader().state.value} ready=${live()?.ready?.value} visible=${live()?.visible?.value}", timeout) }
            capture("tabletop-${mode.name.lowercase()}")
        }
    }
}
