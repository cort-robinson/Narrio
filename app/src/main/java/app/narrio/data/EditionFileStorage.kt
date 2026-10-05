package app.narrio.data

import app.narrio.domain.*
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Retains schema-4 paths. Only fingerprint IDs and supported extensions reach the filesystem. */
internal class EditionFileStorage(private val root: File) {
    private fun directory(book: String) = File(root, BookTextParser.fingerprint(book.toByteArray(Charsets.UTF_8)))
    private fun checked(id: String): String {
        require(id.matches(Regex("[a-f0-9]{64}"))) { "Saved book text is unreadable. Choose the file again." }
        return id
    }
    fun load(book: String, edition: String): BookText = NarrioJson.decodeFromString(File(directory(book), "${checked(edition)}.json").readText())
    fun original(book: String, edition: String): File? {
        checked(edition)
        return listOf("epub", "txt", "vtt").map { File(directory(book), "$edition.$it") }.firstOrNull { it.isFile }
    }
    fun save(book: String, document: BookText, bytes: ByteArray) {
        checked(document.id)
        require(document.format in setOf("EPUB", "TXT", "VTT"))
        val folder = directory(book).apply { check(mkdirs() || isDirectory) }
        atomic(File(folder, "${document.id}.${document.format.lowercase()}"), bytes)
        atomic(File(folder, "${document.id}.json"), NarrioJson.encodeToString(document).toByteArray(Charsets.UTF_8))
    }
    private fun atomic(file: File, bytes: ByteArray) {
        val pending = File(file.parentFile, "${file.name}.pending")
        try {
            java.io.FileOutputStream(pending).use { it.write(bytes); it.fd.sync() }
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { pending.delete() }
    }
    fun remove(book: String, edition: String) {
        checked(edition)
        directory(book).listFiles()?.filter { it.name.startsWith("$edition.") }?.forEach { check(it.delete() || !it.exists()) }
    }
    fun removeAll(book: String) { check(directory(book).deleteRecursively() || !directory(book).exists()) }
}
