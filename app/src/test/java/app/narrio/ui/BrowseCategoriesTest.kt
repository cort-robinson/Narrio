package app.narrio.ui

import app.narrio.data.AppleBooks
import org.junit.Assert.*
import org.junit.Test

class BrowseCategoriesTest {
    @Test fun everyCategoryNamesWhatItsListHolds() {
        val titles = AppleBooks.genres.keys.map(::browseTitle)
        assertEquals("Popular audiobooks", browseTitle("All"))
        assertEquals(titles.size, titles.toSet().size)
        assertTrue(AppleBooks.genres.filterKeys { it != "All" }.values.all { it != null })
    }
}
