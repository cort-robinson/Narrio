package app.narrio.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import app.narrio.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** One audio file chosen on the phone; [path] includes subfolders of a chosen folder ("Disc 1/01.mp3"). */
data class LocalAudioFile(val uri: String, val path: String, val sizeBytes: Long = 0, val durationMs: Long = 0)

/**
 * Pure rules for audio files added from the phone. They become a recording with delivery "local": played from the
 * phone through Android's document access, never uploaded, and never sent to TorBox or a catalog.
 */
object LocalAudio {
    const val DELIVERY = "local"
    const val PROVIDER = "local"

    /** Book audio only, in natural order: disc folders first, unpadded track numbers numerically. */
    fun order(files: List<LocalAudioFile>): List<LocalAudioFile> = files.filter { isBookAudioFile(it.path) }
        .distinctBy { it.uri }.sortedWith { a, b -> AudioOrdering.compare(a.path, b.path) }

    fun format(files: List<LocalAudioFile>): String {
        val formats = files.map { it.path.substringAfterLast('.').uppercase() }.distinct()
        return formats.singleOrNull()?.let { if (it == "M4B" || it == "MP3" || it == "M4A") it else "OTHER" } ?: "OTHER"
    }

    /**
     * The folder a same-named file came from, read from its URI: a file path's parent, or the path inside a document ID
     * such as "primary:Audiobooks/Disc 1/01.mp3". Null when the URI doesn't say.
     */
    fun folderHint(uri: String, name: String): String? {
        val decoded = runCatching { java.net.URLDecoder.decode(uri.replace("+", "%2B"), "UTF-8") }.getOrDefault(uri)
        val path = if (decoded.startsWith("file:")) decoded.removePrefix("file://") else decoded.substringAfterLast("/document/", "").substringAfter(':', "")
        val folder = path.substringBeforeLast('/', "").substringAfterLast('/')
        return folder.takeIf { it.isNotBlank() && path.endsWith("/$name") }?.let { "$it/$name" }
    }

    /**
     * Display paths for files picked one by one: their own names, except that same-named files gain the folder their
     * URI names, or a number when it names none.
     */
    fun paths(picked: List<Pair<String, String>>): List<String> {
        val repeated = picked.groupBy { it.second }.filterValues { it.size > 1 }.keys
        // When every file's URI names its folder, all keep it, so files from several folders stay in folder order.
        val hints = picked.map { (uri, name) -> folderHint(uri, name) }
        if (repeated.isNotEmpty() && hints.all { it != null }) return hints.map { it!! }
        return picked.mapIndexed { index, (uri, name) ->
            if (name !in repeated) name
            else folderHint(uri, name) ?: "${name.substringBeforeLast('.')} (${index + 1}).${name.substringAfterLast('.')}"
        }
    }

    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    /** Stable for the same chosen files, so adding them again keeps the same listening history. */
    fun recordingId(files: List<LocalAudioFile>) = "local:" + digest(files.map { it.uri }.sorted().joinToString("\n")).take(20)

    fun partId(file: LocalAudioFile) = "local:" + digest(file.uri).take(24)

    /** The book's recording made of [files] (already ordered). */
    fun recording(book: Audiobook, files: List<LocalAudioFile>, folder: String = ""): Audiobook {
        require(files.isNotEmpty()) { "Choose at least one audio file." }
        val id = recordingId(files)
        val parts = files.map { file ->
            AudioPart(partId(file), file.path, file.path.substringAfterLast('/').substringBeforeLast('.').replace('_', ' '),
                durationMs = file.durationMs, archiveUrl = file.uri, sizeBytes = file.sizeBytes)
        }
        val format = format(files)
        val source = AudioSource(id, if (files.size == 1) "Audio file on this phone" else "Audio files on this phone", format, parts, delivery = DELIVERY)
        val name = folder.ifBlank { if (files.size == 1) files.single().path.substringAfterLast('/') else "${files.size} audio files" }
        return book.copy(id = id, recordingId = "", provider = PROVIDER, sources = listOf(source), detailsLoaded = true,
            torrentHash = "", torrentUrl = "", magnetUri = "", cacheState = "cached", cachedFormats = listOf(format),
            releaseTitle = name, releaseSizeBytes = files.sumOf { it.sizeBytes }, seeders = 0, filesVerified = true,
            bookFilesSelected = false, sourceAddonName = "", narrator = "Narrator not listed",
            narratorFromCatalog = false, durationMs = if (parts.all { it.durationMs > 0 }) parts.sumOf { it.durationMs } else 0,
            description = book.description.ifBlank { "Audio files on this phone. They play offline and are never uploaded." })
    }
}

/**
 * Files read for one import, and the read access taken for them. Nothing is kept until the import is committed
 * ([LocalAudioImporter.commit]); [LocalAudioImporter.discard] gives the access back when it isn't.
 */
data class LocalImport(val folder: String, val files: List<LocalAudioFile>, val grants: List<String>)

/** Reads chosen phone audio through the Storage Access Framework and keeps read access across restarts. */
class LocalAudioImporter(private val context: Context, private val grants: LocalAudioGrants) {
    private val resolver: ContentResolver get() = context.contentResolver

    /** Files picked one by one (OpenMultipleDocuments). Same-named files from different folders keep their folder. */
    suspend fun fromDocuments(uris: List<Uri>): LocalImport = imported { acquired ->
        val described = uris.distinct().mapNotNull { uri ->
            coroutineContext.ensureActive()
            val (name, size) = describe(uri) ?: return@mapNotNull null
            val type = resolver.getType(uri).orEmpty()
            if (!isBookAudioFile(name) && !type.startsWith("audio/")) return@mapNotNull null
            Triple(uri, if (isAudioFile(name)) name else "$name.${type.substringAfter('/').ifBlank { "mp3" }}", size)
        }
        val paths = LocalAudio.paths(described.map { it.first.toString() to it.second })
        val files = described.mapIndexed { index, (uri, _, size) ->
            persist(uri, acquired)
            LocalAudioFile(uri.toString(), paths[index], size)
        }
        LocalImport("", probe(LocalAudio.order(files)), acquired)
    }

    /** Every audio file in a chosen folder and its subfolders (OpenDocumentTree), up to a bounded depth and count. */
    suspend fun fromFolder(tree: Uri): LocalImport = imported { acquired ->
        persist(tree, acquired)
        val root = DocumentsContract.getTreeDocumentId(tree)
        val files = mutableListOf<LocalAudioFile>()
        suspend fun walk(documentId: String, prefix: String, depth: Int) {
            if (depth > MAX_DEPTH || files.size >= MAX_FILES) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
            resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { cursor ->
                val entries = buildList { while (cursor.moveToNext()) add(Triple(cursor.getString(0), cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty()) to cursor.getLong(3)) }
                for ((entry, size) in entries) {
                    coroutineContext.ensureActive()
                    val (id, name, type) = entry
                    if (type == DocumentsContract.Document.MIME_TYPE_DIR) walk(id, "$prefix$name/", depth + 1)
                    else if (isBookAudioFile(name) && files.size < MAX_FILES)
                        files += LocalAudioFile(DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), "$prefix$name", size)
                }
            }
        }
        walk(root, "", 0)
        val folder = describe(DocumentsContract.buildDocumentUriUsingTree(tree, root))?.first.orEmpty()
        LocalImport(folder, probe(LocalAudio.order(files)), acquired)
    }

    /** Keeps the import's read access with [bookId], once its recording has been added to that book. */
    fun commit(bookId: String, import: LocalImport) = grants.add(bookId, import.grants)

    /** Gives back an import's read access that no book uses: it failed, was cancelled, or its book went away. */
    fun discard(import: LocalImport) = release(import.grants)

    /** True when every part can still be opened; false after the file moved, was deleted, or access was removed. */
    suspend fun available(source: AudioSource): Boolean = withContext(Dispatchers.IO) {
        source.parts.all { part ->
            runCatching { resolver.openFileDescriptor(Uri.parse(part.archiveUrl), "r")?.use { true } ?: false }.getOrDefault(false)
        }
    }

    /** The first 256 KB and last 512 KB of a phone file, where M4B chapter lists live. */
    fun edges(part: AudioPart): List<ByteArray> = runCatching {
        resolver.openFileDescriptor(Uri.parse(part.archiveUrl), "r")?.use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                val length = channel.size()
                listOf(0L to minOf(length, 262_144L), (length - 524_288).coerceAtLeast(0) to minOf(length, 524_288L)).map { (position, count) ->
                    val buffer = java.nio.ByteBuffer.allocate(count.toInt())
                    while (buffer.hasRemaining() && channel.read(buffer, position + buffer.position()) > 0) Unit
                    buffer.array().copyOf(buffer.position())
                }
            }
        }
    }.getOrNull().orEmpty()

    /** Gives back read access no other book uses. */
    fun forget(bookId: String) = release(grants.forget(bookId))

    private fun release(uris: Collection<String>) {
        val used = grants.used()
        uris.filter { it !in used && !owned(Uri.parse(it)) }.forEach { uri ->
            runCatching { resolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    /** Runs an import on the IO dispatcher; on failure or cancellation, the read access it took is given back. */
    private suspend fun imported(block: suspend (MutableList<String>) -> LocalImport): LocalImport = withContext(Dispatchers.IO) {
        val acquired = mutableListOf<String>()
        try { block(acquired) } catch (failure: Throwable) { release(acquired); throw failure }
    }

    /** Files in Narrio's own storage need no grant; they stay readable for as long as they exist. */
    private fun owned(uri: Uri) = uri.scheme == "file" &&
        uri.path?.let { java.io.File(it).canonicalPath.startsWith(context.filesDir.canonicalPath + "/") } == true

    /**
     * Takes lasting read access, and checks Android actually kept it. A provider that only lends access until restart
     * would leave a recording that silently stops working, so it is refused with a way forward.
     */
    private fun persist(uri: Uri, acquired: MutableList<String>) {
        if (owned(uri)) return
        runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        if (resolver.persistedUriPermissions.none { it.uri == uri && it.isReadPermission })
            throw ProviderException("Narrio can't keep access to files from this app. Copy them to your phone's storage, such as Downloads, then choose them there.")
        // Recorded as soon as it's held, so a failure or cancellation later in the import gives it back.
        acquired += uri.toString()
    }

    private fun describe(uri: Uri): Pair<String, Long>? = runCatching {
        if (uri.scheme == "file") return@runCatching uri.path?.let { java.io.File(it) }?.takeIf { it.isFile && owned(uri) }?.let { it.name to it.length() }
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) null else cursor.getString(0).orEmpty() to (if (cursor.isNull(1)) 0L else cursor.getLong(1))
        }
    }.getOrNull()

    /** Reads each file's length from its header; unreadable lengths stay unknown until playback measures them. */
    private suspend fun probe(files: List<LocalAudioFile>): List<LocalAudioFile> = files.map { file ->
        coroutineContext.ensureActive()
        val duration = runCatching {
            MediaMetadataRetriever().run {
                try { setDataSource(context, Uri.parse(file.uri)); extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0 }
                finally { release() }
            }
        }.getOrDefault(0L)
        file.copy(durationMs = duration.coerceAtLeast(0))
    }

    private companion object {
        const val MAX_DEPTH = 4
        const val MAX_FILES = 2_000
    }
}
