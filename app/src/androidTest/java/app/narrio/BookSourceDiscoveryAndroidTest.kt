package app.narrio

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.narrio.data.*
import app.narrio.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise release matching on Android's regex runtime, with controlled public-release fixtures. */
@RunWith(AndroidJUnit4::class)
class BookSourceDiscoveryAndroidTest {
    @Test fun titleOnlyAndTaggedReleaseNamesUseAndroidCompatibleMatching() {
        val book = Audiobook("catalog:eragon", "Eragon", "Christopher Paolini", provider = "catalog")
        for (name in listOf("01_ERAGON Audiobook", "Eragon [M4B]", "Eragon (Unabridged)", "Eragon {Retail}", "Eragon [M4B")) {
            val release = Audiobook("knaben:test", name, "Author not verified", provider = "knaben")
            assertEquals(name, MatchConfidence.POSSIBLE, SourceQuality.confidence(book, release))
        }
    }

    @Test fun publicReleaseNamesSurviveDiscoveryAndUnavailableDelivery() = runBlocking {
        for ((title, author, releaseName) in listOf(
            Triple("Eragon", "Christopher Paolini", "Eragon - Christopher Paolini"),
            Triple("The Subtle Art of Not Giving a F*ck", "Mark Manson", "The Subtle Art of Not Giving a F*ck - Mark Manson"),
        )) {
            val book = Audiobook("catalog:test", title, author, provider = "catalog")
            val release = Audiobook("knaben:test", releaseName, author, provider = "knaben", seeders = 3, torrentHash = "a".repeat(40))
            fun discovery(results: List<Audiobook>) = object : RecordingDiscovery {
                override suspend fun search(query: String, category: String) = results
                override suspend fun recording(id: String) = results.first()
            }
            val sources = BookSourceDiscovery(discovery(emptyList()), listOf(discovery(listOf(release))), { emptyList() },
                { throw ProviderException("Unavailable") })
            val result = sources.search(book, true)
            assertTrue(result.recordings.isEmpty())
            assertEquals(listOf(release.id), result.possible.map { it.id })
            assertNotNull(result.error)
        }
    }
}
