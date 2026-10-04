package app.narrio.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import app.narrio.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import kotlin.coroutines.coroutineContext

/** Original files and normalized locators are private, durable, and independent of audio downloads. */
class FollowAlongStore(private val context: Context, private val library: LibraryDao, private val http: OkHttpClient, private val torbox: TorBoxDelivery) {
    private val root = File(context.filesDir, "follow-along")
    private val mutations = Mutex()
    private fun directory(bookId: String) = File(root, BookTextParser.fingerprint(bookId.toByteArray(Charsets.UTF_8)))

    suspend fun load(entry: BookTextEntry): BookText = withContext(Dispatchers.IO) {
        if (!entry.documentId.matches(Regex("[a-f0-9]{64}"))) throw ProviderException("Saved book text is unreadable. Choose the file again.")
        try { NarrioJson.decodeFromString<BookText>(File(directory(entry.bookId), "${entry.documentId}.json").readText()) }
        catch (_: Exception) { throw ProviderException("Saved book text is unavailable. Choose the file again to restore it.") }
    }

    suspend fun import(uri: Uri, book: Audiobook, sourceId: String, partId: String): BookText = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }.orEmpty()
        val format = textFileFormat(name) ?: when (resolver.getType(uri)) {
            "application/epub+zip" -> "EPUB"
            "text/plain" -> "TXT"
            "text/vtt" -> "VTT"
            else -> throw ProviderException("Choose an EPUB, UTF-8 .txt, or WebVTT .vtt file.")
        }
        val bytes = resolver.openInputStream(uri)?.use { BookTextParser.readBounded(it) }
            ?: throw ProviderException("This file couldn't be opened. Choose it again.")
        attach(bytes, format, book, sourceId, partId, book.title, book.author, "Imported from your device")
    }

    /** [validate] can reject the parsed text before anything replaces the current attachment. */
    suspend fun fetch(candidate: BookTextSource, book: Audiobook, sourceId: String, partId: String, validate: (BookText) -> Unit = {}): BookText = withContext(Dispatchers.IO) {
        // Resolve TorBox in memory; no credential or generated link is stored with the ebook.
        val url = when (candidate.provider) {
            "torbox" -> torbox.resolve(AudioPart(candidate.id, candidate.title, candidate.title, torrentId = candidate.torrentId, fileId = candidate.fileId))
            "torbox-cache" -> torbox.cachedTextLink(candidate.torrentHash, candidate.magnetUri, candidate.fileName)
            else -> candidate.url
        }
        if (!url.startsWith("https://")) throw ProviderException("This source has no secure book-text link. Choose a local file instead.")
        val bytes = http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw ProviderException("This book-text file is unavailable. Retry, or choose another edition.")
            BookTextParser.readBounded(response.body?.byteStream() ?: throw ProviderException("This book-text file is empty."))
        }
        attach(bytes, candidate.format, book, sourceId, partId, if (candidate.provider == "gutenberg") candidate.title else book.title,
            candidate.author.ifBlank { book.author }, candidate.attribution.ifBlank { "From this audio source" }, candidate.language, validate)
    }

    private suspend fun attach(bytes: ByteArray, format: String, book: Audiobook, sourceId: String, partId: String, title: String, author: String, attribution: String,
                               language: String = "", validate: (BookText) -> Unit = {}): BookText {
        var document = BookTextParser.parse(bytes, format, title, author, attribution)
        if (document.language.isBlank() && language.isNotBlank()) document = document.copy(language = language)
        validate(document)
        if (format == "VTT") {
            if (sourceId.isBlank() || partId.isBlank()) throw ProviderException("Start an audio part before adding its timing track.")
            document = document.copy(timedSourceId = sourceId, timedPartId = partId)
        }
        // Include timing identity in the content ID so attaching a VTT to another layout never overwrites it.
        if (format == "VTT") document = document.copy(id = BookTextParser.fingerprint((document.id + sourceId + "\n" + partId).toByteArray()))
        mutations.withLock {
            coroutineContext.ensureActive()
            val folder = directory(book.id).apply { mkdirs() }
            writeAtomic(File(folder, "${document.id}.${format.lowercase()}"), bytes)
            writeAtomic(File(folder, "${document.id}.json"), NarrioJson.encodeToString(document).toByteArray(Charsets.UTF_8))
            library.attachText(book, document.id)
            // Replacement keeps only the currently attached original and normalized document.
            folder.listFiles()?.filter { !it.name.startsWith("${document.id}.") }?.forEach { it.delete() }
        }
        return document
    }

    private fun writeAtomic(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
    }

    suspend fun bind(bookId: String, binding: TextBinding) = mutations.withLock {
        if (library.bookText(bookId)?.documentId != binding.documentId) throw ProviderException("The book text changed. Choose the chapter again.")
        library.putTextBinding(TextBindingEntry(bookId, binding.sourceId, binding.partId, NarrioJson.encodeToString(binding)))
    }

    /** Serial read/merge/write preserves a manual match made during recognition. No schema changes. */
    suspend fun mergeNarration(bookId: String, fallback: TextBinding, anchors: List<TextAnchor>, durationMs: Long): TextBinding = mutations.withLock {
        val entry = library.bookText(bookId)?.takeIf { it.documentId == fallback.documentId }
            ?: throw ProviderException("The active ebook changed during narration sync.")
        val document = load(entry)
        val latest = library.observeTextBindings(bookId).first().map { it.binding() }
            .firstOrNull { it.documentId == document.id && it.sourceId == fallback.sourceId && it.partId == fallback.partId } ?: fallback
        val merged = FollowAlongTiming.mergeAuto(document, latest, anchors, durationMs)
        val auto = merged.anchors.filter { it.auto }
        val bounded = if (auto.size <= 2048) merged else merged.copy(anchors = (merged.anchors.filterNot { it.auto } +
            (0 until 2048).map { auto[it * auto.lastIndex / 2047] }).sortedBy { it.positionMs })
        library.putTextBinding(TextBindingEntry(bookId, bounded.sourceId, bounded.partId, NarrioJson.encodeToString(bounded)))
        bounded
    }

    suspend fun remove(bookId: String) = withContext(Dispatchers.IO) { mutations.withLock {
        library.deleteBookText(bookId)
        directory(bookId).deleteRecursively()
    } }
}
