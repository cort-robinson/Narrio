package app.narrio.data

import app.narrio.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class TorBoxLibraryTest {
    private fun item(id: Long, name: String, created: String, files: String, ready: Boolean = true) =
        """{"id":$id,"hash":"${id.toString().padStart(40, 'a')}","name":"$name","created_at":"$created","size":1000,"download_finished":$ready,"download_present":$ready,"download_state":"${if (ready) "cached" else "downloading"}","files":$files}"""
    private val mp3s = """[{"id":0,"name":"Book/02.mp3","size":5},{"id":1,"name":"Book/01.mp3","size":5}]"""

    @Test fun everyKindWithAudioIsListedNewestFirstAndSearchable() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                "/torrents/mylist" -> MockResponse().setBody("""{"success":true,"data":[${item(1, "Old Torrent Audiobook", "2026-01-01T00:00:00Z", mp3s)},${item(2, "Linux ISO", "2026-09-01T00:00:00Z", """[{"id":0,"name":"x.iso"}]""")}]}""")
                "/usenet/mylist" -> MockResponse().setBody("""{"success":true,"data":[${item(3, "Usenet Audiobook", "2026-05-01T00:00:00Z", mp3s, ready = false)}]}""")
                // An account without web downloads still lists the others.
                else -> MockResponse().setResponseCode(403)
            }
        }
        server.start()
        try {
            val library = TorBoxLibrary(TorBoxDelivery(OkHttpClient(), { "test-secret" }, server.url("/").toString()))
            val items = library.items()
            assertEquals(listOf(3L, 1L), items.map { it.id })
            assertEquals(listOf(TorBoxKind.USENET, TorBoxKind.TORRENT), items.map { it.kind })
            assertFalse(items.first().ready)
            assertEquals(2, items.last().audioFiles)
            assertEquals(listOf(1L), TorBoxLibrary.search(items, "torrent  AUDIOBOOK").map { it.id })
            assertEquals(items, TorBoxLibrary.search(items, " "))
        } finally { server.shutdown() }
    }

    @Test fun usenetAndWebFilesUseTheirOwnLinkEndpoints() {
        val json = NarrioJson.parseToJsonElement(item(5, "Web Audiobook", "", mp3s)).jsonObject
        val recording = TorBoxLibrary.recording(TorBoxKind.WEB, json)
        val source = recording.sources.single()
        assertEquals("torbox-web:5", recording.id)
        assertEquals("", recording.torrentHash)
        assertEquals("torbox-web", source.delivery)
        assertEquals(listOf("torbox-web:5:1", "torbox-web:5:0"), source.parts.map { it.id })
        assertEquals(TorBoxKind.WEB, TorBoxKind.of(source.parts.first().id))
        assertEquals(TorBoxKind.USENET, TorBoxKind.of("torbox-usenet:1:2"))
        assertEquals(TorBoxKind.TORRENT, TorBoxKind.of("torbox:1:2"))
        assertEquals("TorBox", deliveryLabel(source))
        // A torrent keeps the existing account recording identity.
        assertEquals("torbox:5", TorBoxLibrary.recording(TorBoxKind.TORRENT, json).id)
    }

    @Test fun webFileLinksAreRequestedFromTheWebDownloadEndpoint() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"success":true,"data":"https://cdn.example.org/file.mp3"}"""))
        server.start()
        try {
            val torbox = TorBoxDelivery(OkHttpClient(), { "test-secret" }, server.url("/").toString())
            assertEquals("https://cdn.example.org/file.mp3", torbox.resolve(AudioPart("torbox-web:5:1", "a.mp3", "a", torrentId = 5, fileId = 1)))
            val url = server.takeRequest().requestUrl!!
            assertEquals("/webdl/requestdl", url.encodedPath)
            assertEquals("5", url.queryParameter("web_id"))
        } finally { server.shutdown() }
    }
}
