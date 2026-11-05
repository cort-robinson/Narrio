package app.narrio.data

import app.narrio.domain.Audiobook
import org.junit.Assert.*
import org.junit.Test

class ArchiveDiscoveryTest {
    private fun book(id: String, title: String, author: String) = Audiobook(id, title, author, provider = "catalog")

    /** Discover marks a chart book Free only when LibriVox reads the same title by the same author. */
    @Test fun freeReadingsMatchTitleAndAuthorSurname() {
        val books = listOf(
            book("pp", "Pride and Prejudice", "Jane Austen"),
            book("eden", "East of Eden", "John Steinbeck"),
            book("dracula", "Dracula (Unabridged)", "Bram Stoker"),
            book("emma", "Emma", "Jane Austen"),
        )
        val readings = listOf(
            "Pride and Prejudice (version 3)" to "Austen, Jane",
            "Dracula: Dramatic Reading" to "Bram Stoker",
            "Emma" to "Someone Else",
        )
        assertEquals(setOf("pp", "dracula"), ArchiveDiscovery.freeMatches(books, readings))
        assertEquals("pride and prejudice", ArchiveDiscovery.freeTitle("Pride and Prejudice: A Novel"))
        assertEquals("dont look", ArchiveDiscovery.freeTitle("Don’t Look [Abridged]"))
        // Archive finds titles with apostrophes only when the query keeps them; quotes and Lucene syntax never reach it.
        assertEquals("Alice's Adventures in Wonderland", ArchiveDiscovery.queryTitle("Alice’s Adventures in Wonderland (Unabridged)"))
        assertEquals("The Count of Monte Cristo", ArchiveDiscovery.queryTitle("The \"Count\" of Monte-Cristo: A Novel"))
    }
}
