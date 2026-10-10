package app.narrio

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.narrio.domain.*
import app.narrio.ui.*
import app.narrio.updates.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Controlled updater states; no release network calls or installs are made by UI tests. */
@RunWith(AndroidJUnit4::class)
class AppUpdatesExperienceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val update = AppUpdate("1.6.0-dev.123", 123, "Narrio-1.6.0-dev.123.apk", 42, "a".repeat(64),
        "b".repeat(64), "c".repeat(40), "", "")
    private var captureMode = ThemeMode.NIGHT

    private fun content(state: MutableState<UpdateState>, mode: ThemeMode = ThemeMode.NIGHT,
                        automatic: (Boolean) -> Unit = {}, check: () -> Unit = {}, download: () -> Unit = {},
                        install: () -> Unit = {}, allow: () -> Unit = {}, automaticInstallSupported: Boolean = true) {
        captureMode = mode
        compose.activity.setContent {
            NarrioTheme(AppearanceSettings(mode = mode)) {
                SideEffect {
                    androidx.core.view.WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).apply {
                        isAppearanceLightStatusBars = mode == ThemeMode.DAY
                        isAppearanceLightNavigationBars = mode == ThemeMode.DAY
                    }
                }
                androidx.compose.material3.Surface {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
                        UpdateSettingsContent(state.value, if (state.value.channel == UpdateChannel.STABLE) "1.5.0" else "1.5.0-dev.58", automatic, check, download, install, allow, automaticInstallSupported)
                    }
                }
            }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            androidx.core.view.WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).apply {
                isAppearanceLightStatusBars = captureMode == ThemeMode.DAY
                isAppearanceLightNavigationBars = captureMode == ThemeMode.DAY
            }
        }
        // System bars animate independently from Compose's idle clock.
        android.os.SystemClock.sleep(500)
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val config = compose.activity.resources.configuration
        val shape = if (config.screenWidthDp >= 600) "expanded" else if (config.fontScale > 1.2f) "large-text" else "phone"
        val directory = File(compose.activity.filesDir, "update-captures").apply { mkdirs() }
        File(directory, "$name-$shape.png").outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }

    @Test fun permissionAndPlaybackGateInstallationAndActionsRemainReachable() {
        val state = mutableStateOf(UpdateState(UpdateChannel.DEV, phase = UpdatePhase.READY, available = update, permissionNeeded = true))
        var allowed = 0; var installed = 0; var checked = 0
        content(state, allow = { allowed++ }, install = { installed++ }, check = { checked++ })
        compose.onNodeWithText("Allow app updates").performScrollTo().performClick()
        assertEquals(1, allowed)
        capture("ready-night")
        compose.runOnIdle { state.value = state.value.copy(permissionNeeded = false, waitingForPlayback = true) }
        compose.onNodeWithText("Install update").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Check for updates").performScrollTo().performClick()
        assertEquals(1, checked)
        compose.runOnIdle { state.value = state.value.copy(waitingForPlayback = false) }
        compose.onNodeWithText("Install update").performScrollTo().performClick()
        assertEquals(1, installed)
        compose.runOnIdle { state.value = state.value.copy(phase = UpdatePhase.CONFIRMATION) }
        compose.onNodeWithText("Confirm update").performScrollTo().assertIsEnabled()
        capture("confirmation-night")
    }

    @Test fun failuresAreActionableAndAutomaticToggleWorksWithoutBlockingManualUpdates() {
        val state = mutableStateOf(UpdateState(UpdateChannel.STABLE, automatic = false,
            available = update.copy(version = "1.6.0", code = 1_006_000, apk = "Narrio-1.6.0.apk"),
            error = "Could not download the update. Try again."))
        var downloaded = 0
        content(state, mode = ThemeMode.DAY, automatic = { state.value = state.value.copy(automatic = it) }, download = { downloaded++ })
        compose.onNodeWithText("Could not download the update. Try again.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Download update").performScrollTo().performClick()
        assertEquals(1, downloaded)
        compose.onNodeWithContentDescription("Automatic app updates").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(state.value.automatic) }
        capture("retry-day")
        compose.runOnIdle { state.value = state.value.copy(phase = UpdatePhase.DOWNLOADING, progress = 0.4f) }
        compose.onNodeWithText("Check for updates").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Download update").assertDoesNotExist()
    }

    @Test fun localAppDisablesNetworkUpdaterAndSettingsSurvivesRecreation() {
        val vm = ViewModelProvider(compose.activity)[NarrioViewModel::class.java]
        compose.runOnIdle { vm.navigate(2) }
        compose.onNodeWithTag("settings-options").performScrollToNode(hasText("App updates"))
        compose.onNodeWithText("App updates").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Local and debug builds update through your development tools.").assertIsDisplayed()
        compose.onNodeWithText("Check for updates").assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        compose.runOnIdle { ViewModelProvider(compose.activity)[NarrioViewModel::class.java].navigate(2) }
        compose.onNodeWithTag("settings-options").performScrollToNode(hasText("App updates"))
        compose.onNodeWithText("App updates").performScrollTo().assertIsDisplayed()
        val graph = (compose.activity.application as NarrioApplication).graph
        assertNull(graph.updates.state.value.channel)
        assertEquals(UpdatePhase.IDLE, graph.updates.state.value.phase)
    }

    @Test fun installerRejectsCorruptArchivesBeforeCreatingAnySession() {
        val file = File(compose.activity.cacheDir, "invalid-update.apk")
        try {
            file.writeText("not an APK")
            val installer = AppUpdateInstaller(compose.activity)
            assertTrue(installer.certificate().matches(Regex("[a-f0-9]{64}")))
            val before = compose.activity.packageManager.packageInstaller.mySessions.map { it.sessionId }
            assertThrows(IllegalArgumentException::class.java) { installer.verify(file, update) }
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            assertThrows(IllegalStateException::class.java) { installer.verify(file, update.copy(bytes = file.length(), sha256 = digest)) }
            val installedApk = File(compose.activity.applicationInfo.sourceDir)
            val selfDigest = java.security.MessageDigest.getInstance("SHA-256").digest(installedApk.readBytes()).joinToString("") { "%02x".format(it) }
            assertThrows(IllegalArgumentException::class.java) { installer.verify(installedApk,
                update.copy(code = BuildConfig.VERSION_CODE.toLong(), version = BuildConfig.VERSION_NAME,
                    bytes = installedApk.length(), sha256 = selfDigest, certificate = installer.certificate())) }
            assertEquals(before, compose.activity.packageManager.packageInstaller.mySessions.map { it.sessionId })
        } finally { file.delete() }
    }

    @Test fun olderAndroidExplainsManualInstallationWithoutPromisingBackgroundInstall() {
        val state = mutableStateOf(UpdateState(UpdateChannel.STABLE, phase = UpdatePhase.READY,
            available = update.copy(version = "1.6.0", code = 1_006_000)))
        content(state, mode = ThemeMode.DAY, automaticInstallSupported = false)
        compose.onNodeWithText("Downloads use Wi-Fi. When an update is ready, pause playback and tap Install update. Android will ask you to confirm.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Install update").performScrollTo().assertIsEnabled()
        capture("older-android-day")
    }
}
