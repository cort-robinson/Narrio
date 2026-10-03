package app.narrio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.narrio.domain.Audiobook
import app.narrio.updates.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Opt-in real package replacement. Only isolated non-debuggable Narrio Local fixtures are allowed. */
@RunWith(AndroidJUnit4::class)
class AppUpdateInstallTest {
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph get() = (context.applicationContext as NarrioApplication).graph
    private fun fixture(stage: String) {
        assumeTrue(arguments.getString("updateStage") == stage)
        check(android.os.Build.VERSION.SDK_INT >= 31) { "Automatic install fixture needs Android 12+" }
        check(context.packageName == "app.narrio.local" && !BuildConfig.DEBUG) { "Use an isolated signed release Local fixture" }
    }

    @Test fun installSignedFixture(): Unit = runBlocking {
        fixture("install")
        val path = requireNotNull(arguments.getString("updateApk"))
        require(path == "/data/local/tmp/narrio-update-fixture.apk")
        val file = File(context.cacheDir, "update-fixture.apk")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat $path"))
            .use { input -> file.outputStream().use { output -> input.copyTo(output) } }
        val installer = AppUpdateInstaller(context)
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val update = AppUpdate(requireNotNull(arguments.getString("updateVersion")), requireNotNull(arguments.getString("updateCode")).toLong(),
            file.name, file.length(), digest, installer.certificate(), "a".repeat(40), "", "")
        installer.verify(file, update)
        assertTrue(installer.permissionGranted())
        graph.library.save(Audiobook("update-fixture-book", "Update fixture", "Fixture author", "Fixture narrator", "English"))
        context.getSharedPreferences("updateFixture", 0).edit().putString("preserved", "before-update").commit()
        installer.install(file, update, manual = false, idle = { true }) { id ->
            context.getSharedPreferences("appUpdates", 0).edit().putInt("session", id).commit()
        }
        // A committed request is not proof of replacement. Android/Play Protect may still require
        // confirmation; run UpdateFixtureVerification after the system finishes installation.
        assertTrue(context.getSharedPreferences("appUpdates", 0).getInt("session", -1) >= 0)
    }

}
