package app.narrio.data

import app.narrio.domain.*
import org.junit.Assert.*
import org.junit.Test

class LocalAudioTest {
    private fun file(path: String, ms: Long = 60_000) = LocalAudioFile("content://docs/tree/book/document/${path.hashCode()}", path, 1_000, ms)
    private val book = Audiobook("catalog:eragon", "Eragon", "Christopher Paolini", provider = "catalog", description = "A farm boy finds a dragon egg.")

    @Test fun phoneFilesPlayInNaturalOrderWithoutNonAudio() {
        val ordered = LocalAudio.order(listOf(file("Disc 2/1.mp3"), file("Disc 1/10.mp3"), file("Disc 1/2.mp3"), file("cover.jpg"),
            file("Disc 1/sample.mp3"), file("Disc 1/2.mp3")))
        assertEquals(listOf("Disc 1/2.mp3", "Disc 1/10.mp3", "Disc 2/1.mp3"), ordered.map { it.path })
    }

    @Test fun phoneFilesBecomeALocalRecordingWithStableIds() {
        val files = LocalAudio.order(listOf(file("Eragon 02.m4b"), file("Eragon 01.m4b")))
        val recording = LocalAudio.recording(book, files, "Eragon")
        val source = recording.sources.single()
        assertEquals(LocalAudio.PROVIDER, recording.provider)
        assertEquals(LocalAudio.DELIVERY, source.delivery)
        assertEquals("M4B", source.format)
        assertEquals(files.map { it.uri }, source.parts.map { it.archiveUrl })
        assertEquals(120_000L, recording.durationMs)
        assertEquals("Eragon", recording.releaseTitle)
        assertEquals(book.description, recording.description)
        // The same files chosen again, in any order, keep the same recording and part IDs, so the place is kept.
        val again = LocalAudio.recording(book, LocalAudio.order(files.reversed()), "Eragon")
        assertEquals(recording.id, again.id)
        assertEquals(source.parts.map { it.id }, again.sources.single().parts.map { it.id })
        assertTrue(recording.id.startsWith("local:"))
        // Attaching keeps the book's identity and records the phone recording separately.
        assertEquals(book.id, recording.forBook(book).id)
        assertEquals(recording.id, recording.forBook(book).recordingId)
    }

    @Test fun mixedFormatsAndUnknownLengths() {
        assertEquals("OTHER", LocalAudio.format(listOf(file("a.mp3"), file("b.m4b"))))
        assertEquals("M4A", LocalAudio.format(listOf(file("a.m4a"))))
        assertEquals(0L, LocalAudio.recording(book, listOf(file("a.mp3", 0), file("b.mp3"))).durationMs)
        assertEquals("phone", "On this phone", deliveryLabel(LocalAudio.recording(book, listOf(file("a.mp3"))).sources.single()))
    }

    @Test fun grantsAreReleasedOnlyWhenNoOtherBookUsesThem() {
        val grants = LocalAudioGrants(MemoryValues())
        grants.add("a", listOf("content://tree/1", "content://doc/2"))
        grants.add("b", listOf("content://tree/1"))
        assertEquals(setOf("content://doc/2"), grants.forget("a"))
        assertEquals(setOf("content://tree/1"), grants.forget("b"))
        assertTrue(grants.get("a").isEmpty())
    }

    @Test fun searchWordsAreRememberedPerBook() {
        val words = SourceWordsStore(MemoryValues())
        words.set("a", "  Philosopher's   Stone ")
        assertEquals("Philosopher's Stone", words.get("a"))
        assertNull(words.get("b"))
        words.forget("a")
        assertNull(words.get("a"))
    }
}
