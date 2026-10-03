package app.narrio.data

import app.narrio.domain.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class TorrentFileDiscoveryTest {
    private val name = "Christopher Paolini - Eragon"
    // Names from the public B027DBB27615AB2EC9A434AD4CB7D74455D28BE6 manifest.
    // Small controlled metadata only: no audio, account data, or external calls in CI.
    private val paths = listOf("D02/Christopher Paolini - Eragon - D02.10-11.mp3",
        "D01/Christopher Paolini - Eragon - D01.02-12.mp3", "D01/Christopher Paolini - Eragon - D01.01-12.mp3")
    private fun encode(value: Any): ByteArray = when (value) {
        is String -> value.toByteArray().let { "${it.size}:".toByteArray() + it }
        is Long -> "i${value}e".toByteArray()
        is List<*> -> "l".toByteArray() + value.fold(byteArrayOf()) { bytes, item -> bytes + encode(item!!) } + "e".toByteArray()
        is Map<*, *> -> "d".toByteArray() + value.entries.sortedBy { it.key as String }.fold(byteArrayOf()) { bytes, item -> bytes + encode(item.key!!) + encode(item.value!!) } + "e".toByteArray()
        else -> error("Unsupported fixture")
    }
    private fun fixture(title: String = name, filenames: List<String> = paths): Pair<ByteArray, String> {
        val info = mapOf("name" to title, "files" to filenames.map { mapOf("length" to 102400L, "path" to it.split('/')) })
        val hash = MessageDigest.getInstance("SHA-1").digest(encode(info)).joinToString("") { "%02x".format(it) }
        return encode(mapOf("info" to info)) to hash
    }

    @Test fun eragonCacheMissFindsVerifiedSeededFilesWithoutPreparingOrFetchingAudio() = runBlocking {
        val (metadata, hash) = fixture()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                "/v2/search" -> MockResponse().setBody("""{"hits":[{"title":"$name","hash":"$hash","categoryId":[1003000],"seeders":10},{"title":"$name","hash":"${"b".repeat(40)}","categoryId":[1003000],"seeders":0}]}""")
                "/torrents/mylist" -> MockResponse().setBody("""{"success":true,"data":[]}""")
                "/torrents/checkcached" -> MockResponse().setBody("""{"success":true,"data":{}}""")
                "/metadata/${hash.uppercase()}.torrent" -> MockResponse().setBody(Buffer().write(metadata))
                else -> MockResponse().setResponseCode(400)
            }
        }
        server.start()
        try {
            val http = OkHttpClient()
            val torbox = TorBoxDelivery(http, { "test-secret" }, server.url("/").toString())
            val files = TorrentFileDiscovery(http, server.url("/metadata/").toString())
            val public = object : RecordingDiscovery {
                override suspend fun search(query: String, category: String) = emptyList<Audiobook>()
                override suspend fun recording(id: String): Audiobook = error("No public recordings")
            }
            val catalogBook = Audiobook("catalog:eragon", "Eragon", "Christopher Paolini", provider = "catalog")
            val result = BookSourceDiscovery(public, KnabenDiscovery(http, server.url("/v2/").toString()), torbox::library, torbox::checkCached, files::recording).search(catalogBook, true)
            assertNull(result.error)
            val recording = result.recordings.single()
            assertEquals(hash, recording.torrentHash)
            assertEquals("uncached", recording.cacheState)
            assertTrue(recording.cachedFormats.isEmpty())
            assertTrue(recording.filesVerified)
            assertEquals(paths.sortedWith(AudioOrdering), recording.sources.single().parts.map { it.name })
            assertTrue(recording.sources.all { it.delivery == "torbox" && it.parts.all { part -> part.archiveUrl.isEmpty() && part.fileId == null } })
            assertEquals(4, server.requestCount)
            repeat(server.requestCount) {
                val request = server.takeRequest()
                assertEquals("GET", request.method)
                assertFalse(request.path!!.contains("createtorrent"))
                if (request.path!!.startsWith("/metadata/")) assertNull(request.getHeader("Authorization"))
            }
        } finally { server.shutdown() }
    }

    @Test fun rejectsWrongHashMalformedOversizedAndUnsafeFileMetadata() {
        val (metadata, hash) = fixture()
        assertEquals(name, TorrentFiles.parse(metadata, hash)!!.name)
        assertNull(TorrentFiles.parse(metadata, "0".repeat(40)))
        assertNull(TorrentFiles.parse(metadata + "junk".toByteArray(), hash))
        assertNull(TorrentFiles.parse(metadata.copyOf(metadata.size - 1), hash))
        assertNull(TorrentFiles.parse(ByteArray(TorrentFiles.MAX_BYTES + 1), hash))
        for (path in listOf("../book.mp3", "disc/../../book.mp3", "disc\\book.mp3")) {
            val (unsafe, unsafeHash) = fixture(filenames = listOf(path))
            assertNull(path, TorrentFiles.parse(unsafe, unsafeHash))
        }
    }

    @Test fun torrentIdentityAndAudioChecksStillRejectOtherBooksSamplesAndDeadReleases() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val book = Audiobook("catalog", "Eragon", "Christopher Paolini", provider = "catalog")
            val provider = TorrentFileDiscovery(OkHttpClient(), server.url("/").toString())
            for ((title, filenames) in listOf("Another Author - Eragon" to paths, "Christopher Paolini - Eragon 2" to paths,
                name to listOf("preview.mp3", "film.mp4"))) {
                val (bytes, hash) = fixture(title, filenames)
                server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
                val recording = Audiobook("release", name, "Author not verified", provider = "knaben", torrentHash = hash,
                    magnetUri = "magnet:?xt=urn:btih:$hash", cacheState = "uncached", seeders = 10)
                assertTrue(SourceQuality.filter(book, listOfNotNull(provider.recording(recording))).isEmpty())
            }
            val (bytes, hash) = fixture()
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            val dead = provider.recording(Audiobook("dead", name, "Author not verified", provider = "knaben", torrentHash = hash,
                magnetUri = "magnet:?xt=urn:btih:${"f".repeat(40)}", cacheState = "uncached", seeders = 0))!!
            assertEquals("magnet:?xt=urn:btih:$hash", dead.magnetUri)
            assertTrue(SourceQuality.filter(book, listOf(dead)).isEmpty())
            val cached = dead.copy(id = "cached", torrentHash = "a".repeat(40), cacheState = "cached", cachedFormats = listOf("MP3"))
            val seeded = dead.copy(seeders = 10)
            assertEquals(listOf(cached.id, seeded.id), SourceQuality.filter(book, listOf(seeded, cached)).map { it.id })
        } finally { server.shutdown() }
    }
}
