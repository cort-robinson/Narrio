package app.narrio

import android.net.Uri
import androidx.media3.datasource.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.narrio.data.*
import app.narrio.domain.*
import app.narrio.playback.RefreshingDataSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PersistenceAndRenewalTest {
    @Test fun formatsKeepIndependentDurablePositions() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LibraryDatabase::class.java).build()
        try {
            val dao = db.library(); val book = Audiobook("edition", "A book", "An author")
            val mp3 = AudioSource("edition:mp3", "Parts", "MP3", listOf(AudioPart("part-2", "part2.mp3", "2")))
            val m4b = AudioSource("edition:m4b", "Whole book", "M4B", listOf(AudioPart("whole", "whole.m4b", "Book")))
            dao.save(book)
            dao.progress(book.id, NarrioJson.encodeToString(mp3), "part-2", 125_000, 1)
            dao.progress(book.id, NarrioJson.encodeToString(m4b), "whole", 6_000, 2)
            assertEquals(125_000L, dao.position(book.id, mp3.id)?.positionMs)
            assertEquals(6_000L, dao.position(book.id, m4b.id)?.positionMs)
            assertEquals("whole", dao.find(book.id)?.partId)
            dao.remove(book.id)
            assertNull(dao.position(book.id, mp3.id)); assertNull(dao.find(book.id))
        } finally { db.close() }
    }

    @Test fun credentialIsEncryptedAndRemovedOnDisconnect() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = CredentialStore(context)
        store.write("synthetic-test-key")
        assertEquals("synthetic-test-key", store.read())
        assertFalse(context.getSharedPreferences("credentials", 0).getString("data", "").orEmpty().contains("synthetic-test-key"))
        store.clear(); assertNull(store.read())
    }

    @Test fun expiredLinkRenewsAtTheSameByteOffsetWithoutLeakingKeyToPlayer() {
        var requests = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("Bearer synthetic-test-key", chain.request().header("Authorization"))
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"success":true,"data":"https://cdn.example/link-$requests.mp3"}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val provider = TorBoxDelivery(http, { "synthetic-test-key" })
        val original = DataSpec.Builder().setUri("narrio://audio/stable-part").setPosition(543210).build()
        val opened = mutableListOf<DataSpec>()
        val upstream = object : DataSource {
            override fun open(spec: DataSpec): Long {
                opened += spec
                if (opened.size == 1) throw HttpDataSource.InvalidResponseCodeException(403, "Expired", null, emptyMap(), spec, byteArrayOf())
                return 1
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int) = -1
            override fun getUri(): Uri? = opened.lastOrNull()?.uri
            override fun addTransferListener(listener: TransferListener) {}
            override fun close() {}
        }
        val part = AudioPart("stable-part", "part.mp3", "Part", torrentId = 3, fileId = 4)
        val source = RefreshingDataSource(upstream, provider, ConcurrentHashMap(mapOf(original.uri.toString() to part)), ConcurrentHashMap())
        assertEquals(1, source.open(original))
        assertEquals(2, requests); assertEquals(listOf(543210L,543210L), opened.map { it.position })
        assertTrue(opened.none { it.uri.toString().contains("synthetic-test-key") })
        assertEquals("stable-part", part.id)
    }
}
