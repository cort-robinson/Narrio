package app.narrio.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class TorBoxWebEbooksTest {
    private val url = "https://ebooks.example/download/book.epub?edition=1"
    private val hash = TorBoxWebEbooks.linkHash(url)
    private fun ready(id: Int = 12) = """{"id":$id,"hash":"$hash","download_finished":true,"download_present":true,"files":[{"id":0,"name":"book.epub"},{"id":4,"name":"readme.txt"}]}"""
    private fun response(data: String) = MockResponse().setBody("""{"success":true,"data":$data}""")

    @Test fun cachedUrlUsesWebDownloadCacheAndAddsOnlyTheCachedItem() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(response("""{"$hash":{"hash":"$hash","files":[{"name":"book.epub"}]}}"""))
            server.enqueue(response("[]"))
            server.enqueue(response("""{"webdownload_id":12}"""))
            server.enqueue(response(ready()))
            server.enqueue(response("\"https://cdn.example/book.epub\""))
            val delivery = TorBoxDelivery(OkHttpClient(), { "fixture-key" }, server.url("/").toString())
            val result = TorBoxWebEbooks(delivery, 0, 2).acquire(url) {}
            val cache = server.takeRequest()
            assertEquals("/webdl/checkcached", cache.requestUrl!!.encodedPath)
            assertEquals(hash, cache.requestUrl!!.queryParameter("hash"))
            assertEquals("true", cache.requestUrl!!.queryParameter("list_files"))
            assertEquals("Bearer fixture-key", cache.getHeader("Authorization"))
            assertNull(cache.getHeader("Cookie"))
            assertEquals("/webdl/mylist", server.takeRequest().requestUrl!!.encodedPath)
            val create = server.takeRequest()
            assertEquals("/webdl/createwebdownload", create.requestUrl!!.encodedPath)
            val body = create.body.readUtf8()
            assertTrue(body.contains(url))
            assertEquals("true", body.substringAfter("name=\"add_only_if_cached\"").substringAfter("\r\n\r\n").substringBefore("\r\n"))
            server.takeRequest()
            assertEquals("torbox-web", result.provider)
            assertEquals(12L, result.torrentId)
            assertEquals(0L, result.fileId)
            assertEquals("EPUB", result.format)
            assertTrue(result.url.isEmpty())
            assertEquals("https://cdn.example/book.epub", delivery.webTextLink(result.torrentId!!, result.fileId!!))
            val link = server.takeRequest()
            assertEquals("/webdl/requestdl", link.requestUrl!!.encodedPath)
            assertEquals("12", link.requestUrl!!.queryParameter("web_id"))
            assertEquals("0", link.requestUrl!!.queryParameter("file_id"))
            assertEquals("false", link.requestUrl!!.queryParameter("redirect"))
        } finally { server.shutdown() }
    }

    @Test fun cacheMissStartsWebDownloadAndWaitsForReadyFiles() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(response("{}")); server.enqueue(response("[]"))
            server.enqueue(response("""{"webdownload_id":"12"}"""))
            server.enqueue(response("""{"id":12,"download_finished":false,"download_present":false,"progress":0.5}"""))
            server.enqueue(response(ready()))
            val steps = mutableListOf<String>()
            val delivery = TorBoxDelivery(OkHttpClient(), { "fixture-key" }, server.url("/").toString())
            assertEquals("EPUB", TorBoxWebEbooks(delivery, 0, 3).acquire(url, steps::add).format)
            repeat(2) { server.takeRequest() }
            assertEquals("false", server.takeRequest().body.readUtf8().substringAfter("name=\"add_only_if_cached\"").substringAfter("\r\n\r\n").substringBefore("\r\n"))
            repeat(2) { assertEquals("12", server.takeRequest().requestUrl!!.queryParameter("id")) }
            assertTrue(steps.any { "50%" in it })
        } finally { server.shutdown() }
    }

    @Test fun retryReusesAnExistingPendingDownloadInsteadOfCreatingAnother() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(response("{}"))
            val pending = """{"id":12,"hash":"$hash","download_finished":false,"download_present":false}"""
            server.enqueue(response("[$pending]")); server.enqueue(response(pending))
            val delivery = TorBoxDelivery(OkHttpClient(), { "fixture-key" }, server.url("/").toString())
            assertTrue(runCatching { TorBoxWebEbooks(delivery, 0, 1).acquire(url) {} }.exceptionOrNull() is WebEbookPendingException)
            val paths = List(server.requestCount) { server.takeRequest().requestUrl!!.encodedPath }
            assertEquals(listOf("/webdl/checkcached", "/webdl/mylist", "/webdl/mylist"), paths)
        } finally { server.shutdown() }
    }

    @Test fun accountDiscoveryOnlyOffersReadySupportedEbookFiles() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(response("[${ready()},${ready(13).replace("\"download_present\":true", "\"download_present\":false")}]"))
            val delivery = TorBoxDelivery(OkHttpClient(), { "fixture-key" }, server.url("/").toString())
            assertEquals(listOf("torbox-web:12:0"), TorBoxWebEbooks(delivery).accountText().map { it.second.id })
        } finally { server.shutdown() }
    }
}
