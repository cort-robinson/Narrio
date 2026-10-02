package app.narrio.domain

import app.narrio.playback.ChapterReader
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class AudioIdentityTest {
    @Test fun unpaddedPartsAndDiscFoldersKeepNarrativeOrder() {
        val parts = listOf("Disc 2/Chapter 1.mp3", "Disc 1/Chapter 10.mp3", "Disc 1/Chapter 2.mp3", "Disc 1/Chapter 1.mp3")
        assertEquals(listOf("Disc 1/Chapter 1.mp3", "Disc 1/Chapter 2.mp3", "Disc 1/Chapter 10.mp3", "Disc 2/Chapter 1.mp3"), parts.sortedWith(AudioOrdering))
    }
    @Test fun matchingStablePartResumesAfterFilesAreReordered() {
        val parts = listOf(AudioPart("file-2", "2.mp3", "2"), AudioPart("file-1", "1.mp3", "1"))
        assertEquals(1, resumeIndex(parts, "file-1"))
        assertEquals(0, resumeIndex(parts, "a-different-recording"))
    }
    @Test fun durationsSupportHourAndDecimalMetadata() {
        assertEquals(26_131_000, parseDuration("7:15:31"))
        assertEquals(284_210, parseDuration("284.21"))
        assertEquals(0, parseDuration("not listed"))
    }
    @Test fun chplChaptersDecodeWithoutTreatingFilesAsChapters() {
        val titles = listOf("Opening", "A second chapter")
        val payload = ByteBuffer.allocate(9 + titles.sumOf { 9 + it.toByteArray().size })
        payload.putInt(0x01000000); payload.putInt(0); payload.put(2)
        titles.forEachIndexed { i, title -> payload.putLong(i * 60_000L * 10_000); payload.put(title.length.toByte()); payload.put(title.toByteArray()) }
        val box = ByteBuffer.allocate(payload.capacity() + 8).putInt(payload.capacity() + 8).put("chpl".toByteArray()).put(payload.array()).array()
        assertEquals(listOf(Chapter("Opening", 0), Chapter("A second chapter", 60_000)), ChapterReader.parseChpl(box))
        assertTrue(ChapterReader.parseChpl(box.copyOf(14)).isEmpty())
    }
    @Test fun onlyAudioEntersThePlaylist() {
        assertTrue(isAudioFile("book.M4B")); assertTrue(isAudioFile("part 1.mp3"))
        assertFalse(isAudioFile("cover.jpg")); assertFalse(isAudioFile("book.zip")); assertFalse(isAudioFile("index.xml"))
    }
}
