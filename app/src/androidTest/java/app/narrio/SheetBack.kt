package app.narrio

import android.view.KeyEvent
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.platform.app.InstrumentationRegistry

/**
 * A real Back key press on the sheet tagged [tag]. A sheet is its own window, and the system sends Back to the window
 * with input focus; on slower emulators (API 35 on CI) focus can reach a new sheet well after it's drawn, so the press
 * waits for it rather than landing on the screen underneath.
 */
fun ComposeTestRule.pressBackInSheet(tag: String) {
    waitUntil(10_000) {
        onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull()?.let { (it.root as? ViewRootForTest)?.view?.hasWindowFocus() } == true
    }
    InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
    waitForIdle()
}
