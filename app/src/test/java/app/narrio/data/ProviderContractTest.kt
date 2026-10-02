package app.narrio.data

import app.narrio.domain.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class ProviderContractTest {
    @Test fun archiveUsesNarratedEditionAndAvoidsDuplicateBitrates() {
        val root = NarrioJson.parseToJsonElement("""{
            "metadata":{"identifier":"edition-1","title":"Example","creator":["An Author"],"language":"eng","runtime":"1:01:02","description":"<div>Read in English by A Reader</div><p>The story.</p>"},
            "files":[
              {"name":"chapter_10.mp3","source":"original","format":"VBR MP3","length":"20","title":"Chapter 10"},
              {"name":"chapter_2.mp3","source":"original","format":"VBR MP3","length":"30","title":"Chapter 2"},
              {"name":"chapter_2_64kb.mp3","source":"derivative","format":"64Kbps MP3"},
              {"name":"whole.m4b","source":"original"},
              {"name":"edition-1_archive.torrent","btih":"abc"},
              {"name":"cover.jpg"}]
            }""").jsonObject
        val book = ArchiveDiscovery.parseRecording(root)
        assertEquals("A Reader", book.narrator); assertEquals("English", book.language)
        assertEquals(3_662_000, book.durationMs)
        assertEquals(listOf("chapter_2.mp3", "chapter_10.mp3"), book.sources.first().parts.map { it.name })
        assertEquals(1, book.sources[1].parts.size)
        assertTrue(book.torrentUrl.endsWith("edition-1_archive.torrent"))
    }
    @Test fun torboxUsesFileIdentityAndMatchingChosenArchiveLayout() {
        val item = NarrioJson.parseToJsonElement("""{"id":41,"files":[
          {"id":10,"name":"book/chapter_10.mp3"},{"id":2,"name":"book/chapter_2.mp3"},
          {"id":3,"name":"book/chapter_2_64kb.mp3"},{"id":4,"name":"book/sample.mp3"},
          {"id":8,"name":"whole.m4b"},{"id":9,"name":"cover.jpg"}]}""").jsonObject
        val book = Audiobook("recording", "Book", "Author", sources = listOf(AudioSource("layout", "Parts", "MP3", listOf(AudioPart("a", "chapter_2.mp3", "2"), AudioPart("b", "chapter_10.mp3", "10")))))
        val sources = TorBoxDelivery.mapSources(item, book)
        assertEquals(listOf("torbox:41:2", "torbox:41:10"), sources[0].parts.map { it.id })
        assertEquals("M4B", sources[1].format)
    }
    @Test fun cachedTorrentIsReusedAndTemporaryLinkNeverBecomesIdentity() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val client = TorBoxDelivery(OkHttpClient(), { "test-secret" }, server.url("/").toString())
            server.enqueue(MockResponse().setBody("""{"success":true,"data":[{"id":7,"hash":"hash-1","download_finished":true,"download_present":true,"progress":1,"download_state":"cached"}]}"""))
            val prep = client.prepare(Audiobook("id", "Book", "Author", torrentUrl = "https://archive.org/download/id/id.torrent", torrentHash = "hash-1"))
            assertTrue(prep.ready); assertEquals(7, prep.torrentId)
            val request = server.takeRequest()
            assertEquals("Bearer test-secret", request.getHeader("Authorization")); assertEquals("GET", request.method)
            server.enqueue(MockResponse().setBody("""{"success":true,"data":"https://cdn.example/audio?expires=one"}"""))
            val part = AudioPart("stable-file", "audio.mp3", "Audio", torrentId = 7, fileId = 9)
            assertTrue(client.resolve(part).startsWith("https://cdn.example/")); assertEquals("stable-file", part.id)
            val linkRequest = server.takeRequest()
            assertEquals("9", linkRequest.requestUrl?.queryParameter("file_id"))
            assertEquals("false", linkRequest.requestUrl?.queryParameter("redirect"))
        } finally { server.shutdown() }
    }
    @Test fun falseCompletionIsNotAdvertisedAsReady() = runTest {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"success":true,"data":[{"id":7,"download_finished":false,"download_present":false,"download_state":"completed","progress":1}]}"""))
            val result = TorBoxDelivery(OkHttpClient(), { "test-secret" }, server.url("/").toString()).status(7)
            assertFalse(result.ready)
        } finally { server.shutdown() }
    }
    @Test fun newSourceUploadsTorrentThenReportsReadinessFromAccount() = runTest {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"success":true,"data":[]}"""))
            server.enqueue(MockResponse().setBody("d8:announce20:https://example.org/e"))
            server.enqueue(MockResponse().setBody("""{"success":true,"data":{"torrent_id":42}}"""))
            server.enqueue(MockResponse().setBody("""{"success":true,"data":[{"id":42,"download_finished":true,"download_present":true,"progress":1,"download_state":"cached"}]}"""))
            val provider = TorBoxDelivery(OkHttpClient(), { "test-secret" }, server.url("/").toString())
            val prep = provider.prepare(Audiobook("id", "Book", "Author", torrentUrl = server.url("/recording.torrent").toString(), torrentHash = "abc"))
            assertTrue(prep.ready); assertEquals(42L, prep.torrentId)
            server.takeRequest()
            assertNull(server.takeRequest().getHeader("Authorization"))
            val upload = server.takeRequest()
            assertEquals("POST", upload.method)
            assertTrue(upload.getHeader("Content-Type").orEmpty().startsWith("multipart/form-data"))
            assertTrue(upload.body.readUtf8().contains("filename=\"recording.torrent\""))
            assertTrue(server.takeRequest().path.orEmpty().contains("torrents/mylist"))
        } finally { server.shutdown() }
    }
    @Test fun providerErrorsDoNotEchoCredentials() {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"success":false,"detail":"credential test-secret invalid"}"""))
            val client = TorBoxDelivery(OkHttpClient(), { "test-secret" }, server.url("/").toString())
            val error = runCatching { client.resolve(AudioPart("id", "audio.mp3", "Audio", torrentId = 1, fileId = 2)) }.exceptionOrNull()
            assertNotNull(error); assertFalse(error?.message.orEmpty().contains("test-secret"))
        } finally { server.shutdown() }
    }
}
