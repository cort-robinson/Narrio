package app.narrio.data

import app.narrio.domain.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class ReleaseLinksTest {
    private val hash = "b027dbb27615ab2ec9a434ad4cb7d74455d28be6"

    private fun rejects(link: String, wording: String) {
        val error = assertThrows(ProviderException::class.java) { ReleaseLinks.parse(link) }
        assertTrue("${error.message} should mention $wording", error.message!!.contains(wording, ignoreCase = true))
    }

    @Test fun magnetLinksAndBareHashesKeepTheirInfoHashAndName() {
        val magnet = "magnet:?xt=urn:btih:${hash.uppercase()}&dn=Eragon%20-%20Christopher%20Paolini&tr=udp%3A%2F%2Ftracker.example%3A80"
        assertEquals(ReleaseLink.Magnet(hash, "Eragon - Christopher Paolini", magnet), ReleaseLinks.parse("  $magnet\n"))
        assertEquals(ReleaseLink.Magnet(hash, "", "magnet:?xt=urn:btih:$hash"), ReleaseLinks.parse(hash.uppercase()))
        // Older links write the same 20 bytes in base32.
        assertEquals(hash, (ReleaseLinks.parse("magnet:?xt=urn:btih:WAT5XMTWCWVS5SNEGSWUZN6XIRK5FC7G") as ReleaseLink.Magnet).hash)
    }

    @Test fun httpsTorrentLinksAreAcceptedOnlyOnPublicHosts() {
        assertEquals(ReleaseLink.TorrentUrl("https://releases.example.org/eragon.torrent"), ReleaseLinks.parse("https://releases.example.org/eragon.torrent"))
        rejects("http://releases.example.org/eragon.torrent", "https")
        rejects("https://192.168.1.4/eragon.torrent", "IP")
        rejects("https://user:pass@releases.example.org/eragon.torrent", "credentials")
    }

    @Test fun otherTextExplainsWhatToPaste() {
        rejects("", "magnet")
        rejects("Eragon audiobook", "magnet")
        rejects("magnet:?dn=Eragon", "info hash")
        rejects("magnet:?xt=urn:btih:1234", "info hash")
        rejects("magnet:?xt=urn:btih:$hash https://other", "single link")
    }

    @Test fun torrentFilesReportTheirOwnInfoHash() {
        val (bytes, expected) = torrent(listOf("D01/01.mp3"))
        assertEquals(expected, TorrentFiles.read(bytes)!!.first)
        assertNull(TorrentFiles.read("not a torrent".toByteArray()))
    }

    private fun encode(value: Any): ByteArray = when (value) {
        is String -> value.toByteArray().let { "${it.size}:".toByteArray() + it }
        is Long -> "i${value}e".toByteArray()
        is List<*> -> "l".toByteArray() + value.fold(byteArrayOf()) { bytes, item -> bytes + encode(item!!) } + "e".toByteArray()
        is Map<*, *> -> "d".toByteArray() + value.entries.sortedBy { it.key as String }.fold(byteArrayOf()) { bytes, item -> bytes + encode(item.key!!) + encode(item.value!!) } + "e".toByteArray()
        else -> error("Unsupported fixture")
    }
    private fun torrent(paths: List<String>, name: String = "Eragon"): Pair<ByteArray, String> {
        val info = mapOf("name" to name, "files" to paths.map { mapOf("length" to 102400L, "path" to it.split('/')) })
        return encode(mapOf("info" to info)) to MessageDigest.getInstance("SHA-1").digest(encode(info)).joinToString("") { "%02x".format(it) }
    }

    /** Controlled TorBox and metadata mirror; no account, audio, or real torrent is used. */
    private fun resolve(cached: String, mine: String = "[]", metadata: ByteArray? = null, block: suspend (LinkedReleases) -> Unit) = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                "/torrents/mylist" -> MockResponse().setBody("""{"success":true,"data":$mine}""")
                "/usenet/mylist", "/webdl/mylist" -> MockResponse().setBody("""{"success":true,"data":[]}""")
                "/torrents/checkcached" -> MockResponse().setBody("""{"success":true,"data":$cached}""")
                else -> if (!request.requestUrl!!.encodedPath.startsWith("/metadata/")) MockResponse().setResponseCode(400)
                    else metadata?.let { MockResponse().setBody(Buffer().write(it)) } ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
        try {
            val http = OkHttpClient()
            val torbox = TorBoxDelivery(http, { "test-secret" }, server.url("/").toString())
            block(LinkedReleases(http, torbox, TorBoxLibrary(torbox), TorrentFileDiscovery(http, server.url("/metadata/").toString())))
        } finally { server.shutdown() }
    }
    private val magnet = ReleaseLinks.parse("magnet:?xt=urn:btih:$hash&dn=Eragon") as ReleaseLink.Magnet

    @Test fun cachedLinkBecomesAReadyRecordingWithItsFiles() = resolve("""{"$hash":{"name":"Eragon","files":[{"name":"Eragon/Eragon.m4b","size":5}]}}""") { links ->
        val recording = links.resolve(magnet, connected = true)
        assertEquals("knaben:$hash", recording.id)
        assertEquals("cached", recording.cacheState)
        assertEquals(listOf("M4B"), recording.cachedFormats)
        assertEquals(ReleaseLinks.LINK_PROVIDER, recording.sourceAddonName)
        assertTrue(SourceQuality.ready(recording))
    }

    @Test fun linkAlreadyInTheAccountUsesThatDownload() = resolve("{}", mine = """[{"id":7,"hash":"$hash","name":"Eragon","download_finished":true,"download_present":true,"files":[{"id":0,"name":"Eragon/01.mp3","size":5}]}]""") { links ->
        val recording = links.resolve(magnet, connected = true)
        assertEquals("torbox:7", recording.id)
        assertEquals("torbox", recording.sources.single().delivery)
    }

    @Test fun uncachedLinkReadsVerifiedPublicMetadataAndWaitsForExplicitPreparation() {
        val (bytes, torrentHash) = torrent(listOf("D02/01.mp3", "D01/10.mp3", "D01/2.mp3", "cover.jpg"))
        val link = ReleaseLinks.parse("magnet:?xt=urn:btih:$torrentHash") as ReleaseLink.Magnet
        resolve("{}", metadata = bytes) { links ->
            val recording = links.resolve(link, connected = true)
            assertEquals("uncached", recording.cacheState)
            assertTrue(recording.filesVerified)
            assertEquals(listOf("D01/2.mp3", "D01/10.mp3", "D02/01.mp3"), recording.sources.single().parts.map { it.name })
            assertFalse("Preparation stays an explicit choice", SourceQuality.ready(recording))
        }
        // Metadata whose info hash differs from the link is never trusted.
        resolve("{}", metadata = bytes) { links ->
            val recording = links.resolve(magnet, connected = true)
            assertTrue(recording.sources.isEmpty())
            assertEquals(magnet.uri, recording.magnetUri)
        }
    }

    @Test fun nonAudioAndDisconnectedLinksSayWhy() {
        resolve("""{"$hash":{"files":[{"name":"Eragon.epub","size":5}]}}""") { links ->
            val noAudio = runCatching { links.resolve(magnet, connected = true) }.exceptionOrNull()
            assertTrue(noAudio!!.message!!.contains("no audio files"))
            val disconnected = runCatching { links.resolve(magnet, connected = false) }.exceptionOrNull()
            assertTrue(disconnected!!.message!!.contains("Connect TorBox"))
        }
    }
}
