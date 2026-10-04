package app.narrio

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.narrio.data.AddonManager
import app.narrio.ui.NarrioViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AddonSettingsExperienceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val vm get() = ViewModelProvider(compose.activity)[NarrioViewModel::class.java]
    private fun reveal(matcher: SemanticsMatcher) = compose.onNodeWithTag("addon-options").performScrollToNode(matcher)

    @Test fun managerShowsProvidersPersistsDisabledStateAndReturnsToSettings() {
        val original = vm.graph.addons.installed.value.first { it.id == "audiobookbay" }.enabled
        try {
            compose.runOnIdle { vm.navigate(2) }
            compose.onNodeWithText("Add-ons · Book metadata, audio & ebooks").performScrollTo().performClick()
            compose.onNodeWithText("Add-on manifest URL").assertIsDisplayed()
            compose.onNodeWithText("Import add-on").assertIsNotEnabled()
            reveal(hasText("Audible Audiobooks")); compose.onNodeWithText("Audible Audiobooks").assertIsDisplayed()
            reveal(hasText("Open Library")); compose.onNodeWithText("Open Library").assertIsDisplayed()
            reveal(hasContentDescription("Enable AudiobookBay")); compose.onNodeWithContentDescription("Enable AudiobookBay").performClick()
            compose.waitUntil { vm.graph.addons.installed.value.first { it.id == "audiobookbay" }.enabled != original }
            compose.runOnIdle {
                val restored = AddonManager.create(compose.activity, vm.graph.http)
                assertEquals(!original, restored.installed.value.first { it.id == "audiobookbay" }.enabled)
            }
            reveal(hasText("Knaben AudioBooks")); compose.onNodeWithText("Knaben AudioBooks").assertIsDisplayed()
            reveal(hasText("The Pirate Bay")); compose.onNodeWithText("The Pirate Bay").assertIsDisplayed()
            reveal(hasText("Knaben Ebooks")); compose.onNodeWithText("Knaben Ebooks").assertIsDisplayed()
            compose.onNodeWithContentDescription("Back to settings").performClick()
            compose.onNodeWithText("Add-ons · Book metadata, audio & ebooks").assertIsDisplayed()
            compose.onNodeWithText("Add-ons · Book metadata, audio & ebooks").performScrollTo().performClick()
            reveal(hasText("Add-on manifest URL"))
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(compose.activity.filesDir, "addon-settings.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        } finally { compose.runOnIdle { vm.graph.addons.enable("audiobookbay", original) } }
    }
}
