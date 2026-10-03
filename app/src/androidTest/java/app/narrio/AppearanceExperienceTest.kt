package app.narrio

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.narrio.data.AppearanceStore
import app.narrio.domain.*
import app.narrio.ui.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Appearance-only native workflows, with no account changes or audio playback. */
@RunWith(AndroidJUnit4::class)
class AppearanceExperienceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val vm get() = ViewModelProvider(compose.activity)[NarrioViewModel::class.java]
    private lateinit var original: AppearanceSettings

    @Before fun openAppearance() {
        compose.runOnIdle { original = vm.appearance.value; vm.updateAppearance(AppearanceSettings()); vm.navigate(2) }
        compose.onNodeWithText("Appearance").performScrollTo().performClick()
    }

    @After fun restoreAppearance() { compose.runOnIdle { vm.updateAppearance(original) } }

    private fun target(tag: String): SemanticsNodeInteraction {
        val list = if (compose.onAllNodesWithTag("custom-options").fetchSemanticsNodes().isNotEmpty()) "custom-options" else "appearance-options"
        compose.onNodeWithTag(list).performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }

    private fun capture(name: String) {
        compose.runOnIdle {
            val keyboard = compose.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            keyboard.hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
        compose.waitForIdle()
        android.os.SystemClock.sleep(400)
        val config = compose.activity.resources.configuration
        val device = if (config.screenWidthDp >= 600) "expanded" else if (config.fontScale > 1.2f) "large-text" else "phone"
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val directory = File(compose.activity.filesDir, "appearance-captures").apply { mkdirs() }
        File(directory, "$name-$device.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun paletteTypographyAndSizeApplyImmediatelyAndSurviveRecreation() {
        target("palette-OCEAN").performClick()
        compose.onNodeWithTag("palette-OCEAN").assertIsSelected()
        capture("palettes-night")
        target("theme-preview")
        capture("appearance-night")
        target("mode-DAY").performClick()
        target("theme-preview")
        capture("appearance-day")
        target("mode-SYSTEM").performClick()
        compose.onNodeWithTag("mode-SYSTEM").assertIsSelected()
        target("mode-DAY").performClick()
        target("font-SYSTEM").performClick()
        target("text-size-LARGE").performClick()
        capture("typography")
        compose.activityRule.scenario.recreate()
        compose.runOnIdle {
            assertEquals(ThemePalette.OCEAN, vm.appearance.value.palette)
            assertEquals(ThemeMode.DAY, vm.appearance.value.mode)
            assertEquals(AppFont.SYSTEM, vm.appearance.value.font)
            assertEquals(AppTextSize.LARGE, vm.appearance.value.textSize)
            assertEquals(vm.appearance.value, AppearanceStore(vm.graph.preferences).read())
        }
        target("text-size-LARGE").assertIsSelected()
        target("restore-appearance").performClick()
        compose.runOnIdle { assertEquals(AppearanceSettings(), vm.appearance.value) }
    }

    @Test fun customThemeValidatesSavesBothModesAndKeepsTheSavedCopyOnCancel() {
        target("edit-custom-theme").performClick()
        target("custom-theme-name").performTextReplacement("Aurora")
        compose.onNodeWithText("Start from a preset").performScrollTo().performClick()
        compose.onNodeWithText("Forest").performClick()
        target("colour-hex").performTextReplacement("broken")
        compose.onNodeWithTag("save-custom-theme").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Choose #8ACED8").performScrollTo().performClick()
        compose.onNodeWithTag("colour-hex").assertTextContains("#8ACED8")
        compose.onNodeWithContentDescription("Accent Hue").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(220f) }
        target("colour-hex")
        compose.onNodeWithTag("colour-hex").performTextReplacement("#AABBCC")
        compose.onNodeWithTag("save-custom-theme").assertIsEnabled()
        target("custom-day").performClick()
        target("colour-role-BACKGROUND").performClick()
        target("colour-hex").performTextReplacement("#FFF2E0")
        capture("custom-colours")
        target("theme-preview")
        capture("custom-preview")
        compose.onNodeWithTag("save-custom-theme").performClick()
        val saved = CustomTheme("Aurora", presetColours(ThemePalette.FOREST, true).copy(accent = 0xAABBCC),
            presetColours(ThemePalette.FOREST, false).copy(background = 0xFFF2E0))
        compose.runOnIdle { assertEquals(saved, vm.appearance.value.custom); assertEquals(ThemePalette.CUSTOM, vm.appearance.value.palette) }
        target("palette-FOREST").performClick()
        target("palette-CUSTOM").performClick()
        compose.runOnIdle { assertEquals(saved, vm.appearance.value.custom) }
        target("edit-custom-theme").performClick()
        target("custom-theme-name").performTextReplacement("Discard this draft")
        compose.activityRule.scenario.recreate()
        target("custom-theme-name").assert(hasText("Discard this draft"))
        compose.onNodeWithContentDescription("Cancel custom theme").performClick()
        compose.runOnIdle { assertEquals(saved, vm.appearance.value.custom) }
        target("restore-appearance").performClick()
        compose.runOnIdle { assertEquals(AppearanceSettings(custom = saved), vm.appearance.value) }
    }

    @Test fun localStoreMigratesLegacyModeAndDoesNotTouchUnrelatedPreferences() {
        val preferences = compose.activity.getSharedPreferences("appearance-store-test", Context.MODE_PRIVATE)
        try {
            preferences.edit().clear().putString("theme", "System").putBoolean("downloadWifi", false).commit()
            val store = AppearanceStore(preferences)
            assertEquals(ThemeMode.SYSTEM, store.read().mode)
            val value = store.read().copy(palette = ThemePalette.ROSEWOOD, font = AppFont.MANROPE)
            store.save(value)
            assertEquals(value, AppearanceStore(preferences).read())
            assertFalse(preferences.contains("theme"))
            assertFalse(preferences.getBoolean("downloadWifi", true))
        } finally { preferences.edit().clear().commit() }
    }

    @Test fun everyPresetAndExtremeCustomPaletteHasReadableMaterialRoles() {
        fun rgb(colour: Color) = colour.toArgb() and 0xFFFFFF
        fun pair(foreground: Color, background: Color, minimum: Double = 4.5) {
            assertTrue("${ThemeContrast.hex(rgb(foreground))} on ${ThemeContrast.hex(rgb(background))}", ThemeContrast.ratio(rgb(foreground), rgb(background)) >= minimum)
        }
        val settings = ThemePalette.entries.filter { it != ThemePalette.CUSTOM }.map { AppearanceSettings(palette = it) } +
            listOf(0, 0xFFFFFF, 0x747474, 0x777777, 0x7F7F7F, 0xFF00FF, 0x00FF00, 0x0000FF).map {
                val colours = ThemeColours(it, it, it)
                AppearanceSettings(palette = ThemePalette.CUSTOM, custom = CustomTheme(night = colours, day = colours))
            }
        settings.forEach { appearance -> listOf(false, true).forEach { dark ->
            val scheme = colourSchemeFor(appearance, dark)
            with(scheme) {
                listOf(background, surface, surfaceContainerLow, surfaceContainer, surfaceContainerHigh, surfaceContainerHighest).forEach {
                    pair(onSurface, it); pair(onSurfaceVariant, it); pair(primary, it); pair(secondary, it); pair(outline, it, 3.0)
                }
                pair(onPrimary, primary); pair(onPrimaryContainer, primaryContainer)
                pair(onSecondary, secondary); pair(onSecondaryContainer, secondaryContainer)
                pair(onError, error); pair(onErrorContainer, errorContainer)
                pair(inverseOnSurface, inverseSurface); pair(inversePrimary, inverseSurface)
            }
        } }
    }
}
