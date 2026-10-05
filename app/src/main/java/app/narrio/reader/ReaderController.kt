package app.narrio.reader

import android.graphics.RectF
import androidx.annotation.ColorInt
import app.narrio.domain.ContentCursor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.HyperlinkNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.util.AbsoluteUrl

/** A content range in one edition; [end] is exclusive. */
data class CursorRange(val start: ContentCursor, val end: ContentCursor)

/** A decoration drawn over a content range, such as a narrated sentence or a highlight. */
data class TextDecoration(val id: String, val range: CursorRange, val style: Style = Style.HIGHLIGHT, @ColorInt val tint: Int, val active: Boolean = false) {
    enum class Style { HIGHLIGHT, UNDERLINE }
}

/** The page on screen: its first visible character and the first character after it (null at a resource end). */
data class VisibleRange(val first: ContentCursor, val end: ContentCursor?) {
    /** True when [cursor] is shown on this page. */
    fun contains(cursor: ContentCursor): Boolean = cursor.resource == first.resource && cursor.offset >= first.offset &&
        (end == null || end.resource != cursor.resource || cursor.offset < end.offset)
}

sealed interface ReaderEvent {
    /** The reader turned, scrolled, or jumped. [jump] is true for contents, scrubber, and link navigation. */
    data class Moved(val cursor: ContentCursor, val jump: Boolean) : ReaderEvent
    data class Footnote(val html: String, val target: Link) : ReaderEvent
    data class ExternalLink(val url: AbsoluteUrl) : ReaderEvent
    data class Image(val element: Content.ImageElement) : ReaderEvent
    data class DecorationActivated(val group: String, val id: String, val rect: RectF?) : ReaderEvent
    /** A tap away from the page-turn edges: show or hide the reader controls. */
    data object ToggleControls : ReaderEvent
}

/**
 * The reader as a reusable component: one opened [ReaderBook] shown by [EpubReaderView].
 *
 * - [cursor] is the reader's place: the first character the reader saw after their own navigation. Relayouts
 *   (rotation, folding, font changes) restore it instead of replacing it, so repeated changes never drift.
 * - [visible] is the page on screen right now; [events] reports navigation and taps.
 * - [goTo] moves programmatically; [follow] keeps a cursor on screen while [following] is on and stops following
 *   when the reader turns a page themselves (together mode's narration auto-page-turn).
 * - [setDecorations] draws ranges by content cursor; [selection] returns the selected range as cursors.
 */
@OptIn(ExperimentalReadiumApi::class)
class ReaderController(val book: ReaderBook, private val scope: CoroutineScope) :
    EpubNavigatorFragment.Listener, DecorableNavigator.Listener {

    private val _cursor = MutableStateFlow<ContentCursor?>(null)
    val cursor: StateFlow<ContentCursor?> = _cursor.asStateFlow()
    private val _visible = MutableStateFlow<VisibleRange?>(null)
    val visible: StateFlow<VisibleRange?> = _visible.asStateFlow()
    private val _following = MutableStateFlow(false)
    val following: StateFlow<Boolean> = _following.asStateFlow()
    private val _ready = MutableStateFlow(false)
    /** True once the first page has been laid out. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()
    private val _events = MutableSharedFlow<ReaderEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<ReaderEvent> = _events.asSharedFlow()
    /** Where the last jump came from, for "Back to …"; cleared by [goBack] or two page turns. */
    private val _returnPoint = MutableStateFlow<ContentCursor?>(null)
    val returnPoint: StateFlow<ContentCursor?> = _returnPoint.asStateFlow()

    internal var navigator: EpubNavigatorFragment? = null
        private set
    private var programmaticUntil = 0L
    private var jumpPending = false
    private var restorePending = false
    private var settleJob: Job? = null
    private var restoreJob: Job? = null
    private var turnsSinceJump = 0
    private var restoreAttempts = 0
    /** Where the last [goTo] should land; re-sent if the navigator settles elsewhere (its pager can override a jump). */
    private var target: ContentCursor? = null
    private var targetAttempts = 0
    private var opening = false
    private val decorationGroups = mutableMapOf<String, List<TextDecoration>>()

    /** Sets the initial place without treating it as reading activity. */
    fun restore(cursor: ContentCursor?) { _cursor.value = cursor }

    // Navigation

    /** Moves to [cursor]. Programmatic moves don't count as reading and don't change [cursor] unless [asReader]. */
    suspend fun goTo(cursor: ContentCursor, animated: Boolean = false, asReader: Boolean = false): Boolean {
        val locator = book.locator(cursor) ?: return false
        val navigator = navigator ?: run { if (asReader) _cursor.value = cursor; return false }
        if (asReader) { _cursor.value?.let { if (it != cursor) _returnPoint.value = it }; jumpPending = true; turnsSinceJump = 0 }
        else markProgrammatic()
        target = cursor; targetAttempts = 2
        return navigator.go(locator, animated)
    }

    /** A jump the reader asked for: contents, scrubber, or "Back to". */
    fun jumpTo(cursor: ContentCursor) { scope.launch { goTo(cursor, asReader = true) } }

    fun jumpTo(place: BookPlace) = jumpTo(book.cursor(place.resource, place.offset))

    /** Follows a publication link (a footnote's "Go to note") as the reader's own jump. */
    fun jumpTo(link: Link) {
        val navigator = navigator ?: return
        _cursor.value?.let { _returnPoint.value = it }
        jumpPending = true; turnsSinceJump = 0
        navigator.go(link, animated = false)
    }

    fun goBack() { _returnPoint.value?.let { target -> _returnPoint.value = null; scope.launch { goTo(target, asReader = true); _returnPoint.value = null } } }

    fun next(animated: Boolean = true) { navigator?.goForward(animated) }
    fun previous(animated: Boolean = true) { navigator?.goBackward(animated) }

    // Together mode hooks

    fun startFollowing() { _following.value = true }
    fun stopFollowing() { _following.value = false }

    /** While [following], turns the page so [cursor] is visible. Returns true when it moved. */
    suspend fun follow(cursor: ContentCursor, animated: Boolean = true): Boolean {
        if (!_following.value) return false
        if (_visible.value?.contains(cursor) == true) return false
        return goTo(cursor, animated)
    }

    // Decorations and selection

    suspend fun setDecorations(group: String, decorations: List<TextDecoration>) {
        decorationGroups[group] = decorations
        val navigator = navigator ?: return
        navigator.applyDecorations(decorations.flatMap { readiumDecorations(it) }, group)
        navigator.addDecorationListener(group, this)
    }

    private suspend fun readiumDecorations(decoration: TextDecoration): List<Decoration> {
        val start = decoration.range.start
        val end = decoration.range.end
        val links = book.readingOrder.filter { book.resourceName(it) == start.resource || book.resourceName(it) == end.resource }
        val style = when (decoration.style) {
            TextDecoration.Style.HIGHLIGHT -> Decoration.Style.Highlight(decoration.tint, decoration.active)
            TextDecoration.Style.UNDERLINE -> Decoration.Style.Underline(decoration.tint, decoration.active)
        }
        var part = 0
        return links.flatMap { link ->
            val resource = book.resourceName(link)
            val index = book.index(link) ?: return@flatMap emptyList()
            val from = if (resource == start.resource) start.offset else 0
            val to = if (resource == end.resource) end.offset else index.length
            CursorMapping.rangeQuotes(index, from, to).map { quote ->
                Decoration("${decoration.id}#${part++}", Locator(link.url(), link.mediaType ?: org.readium.r2.shared.util.mediatype.MediaType.XHTML,
                    locations = Locator.Locations(otherLocations = mapOf("cssSelector" to quote.selector)),
                    text = Locator.Text(quote.before, quote.highlight, quote.after)), style)
            }
        }
    }

    override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean =
        _events.tryEmit(ReaderEvent.DecorationActivated(event.group, event.decoration.id.substringBeforeLast('#'), event.rect))

    /** The selected text as a content range, or null without a selection. */
    suspend fun selection(): CursorRange? {
        val navigator = navigator ?: return null
        val json = runCatching { navigator.evaluateJavascript(ReaderScripts.SELECTION) }.getOrNull()?.takeIf { it != "null" } ?: return null
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val link = book.linkFor(navigator.currentLocator.value.href) ?: return null
        val index = book.index(link) ?: return null
        fun cursor(position: JSONObject?) = position?.let { book.cursor(index.resource, CursorMapping.offset(index, it.position())) }
        return CursorRange(cursor(obj.optJSONObject("start")) ?: return null, cursor(obj.optJSONObject("end")) ?: return null)
    }

    /** Runs [script] in the page on screen; for diagnostics and tests. */
    internal suspend fun evaluate(script: String): String? = navigator?.evaluateJavascript(script)

    // Navigator binding

    internal fun attach(fragment: EpubNavigatorFragment) {
        navigator = fragment
        // The first layout settles through several locators; none of them is the reader's own navigation.
        markProgrammatic(4_000)
        opening = true
        restoreAttempts = if (_cursor.value != null) 3 else 0
        fragment.addInputListener(object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
                (event.targetElement?.content as? Content.ImageElement)?.let { _events.tryEmit(ReaderEvent.Image(it)); return true }
                val width = fragment.publicationView.width.toFloat()
                val edge = maxOf(width * .3f, 1f)
                val scroll = fragment.overflow.value.scroll
                if (scroll || event.point.x in edge..(width - edge)) { _events.tryEmit(ReaderEvent.ToggleControls); return true }
                return false
            }
        })
        fragment.addInputListener(org.readium.r2.navigator.util.DirectionalNavigationAdapter(fragment, animatedTransition = true))
        scope.launch {
            fragment.currentLocator.collect { settled() }
        }
        scope.launch { decorationGroups.forEach { (group, decorations) -> setDecorations(group, decorations) } }
    }

    internal fun detach() { navigator = null; settleJob?.cancel(); restoreJob?.cancel(); _ready.value = false }

    /** The page was laid out again (window size, fold, preferences): put the reader's place back on screen. */
    fun relayout() {
        if (navigator == null || _cursor.value == null) return
        restorePending = true
        restoreJob?.cancel()
        restoreJob = scope.launch { delay(450); if (restorePending) restoreNow() }
    }

    private suspend fun restoreNow() {
        restorePending = false
        val target = _cursor.value ?: return
        restoreAttempts = 3
        goTo(target)
        scheduleProbe(350)
    }

    /** Layout changes keep settling after a restore; for this long, page changes are the navigator's, not the reader's. */
    private fun markProgrammatic(windowMs: Long = 1_500) { programmaticUntil = maxOf(programmaticUntil, System.currentTimeMillis() + windowMs) }

    private fun settled() = scheduleProbe(60)

    private fun scheduleProbe(delayMs: Long) {
        settleJob?.cancel()
        settleJob = scope.launch {
            delay(delayMs)
            if (restorePending) { restoreNow(); return@launch }
            val navigator = navigator ?: return@launch
            val json = runCatching { navigator.evaluateJavascript(ReaderScripts.VISIBLE) }.getOrNull() ?: return@launch
            val obj = runCatching { JSONObject(json) }.getOrNull() ?: return@launch
            val link = book.linkFor(navigator.currentLocator.value.href) ?: return@launch
            val index = book.index(link) ?: return@launch
            val firstPosition = obj.optJSONObject("first")?.position() ?: PagePosition(null, 0)
            val first = book.cursor(index.resource, if (firstPosition.blockOffset == null) index.blocks.firstOrNull()?.offset ?: 0 else CursorMapping.offset(index, firstPosition))
            val end = obj.optJSONObject("last")?.position()?.let { book.cursor(index.resource, CursorMapping.offset(index, it)) }
            val previous = _visible.value?.first
            val range = VisibleRange(first, end)
            _visible.value = range
            target?.let { wanted ->
                if (!range.contains(wanted) && targetAttempts > 0) {
                    targetAttempts--
                    if (System.currentTimeMillis() < programmaticUntil) markProgrammatic()
                    book.locator(wanted)?.let { navigator.go(it) }
                    scheduleProbe(350)
                    return@launch
                }
                target = null
            }
            if (_cursor.value == null) _cursor.value = first
            _ready.value = true
            if (opening) { opening = false; programmaticUntil = System.currentTimeMillis() + 700 }
            if (System.currentTimeMillis() < programmaticUntil) {
                // The navigator may adjust the page after a restore (late reflow); put the reader's place back.
                val anchor = _cursor.value
                if (anchor != null && restoreAttempts > 0 && !VisibleRange(first, end).contains(anchor)) {
                    restoreAttempts--
                    goTo(anchor)
                    scheduleProbe(350)
                }
                return@launch
            }
            // Locator updates that leave the same page on screen aren't page turns.
            if (first == previous) return@launch
            val jump = jumpPending
            jumpPending = false
            if (!jump && ++turnsSinceJump >= 2) _returnPoint.value = null
            _cursor.value = first
            _following.value = false
            _events.tryEmit(ReaderEvent.Moved(first, jump))
        }
    }

    private fun JSONObject.position() = PagePosition(if (isNull("o")) null else optInt("o"), optInt("r"))

    // Readium listener

    override fun shouldFollowInternalLink(link: Link, context: HyperlinkNavigator.LinkContext?): Boolean {
        if (context is HyperlinkNavigator.FootnoteContext) { _events.tryEmit(ReaderEvent.Footnote(context.noteContent, link)); return false }
        _cursor.value?.let { _returnPoint.value = it }
        jumpPending = true; turnsSinceJump = 0
        return true
    }

    override fun onExternalLinkActivated(url: AbsoluteUrl) { _events.tryEmit(ReaderEvent.ExternalLink(url)) }
}
