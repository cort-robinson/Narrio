package app.narrio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.narrio.domain.AppearanceSettings
import app.narrio.domain.ThemeMode
import app.narrio.reader.ReaderFixtures
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Captures the reader's planned states for visual review (not part of CI). Screenshots are written to the app's
 * external files directory under `reader-qa/`. Window sizes are temporary `wm` overrides restored afterwards.
 */
@RunWith(AndroidJUnit4::class)
class ReaderVisualQa {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val seeds by lazy { ReaderSeeds(compose) }
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val book by lazy { seeds.book("reader-visual-qa", ReaderFixtures.sampleEpub(24), "EPUB", "The Secret Garden") }
    private val output by lazy { File(compose.activity.getExternalFilesDir(null), "reader-qa").apply { mkdirs() } }

    @After fun restore() {
        // The QA emulator's baseline override.
        shell("wm size 1248x1972"); shell("wm density 420")
        seeds.remove(book)
    }

    private fun shell(command: String) { automation.executeShellCommand(command).close(); Thread.sleep(1_500) }

    /** Screenshots go to /data/local/tmp/reader-qa, which outlives the test APK's uninstall. */
    private fun capture(name: String) {
        compose.waitForIdle(); Thread.sleep(900)
        automation.executeShellCommand("mkdir -p /data/local/tmp/reader-qa").close()
        val display = System.getenv("NARRIO_QA_DISPLAY") ?: "4619827259835644672"
        automation.executeShellCommand("screencap -p -d $display /data/local/tmp/reader-qa/$name.png").use { java.io.FileInputStream(it.fileDescriptor).readBytes() }
    }

    private fun states(mode: ThemeMode, prefix: String) {
        val controller = seeds.open(book, AppearanceSettings(mode = mode))
        android.util.Log.i("ReaderVisualQa", "$prefix layout " + seeds.evaluate(controller,
            "({style: document.documentElement.getAttribute('style'), cols: getComputedStyle(document.documentElement).columnCount, width: getComputedStyle(document.documentElement).columnWidth, w: innerWidth})"))
        compose.runOnIdle { controller.jumpTo(controller.book.cursor("OEBPS/text/chapter2.xhtml", 0)) }
        compose.waitUntil(10_000) { controller.cursor.value?.resource == "OEBPS/text/chapter2.xhtml" }
        compose.runOnIdle { controller.next(animated = false) }
        Thread.sleep(1_200)
        capture("$prefix-reading")
        compose.onNodeWithTag("reader-page").performTouchInput { click(center) }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Contents").fetchSemanticsNodes().isNotEmpty() }
        capture("$prefix-controls")
        compose.onNodeWithTag("reader-text-settings").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("reader-typography").fetchSemanticsNodes().isNotEmpty() }
        capture("$prefix-typography")
        compose.onNodeWithTag("reader-typography").performTouchInput { swipeUp() }
        capture("$prefix-typography-more")
        shell("input keyevent KEYCODE_BACK")
        compose.waitForIdle()
        // The controls stay up behind the sheet.
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Contents").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Contents").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("reader-contents-sheet").fetchSemanticsNodes().isNotEmpty() }
        capture("$prefix-contents")
        compose.onNodeWithText("V · The Cry in the Corridor").performClick()
        compose.waitUntil(10_000) { controller.cursor.value?.resource == "OEBPS/text/chapter5.xhtml" }
        compose.onNodeWithTag("reader-page").performTouchInput { click(center) }
        Thread.sleep(800)
        capture("$prefix-after-jump")
        compose.runOnIdle { seeds.vm.closeReader() }
        compose.waitForIdle()
    }

    @Test fun phoneNightAndDay() {
        states(ThemeMode.NIGHT, "phone-night")
        states(ThemeMode.DAY, "phone-day")
    }

    @Test fun footnoteAndImage() {
        val controller = seeds.open(book, AppearanceSettings(mode = ThemeMode.DAY))
        compose.runOnIdle { controller.jumpTo(controller.book.cursor("OEBPS/text/chapter2.xhtml", 0)) }
        compose.waitUntil(10_000) { controller.cursor.value?.resource == "OEBPS/text/chapter2.xhtml" }
        Thread.sleep(1_000)
        // Tap the note reference in the second paragraph.
        val json = seeds.evaluate(controller, "(function(){var a=document.querySelector('a[epub\\\\:type~=noteref], a[*|type~=noteref]');var r=a.getBoundingClientRect();return {x:r.left+r.width/2,y:r.top+r.height/2,w:window.innerWidth,h:window.innerHeight};})()")
        val point = org.json.JSONObject(json!!)
        compose.onNodeWithTag("reader-page").performTouchInput {
            click(androidx.compose.ui.geometry.Offset(width * (point.getDouble("x") / point.getDouble("w")).toFloat(), height * (point.getDouble("y") / point.getDouble("h")).toFloat()))
        }
        compose.waitUntil(8_000) { compose.onAllNodesWithTag("reader-footnote").fetchSemanticsNodes().isNotEmpty() }
        capture("phone-day-footnote")
        shell("input keyevent KEYCODE_BACK")
        compose.runOnIdle { controller.jumpTo(controller.book.cursor("OEBPS/text/chapter1.xhtml", 0)) }
        compose.waitUntil(10_000) { controller.cursor.value?.resource == "OEBPS/text/chapter1.xhtml" }
        Thread.sleep(1_000)
        val image = org.json.JSONObject(seeds.evaluate(controller, "(function(){var r=document.querySelector('img').getBoundingClientRect();return {x:r.left+r.width/2,y:r.top+r.height/2,w:window.innerWidth,h:window.innerHeight};})()")!!)
        compose.onNodeWithTag("reader-page").performTouchInput {
            click(androidx.compose.ui.geometry.Offset(width * (image.getDouble("x") / image.getDouble("w")).toFloat(), height * (image.getDouble("y") / image.getDouble("h")).toFloat()))
        }
        runCatching { compose.waitUntil(8_000) { compose.onAllNodesWithTag("reader-image").fetchSemanticsNodes().isNotEmpty() } }
        capture("phone-day-image")
        android.util.Log.i("ReaderVisualQa", "image probe $image")
    }

    @Test fun unfoldedSpreadAndPhoneLandscape() {
        shell("wm size 2448x1848"); shell("wm density 395")
        states(ThemeMode.NIGHT, "unfolded-night")
        states(ThemeMode.DAY, "unfolded-day")
        shell("wm size 2340x1080"); shell("wm density 450")
        val controller = seeds.open(book, AppearanceSettings(mode = ThemeMode.NIGHT))
        compose.runOnIdle { controller.jumpTo(controller.book.cursor("OEBPS/text/chapter2.xhtml", 0)) }
        Thread.sleep(2_000)
        capture("phone-landscape-night")
    }
}
