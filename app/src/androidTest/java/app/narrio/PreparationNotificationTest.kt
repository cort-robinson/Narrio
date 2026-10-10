package app.narrio

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.narrio.data.*
import app.narrio.domain.*
import app.narrio.preparation.PreparationActionActivity
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

/**
 * Preparation notifications open the book or play exactly the preparation they announced. Synthetic fixtures stand in
 * for TorBox: a local recording, a ready shelf row, and its preparation record.
 */
@RunWith(AndroidJUnit4::class)
class PreparationNotificationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = (compose.activity.application as NarrioApplication).graph
    private val vm get() = ViewModelProvider(compose.activity)[NarrioViewModel::class.java]
    private val notifications get() = compose.activity.getSystemService(NotificationManager::class.java)
    private val records get() = compose.activity.getSharedPreferences("torbox-preparation-records", Context.MODE_PRIVATE)

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
        return Audiobook(id, title, "Native test fixture", "Synthetic silence", sources = listOf(source), detailsLoaded = true, torrentHash = "d".repeat(40)) to wave
    }

    /** A shelf book whose preparation 7 (WAV) is in [state] with [problem], announced as [generation]. */
    private fun seed(book: Audiobook, state: String, generation: String = "generation-1", problem: String = "") {
        runBlocking { graph.library.save(book); graph.library.preparing(book.id, 7, "WAV"); graph.library.state(book.id, state) }
        val record = PreparationRecord(book.id, generation, book, 7, "WAV", outcome = state, problem = problem,
            readySources = if (state == PreparationStates.READY) book.sources else emptyList())
        records.edit().putString(book.id, NarrioJson.encodeToString(PreparationRecord.serializer(), record)).commit()
    }

    private fun announce(book: Audiobook, ready: Boolean, generation: String = "generation-1", problem: String = ""): Notification {
        PreparationNotifications.show(compose.activity, PreparationChange(book, ready, problem, generation))
        return notifications.activeNotifications.single { it.tag == book.id }.notification
    }

    // Narrio's activity takes each new intent as its own; the test rule finds its activity by the one it launched.
    private var launch: Intent? = null

    private fun prepare() {
        compose.waitUntil(15_000) { graph.playback.service?.initialized == true }
        // No TorBox account is used: the fixture's audio is local and its status is never checked.
        compose.runOnIdle { graph.preferences.edit().putBoolean("notificationAsked", true).apply(); vm.connected.value = false; launch = compose.activity.intent }
        if (Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(compose.activity.packageName, Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun cleanUp(book: Audiobook, wave: File) {
        launch?.let { original -> compose.runOnIdle { compose.activity.intent = original } }
        PreparationNotifications.clear(compose.activity, book.id)
        records.edit().remove(book.id).commit()
        runBlocking { withContext(Dispatchers.Main) { graph.playback.service?.takeIf { vm.playback.value.book?.id == book.id }?.forget() }; graph.library.remove(book.id) }
        wave.delete()
    }

    @Test fun tapOpensTheBookAndListenPlaysThePreparedRecording() {
        val (book, wave) = fixture("prepared-fixture", "A Patient Voice")
        try {
            prepare()
            compose.runOnIdle { vm.updateAppearance(vm.appearance.value.copy(mode = ThemeMode.DAY)) }
            seed(book, PreparationStates.READY)
            val posted = announce(book, ready = true)
            assertEquals("A Patient Voice is ready to listen", posted.extras.getCharSequence(Notification.EXTRA_TITLE).toString())

            posted.contentIntent.send()
            compose.waitUntil(10_000) { vm.selection.value.book?.id == book.id }
            compose.onNodeWithTag("book-details").performScrollToNode(hasText("Your source is ready. Choose Listen to begin."))
            compose.onNodeWithText("Your source is ready. Choose Listen to begin.").assertIsDisplayed()
            capture("preparation-ready-day")

            posted.actions.single { it.title.toString() == "Listen" }.actionIntent.send()
            compose.waitUntil(15_000) { vm.playback.value.book?.id == book.id && vm.playerOpen.value }
            assertEquals("prepared-fixture-source", vm.playback.value.source?.id)
            compose.waitUntil(5_000) { runBlocking { graph.library.find(book.id)?.state } == "listening" }
            assertTrue("Listen dismisses its notification", notifications.activeNotifications.none { it.tag == book.id })
        } finally { cleanUp(book, wave) }
    }

    @Test fun anOlderNotificationOrAnotherAppCantStartPlayback() {
        val (book, wave) = fixture("superseded-fixture", "A Later Voice")
        try {
            prepare()
            seed(book, PreparationStates.READY)
            val old = announce(book, ready = true).actions.single().actionIntent
            // Preparation started again since that notification: its Listen only opens the book.
            seed(book, PreparationStates.READY, generation = "generation-2")
            old.send()
            compose.waitUntil(10_000) { vm.selection.value.book?.id == book.id }
            Thread.sleep(1_000)
            assertNotEquals(book.id, vm.playback.value.book?.id)
            assertEquals(PreparationStates.READY, runBlocking { graph.library.find(book.id)?.state })

            // The exported launcher ignores preparation actions, and the activity that takes them isn't exported.
            compose.runOnIdle {
                vm.back()
                compose.activity.startActivity(Intent(compose.activity, MainActivity::class.java).setAction("app.narrio.action.LISTEN_TO_PREPARED_BOOK")
                    .putExtra("book", book.id).putExtra("generation", "generation-2").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            Thread.sleep(1_500)
            assertNotEquals(book.id, vm.playback.value.book?.id)
            val info = compose.activity.packageManager.getActivityInfo(ComponentName(compose.activity, PreparationActionActivity::class.java), 0)
            assertFalse(info.exported)
        } finally { cleanUp(book, wave) }
    }

    @Test fun aFailedPreparationShowsOnTheShelfAndItsNotificationLeadsToAnotherRecording() {
        val (book, wave) = fixture("failed-fixture", "A Silent Release")
        val problem = "No one has shared this recording for a day."
        try {
            prepare()
            seed(book, PreparationStates.FAILED, problem = problem)
            compose.runOnIdle { vm.updateAppearance(vm.appearance.value.copy(mode = ThemeMode.NIGHT)); vm.navigate(1) }
            val label = "Couldn't get it ready · Try another recording"
            compose.onNodeWithTag("shelf").performScrollToNode(hasText(label))
            compose.onNodeWithText(label).assertIsDisplayed()
            capture("preparation-failed-shelf-night")

            val failed = announce(book, ready = false, problem = problem)
            assertEquals("A Silent Release couldn't get ready", failed.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertTrue("Nothing to listen to yet", failed.actions.isNullOrEmpty())
            failed.contentIntent.send()
            compose.waitUntil(10_000) { vm.selection.value.book?.id == book.id }
            compose.onNodeWithTag("book-details").performScrollToNode(hasText("Try another recording"))
            compose.onNodeWithText(problem, substring = true).assertIsDisplayed()
            compose.onNodeWithText("Try another recording").assertIsDisplayed()
            capture("preparation-failed-book-night")
            compose.runOnIdle { vm.updateAppearance(vm.appearance.value.copy(mode = ThemeMode.DAY)) }
            capture("preparation-failed-book-day")
        } finally { cleanUp(book, wave) }
    }

    @Test fun aPausedPreparationSaysWhyAndOffersToCheckAgain() {
        val (book, wave) = fixture("paused-fixture", "A Quiet Account")
        try {
            prepare()
            seed(book, PreparationStates.PAUSED, problem = PreparationPolicy.DISCONNECTED)
            compose.runOnIdle { vm.updateAppearance(vm.appearance.value.copy(mode = ThemeMode.NIGHT)); vm.navigate(1) }
            val label = "Stopped checking TorBox · Open to check again"
            compose.onNodeWithTag("shelf").performScrollToNode(hasText(label))
            compose.onNodeWithText(label).assertIsDisplayed()
            compose.runOnIdle { vm.open(book) }
            compose.onNodeWithTag("book-details").performScrollToNode(hasText("Check again"))
            compose.onNodeWithText(PreparationPolicy.DISCONNECTED, substring = true).assertIsDisplayed()
            capture("preparation-paused-book-night")
        } finally { cleanUp(book, wave) }
    }

    private fun capture(name: String) {
        compose.waitForIdle(); Thread.sleep(500)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val folder = File(compose.activity.filesDir, "qa-captures").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
}
