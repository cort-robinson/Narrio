package app.narrio.playback

import app.narrio.domain.Chapter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Nero chpl M4B chapters. Unsupported chapter tracks fall back to file parts. */
object ChapterReader {
    fun parseChpl(bytes: ByteArray): List<Chapter> {
        for (offset in 4 until bytes.size - 17) {
            if (bytes[offset] != 'c'.code.toByte() || bytes[offset+1] != 'h'.code.toByte() || bytes[offset+2] != 'p'.code.toByte() || bytes[offset+3] != 'l'.code.toByte()) continue
            val size = ByteBuffer.wrap(bytes, offset - 4, 4).order(ByteOrder.BIG_ENDIAN).int
            if (size < 17 || size > 100_000 || offset - 4 + size > bytes.size) continue
            return runCatching {
                val buffer = ByteBuffer.wrap(bytes, offset + 4, size - 8).slice().order(ByteOrder.BIG_ENDIAN)
                val version = buffer.get().toInt() and 0xff
                buffer.position(4)
                if (version == 1) buffer.int
                val count = buffer.get().toInt() and 0xff
                val chapters = (0 until count).map {
                    val start = buffer.long / 10_000
                    val length = buffer.get().toInt() and 0xff
                    val title = ByteArray(length).also { buffer.get(it) }.toString(Charsets.UTF_8)
                    Chapter(title.ifBlank { "Chapter ${it + 1}" }, start)
                }
                require(chapters.all { it.startMs >= 0 } && chapters.zipWithNext().all { (a, b) -> a.startMs <= b.startMs })
                chapters
            }.getOrDefault(emptyList())
        }
        return emptyList()
    }
}
