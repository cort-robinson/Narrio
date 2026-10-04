package app.narrio.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.narrio.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import kotlin.coroutines.coroutineContext

/** Original files and normalized locators are private, durable, and independent of audio downloads. */
class FollowAlongStore(private val context: Context, private val library: LibraryDao, private val http: OkHttpClient, private val torbox: TorBoxDelivery) : EditionFiles {
    private val root = File(context.filesDir, "follow-along")
    private val mutations = Mutex()
    private val files = EditionFileStorage(root)

    internal suspend fun importLocal(bytes: ByteArray, format: String, book: Audiobook): BookText = withContext(Dispatchers.IO) {
        attach(bytes, format, book, "", "", book.title, book.author, "Imported from your device")
    }

    suspend fun load(entry: BookTextEntry): BookText = withContext(Dispatchers.IO) {
        if (!entry.documentId.matches(Regex("[a-f0-9]{64}"))) throw ProviderException("Saved book text is unreadable. Choose the file again.")
        try { files.load(entry.bookId, entry.documentId) }
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
            candidate.author.ifBlank { book.author }, candidate.attribution.ifBlank { "From this audio source" }, candidate.language, candidate.provider, validate)
    }

    private suspend fun attach(bytes: ByteArray, format: String, book: Audiobook, sourceId: String, partId: String, title: String, author: String, attribution: String,
                               language: String = "", provider: String = "local", validate: (BookText) -> Unit = {}): BookText {
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
            files.save(book.id, document, bytes)
            library.attachEdition(book, document.editionEntry(book.id, provider))
        }
        return document
    }

    override suspend fun editions(bookId: String): List<EbookEdition> = withContext(Dispatchers.IO) { mutations.withLock {
        val active = library.bookText(bookId)?.documentId
        library.editions(bookId).map { entry ->
            val hydrated = if (entry.format.isBlank()) runCatching { files.load(bookId, entry.editionId).editionEntry(bookId, entry.provider, entry.addedAtMs) }.getOrNull() else null
            if (hydrated != null) library.putEdition(hydrated)
            (hydrated ?: entry).edition(entry.editionId == active)
        }
    } }

    override suspend fun original(bookId: String, editionId: String): File? = withContext(Dispatchers.IO) {
        if (library.edition(bookId, editionId) == null) null else files.original(bookId, editionId)
    }

    suspend fun activateEdition(bookId: String, editionId: String) = mutations.withLock {
        library.activateEdition(bookId, editionId)
    }

    suspend fun removeEdition(bookId: String, editionId: String) = withContext(Dispatchers.IO) { mutations.withLock {
        library.removeEdition(bookId, editionId)
        files.remove(bookId, editionId)
    } }

    suspend fun bind(bookId: String, binding: TextBinding) = mutations.withLock {
        if (library.bookText(bookId)?.documentId != binding.documentId) throw ProviderException("The book text changed. Choose the chapter again.")
        library.putTextBinding(TextBindingEntry(bookId, binding.sourceId, binding.partId, NarrioJson.encodeToString(binding)))
    }

    suspend fun remove(bookId: String) = withContext(Dispatchers.IO) { mutations.withLock {
        library.removeText(bookId)
        files.removeAll(bookId)
    } }
}
