package app.narrio.updates

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.serialization.json.JsonPrimitive

class UpdateRepositoryTest {
    private fun server(block: suspend (MockWebServer, UpdateRepository) -> Unit) = runBlocking {
        val server = MockWebServer(); server.start()
        try { block(server, UpdateRepository(OkHttpClient(), UpdatePolicy(UpdateChannel.DEV, 58, signer),
            server.url("/repo").toString()) { url -> if (url.startsWith(UpdatePolicy.REPOSITORY)) server.url("/" + url.substringAfter("https://")).toString() else url }) }
        finally { server.shutdown() }
    }

    @Test fun skipsFailedAndPendingPreviewsAndSelectsLastPassingBuild() = server { server, repository ->
        server.enqueue(MockResponse().setBody("[${release(code = 63)},${release(false)},${release(code = 62)},${release(code = 61)},${release()}]"))
        for ((code, result) in listOf(63L to "failure", 62L to "cancelled", 61L to "success")) {
            server.enqueue(MockResponse().setBody(manifest(code = code).toString()))
            val run = workflow(code, result).let { if (code == 62L) it.changed("status", JsonPrimitive("in_progress")) else it }
            server.enqueue(MockResponse().setBody(run.toString()))
        }
        assertEquals(61L, repository.latest()?.code)
        assertEquals("/repo/releases?per_page=30", server.takeRequest().path)
        assertEquals(7, server.requestCount)
    }

    @Test fun stableUsesStableLatestEndpointOnly() = server { server, _ ->
        val repo = UpdateRepository(OkHttpClient(), UpdatePolicy(UpdateChannel.STABLE, 1_004_002, signer), server.url("/repo").toString()) {
            if (it.startsWith(UpdatePolicy.REPOSITORY)) server.url("/manifest").toString() else it
        }
        server.enqueue(MockResponse().setBody(release(false).toString()))
        server.enqueue(MockResponse().setBody(manifest(false).toString()))
        assertEquals("1.5.0", repo.latest()?.version)
        assertEquals("/repo/releases/latest", server.takeRequest().path)
        assertEquals(2, server.requestCount)
    }

    @Test fun downloadsVerifyChecksumReuseCacheAndRemoveCorruptPartial() = server { server, repository ->
        val bytes = "APK!"
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }
        val update = UpdatePolicy(UpdateChannel.DEV, 58, signer).candidate(release(), manifest(sha = hash))
            .copy(downloadUrl = server.url("/apk").toString())
        val directory = Files.createTempDirectory("narrio-update-test").toFile()
        try {
            server.enqueue(MockResponse().setBody("BAD!"))
            try { repository.download(update, directory) {}; fail("Corrupt bytes must fail") } catch (_: IllegalArgumentException) { }
            assertTrue(directory.listFiles().orEmpty().isEmpty())
            server.enqueue(MockResponse().setBody(bytes))
            var progress = 0f
            val file = repository.download(update, directory) { progress = it }
            assertEquals(bytes, file.readText())
            assertEquals(1f, progress)
            assertEquals(file, repository.download(update, directory) {})
            assertEquals(2, server.requestCount)
            file.writeText("BAD!")
            server.enqueue(MockResponse().setBody(bytes))
            assertTrue(UpdateRepository.verifiesBytes(repository.download(update, directory) {}, update))
        } finally { directory.deleteRecursively() }
    }

    @Test fun rejectsOversizedDownloadAndMetadata() = server { server, repository ->
        server.enqueue(MockResponse().setBody(" ".repeat(1024 * 1024 + 1)))
        try { repository.latest(); fail("Metadata limit must apply") } catch (_: java.io.IOException) { }
        val directory = Files.createTempDirectory("narrio-update-size").toFile()
        val update = UpdatePolicy(UpdateChannel.DEV, 58, signer).candidate(release(), manifest()).copy(downloadUrl = server.url("/apk").toString())
        try {
            server.enqueue(MockResponse().setBody("TOO LARGE"))
            try { repository.download(update, directory) {}; fail("Size mismatch must fail") } catch (_: IllegalArgumentException) { }
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun cancelledAndTruncatedDownloadsLeaveNoInstallableFiles() = server { server, repository ->
        val directory = Files.createTempDirectory("narrio-update-interrupt").toFile()
        val update = UpdatePolicy(UpdateChannel.DEV, 58, signer).candidate(release(), manifest()).copy(downloadUrl = server.url("/apk").toString())
        try {
            server.enqueue(MockResponse().setChunkedBody("ABC", 1))
            try { repository.download(update, directory) {}; fail("Truncated bytes must fail") } catch (_: IllegalArgumentException) { }
            assertTrue(directory.listFiles().orEmpty().isEmpty())
            server.enqueue(MockResponse().setBody("ABCD"))
            try { repository.download(update, directory) { throw kotlinx.coroutines.CancellationException("Stopped") }; fail("Cancellation must propagate") }
            catch (_: kotlinx.coroutines.CancellationException) { }
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        } finally { directory.deleteRecursively() }
    }
}
