package app.narrio

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.narrio.data.*
import app.narrio.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/** Run the two methods in separate instrumentation processes with a force-stop between them for restart QA. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ReadingRestartTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val seeds by lazy { ReaderSeeds(compose) }

    @Test fun aSaveEbookOnlyReadingPlace() {
        val bytes = ("Chapter One\n\n" + (1..100).joinToString("\n\n") {
            "Paragraph $it. The reader turns a page through this offline imported book. ".repeat(10)
        }).toByteArray()
        val graph = seeds.graph
        val book = runBlocking { LocalEbookImporter(compose.activity, graph.library, graph.followAlong).import(bytes, "TXT", "Integration restart fixture") }
        graph.preferences.edit().putString("integrationRestartBook", book.id).apply()
        assertTrue(book.sources.isEmpty())
        val controller = seeds.open(book)
        compose.runOnIdle { controller.next(false) }
        compose.waitUntil(10_000) { controller.visible.value?.first?.offset?.let { it > 0 } == true }
        val first = controller.cursor.value!!
        compose.runOnIdle { controller.next(false) }
        compose.waitUntil(10_000) { controller.cursor.value?.offset?.let { it > first.offset } == true }
        compose.waitUntil(10_000) { runBlocking { graph.sharedPositions.current(book.id)?.text?.offset?.let { it > 0 } == true } }
        val position = runBlocking { graph.sharedPositions.current(book.id) }!!
        assertEquals(PositionOrigin.READING, position.origin)
        assertTrue(position.text!!.locatorJson.isNotBlank())
        graph.preferences.edit().putString("integrationRestartPosition", NarrioJson.encodeToString(position)).commit()
        compose.runOnIdle { seeds.vm.closeReader() }
    }

    @Test fun bReopenThePersistedReadingPlace() {
        val graph = seeds.graph
        val bookId = graph.preferences.getString("integrationRestartBook", null)!!
        val expected = NarrioJson.decodeFromString<SharedPosition>(graph.preferences.getString("integrationRestartPosition", null)!!)
        val book = runBlocking { graph.library.find(bookId)!!.book() }
        assertEquals(expected, runBlocking { RoomSharedPositionStore(graph.library).current(bookId) })
        val controller = seeds.open(book)
        compose.waitUntil(10_000) { controller.visible.value?.contains(expected.text!!) == true }
        assertEquals(expected.sequence, runBlocking { graph.sharedPositions.current(bookId)!!.sequence })
        seeds.remove(book)
        graph.preferences.edit().remove("integrationRestartBook").remove("integrationRestartPosition").apply()
    }
}
