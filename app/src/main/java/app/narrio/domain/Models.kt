package app.narrio.domain

import kotlinx.serialization.Serializable
import java.math.BigInteger

@Serializable
data class AudioPart(
    val id: String,
    val name: String,
    val title: String,
    val durationMs: Long = 0,
    val archiveUrl: String = "",
    val torrentId: Long? = null,
    val fileId: Long? = null,
)

@Serializable
data class AudioSource(
    val id: String,
    val label: String,
    val format: String,
    val parts: List<AudioPart>,
    val delivery: String = "archive",
    val torrentId: Long? = null,
)

@Serializable
data class Audiobook(
    val id: String,
    val title: String,
    val author: String,
    val narrator: String = "Narrator not listed",
    val language: String = "English",
    val description: String = "",
    val durationMs: Long = 0,
    val coverUrl: String = "",
    val subjects: List<String> = emptyList(),
    val sources: List<AudioSource> = emptyList(),
    val torrentUrl: String = "",
    val torrentHash: String = "",
    val provider: String = "archive",
    val detailsLoaded: Boolean = false,
)

data class Chapter(val title: String, val startMs: Long)

data class Preparation(val torrentId: Long, val ready: Boolean, val progress: Float, val state: String)

/** Full path comparison keeps disc folders and unpadded chapter numbers in order. */
object AudioOrdering : Comparator<String> {
    private val tokens = Regex("[0-9]+|[^0-9]+")
    override fun compare(a: String, b: String): Int {
        val left = tokens.findAll(a.lowercase()).map { it.value }.toList()
        val right = tokens.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(left.size, right.size)) {
            val x = left[i]; val y = right[i]
            val order = if (x.first().isDigit() && y.first().isDigit()) BigInteger(x).compareTo(BigInteger(y)) else x.compareTo(y)
            if (order != 0) return order
        }
        return left.size.compareTo(right.size).takeIf { it != 0 } ?: a.compareTo(b)
    }
}

fun isAudioFile(name: String) = name.substringAfterLast('.').lowercase() in setOf("mp3", "m4b", "m4a", "aac", "flac", "ogg", "opus", "wav")
fun resumeIndex(parts: List<AudioPart>, partId: String): Int = parts.indexOfFirst { it.id == partId }.coerceAtLeast(0)
fun parseDuration(value: String): Long {
    val bits = value.trim().split(':')
    return runCatching { bits.fold(0.0) { total, bit -> total * 60 + bit.toDouble() }.times(1000).toLong() }.getOrDefault(0)
}
fun formatTime(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
fun durationLabel(ms: Long): String = if (ms > 0) "${ms / 3_600_000}h ${(ms / 60_000) % 60}m" else "Length on playback"

interface RecordingDiscovery {
    suspend fun search(query: String, category: String = "All"): List<Audiobook>
    suspend fun recording(id: String): Audiobook
}

interface DeliveryProvider {
    suspend fun prepare(book: Audiobook): Preparation
    suspend fun status(torrentId: Long): Preparation
    suspend fun sources(book: Audiobook, torrentId: Long): List<AudioSource>
    fun resolve(part: AudioPart): String
}
