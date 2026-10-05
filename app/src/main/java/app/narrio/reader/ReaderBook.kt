package app.narrio.reader

import android.content.Context
import app.narrio.data.BookTextParser
import app.narrio.data.ProviderException
import app.narrio.domain.ContentCursor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.epub.pageList
import org.readium.r2.shared.publication.services.positionsByReadingOrder
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.data.ReadError
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.use
import org.readium.r2.shared.util.http.HttpClient
import org.readium.r2.shared.util.http.HttpError
import org.readium.r2.shared.util.http.HttpRequest
import org.readium.r2.shared.util.http.HttpStreamResponse
import org.readium.r2.shared.util.http.HttpTry
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.shared.util.resource.TransformingContainer
import org.readium.r2.shared.util.resource.map
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** A table-of-contents entry or page-list mark resolved to the content offset space. */
data class BookPlace(val resource: String, val offset: Int)
data class ContentsEntry(val title: String, val depth: Int, val link: Link, val place: BookPlace?)
data class PageMark(val label: String, val place: BookPlace)

/**
 * An edition opened for reading. Every resource Readium serves passes through [ReaderDocuments] first, so the
 * navigator only ever sees sanitized content whose blocks carry their parser offsets. [layout], [contents], and
 * [pages] resolve in the background (see [prepare]); until then progress falls back to Readium's own estimate.
 */
class ReaderBook private constructor(
    val bookId: String,
    val editionId: String,
    val kind: EditionKind,
    val publication: Publication,
    private val filter: ReaderResourceFilter,
) {
    val title: String get() = publication.metadata.title.orEmpty()
    val author: String get() = publication.metadata.authors.joinToString(", ") { it.name }
    val readingOrder: List<Link> get() = publication.readingOrder

    private val _layout = MutableStateFlow<EditionLayout?>(null)
    val layout: StateFlow<EditionLayout?> = _layout.asStateFlow()
    private val _contents = MutableStateFlow<List<ContentsEntry>>(emptyList())
    val contents: StateFlow<List<ContentsEntry>> = _contents.asStateFlow()
    private val _pages = MutableStateFlow<List<PageMark>>(emptyList())
    val pages: StateFlow<List<PageMark>> = _pages.asStateFlow()

    /** The parser resource name of a reading-order resource: its EPUB entry path, or "text" for plain text. */
    fun resourceName(link: Link): String = if (kind == EditionKind.TXT) TxtEpub.RESOURCE else link.url().path.orEmpty()

    /** The reading-order resource holding [offset] of [resource]. */
    fun linkFor(resource: String, offset: Int): Link? = when (kind) {
        EditionKind.TXT -> readingOrder.lastOrNull { (TxtEpub.fileStart(it.url().path.orEmpty()) ?: Int.MAX_VALUE) <= offset } ?: readingOrder.firstOrNull()
        EditionKind.EPUB -> readingOrder.firstOrNull { it.url().path == resource }
    }

    fun linkFor(href: Url): Link? = readingOrder.firstOrNull { it.url().removeFragment().isEquivalent(href.removeFragment()) }

    /** The text index of [link], preparing the resource if it hasn't been served yet. */
    suspend fun index(link: Link): ResourceTextIndex? {
        val path = link.url().path ?: return null
        filter.cached(path)?.let { return it }
        withContext(Dispatchers.IO) { publication.get(link)?.use { it.read() } }
        return filter.cached(path) ?: filter.lastIndex(path)
    }

    /** A Readium locator that lands on exactly [cursor]'s character. */
    suspend fun locator(cursor: ContentCursor): Locator? {
        val link = linkFor(cursor.resource, cursor.offset) ?: return null
        val type = link.mediaType ?: MediaType.XHTML
        val index = index(link) ?: return Locator(link.url(), type, locations = Locator.Locations(progression = 0.0))
        val quote = CursorMapping.quote(index, cursor.offset)
        val start = index.blocks.firstOrNull()?.offset ?: 0
        val progression = if (index.length <= start) 0.0 else ((cursor.offset - start).toDouble() / (index.length - start)).coerceIn(0.0, 1.0)
        return Locator(link.url(), type, title = link.title,
            locations = Locator.Locations(progression = progression, otherLocations = quote?.let { mapOf("cssSelector" to it.selector) } ?: emptyMap()),
            text = quote?.let { Locator.Text(it.before, it.highlight, it.after) } ?: Locator.Text())
    }

    fun cursor(resource: String, offset: Int, locatorJson: String = ""): ContentCursor =
        ContentCursor(editionId, resource, offset, 1, _layout.value?.progression(resource, offset) ?: 0.0, locatorJson)

    /** Compares two places in reading order; null when either resource isn't in this edition. */
    fun compare(a: BookPlace, b: BookPlace): Int? {
        if (a.resource == b.resource) return a.offset.compareTo(b.offset)
        val order = readingOrder.map(::resourceName)
        val first = order.indexOf(a.resource).takeIf { it >= 0 } ?: return null
        val second = order.indexOf(b.resource).takeIf { it >= 0 } ?: return null
        return first.compareTo(second)
    }

    /**
     * Prepares the whole edition off the main thread: Readium's positions (which the navigator otherwise computes
     * synchronously), every resource's text summary, and from those the edition layout, contents, and page list.
     */
    @OptIn(ExperimentalReadiumApi::class)
    suspend fun prepare() = withContext(Dispatchers.IO) {
        publication.positionsByReadingOrder()
        for (link in readingOrder) if (filter.summary(link.url().path.orEmpty()) == null) publication.get(link)?.use { it.read() }
        val lengths = HashMap<String, Int>()
        for (link in readingOrder) {
            val summary = filter.summary(link.url().path.orEmpty()) ?: continue
            val resource = resourceName(link)
            lengths[resource] = maxOf(lengths[resource] ?: 0, summary.length)
        }
        _layout.value = EditionLayout(readingOrder.map(::resourceName), lengths)
        fun place(link: Link): BookPlace? {
            val target = linkFor(link.url()) ?: return null
            val summary = filter.summary(target.url().path.orEmpty())
            val fragment = link.url().fragment
            val offset = when {
                fragment != null -> summary?.anchors?.get(fragment)
                kind == EditionKind.TXT -> TxtEpub.fileStart(target.url().path.orEmpty())
                else -> 0
            } ?: summary?.firstOffset ?: 0
            return BookPlace(resourceName(target), offset)
        }
        fun flatten(links: List<Link>, depth: Int): List<ContentsEntry> = links.flatMap { link ->
            listOf(ContentsEntry(link.title?.trim().orEmpty().ifBlank { "Untitled" }, depth, link, place(link))) + flatten(link.children, depth + 1)
        }
        _contents.value = flatten(publication.tableOfContents, 0).ifEmpty {
            readingOrder.mapIndexed { index, link -> ContentsEntry(link.title ?: "Part ${index + 1}", 0, link, place(link)) }
        }
        _pages.value = publication.pageList.mapNotNull { link -> place(link)?.let { PageMark(link.title?.trim().orEmpty(), it) } }.filter { it.label.isNotEmpty() }
    }

    companion object {
        /**
         * Opens an edition's original file. EPUB opens directly; plain text is converted once into
         * [cacheDirectory]. Network access is refused: the reader never fetches publication content.
         */
        suspend fun open(context: Context, bookId: String, editionId: String, file: File, format: String, title: String, author: String, cacheDirectory: File): ReaderBook =
            withContext(Dispatchers.IO) {
                val kind = if (format.equals("TXT", true)) EditionKind.TXT else EditionKind.EPUB
                val epub = if (kind == EditionKind.TXT) {
                    val converted = File(cacheDirectory, "$editionId.epub")
                    if (!converted.exists()) {
                        cacheDirectory.mkdirs()
                        val bytes = TxtEpub.convert(BookTextParser.readBounded(file.inputStream()), title, author)
                        val partial = File(cacheDirectory, "$editionId.part")
                        partial.writeBytes(bytes)
                        if (!partial.renameTo(converted)) throw ProviderException("This text couldn't be prepared for reading.")
                    }
                    converted
                } else file
                val retriever = AssetRetriever(context.contentResolver, OfflineHttpClient)
                val opener = PublicationOpener(DefaultPublicationParser(context, OfflineHttpClient, retriever, pdfFactory = null))
                val asset = retriever.retrieve(epub).getOrElse { throw ProviderException("This ebook couldn't be opened. Choose another edition.") }
                val filter = ReaderResourceFilter(kind)
                val publication = opener.open(asset, allowUserInteraction = false, onCreatePublication = {
                    val types = (manifest.readingOrder + manifest.resources).associate { it.url().path to it.mediaType }
                    container = TransformingContainer(container) { url, resource -> filter.transform(url, types[url.path], resource) }
                }).getOrElse { asset.close(); throw ProviderException("This ebook couldn't be opened. It may be damaged or use an unsupported format.") }
                if (publication.readingOrder.isEmpty()) { publication.close(); throw ProviderException("This ebook has no readable chapters.") }
                ReaderBook(bookId, editionId, kind, publication, filter)
            }
    }
}

/** Refuses every request, so neither the parser nor the navigator can reach the network. */
private object OfflineHttpClient : HttpClient {
    override suspend fun stream(request: HttpRequest): HttpTry<HttpStreamResponse> =
        Try.failure(HttpError.Unreachable(org.readium.r2.shared.util.DebugError("Narrio's reader doesn't load remote content.")))
}

/** What the background pass keeps for every resource: its length and id anchors, not its text. */
internal class ResourceSummary(val length: Int, val anchors: Map<String, Int>, val firstOffset: Int?)

/** Applies [ReaderDocuments] to every resource and keeps recent chapter indexes for cursor mapping. */
internal class ReaderResourceFilter(private val kind: EditionKind) {
    private val recent = object : LinkedHashMap<String, ResourceTextIndex>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ResourceTextIndex>) = size > 8
    }
    private val summaries = ConcurrentHashMap<String, ResourceSummary>()
    @Volatile private var last: Pair<String, ResourceTextIndex>? = null

    fun cached(path: String): ResourceTextIndex? = synchronized(recent) { recent[path] }
    fun lastIndex(path: String): ResourceTextIndex? = last?.takeIf { it.first == path }?.second
    fun summary(path: String): ResourceSummary? = summaries[path]

    fun transform(url: Url, mediaType: MediaType?, resource: Resource): Resource {
        val path = url.path ?: return resource
        val extension = path.substringAfterLast('.', "").lowercase()
        return when {
            mediaType?.matches(MediaType.XHTML) == true || mediaType == null && extension in setOf("xhtml", "xht") -> chapter(path, resource, xhtml = true)
            mediaType?.matches(MediaType.HTML) == true || mediaType == null && extension in setOf("html", "htm") -> chapter(path, resource, xhtml = false)
            mediaType?.matches(MediaType.SVG) == true || mediaType == null && extension == "svg" -> safely(resource) { ReaderDocuments.prepareSvg(it) }
            mediaType?.matches(MediaType.CSS) == true || mediaType == null && extension == "css" -> safely(resource) { ReaderDocuments.prepareCss(it) }
            // Scripts never reach a page; serve nothing for them.
            mediaType?.matches(MediaType.JAVASCRIPT) == true || extension in setOf("js", "mjs") -> resource.map { Try.success(ByteArray(0)) }
            else -> resource
        }
    }

    private fun chapter(path: String, resource: Resource, xhtml: Boolean): Resource = safely(resource) { bytes ->
        val resourceName = if (kind == EditionKind.TXT) TxtEpub.RESOURCE else path
        val prepared = ReaderDocuments.prepareChapter(bytes, resourceName, xhtml, kind)
        prepared.index?.let { index ->
            synchronized(recent) { recent[path] = index }
            last = path to index
            summaries[path] = ResourceSummary(index.length, index.anchors, index.blocks.firstOrNull()?.offset)
        }
        prepared.bytes
    }

    /** A resource the filter can't process is withheld rather than served unfiltered. */
    private fun safely(resource: Resource, transform: (ByteArray) -> ByteArray): Resource = resource.map { bytes ->
        try { Try.success(transform(bytes)) }
        catch (error: Exception) { Try.failure(ReadError.Decoding(org.readium.r2.shared.util.ThrowableError(error))) }
    }
}
