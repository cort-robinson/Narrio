package app.narrio

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.narrio.ui.NarrioViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Live provider check for book text, not part of required CI. Read along's UI is covered by [ReadAlongExperienceTest]. */
@RunWith(AndroidJUnit4::class)
class FollowAlongTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val graph get() = (compose.activity.application as NarrioApplication).graph

    @Test fun publicLookupDownloadsARealEpubWithChapters() = runBlocking {
        val book = NarrioViewModel.curated.first()
        val result = graph.textDiscovery.search("Secret Garden").first { it.title.equals("The Secret Garden", true) }
        val document = graph.followAlong.fetch(result, book, "public-fixture-source", "public-fixture-part")
        try {
            assertEquals("EPUB", document.format)
            assertTrue(document.chapters.size >= 27)
            assertTrue(document.chapters.flatMap { it.passages }.any { it.text.contains("Mary Lennox") })
            assertEquals(document, graph.followAlong.load(graph.library.bookText(book.id)!!))
            assertTrue(document.attribution.contains("Project Gutenberg"))
        } finally { graph.followAlong.remove(book.id) }
    }
}
