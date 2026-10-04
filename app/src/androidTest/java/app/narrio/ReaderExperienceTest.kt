package app.narrio

import android.content.pm.ActivityInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.narrio.domain.ContentCursor
import app.narrio.domain.PositionOrigin
import app.narrio.reader.ReaderController
import app.narrio.reader.ReaderFixtures
import app.narrio.reader.TxtEpub
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Controlled EPUB/TXT fixtures in the real Readium navigator: no account, network, or audio needed. */
@RunWith(AndroidJUnit4::class)
class ReaderExperienceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val seeds by lazy { ReaderSeeds(compose) }
    private val opened = mutableListOf<app.narrio.domain.Audiobook>()

    @After fun cleanUp() {
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        opened.forEach(seeds::remove)
    }

    private fun seed(id: String, bytes: ByteArray, format: String, title: String) = seeds.book(id, bytes, format, title).also { opened += it }

    @Test fun opensAnEpubAndTurnsPagesWithTheChromeOnACenterTap() {
        val book = seed("reader-open-test", ReaderFixtures.sampleEpub(), "EPUB", "The Secret Garden")
        val controller = seeds.open(book)
        val first = controller.cursor.value!!
        assertEquals("OEBPS/text/chapter1.xhtml", first.resource)
        compose.onNodeWithTag("reader-page").assertIsDisplayed()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("I · There Is No One Left").fetchSemanticsNodes().isNotEmpty() }

        compose.runOnIdle { controller.next(animated = false) }
        compose.waitUntil(10_000) { (controller.visible.value?.first?.offset ?: 0) > first.offset }
        assertTrue("a page turn moves the reader's place", controller.cursor.value!!.offset > first.offset)

        compose.onNodeWithTag("reader-page").performTouchInput { click(center) }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Contents").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Contents").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("IV · Martha").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("IV · Martha").performClick()
        compose.waitUntil(10_000) { controller.cursor.value?.resource == "OEBPS/text/chapter4.xhtml" }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("reader-return").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun hostileEpubRunsNoScriptsAndLoadsNothingRemote() {
        val book = seed("reader-hostile-test", ReaderFixtures.hostileEpub(), "EPUB", "Hostile fixture")
        val controller = seeds.open(book)
        compose.waitUntil(5_000) { true }
        Thread.sleep(1_500) // give any surviving handler (onload, onerror, refresh) time to fire
        val report = JSONObject(seeds.evaluate(controller, PROBE)!!)
        assertEquals("no publication script ran", "undefined", report.getString("hostile"))
        assertNotEquals("pwned", report.getString("title"))
        assertEquals("event handlers: ${report.getJSONArray("handlers")}", 0, report.getJSONArray("handlers").length())
        assertEquals("scripts: ${report.getJSONArray("publicationScripts")}", 0, report.getJSONArray("publicationScripts").length())
        assertEquals("remote references: ${report.getJSONArray("remoteAttributes")}", 0, report.getJSONArray("remoteAttributes").length())
        assertTrue("Readium's own scripts still run", report.getBoolean("readium"))
        val hosts = report.getJSONArray("hosts")
        for (index in 0 until hosts.length()) assertTrue(hosts.getString(index), hosts.getString(index).startsWith("readium_"))
        assertTrue("the page is still readable", report.getString("text").contains("Readable text stays readable"))
        assertTrue("the page is still in the Readium package", report.getString("location").startsWith("https://readium_package/"))
    }

    @Test fun readingPlaceSurvivesFontChangesAndRotation() {
        val book = seed("reader-restore-test", ReaderFixtures.sampleEpub(30), "EPUB", "The Secret Garden")
        val controller = seeds.open(book)
        // Read into the third chapter, a few pages in.
        val target = runBlocking { controller.book.cursor("OEBPS/text/chapter3.xhtml", 900) }
        compose.runOnIdle { controller.jumpTo(target) }
        compose.waitUntil(10_000) { controller.cursor.value?.resource == "OEBPS/text/chapter3.xhtml" }
        compose.runOnIdle { controller.next(animated = false) }
        compose.waitUntil(10_000) { controller.visible.value?.first?.let { it.offset > controller.cursor.value!!.offset - 1 } == true && settled(controller) }
        val place = controller.cursor.value!!
        assertShows(controller, place)

        compose.runOnIdle { seeds.reader(book).update { larger().larger().larger() } }
        compose.waitUntil(10_000) { settled(controller) && controller.visible.value?.contains(place) == true }
        assertEquals("a font change keeps the reader's place", place, controller.cursor.value)
        compose.runOnIdle { seeds.reader(book).update { copy(font = app.narrio.reader.ReaderFont.OPEN_DYSLEXIC) } }
        compose.waitUntil(10_000) { settled(controller) && controller.visible.value?.contains(place) == true }

        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        compose.waitUntil(15_000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE }
        compose.waitUntil(15_000) { settled(controller) && controller.visible.value?.contains(place) == true }
        compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        compose.waitUntil(15_000) { compose.activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT }
        compose.waitUntil(15_000) { settled(controller) && controller.visible.value?.contains(place) == true }
        assertEquals("rotation keeps the reader's place, without drifting", place, controller.cursor.value)
    }

    @Test fun openingNeverMovesTheSharedPositionButReadingDoes() {
        val book = seed("reader-commit-test", ReaderFixtures.sampleEpub(), "EPUB", "The Secret Garden")
        val controller = seeds.open(book)
        Thread.sleep(4_000)
        assertNull("opening the book is not reading activity", runBlocking { seeds.graph.sharedPositions.current(book.id) })
        compose.runOnIdle { controller.next(animated = false) }
        compose.waitUntil(10_000) { settled(controller) }
        compose.runOnIdle { controller.next(animated = false) }
        compose.waitUntil(10_000) { runBlocking { seeds.graph.sharedPositions.current(book.id) } != null }
        val shared = runBlocking { seeds.graph.sharedPositions.current(book.id) }!!
        assertEquals(PositionOrigin.READING, shared.origin)
        assertEquals(controller.cursor.value!!.copy(locatorJson = "", progression = 0.0), shared.text!!.copy(locatorJson = "", progression = 0.0))
        assertTrue("the commit carries an exact Readium locator", shared.text!!.locatorJson.contains("cssSelector"))

        // Reopening restores the shared position.
        compose.runOnIdle { seeds.vm.closeReader() }
        compose.waitForIdle()
        val reopened = seeds.open(book)
        assertEquals(shared.text!!.offset, reopened.cursor.value!!.offset)
        compose.waitUntil(10_000) { reopened.visible.value?.contains(shared.text!!) == true }
    }

    @Test fun plainTextOpensInTheSameReaderWithTextOffsets() {
        val text = buildString {
            append("CHAPTER I. The Garden\n\n")
            repeat(40) { append("Paragraph $it of a plain text book. Mary walked along the wall and looked for the door.\n\n") }
            append("CHAPTER II. The Key\n\nShe found the key at last.\n")
        }.toByteArray()
        val book = seed("reader-text-test", text, "TXT", "A Plain Book")
        val controller = seeds.open(book)
        assertEquals(TxtEpub.RESOURCE, controller.cursor.value!!.resource)
        compose.runOnIdle { controller.next(animated = false) }
        compose.waitUntil(10_000) { (controller.visible.value?.first?.offset ?: 0) > 0 && settled(controller) }
        val passage = app.narrio.data.BookTextParser.parse(text, "TXT", "A Plain Book").chapters.flatMap { it.passages }
            .last { it.offset <= controller.visible.value!!.first.offset }
        assertEquals("text", passage.resource)
    }

    private fun settled(controller: ReaderController): Boolean { Thread.sleep(400); return controller.visible.value != null }

    private fun assertShows(controller: ReaderController, cursor: ContentCursor) =
        assertTrue("$cursor is on the page ${controller.visible.value}", controller.visible.value?.contains(cursor) == true)

    private companion object {
        val PROBE = """(function () {
  var all = Array.prototype.slice.call(document.querySelectorAll('*'));
  var handlers = [];
  all.forEach(function (e) { Array.prototype.forEach.call(e.attributes, function (a) { if (/^on/i.test(a.name.split(':').pop())) handlers.push(e.tagName + '@' + a.name + '=' + a.value.slice(0, 60)); }); });
  var scripts = Array.prototype.slice.call(document.querySelectorAll('script')).filter(function (s) { return (s.getAttribute('src') || '').indexOf('https://readium_assets/') !== 0; })
    .map(function (s) { return (s.parentNode && s.parentNode.tagName) + ' src=' + s.getAttribute('src') + ' ' + s.textContent.slice(0, 80); });
  var remote = [];
  all.forEach(function (e) { Array.prototype.forEach.call(e.attributes, function (a) {
    if (/${ReaderFixtures.HOSTILE_HOST.replace(".", "\\.")}/.test(a.value) || (/^(src|srcset|poster|data|xlink:href)$/i.test(a.name) && /^(https?:)?\/\//i.test(a.value.trim()) && !/^https:\/\/readium_(assets|package)\//.test(a.value.trim()))) remote.push(e.tagName + '@' + a.name + '=' + a.value.slice(0, 80));
  }); });
  var hosts = performance.getEntriesByType('resource').map(function (r) { try { return new URL(r.name).host; } catch (e) { return r.name; } });
  return { hostile: typeof window.narrioHostile, title: document.title, handlers: handlers, publicationScripts: scripts, remoteAttributes: remote,
    readium: typeof window.readium === 'object', hosts: hosts, text: document.body.textContent, location: location.href };
})();"""
    }
}
