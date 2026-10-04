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
    val sizeBytes: Long = 0,
)

@Serializable
data class AudioSource(
    val id: String,
    val label: String,
    val format: String,
    val parts: List<AudioPart>,
    val delivery: String = "archive",
    val torrentId: Long? = null,
    val textFiles: List<BookTextSource> = emptyList(),
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
    val magnetUri: String = "",
    val cacheState: String = "unchecked",
    val cachedFormats: List<String> = emptyList(),
    val releaseSizeBytes: Long = 0,
    val releaseTitle: String = "",
    val metadataSource: String = "",
    val metadataUrl: String = "",
    val metadataUpdatedAtMs: Long = 0,
    val narratorFromCatalog: Boolean = false,
    val seeders: Long = 0,
    val filesVerified: Boolean = false,
    val bookFilesSelected: Boolean = false,
    val sourceAddonName: String = "",
)

data class Chapter(val title: String, val startMs: Long)

data class Preparation(
    val torrentId: Long, val ready: Boolean, val progress: Float, val state: String,
    val downloadBytesPerSecond: Long = 0, val etaSeconds: Long = 0,
    val seeds: Long? = null, val checkedAtMs: Long = 0,
)

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
fun isBookAudioFile(name: String) = isAudioFile(name) && !Regex("(?i)(^|[/ _-])(sample|trailer|preview)([/ _.-]|$)").containsMatchIn(name)
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
fun sizeLabel(bytes: Long): String = when { bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0); bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1_000_000.0); else -> "%.0f KB".format(bytes / 1000.0) }
fun providerLabel(book: Audiobook) = when (book.provider) { "catalog" -> book.metadataSource.ifBlank { "Book catalog" }; "torbox" -> "My TorBox"; "knaben" -> book.sourceAddonName.ifBlank { "Indexed release" }; else -> "LibriVox" }
fun narrationLabel(book: Audiobook) = when {
    book.narratorFromCatalog -> "Catalog narrator: ${book.narrator}"
    book.narrator.startsWith("Narrator not ") -> book.narrator
    else -> "Read by ${book.narrator}"
}
/** Copy descriptive fields without changing release, file, cache, or playback identity. */
fun Audiobook.withMetadataFrom(details: Audiobook) = copy(
    title = details.title, author = details.author, narrator = details.narrator,
    description = details.description, coverUrl = details.coverUrl, releaseTitle = details.releaseTitle,
    metadataSource = details.metadataSource, metadataUrl = details.metadataUrl,
    metadataUpdatedAtMs = details.metadataUpdatedAtMs, narratorFromCatalog = details.narratorFromCatalog,
)

interface RecordingDiscovery {
    suspend fun search(query: String, category: String = "All"): List<Audiobook>
    suspend fun searchBook(book: Audiobook, title: String): List<Audiobook> = search(title)
    suspend fun recording(id: String): Audiobook
}

interface DeliveryProvider {
    suspend fun prepare(book: Audiobook): Preparation
    suspend fun status(torrentId: Long): Preparation
    suspend fun sources(book: Audiobook, torrentId: Long): List<AudioSource>
    fun resolve(part: AudioPart): String
}
