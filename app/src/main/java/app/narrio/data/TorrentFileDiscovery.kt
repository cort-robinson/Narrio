package app.narrio.data

import app.narrio.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** Reads public file metadata only. It never joins peers, fetches audio, or creates a TorBox download. */
class TorrentFileDiscovery(http: OkHttpClient, private val baseUrl: String = "https://itorrents.org/torrent/") {
    private val client = http.newBuilder().callTimeout(12, TimeUnit.SECONDS).build()

    suspend fun recording(book: Audiobook): Audiobook? = withContext(Dispatchers.IO) {
        val hash = book.torrentHash.lowercase()
        if (!hash.matches(Regex("[a-f0-9]{40}"))) return@withContext null
        val response = suspendCancellableCoroutine { continuation ->
            val call = client.newCall(Request.Builder().url("$baseUrl${hash.uppercase()}.torrent").build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { _, value, _ -> value.close() }
                }
            })
        }
        response.use {
            // Missing metadata is normal for some indexed releases; it isn't proof of usable audio.
            if (it.code == 404) return@withContext null
            if (!it.isSuccessful) throw IOException("Audio file metadata is unavailable.")
            val body = it.body ?: return@withContext null
            if (body.contentLength() > TorrentFiles.MAX_BYTES) return@withContext null
            val source = body.source()
            source.request(TorrentFiles.MAX_BYTES.toLong() + 1)
            if (source.buffer.size > TorrentFiles.MAX_BYTES) return@withContext null
            val files = TorrentFiles.parse(source.readByteArray(), hash) ?: return@withContext null
            val sources = files.files.filter { it.size > 0 && isBookAudioFile(it.name) }
                .groupBy { when (val ext = it.name.substringAfterLast('.').uppercase()) { "MP3", "M4B" -> ext; else -> "OTHER" } }
                .map { (format, group) -> AudioSource("manifest:$hash:$format", if (format == "M4B") "Whole-book audio" else "Ordered audio parts",
                    format, group.sortedWith { a, b -> AudioOrdering.compare(a.name, b.name) }.map { file ->
                        AudioPart("manifest:$hash:${file.name}", file.name, file.name.substringAfterLast('/').substringBeforeLast('.').replace('_', ' '), sizeBytes = file.size)
                    }, delivery = "torbox") }
            val magnet = book.magnetUri.takeIf { Regex("[?&]xt=urn:btih:$hash(?:&|$)", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                ?: "magnet:?xt=urn:btih:$hash"
            if (sources.isEmpty()) null else book.copy(title = files.name, releaseTitle = files.name, sources = sources, filesVerified = true, magnetUri = magnet)
        }
    }
}

internal data class TorrentFile(val name: String, val size: Long)
internal data class TorrentFiles(val name: String, val files: List<TorrentFile>) {
    companion object {
        const val MAX_BYTES = 4_000_000

        /** Verify the original info bytes against the indexed hash before trusting names or lengths. */
        fun parse(bytes: ByteArray, expectedHash: String): TorrentFiles? = runCatching {
            require(bytes.size <= MAX_BYTES)
            val parser = Bencode(bytes)
            val root = parser.value() as Map<*, *>
            require(parser.position == bytes.size)
            val range = requireNotNull(parser.infoRange)
            val hash = MessageDigest.getInstance("SHA-1").digest(bytes.copyOfRange(range.first, range.second))
                .joinToString("") { "%02x".format(it) }
            require(hash.equals(expectedHash, true))
            val info = root["info"] as Map<*, *>
            fun text(value: Any?) = (value as ByteArray).toString(Charsets.UTF_8)
            fun safe(segment: String) = segment.isNotBlank() && segment !in setOf(".", "..") &&
                segment.none { it == '/' || it == '\\' || it.isISOControl() }
            val name = text(info["name.utf-8"] ?: info["name"])
            require(safe(name))
            val entries = info["files"] as? List<*>
            val files = if (entries == null) listOf(TorrentFile(name, info["length"] as Long)) else entries.map { entry ->
                val file = entry as Map<*, *>
                val path = (file["path.utf-8"] ?: file["path"]) as List<*>
                require(path.isNotEmpty())
                val segments = path.map(::text)
                require(segments.all(::safe))
                TorrentFile(segments.joinToString("/"), file["length"] as Long)
            }
            require(files.isNotEmpty() && files.all { it.size >= 0 } && files.map { it.name }.distinct().size == files.size)
            TorrentFiles(name, files)
        }.getOrNull()
    }
}

/** Bounds keep malformed third-party metadata from allocating unbounded collections or nesting. */
private class Bencode(private val bytes: ByteArray) {
    var position = 0
        private set
    var infoRange: Pair<Int, Int>? = null
        private set
    private var values = 0

    fun value(depth: Int = 0): Any {
        require(depth <= 20 && ++values <= 50_000 && position < bytes.size)
        return when (bytes[position].toInt().toChar()) {
            'i' -> {
                position++
                val start = position
                while (position < bytes.size && bytes[position].toInt().toChar() != 'e') position++
                require(position < bytes.size)
                val number = bytes.copyOfRange(start, position).toString(Charsets.US_ASCII)
                require(number.matches(Regex("0|-?[1-9][0-9]*")))
                position++
                number.toLong()
            }
            'l', 'd' -> {
                val dictionary = bytes[position++].toInt().toChar() == 'd'
                val list = mutableListOf<Any>()
                val map = linkedMapOf<String, Any>()
                while (position < bytes.size && bytes[position].toInt().toChar() != 'e') {
                    if (dictionary) {
                        val key = (value(depth + 1) as ByteArray).toString(Charsets.UTF_8)
                        require(key !in map)
                        val start = position
                        map[key] = value(depth + 1)
                        if (depth == 0 && key == "info") infoRange = start to position
                    } else list += value(depth + 1)
                }
                require(position < bytes.size)
                position++
                if (dictionary) map else list
            }
            in '0'..'9' -> {
                val start = position
                while (position < bytes.size && bytes[position].toInt().toChar().isDigit()) position++
                require(position < bytes.size && bytes[position++].toInt().toChar() == ':')
                val length = bytes.copyOfRange(start, position - 1).toString(Charsets.US_ASCII).toInt()
                require(length >= 0 && length <= bytes.size - position)
                bytes.copyOfRange(position, position + length).also { position += length }
            }
            else -> error("Invalid torrent metadata")
        }
    }
}
