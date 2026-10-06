package app.narrio.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.narrio.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Creates a source-free shelf book before optional catalog enrichment. No network is needed to import. */
class LocalEbookImporter(
    private val context: Context,
    private val library: LibraryDao,
    private val storage: FollowAlongStore,
    private val enrich: suspend (Audiobook) -> Audiobook = { it },
) {
    private suspend fun read(uri: Uri): Triple<ByteArray, String, String> = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }.orEmpty().ifBlank { uri.lastPathSegment.orEmpty() }
            val format = textFileFormat(name) ?: when (resolver.getType(uri)) {
                "application/epub+zip" -> "EPUB"
                "text/plain" -> "TXT"
                else -> null
            }
            if (format !in setOf("EPUB", "TXT")) throw EbookImportException(EbookImportFailure.UNSUPPORTED,
                unsupportedEbook(name))
            val bytes = resolver.openInputStream(uri)?.use { BookTextParser.readBounded(it) }
                ?: throw EbookImportException(EbookImportFailure.UNREADABLE, "This file couldn't be opened. Choose it again.")
            Triple(bytes, format!!, name.substringBeforeLast('.').ifBlank { "Imported book" })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: EbookImportException) { throw error }
        catch (error: Exception) { throw EbookImportException(EbookImportFailure.UNREADABLE, error.message ?: "This file couldn't be opened.") }
    }
    suspend fun import(uri: Uri): Audiobook = importResult(uri).book
    suspend fun importResult(uri: Uri): EbookImportResult {
        val (bytes, format, title) = read(uri)
        val doc = parse(bytes, format, title)
        val existing = library.bookWithEdition(doc.id) ?: library.find(localEbookBook(doc).id)
        return EbookImportResult(import(bytes, format, title), created = existing == null)
    }
    suspend fun importEdition(uri: Uri, book: Audiobook): EbookEdition {
        val (bytes, format, _) = read(uri)
        parse(bytes, format, book.title)
        val document = storage.importLocal(bytes, format, book)
        return storage.editions(book.id).first { it.id == document.id }
    }
    private fun parse(bytes: ByteArray, format: String, title: String): BookText = try {
        BookTextParser.parse(bytes, format, title)
    } catch (error: EbookImportException) { throw error }
    catch (error: Exception) { throw EbookImportException(EbookImportFailure.UNREADABLE, error.message ?: "This book is unreadable.") }

    suspend fun import(bytes: ByteArray, format: String, fallbackTitle: String): Audiobook {
        require(format in setOf("EPUB", "TXT"))
        val document = parse(bytes, format, fallbackTitle)
        val candidate = localEbookBook(document)
        val existing = library.bookWithEdition(document.id)?.book() ?: library.find(candidate.id)?.book()
        val book = existing ?: candidate
        storage.importLocal(bytes, format, book)
        // Failure leaves the imported edition and OPF metadata usable. Keep the current identity and audio.
        val details = try { enrich(book) } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { book }
        library.updateBookDetails(details)
        return library.find(book.id)!!.book()
    }
}

internal fun localEbookBook(document: BookText): Audiobook = Audiobook(
    id = "catalog:${BookIdentity.key(document.title, document.author)}", title = document.title, author = document.author,
    language = document.language, provider = "local", detailsLoaded = true,
)

enum class EbookImportFailure { UNSUPPORTED, DRM_PROTECTED, UNREADABLE }
class EbookImportException(val reason: EbookImportFailure, message: String) : java.io.IOException(message)
data class EbookImportResult(val book: Audiobook, val created: Boolean)

/** Says which formats work and, for a recognizable ebook format, why this one doesn't. */
fun unsupportedEbook(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "mobi", "azw", "azw3", "kfx" -> "Kindle files (MOBI, AZW3) can't be opened. Choose an EPUB or a UTF-8 .txt file."
    "pdf" -> "PDF reading isn't available yet. Choose an EPUB or a UTF-8 .txt file."
    "cbz", "cbr" -> "Comic archives can't be opened. Choose an EPUB or a UTF-8 .txt file."
    "vtt" -> "That's a timing track, not an ebook. Choose an EPUB or text file."
    else -> "Choose an EPUB or a UTF-8 .txt ebook."
}
