package app.narrio

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.narrio.data.PreparationChange
import app.narrio.domain.*
import app.narrio.preparation.PreparationNotifications
import app.narrio.ui.NarrioViewModel
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Preparation notifications open the book or play its prepared recording; synthetic fixtures stand in for TorBox. */
@RunWith(AndroidJUnit4::class)
class PreparationNotificationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = (compose.activity.application as NarrioApplication).graph
    private val vm get() = ViewModelProvider(compose.activity)[NarrioViewModel::class.java]
    private val notifications get() = compose.activity.getSystemService(NotificationManager::class.java)

    private fun fixture(id: String, title: String): Pair<Audiobook, File> {
        val wave = File(compose.activity.filesDir, "$id.wav")
        val size = 30 * 8000 * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + size); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16); put("data".toByteArray()); putInt(size)
        }.array()
        wave.outputStream().use { stream -> stream.write(header); stream.write(ByteArray(size)) }
        val source = AudioSource("$id-source", "Native fixture audio", "WAV",
            listOf(AudioPart("$id-part", "$id.wav", "Chapter 1", durationMs = 30_000, archiveUrl = Uri.fromFile(wave).toString())))
        return Audiobook(id, title, "Native test fixture", "Synthetic silence", sources = listOf(source), detailsLoaded = true) to wave
    }

    /** A shelf book TorBox was asked to prepare (preparation 7, WAV), left in [state]. */
    private fun seed(book: Audiobook, state: String) = runBlocking {
        graph.library.save(book); graph.library.preparing(book.id, 7, "WAV"); graph.library.state(book.id, state)
    }

    private var launch: Intent? = null
    private fun deliver(action: String, book: Audiobook) = compose.runOnIdle {
        launch = launch ?: compose.activity.intent
        compose.activity.startActivity(PreparationNotifications.intent(compose.activity, action, book.id))
    }

    private fun posted(book: Audiobook) = notifications.activeNotifications.single { it.tag == book.id }.notification

    private fun capture(name: String) {
        compose.waitForIdle(); Thread.sleep(500)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val folder = File(compose.activity.filesDir, "qa-captures").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }

    private fun cleanUp(book: Audiobook, wave: File) {
        // onNewIntent replaces the activity's intent; the test rule finds its activity by the one it launched.
        launch?.let { original -> compose.runOnIdle { compose.activity.intent = original } }
        PreparationNotifications.clear(compose.activity, book.id)
        runBlocking { withContext(Dispatchers.Main) { graph.playback.service?.takeIf { vm.playback.value.book?.id == book.id }?.forget() }; graph.library.remove(book.id) }
        wave.delete()
    }

    @Test fun tapOpensTheBookAndListenPlaysItsPreparedRecording() {
        val (book, wave) = fixture("prepared-fixture", "A Patient Voice")
        try {
            compose.waitUntil(15_000) { graph.playback.service?.initialized == true }
            // No TorBox account is used: the fixture's audio is local and its status is never checked.
            compose.runOnIdle { graph.preferences.edit().putBoolean("notificationAsked", true).apply(); vm.connected.value = false
                vm.updateAppearance(vm.appearance.value.copy(mode = ThemeMode.DAY)) }
            seed(book, "ready")

            deliver(PreparationNotifications.ACTION_OPEN, book)
            compose.waitUntil(10_000) { vm.selection.value.book?.id == book.id }
            compose.onNodeWithTag("book-details").performScrollToNode(hasText("Your source is ready. Choose Listen to begin."))
            compose.onNodeWithText("Your source is ready. Choose Listen to begin.").assertIsDisplayed()
            capture("preparation-ready-day")

            deliver(PreparationNotifications.ACTION_LISTEN, book)
            compose.waitUntil(15_000) { vm.playback.value.book?.id == book.id && vm.playerOpen.value }
            assertEquals("prepared-fixture-source", vm.playback.value.source?.id)
            compose.waitUntil(5_000) { runBlocking { graph.library.find(book.id)?.state } == "listening" }
        } finally { cleanUp(book, wave) }
    }

    @Test fun aFailedPreparationShowsOnTheShelfAndItsNotificationLeadsToAnotherRecording() {
        val (book, wave) = fixture("failed-fixture", "A Silent Release")
        try {
            compose.runOnIdle { graph.preferences.edit().putBoolean("notificationAsked", true).apply(); vm.connected.value = false }
            seed(book, "failed")
            compose.runOnIdle { vm.updateAppearance(vm.appearance.value.copy(mode = ThemeMode.NIGHT)); vm.navigate(1) }
            val label = "Couldn't get it ready · Try another recording"
            compose.onNodeWithTag("shelf").performScrollToNode(hasText(label))
            compose.onNodeWithText(label).assertIsDisplayed()
            capture("preparation-failed-shelf-night")

            if (Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(compose.activity.packageName, Manifest.permission.POST_NOTIFICATIONS)
            PreparationNotifications.show(compose.activity, PreparationChange(book, ready = false, problem = "No one has shared this release for a day."))
            val failed = posted(book)
            assertEquals("A Silent Release couldn't be prepared", failed.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertTrue("Nothing to listen to yet", failed.actions.isNullOrEmpty())
            compose.runOnIdle { launch = compose.activity.intent }
            failed.contentIntent.send()
            compose.waitUntil(10_000) { vm.selection.value.book?.id == book.id }
            compose.onNodeWithTag("book-details").performScrollToNode(hasText("Try another recording"))
            compose.onNodeWithText("Try another recording").assertIsDisplayed()
            capture("preparation-failed-book-night")
            compose.runOnIdle { vm.updateAppearance(vm.appearance.value.copy(mode = ThemeMode.DAY)) }
            capture("preparation-failed-book-day")

            PreparationNotifications.show(compose.activity, PreparationChange(book, ready = true))
            val ready = posted(book)
            assertEquals("A Silent Release is ready to listen", ready.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertEquals("Listen", ready.actions.single().title.toString())
        } finally { cleanUp(book, wave) }
    }
}
