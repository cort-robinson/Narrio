package app.narrio

import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import app.narrio.data.BookTextParser
import app.narrio.data.NarrioJson
import app.narrio.domain.AppearanceSettings
import app.narrio.domain.Audiobook
import app.narrio.reader.ReaderController
import app.narrio.ui.NarrioViewModel
import app.narrio.ui.ReaderState
import app.narrio.ui.ReaderViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import java.io.File

typealias ReaderRule = AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>

/** Shelf books with attached text, stored exactly as follow-along import stores them. */
class ReaderSeeds(private val compose: ReaderRule) {
    val graph get() = (compose.activity.application as NarrioApplication).graph
    val vm get() = ViewModelProvider(compose.activity)[NarrioViewModel::class.java]

    fun book(id: String, bytes: ByteArray, format: String, title: String): Audiobook {
        val book = Audiobook(id, title, "Reader fixture", "Unnarrated", detailsLoaded = true)
        val document = BookTextParser.parse(bytes, format, title, "Reader fixture")
        val folder = File(compose.activity.filesDir, "follow-along/" + BookTextParser.fingerprint(id.toByteArray())).apply { deleteRecursively(); mkdirs() }
        File(folder, "${document.id}.${format.lowercase()}").writeBytes(bytes)
        File(folder, "${document.id}.json").writeText(NarrioJson.encodeToString(document))
        runBlocking { graph.library.attachText(book, document.id) }
        return book
    }

    /** Opens [book] in the reader and waits for its first laid-out page. */
    fun open(book: Audiobook, appearance: AppearanceSettings = AppearanceSettings()): ReaderController {
        compose.runOnIdle {
            graph.preferences.edit().putBoolean("notificationAsked", true).remove("readerSettings").apply()
            vm.updateAppearance(appearance)
            vm.read(book.id)
        }
        lateinit var controller: ReaderController
        compose.waitUntil(30_000) {
            val reader = ViewModelProvider(compose.activity, ReaderViewModel.factory(book.id))["reader:${book.id}", ReaderViewModel::class.java]
            (reader.state.value as? ReaderState.Ready)?.session?.controller?.takeIf { it.ready.value }?.also { controller = it } != null
        }
        // The first layout settles for a moment; navigation after that is the reader's own.
        Thread.sleep(1_200)
        compose.waitForIdle()
        return controller
    }

    fun reader(book: Audiobook): ReaderViewModel = ViewModelProvider(compose.activity, ReaderViewModel.factory(book.id))["reader:${book.id}", ReaderViewModel::class.java]

    fun remove(book: Audiobook) = runBlocking {
        compose.runOnIdle { vm.closeReader() }
        graph.followAlong.remove(book.id)
        graph.library.remove(book.id)
    }

    /** Runs [script] in the page on screen and returns its JSON result. */
    fun evaluate(controller: ReaderController, script: String): String? = runBlocking {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { controller.evaluate(script) }
    }
}
