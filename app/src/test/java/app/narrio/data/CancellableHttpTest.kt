package app.narrio.data

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class CancellableHttpTest {
    @Test fun cancellingAStalledResponseBodyReleasesTheCoroutineAndSocket() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("body").setBodyDelay(3, TimeUnit.SECONDS)); server.start()
        val client = OkHttpClient()
        try {
            val job = launch(Dispatchers.IO) { client.readCancellable(Request.Builder().url(server.url("/")).build()) { it.body!!.string() } }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            delay(50)
            val started = System.nanoTime()
            job.cancelAndJoin()
            assertTrue((System.nanoTime() - started) / 1_000_000 < 500)
            withTimeout(2_000) { while (client.dispatcher.runningCallsCount() != 0) delay(10) }
            assertTrue(job.isCancelled)
        } finally { server.shutdown() }
    }
}
