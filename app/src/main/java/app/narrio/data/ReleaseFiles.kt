package app.narrio.data

import app.narrio.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString

/**
 * Choosing files inside a release: which audio files play, and in what order.
 *
 * A choice is saved as file keys for one book, release, and format (see [key]): a phone file's part ID, which comes from
 * its content URI, or a release file's canonical path, because the same release file has a different part ID before and
 * after TorBox prepares the release. Playback and phone downloads apply the choice to whatever layout of that release
 * arrives (an indexed cache listing, a prepared TorBox download, a public recording, or phone files) by keeping and
 * ordering the matching files. The layout's source ID and every part ID are unchanged, so listening positions,
 * bookmarks, read-along timing, and downloaded files stay attached to the same files. A choice never plays files the
 * listener didn't choose: when a chosen file can't be found, playback stops and names it.
 */
object FileChoices {
    private val hash = Regex("[a-f0-9]{40}")

    /** The release a recording comes from, independent of which of its books or files were chosen. */
    fun releaseKey(recording: Audiobook): String = recording.torrentHash.lowercase().takeIf { it.matches(hash) }
        ?: recording.recordingId.ifBlank { recording.id }.substringBefore(":book:")

    fun key(bookId: String, recording: Audiobook, source: AudioSource) = "$bookId|${releaseKey(recording)}|${source.format.uppercase()}"

    /** Release paths compare without a leading "./", repeated slashes, or Windows separators. */
    fun canonical(path: String): String = path.replace('\\', '/').replace(Regex("/{2,}"), "/").removePrefix("./").trim('/')

    /** A phone file is its part ID (from its content URI, so same-named files in different folders differ); others, their path. */
    fun fileKey(part: AudioPart): String = if (part.id.startsWith("local:")) part.id else canonical(part.name)

    /**
     * The part [key] names. A path also matches when an account listing adds or drops the release folder, but only when
     * exactly one file matches that way.
     */
    fun locate(parts: List<AudioPart>, key: String): AudioPart? {
        parts.firstOrNull { fileKey(it) == key }?.let { return it }
        if (key.startsWith("local:")) return null
        val path = canonical(key)
        parts.firstOrNull { fileKey(it) == path }?.let { return it }
        return parts.filter { part -> canonical(part.name).let { it.endsWith("/$path") || path.endsWith("/$it") } }.singleOrNull()
    }

    /** Disc folders and unpadded numbers in order, as the release's own layouts are. */
    fun natural(parts: List<AudioPart>): List<AudioPart> = parts.sortedWith { a, b -> AudioOrdering.compare(a.name, b.name) }

    /** [source] limited to [keys], in that order; null unless every key is in it. */
    fun apply(source: AudioSource, keys: List<String>): AudioSource? {
        if (keys.isEmpty() || missing(source, keys).isNotEmpty()) return null
        return source.copy(parts = keys.mapNotNull { locate(source.parts, it) }.distinctBy { it.id })
    }

    fun missing(source: AudioSource, keys: List<String>) = keys.filter { locate(source.parts, it) == null }

    /** "Only this book's files": what automatic matching keeps from a bundle, else every file, in natural order. */
    fun bookFiles(book: Audiobook, recording: Audiobook, full: AudioSource): List<String> {
        val names = SourceQuality.bookFileNames(book, recording, full)
        return natural(full.parts).filter { part -> names == null || part.name in names }.map(::fileKey)
    }

    /** The files to show: chosen ones in their chosen order, then the rest in natural order. */
    fun arrange(full: AudioSource, chosen: List<String>): List<AudioPart> {
        val first = chosen.mapNotNull { locate(full.parts, it) }.distinctBy { it.id }
        return first + natural(full.parts).filter { it !in first }
    }

    /** Includes files the given layout lacks (another book of a bundle, say) from a full listing of the release. */
    fun merge(source: AudioSource, full: AudioSource): AudioSource =
        source.copy(parts = full.parts + source.parts.filter { part -> full.parts.none { fileKey(it) == fileKey(part) } })

    /** Plain wording for chosen files that can't be found, naming a few of them. */
    fun unavailable(keys: List<String>, known: List<AudioPart>): String {
        val names = keys.map { key -> known.firstOrNull { fileKey(it) == key }?.name ?: key }.map { it.substringAfterLast('/') }
        val shown = names.take(3).joinToString() + if (names.size > 3) " and ${names.size - 3} more" else ""
        return "Some files you chose for this recording can't be found right now ($shown). Try again, or choose its files again in Advanced."
    }
}

/** The complete file list of each phone recording, kept so files left out of a choice can be chosen again. */
class LocalManifests(private val values: TextValues) {
    fun put(bookId: String, source: AudioSource) = values.put(key(bookId), NarrioJson.encodeToString(all(bookId) + (source.id to source)))
    fun get(bookId: String, sourceId: String): AudioSource? = all(bookId)[sourceId]
    fun forget(bookId: String) = values.put(key(bookId), null)
    private fun all(bookId: String): Map<String, AudioSource> =
        values.get(key(bookId))?.let { runCatching { NarrioJson.decodeFromString<Map<String, AudioSource>>(it) }.getOrNull() }.orEmpty()
    private fun key(bookId: String) = "localManifest.v1:$bookId"
}

/** Reads a release's complete file list and applies saved file choices for playback and downloads. */
class ReleaseFiles(
    private val store: FileSelectionStore,
    private val torbox: TorBoxDelivery?,
    private val library: TorBoxLibrary?,
    private val archive: RecordingDiscovery?,
    private val torrentFiles: TorrentFileDiscovery?,
    private val manifests: LocalManifests? = null,
) {
    fun chosen(bookId: String, recording: Audiobook, source: AudioSource): List<String>? = store.get(FileChoices.key(bookId, recording, source))

    fun save(bookId: String, recording: Audiobook, source: AudioSource, keys: List<String>?) =
        store.set(FileChoices.key(bookId, recording, source), keys)

    /** Removing a book from the shelf removes its file choices and phone file lists. */
    fun forget(bookId: String) {
        store.forget(bookId)
        manifests?.forget(bookId)
    }

    /**
     * Every audio file of [source]'s format in its release, using the same source and part IDs as [source]. Book-file
     * narrowing is ignored, so the listener can include files automatic matching left out; a phone recording uses the
     * file list it was added with.
     */
    suspend fun fullLayout(recording: Audiobook, source: AudioSource): AudioSource {
        val bare = recording.copy(sources = emptyList(), bookFilesSelected = false)
        val kind = TorBoxKind.of(source.id)
        val layouts: List<AudioSource> = when {
            source.delivery == LocalAudio.DELIVERY -> listOfNotNull(manifests?.get(recording.id, source.id))
            source.delivery == "archive" -> archive?.recording(recording.recordingId.ifBlank { recording.id }.substringBefore(":book:"))?.sources.orEmpty()
            (source.torrentId ?: 0) > 0 && (kind != TorBoxKind.TORRENT || source.id.startsWith("torbox:")) ->
                library?.sources(kind, source.torrentId!!, bare).orEmpty()
            source.id.startsWith("cache:") -> torbox?.checkCached(listOf(bare.copy(provider = "knaben")))?.firstOrNull()?.sources.orEmpty()
            source.id.startsWith("manifest:") -> torrentFiles?.recording(bare)?.sources.orEmpty()
            else -> emptyList()
        }
        val full = layouts.firstOrNull { it.id == source.id } ?: layouts.firstOrNull { it.format.equals(source.format, true) } ?: return source
        return FileChoices.merge(source, full.copy(id = source.id, label = source.label, delivery = source.delivery))
    }

    /**
     * [source] as the listener chose its files for [recording] (the book's adopted recording), or unchanged without a
     * choice. Files the layout lacks are looked up in the full release once. It fails closed: if any chosen file still
     * can't be found, it throws [ProviderException] naming them rather than playing other files.
     */
    suspend fun apply(recording: Audiobook, source: AudioSource): AudioSource {
        val keys = chosen(recording.id, recording, source) ?: return source
        FileChoices.apply(source, keys)?.let { return it }
        val full = try { fullLayout(recording, source) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { throw ProviderException(FileChoices.unavailable(FileChoices.missing(source, keys), source.parts)) }
        return FileChoices.apply(full, keys) ?: throw ProviderException(FileChoices.unavailable(FileChoices.missing(full, keys), full.parts))
    }
}
