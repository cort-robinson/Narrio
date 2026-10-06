package app.narrio.data

import app.narrio.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReadingStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun staleReadingOrListeningCannotOverwriteANewerAction() {
        val reading = PositionUpdate("book", PositionOrigin.READING,
            text = ContentCursor("edition", "chapter.xhtml", 42, locatorJson = "locator"), basedOnSequence = 0)
        val first = nextSharedPosition(null, reading, 100)!!
        assertEquals(1L, first.sequence)
        assertEquals(100L, first.updatedAtMs)
        assertNull(nextSharedPosition(first, reading, 101))
        val audio = AudioCursor("source", "part", 10000)
        val listening = PositionUpdate("book", PositionOrigin.LISTENING, audio = audio, basedOnSequence = 1)
        val second = nextSharedPosition(first, listening, 200)!!
        assertEquals(2L, second.sequence)
        assertEquals(audio, second.audio)
        assertEquals(PositionOrigin.LISTENING, second.origin)
        assertNull(nextSharedPosition(second, reading, 300))
        assertNull(nextSharedPosition(null, listening, 400))
    }

    @Test fun multipleEditionsKeepOriginalsAndLocatorsAcrossReopeningAndRemoval() {
        val root = temporary.newFolder()
        val files = EditionFileStorage(root)
        val firstBytes = "Chapter One\n\nFirst edition text.".toByteArray()
        val secondBytes = "Chapter One\n\nAnother edition text.".toByteArray()
        val first = BookTextParser.parse(firstBytes, "TXT", "Book", "Author")
        val second = BookTextParser.parse(secondBytes, "TXT", "Book", "Author")
        files.save("book", first, firstBytes)
        files.save("book", second, secondBytes)
        val reopened = EditionFileStorage(root)
        assertArrayEquals(firstBytes, reopened.original("book", first.id)!!.readBytes())
        assertEquals(first, reopened.load("book", first.id))
        assertEquals(second, reopened.load("book", second.id))
        reopened.remove("book", second.id)
        assertNull(reopened.original("book", second.id))
        assertEquals(first.chapters, reopened.load("book", first.id).chapters)
        assertTrue(root.walk().none { it.name.endsWith(".pending") })
    }

    @Test fun untrustedEditionIdsCannotEscapePrivateStorage() {
        val files = EditionFileStorage(temporary.newFolder())
        assertThrows(IllegalArgumentException::class.java) { files.original("book", "../outside") }
        assertThrows(IllegalArgumentException::class.java) { files.remove("book", "../outside") }
    }

    @Test fun importedBooksHaveStableMetadataIdentityAndNoAudio() {
        val a = localEbookBook(BookTextParser.parse("Some text.".toByteArray(), "TXT", "The Book", "Writer"))
        val b = localEbookBook(BookTextParser.parse("Other edition.".toByteArray(), "TXT", "The Book", "Writer"))
        assertEquals(a.id, b.id)
        assertTrue(a.sources.isEmpty())
        assertTrue(a.detailsLoaded)
    }
}
