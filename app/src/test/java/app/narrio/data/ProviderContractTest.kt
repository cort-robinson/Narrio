package app.narrio.data

import app.narrio.domain.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class ProviderContractTest {
    @Test fun flattenedSearchDescriptionDoesNotTurnCatalogBoilerplateIntoNarration() {
        val meta = NarrioJson.parseToJsonElement("""{"identifier":"raven","title":"The Raven","creator":"Edgar Allan Poe","description":"LibriVox recording. Read by Chris Goringe For further information, including reader information, visit the catalog."}""").jsonObject
        assertEquals("Chris Goringe", ArchiveDiscovery.parseBook(meta, false).narrator)
    }
    @Test fun connectingExplainsARejectedKeyAPlanWithoutApiAndNoConnection() = runTest {
        val server = MockWebServer(); server.start()
        try {
            val client = TorBoxDelivery(OkHttpClient(), { null }, server.url("/").toString())
            server.enqueue(MockResponse().setResponseCode(401))
            val rejected = runCatching { client.connect("wrong-key") }.exceptionOrNull()
            assertTrue(rejected is ProviderException)
            assertTrue(rejected!!.message!!.startsWith("TorBox didn't accept this API key."))
            assertEquals("Bearer wrong-key", server.takeRequest().getHeader("Authorization"))
            server.enqueue(MockResponse().setBody("""{"success":true,"data":{"plan":0}}"""))
            assertTrue(runCatching { client.connect("free-key") }.exceptionOrNull()!!.message!!.contains("needs a plan with API access"))
            server.enqueue(MockResponse().setBody("""{"success":true,"data":{"plan":1}}"""))
            assertEquals("TorBox connected", client.connect("good-key"))
        } finally { server.shutdown() }
        val offline = TorBoxDelivery(OkHttpClient(), { null }, server.url("/").toString())
        val unreachable = runCatching { offline.connect("any-key") }.exceptionOrNull()
        assertTrue(unreachable is ProviderException)
        assertEquals("Couldn't reach TorBox. Check your internet connection and try again.", unreachable!!.message)
    }
    @Test fun uncachedDefaultPlaybackNeverCreatesATorrent() = runTest {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"success":true,"data":{}}"""))
            val client = TorBoxDelivery(OkHttpClient(), { "test-secret" }, server.url("/").toString())
            val error = runCatching { client.prepareCached(Audiobook("id", "Book", "Author", torrentHash = "a".repeat(40), magnetUri = "magnet:?xt=urn:btih:${"a".repeat(40)}"), "M4B") }.exceptionOrNull()
            assertTrue(error is ProviderException)
            assertEquals(1, server.requestCount)
            val request = server.takeRequest()
            assertEquals("/torrents/checkcached", request.requestUrl?.encodedPath)
            assertEquals("true", request.requestUrl?.queryParameter("list_files"))
        } finally { server.shutdown() }
    }
    @Test fun cacheAvailabilityChecksTheChosenFilesAndRejectsNonAudio() = runTest {
        val server = MockWebServer(); server.start()
        val hash = "a".repeat(40); val other = "b".repeat(40)
        try {
            server.enqueue(MockResponse().setBody("""{"success":true,"data":{"$hash":{"files":[{"name":"root/whole.m4b","size":100},{"name":"root/chapter_1.mp3"}]},"$other":{"files":[{"name":"film.mp4"},{"name":"sample.mp3"}]}}}"""))
            val client = TorBoxDelivery(OkHttpClient(), { "test-secret" }, server.url("/").toString())
            val sources = listOf(AudioSource("mp3", "Parts", "MP3", listOf(AudioPart("1", "chapter_1.mp3", "1"), AudioPart("2", "chapter_2.mp3", "2"))), AudioSource("m4b", "Whole", "M4B", listOf(AudioPart("m", "whole.m4b", "Whole"))))
            val result = client.checkCached(listOf(Audiobook("archive", "Book", "Author", torrentHash = hash, sources = sources), Audiobook("indexed", "Film", "Unknown", torrentHash = other, provider = "knaben")))
            assertEquals(listOf("M4B"), result[0].cachedFormats)
            assertEquals("uncached", result[1].cacheState)
            assertTrue(result[1].sources.isEmpty())
        } finally { server.shutdown() }
    }
    @Test fun cachedIndexedReleaseListsRealOrderedAudioAndUsesMagnet() = runTest {
        val server = MockWebServer(); server.start(); val hash = "a".repeat(40)
        try {
            server.enqueue(MockResponse().setBody("""{"success":true,"data":[{"hash":"$hash","files":[{"name":"Book/10.mp3","size":123},{"name":"Book/2.mp3","size":456}]}]}"""))
            server.enqueue(MockResponse().setBody("""{"success":true,"data":[]}"""))
            server.enqueue(MockResponse().setBody("""{"success":true,"data":{"torrent_id":42}}"""))
            server.enqueue(MockResponse().setBody("""{"success":true,"data":[{"id":42,"download_finished":true,"download_present":true}]}"""))
            val client = TorBoxDelivery(OkHttpClient(), { "test-secret" }, server.url("/").toString())
            val book = Audiobook("indexed", "Release", "Unverified", provider = "knaben", torrentHash = hash, magnetUri = "magnet:?xt=urn:btih:$hash")
            val checked = client.checkCached(listOf(book)).first()
            assertEquals(listOf("Book/2.mp3", "Book/10.mp3"), checked.sources.first().parts.map { it.name })
            assertEquals(456, checked.sources.first().parts.first().sizeBytes)
            assertTrue(client.prepare(book).ready)
            server.takeRequest(); server.takeRequest()
            val form = server.takeRequest().body.readUtf8()
            assertTrue(form.contains("magnet:?xt=urn:btih:$hash")); assertFalse(form.contains("filename="))
        } finally { server.shutdown() }
    }
    @Test fun partiallyCachedIndexedFilesCannotMakeAVerifiedWholeRecordingReady() = runTest {
        val server = MockWebServer(); server.start(); val hash = "a".repeat(40)
        try {
            server.enqueue(MockResponse().setBody("""{"success":true,"data":{"$hash":{"files":[{"name":"root/chapter_1.mp3"}]}}}"""))
            val source = AudioSource("verified", "Parts", "MP3", listOf(AudioPart("1", "chapter_1.mp3", "1"), AudioPart("2", "chapter_2.mp3", "2")), delivery = "torbox")
            val book = Audiobook("indexed", "Book", "Author", provider = "knaben", torrentHash = hash, sources = listOf(source), filesVerified = true)
            val checked = TorBoxDelivery(OkHttpClient(), { "test-secret" }, server.url("/").toString()).checkCached(listOf(book)).single()
            assertEquals("uncached", checked.cacheState)
            assertTrue(checked.cachedFormats.isEmpty())
            assertEquals(book.sources, checked.sources)
        } finally { server.shutdown() }
    }
    @Test fun cacheAndReadyFileRefreshCannotAddAnotherBooksAudioFormat() = runTest {
        val server = MockWebServer(); server.start(); val hash = "a".repeat(40)
        try {
            val files = """[{"id":1,"name":"Christopher Paolini/Eragon/Eragon.m4b"},{"id":2,"name":"Christopher Paolini/Eldest/Eldest.mp3"}]"""
            server.enqueue(MockResponse().setBody("""{"success":true,"data":{"$hash":{"files":$files}}}"""))
            server.enqueue(MockResponse().setBody("""{"success":true,"data":[{"id":7,"hash":"$hash","download_finished":true,"download_present":true,"files":$files}]}"""))
            val source = AudioSource("eragon", "Whole book", "M4B", listOf(AudioPart("eragon", "Eragon/Eragon.m4b", "Eragon")), delivery = "torbox")
            val sibling = AudioSource("eldest", "Parts", "MP3", listOf(AudioPart("eldest", "Eldest/Eldest.mp3", "Eldest")), delivery = "torbox")
            val collection = Audiobook("collection", "Eragon, Eldest - Christopher Paolini", "Author not verified", provider = "knaben", torrentHash = hash,
                sources = listOf(source, sibling), cacheState = "cached", cachedFormats = listOf("M4B", "MP3"))
            val book = SourceQuality.filter(Audiobook("catalog:eragon", "Eragon", "Christopher Paolini", provider = "catalog"), listOf(collection)).single()
            val client = TorBoxDelivery(OkHttpClient(), { "test-secret" }, server.url("/").toString())
            val checked = client.checkCached(listOf(book)).single()
            assertEquals(listOf("M4B"), checked.cachedFormats)
            assertEquals(listOf("Christopher Paolini/Eragon/Eragon.m4b"), checked.sources.single().parts.map { it.name })
            val ready = client.sources(checked, 7)
            assertEquals(listOf("M4B"), ready.map { it.format })
            assertEquals(listOf(1L), ready.single().parts.map { it.fileId })
        } finally { server.shutdown() }
    }
    @Test fun preparationReportsProviderProgressSpeedAndEta() {
        val item = NarrioJson.parseToJsonElement("""{"id":7,"progress":0.35,"download_speed":1048576.5,"eta":3600,"seeds":2,"download_finished":false,"download_present":false,"download_state":"downloading"}""").jsonObject
        val result = TorBoxDelivery(OkHttpClient(), { "unused" }).preparation(item)
        assertEquals(0.35f, result.progress); assertEquals(1_048_576L, result.downloadBytesPerSecond)
        assertEquals(3600L, result.etaSeconds); assertEquals(2L, result.seeds); assertFalse(result.ready)
    }
    @Test fun audiobookIndexerKeepsUnverifiedReleasesSeparateAndReceivesNoAccountKey() = runTest {
        val server = MockWebServer(); server.start(); val hash = "a".repeat(40)
        try {
            server.enqueue(MockResponse().setBody("""{"hits":[{"title":"Example - Reader A","hash":"$hash","categoryId":[1003000],"bytes":123,"tracker":"example"},{"title":"Example - Reader B","hash":"${"b".repeat(40)}","categoryId":[1003000]},{"title":"Example Film","hash":"${"c".repeat(40)}","categoryId":[3001000]}]}"""))
            val result = KnabenDiscovery(OkHttpClient(), server.url("/v2/").toString()).search("Example")
            assertEquals(2, result.size); assertNotEquals(result[0].id, result[1].id)
            assertTrue(result.all { it.narrator.contains("not verified") && it.sources.isEmpty() })
            val request = server.takeRequest()
            assertNull(request.getHeader("Authorization"))
            assertEquals("1003000", request.requestUrl?.queryParameter("c")); assertTrue(request.path!!.contains("dead"))
        } finally { server.shutdown() }
    }
    @Test fun archiveUsesNarratedEditionAndAvoidsDuplicateBitrates() {
        val root = NarrioJson.parseToJsonElement("""{
            "metadata":{"identifier":"edition-1","title":"Example","creator":["An Author"],"language":"eng","runtime":"1:01:02","description":"<div>Read in English by A Reader</div><p>The story.</p>"},
            "files":[
              {"name":"chapter_10.mp3","source":"original","format":"VBR MP3","length":"20","title":"Chapter 10"},
              {"name":"chapter_2.mp3","source":"original","format":"VBR MP3","length":"30","title":"Chapter 2"},
              {"name":"chapter_2_64kb.mp3","source":"derivative","format":"64Kbps MP3"},
              {"name":"preview.mp3","source":"original"},
              {"name":"sample.m4b","source":"original"},
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
