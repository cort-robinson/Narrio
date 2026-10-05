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
    suspend fun import(uri: Uri): Audiobook = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }.orEmpty()
        val format = textFileFormat(name) ?: when (resolver.getType(uri)) {
            "application/epub+zip" -> "EPUB"
            "text/plain" -> "TXT"
            else -> null
        }
        if (format !in setOf("EPUB", "TXT")) throw ProviderException("Choose a DRM-free EPUB or UTF-8 .txt book.")
        val bytes = resolver.openInputStream(uri)?.use { BookTextParser.readBounded(it) }
            ?: throw ProviderException("This file couldn't be opened. Choose it again.")
        import(bytes, format!!, name.substringBeforeLast('.').ifBlank { "Imported book" })
    }

    suspend fun import(bytes: ByteArray, format: String, fallbackTitle: String): Audiobook {
        require(format in setOf("EPUB", "TXT"))
        val document = BookTextParser.parse(bytes, format, fallbackTitle)
        val candidate = localEbookBook(document)
        val existing = library.find(candidate.id)?.book()
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
