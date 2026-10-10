package app.narrio.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.narrio.domain.ContentCursor
import app.narrio.reader.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A highlight being edited in the sheet: an existing one, or a new note over [range] that's saved only on Save. */
data class HighlightDraft(val highlight: Highlight?, val range: CursorRange, val text: String, val color: HighlightColor, val note: String, val focusNote: Boolean)

/**
 * Interface state for search, highlights, and bookmarks in one open reader. Kept apart from the reader chrome, which
 * only hosts [selectionMenu], the search action, and [ReaderMarksLayer].
 */
@Stable
class ReaderMarksUi(val marks: ReaderMarks, private val controller: ReaderController, private val scope: CoroutineScope, context: Context) {
    var searchOpen by mutableStateOf(false)
    var query by mutableStateOf("")
    /** The highlight whose tray is showing. */
    var tray by mutableStateOf<String?>(null)
    var editor by mutableStateOf<HighlightDraft?>(null)
    var guideTab by mutableIntStateOf(0)
    /** Height of the tray and search pill above the folio, so the "Back to" chip sits clear of them. */
    var stackHeight by mutableStateOf(0.dp)
    val snackbar = SnackbarHostState()
    private var searchJumped = false
    private var trayOpenedAt = 0L

    val selectionMenu: ActionMode.Callback = SelectionMenu(context, scope, controller,
        highlight = { range -> marks.highlight(range)?.let { openTray(it.id) } },
        note = { range ->
            val text = marks.book.text(range)
            if (text.isNotBlank()) editor = HighlightDraft(null, range, text, marks.lastColor, "", focusNote = true)
        })

    fun openTray(id: String) { tray = id; trayOpenedAt = System.currentTimeMillis() }
    /** A page tap closes the tray, except the tap that opened it on a highlight. */
    fun pageTapped() { if (System.currentTimeMillis() - trayOpenedAt > 500) tray = null }

    fun openSearch() { searchOpen = true; tray = null }
    fun closeSearchPanel() { searchOpen = false; if (marks.search.state.value.current < 0) endSearch() }
    fun endSearch() { searchOpen = false; query = ""; searchJumped = false; marks.search.clear() }
    fun search(value: String) { query = value; searchJumped = false; marks.search.search(value) }

    /** Goes to match [index]. The first jump of a search sets "Back to"; stepping through matches keeps it. */
    fun showMatch(index: Int) {
        val hit = marks.search.state.value.hits.getOrNull(index) ?: return
        marks.search.select(index)
        searchOpen = false
        controller.jumpTo(hit.range.start, keepReturnPoint = searchJumped)
        searchJumped = true
    }

    fun edit(highlight: Highlight, focusNote: Boolean = true) {
        tray = null
        editor = HighlightDraft(highlight, highlight.range, highlight.text, highlight.color, highlight.note, focusNote)
    }

    fun remove(highlight: Highlight) {
        tray = null
        scope.launch {
            marks.remove(highlight)
            if (snackbar.showSnackbar("Highlight removed", "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) marks.restore(highlight)
        }
    }

    fun toggleBookmark(visible: VisibleRange?) {
        val here = marks.onPage(visible)
        scope.launch {
            if (here.isEmpty()) visible?.first?.let { marks.addBookmark(it) }
            else {
                marks.removeBookmarks(here)
                if (snackbar.showSnackbar(if (here.size == 1) "Bookmark removed" else "Bookmarks removed", "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed)
                    marks.restoreBookmarks(here.map { it.place.entry })
            }
        }
    }

    fun removeBookmark(mark: ReaderBookmark) {
        scope.launch {
            marks.removeBookmarks(listOf(mark))
            if (snackbar.showSnackbar("Bookmark removed", "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) marks.restoreBookmarks(listOf(mark.place.entry))
        }
    }
}

@Composable
fun rememberReaderMarksUi(marks: ReaderMarks, controller: ReaderController): ReaderMarksUi {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    return remember(marks, controller) { ReaderMarksUi(marks, controller, scope, context) }
}

/**
 * The text-selection toolbar. Narrio's Highlight and Note lead; Copy, Share, and every installed text action
 * (Translate, dictionaries) follow, because a custom toolbar replaces the WebView's own.
 */
private class SelectionMenu(
    private val context: Context,
    private val scope: CoroutineScope,
    private val controller: ReaderController,
    private val highlight: suspend (CursorRange) -> Unit,
    private val note: suspend (CursorRange) -> Unit,
) : ActionMode.Callback {
    private val textActions by lazy {
        runCatching {
            context.packageManager.queryIntentActivities(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain"), 0)
                .filter { it.activityInfo.exported || it.activityInfo.packageName == context.packageName }
                .sortedBy { it.loadLabel(context.packageManager).toString() }
        }.getOrDefault(emptyList())
    }

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        menu.add(Menu.NONE, HIGHLIGHT, 0, "Highlight")
        menu.add(Menu.NONE, NOTE, 1, "Note")
        menu.add(Menu.NONE, COPY, 2, android.R.string.copy)
        menu.add(Menu.NONE, SHARE, 3, "Share")
        textActions.take(8).forEachIndexed { index, info ->
            menu.add(Menu.NONE, TEXT_ACTION + index, 10 + index, info.loadLabel(context.packageManager))
        }
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        val id = item.itemId
        scope.launch {
            when (id) {
                HIGHLIGHT, NOTE -> controller.selection()?.let { range -> if (id == HIGHLIGHT) highlight(range) else note(range) }
                else -> controller.selectedText()?.let { text -> act(id, text) }
            }
            mode.finish()
            controller.clearSelection()
        }
        return true
    }

    private fun act(id: Int, text: String) {
        when (id) {
            COPY -> (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Book text", text))
            SHARE -> runCatching {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            else -> textActions.getOrNull(id - TEXT_ACTION)?.activityInfo?.let { activity ->
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
                        .setClassName(activity.packageName, activity.name)
                        .putExtra(Intent.EXTRA_PROCESS_TEXT, text).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
    }

    override fun onDestroyActionMode(mode: ActionMode) = Unit

    private companion object {
        const val HIGHLIGHT = 1; const val NOTE = 2; const val COPY = 3; const val SHARE = 4; const val TEXT_ACTION = 100
    }
}

/**
 * Highlights, bookmarks, and search drawn over the reader: decorations, the page ribbon, the highlight tray and
 * search pill above the folio, the search panel, the highlight sheet, and Undo. [page] is the room the page leaves
 * at the window's edges while reading; [controlsTop]/[controlsBottom] are the system bars that return with the controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoxWithConstraintsScope.ReaderMarksLayer(ui: ReaderMarksUi, session: ReaderSession, appVm: NarrioViewModel, controls: Boolean, dark: Boolean,
                                             page: PageInsets, controlsTop: Dp, controlsBottom: Dp, hideControls: () -> Unit) {
    val top = page.top
    val bottom = page.bottom
    val controller = session.controller
    val marks = ui.marks
    val ready by controller.ready.collectAsStateWithLifecycle()
    val visible by controller.visible.collectAsStateWithLifecycle()
    val highlights by marks.highlights.collectAsStateWithLifecycle()
    val bookmarks by marks.bookmarks.collectAsStateWithLifecycle()
    val search by marks.search.state.collectAsStateWithLifecycle()
    val searchTint = MaterialTheme.colorScheme.primary.toArgb()
    val pill = search.active && search.current >= 0 && !ui.searchOpen

    LaunchedEffect(highlights, dark) { controller.setDecorations(HIGHLIGHT_GROUP, highlightDecorations(highlights, dark)) }
    LaunchedEffect(search.currentHit, pill, searchTint) {
        controller.setDecorations(SEARCH_GROUP, listOfNotNull(search.currentHit?.takeIf { pill }?.let {
            TextDecoration("match", it.range, TextDecoration.Style.HIGHLIGHT, searchTint, active = true)
        }))
    }
    LaunchedEffect(controller) {
        controller.events.collect { event ->
            when (event) {
                is ReaderEvent.DecorationActivated -> if (event.group == HIGHLIGHT_GROUP) ui.openTray(event.id)
                is ReaderEvent.Moved -> ui.tray = null
                ReaderEvent.ToggleControls -> ui.pageTapped()
                else -> Unit
            }
        }
    }
    // A reading bookmark chosen while listening opens the reader there.
    val target by appVm.readerTarget.collectAsStateWithLifecycle()
    LaunchedEffect(target, ready) {
        val cursor = target ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        appVm.readerTarget.value = null
        if (cursor.editionId == session.book.editionId) controller.jumpTo(cursor)
    }
    BackHandler(ui.tray != null) { ui.tray = null }
    BackHandler(pill) { ui.endSearch() }

    // The ribbon hangs from the top of a bookmarked page; with the controls up it hangs from the bar and can be added.
    val here = remember(visible, bookmarks) { marks.onPage(visible, bookmarks) }
    val marked = here.isNotEmpty()
    val ribbonTop by animateDpAsState(if (controls) controlsTop + 56.dp else top, tween(Motion.MEDIUM, easing = Motion.Emphasized), label = "ribbon")
    AnimatedVisibility((marked || controls) && ready && !ui.searchOpen, Modifier.align(Alignment.TopEnd).padding(top = ribbonTop).absolutePadding(right = page.right + 6.dp),
        enter = fadeIn(tween(Motion.SHORT)), exit = fadeOut(tween(Motion.SHORT))) {
        BookmarkRibbon(marked) { ui.toggleBookmark(visible) }
    }

    val density = LocalDensity.current
    Column(Modifier.align(Alignment.BottomCenter).padding(bottom = if (controls) controlsBottom + 160.dp else bottom + 40.dp).padding(horizontal = 16.dp)
        .onSizeChanged { ui.stackHeight = with(density) { it.height.toDp() } },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val trayHighlight = highlights.firstOrNull { it.id == ui.tray }
        AnimatedVisibility(trayHighlight != null && ready, enter = scaleIn(Motion.responsive(), initialScale = .9f) + fadeIn(), exit = scaleOut(targetScale = .9f) + fadeOut()) {
            var shown by remember { mutableStateOf<Highlight?>(null) }
            trayHighlight?.let { shown = it }
            shown?.let { highlight -> HighlightTray(highlight, dark, ui) }
        }
        AnimatedVisibility(pill, enter = scaleIn(Motion.responsive(), initialScale = .9f) + fadeIn(), exit = scaleOut(targetScale = .9f) + fadeOut()) {
            SearchPill(search, { index -> hideControls(); ui.showMatch(index) }, { ui.searchOpen = true }, ui::endSearch)
        }
    }
    SnackbarHost(ui.snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = ui.stackHeight + if (controls) controlsBottom + 168.dp else bottom + 48.dp)) { NarrioSnackbar(it) }

    val wide = maxWidth >= 600.dp
    AnimatedVisibility(ui.searchOpen, Modifier.align(if (wide) Alignment.TopEnd else Alignment.TopCenter),
        enter = if (wide) slideInHorizontally(tween(Motion.MEDIUM, easing = Motion.Emphasized)) { it } + fadeIn() else fadeIn(tween(Motion.SHORT)),
        exit = if (wide) slideOutHorizontally(tween(Motion.SHORT, easing = Motion.EmphasizedAccelerate)) { it } + fadeOut() else fadeOut(tween(Motion.SHORT))) {
        SearchPanel(ui, session, wide, controlsTop, controlsBottom) { index -> hideControls(); ui.showMatch(index) }
    }
    BackHandler(ui.searchOpen) { ui.closeSearchPanel() }

    ui.editor?.let { draft -> HighlightSheet(draft, ui, session, dark) }
}

/** A ribbon hanging from the page's top edge: filled copper when the page is bookmarked, an outline to add one. */
@Composable
private fun BookmarkRibbon(marked: Boolean, toggle: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val pop = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val fill = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    Box(Modifier.size(48.dp).clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
        .clickable(onClickLabel = if (marked) "Remove bookmark" else "Bookmark this page") {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            scope.launch { pop.snapTo(.7f); pop.animateTo(1f, Motion.responsive()) }
            toggle()
        }
        .semantics { contentDescription = if (marked) "Page bookmarked" else "Bookmark this page"; role = Role.Button; stateDescription = if (marked) "Bookmarked" else "Not bookmarked" }
        .testTag("reader-bookmark-ribbon"), contentAlignment = Alignment.TopCenter) {
        Canvas(Modifier.size(18.dp, 30.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(.5f, 0f) }) {
            val path = Path().apply {
                moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width, size.height); lineTo(size.width / 2, size.height * .74f); lineTo(0f, size.height); close()
            }
            if (marked) drawPath(path, fill) else drawPath(path, outline, style = Stroke(1.5.dp.toPx()))
        }
    }
}

/** One colour choice: a 28 dp disc in a 48 dp target, ringed when chosen. */
@Composable
private fun ColorSwatch(color: HighlightColor, selected: Boolean, dark: Boolean, ring: Color, choose: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    Box(Modifier.size(48.dp).selectable(selected, role = Role.RadioButton, onClick = choose)
        .semantics { contentDescription = "${color.label} highlight" }.testTag("highlight-color-${color.key}"), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(34.dp)) {
            if (selected) drawCircle(ink, radius = size.minDimension / 2)
            if (selected) drawCircle(ring, radius = size.minDimension / 2 - 2.dp.toPx())
            drawCircle(Color(color.tint(dark)), radius = 14.dp.toPx())
        }
        if (selected) Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = Color(0xFF20332C))
    }
}

@Composable
private fun HighlightTray(highlight: Highlight, dark: Boolean, ui: ReaderMarksUi) {
    val scope = rememberCoroutineScope()
    val surface = MaterialTheme.colorScheme.surfaceContainerHigh
    Surface(color = surface, shape = RoundedCornerShape(14.dp), shadowElevation = 6.dp, modifier = Modifier.testTag("reader-highlight-tray")) {
        Row(Modifier.padding(horizontal = 6.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.selectableGroup()) {
                HighlightColor.entries.forEach { color ->
                    ColorSwatch(color, highlight.color == color, dark, surface) { scope.launch { ui.marks.update(highlight, color = color) } }
                }
            }
            VerticalDivider(Modifier.height(28.dp).padding(horizontal = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
            IconButton({ ui.edit(highlight) }) { Icon(Icons.Rounded.EditNote, if (highlight.note.isBlank()) "Add a note" else "Edit note") }
            IconButton({ ui.remove(highlight) }) { Icon(Icons.Rounded.DeleteOutline, "Remove highlight") }
        }
    }
}

@Composable
private fun SearchPill(search: SearchState, show: (Int) -> Unit, reopen: () -> Unit, end: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(24.dp), shadowElevation = 4.dp, modifier = Modifier.testTag("reader-search-pill")) {
        Row(Modifier.height(48.dp).padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ show(search.current - 1) }, enabled = search.current > 0) { Icon(Icons.Rounded.ChevronLeft, "Previous match") }
            val count = if (search.searching) "${search.hits.size}+" else "${search.hits.size}"
            Text("“${search.query.trim()}” · ${search.current + 1} of $count", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp).clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = "Show all matches", onClick = reopen)
                    .padding(horizontal = 6.dp, vertical = 12.dp).semantics { liveRegion = LiveRegionMode.Polite })
            IconButton({ show(search.current + 1) }, enabled = search.current < search.hits.lastIndex) { Icon(Icons.Rounded.ChevronRight, "Next match") }
            IconButton(end) { Icon(Icons.Rounded.Close, "End search") }
        }
    }
}

/** Chapter titles for places, from the contents in edition order. */
private class Chapters(marks: ReaderMarks, contents: List<ContentsEntry>) {
    private val starts = contents.mapNotNull { entry -> entry.place?.let { marks.position(marks.book.cursor(it.resource, it.offset)) }?.let { it to entry.title } }
        .sortedBy { it.first }
    private val marks = marks
    fun title(cursor: ContentCursor): String? = marks.position(cursor)?.let { at -> starts.lastOrNull { it.first <= at }?.second }
}

@Composable
private fun rememberChapters(marks: ReaderMarks): Chapters {
    val contents by marks.book.contents.collectAsStateWithLifecycle()
    return remember(marks, contents) { Chapters(marks, contents) }
}

@Composable
private fun SearchPanel(ui: ReaderMarksUi, session: ReaderSession, wide: Boolean, top: Dp, bottom: Dp, show: (Int) -> Unit) {
    val search by ui.marks.search.state.collectAsStateWithLifecycle()
    val chapters = rememberChapters(ui.marks)
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(ui.query) { delay(320); ui.marks.search.search(ui.query) }
    LaunchedEffect(Unit) { if (search.current < 0) runCatching { focus.requestFocus() } }
    val groups = remember(search.hits, chapters) { search.hits.withIndex().groupBy { chapters.title(it.value.range.start) ?: session.book.title }.toList() }
    val list = rememberLazyListState()
    LaunchedEffect(Unit) {
        // Reopening from the pill lands on the match on screen.
        if (search.current >= 0) {
            var row = 0
            for ((_, hits) in groups) { if (hits.any { it.index == search.current }) { row += 1 + hits.indexOfFirst { it.index == search.current }; break }; row += hits.size + 1 }
            list.scrollToItem((row - 2).coerceAtLeast(0))
        }
    }
    Surface(color = if (wide) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.background,
        shadowElevation = if (wide) 8.dp else 0.dp,
        modifier = (if (wide) Modifier.fillMaxHeight().width(400.dp) else Modifier.fillMaxSize()).testTag("reader-search")) {
        Column(Modifier.padding(top = top, bottom = bottom).imePadding()) {
            Row(Modifier.padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(ui::closeSearchPanel) { Icon(if (wide) Icons.Rounded.Close else Icons.AutoMirrored.Rounded.ArrowBack, "Close search") }
                OutlinedTextField(ui.query, { ui.query = it }, Modifier.weight(1f).focusRequester(focus).testTag("reader-search-field"),
                    placeholder = { Text("Search this book") }, singleLine = true, shape = RoundedCornerShape(14.dp),
                    trailingIcon = { if (ui.query.isNotEmpty()) IconButton({ ui.search(""); runCatching { focus.requestFocus() } }) { Icon(Icons.Rounded.Close, "Clear search") } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { ui.marks.search.search(ui.query); focusManager.clearFocus() }))
            }
            val typed = TextSearch.normalizeQuery(ui.query)
            val tooShort = typed.length < BookSearch.MIN_QUERY
            // Until the typed query has been searched, its results aren't known: never report "no matches" early.
            val pending = typed != TextSearch.normalizeQuery(search.query)
            when {
                tooShort -> SearchMessage(Icons.Rounded.Search, "Search this book", "Find a word or phrase in every chapter. Capitals, accents, and curly quotes don't matter.")
                search.hits.isEmpty() && (search.searching || pending) -> Column(Modifier.padding(24.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                    Text(if (search.total == 0 || pending) "Searching…" else "Searching… chapter ${(search.scanned + 1).coerceAtMost(search.total)} of ${search.total}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    if (search.total == 0 || pending) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator({ search.scanned.toFloat() / search.total }, Modifier.fillMaxWidth())
                }
                search.hits.isEmpty() -> SearchMessage(Icons.Rounded.SearchOff, "No matches for “${search.query.trim()}”", "Check the spelling, or try a shorter phrase.")
                else -> {
                    Text(when {
                        search.searching -> "${search.hits.size} matches so far…"
                        else -> "${search.hits.size} ${if (search.hits.size == 1) "match" else "matches"} in ${groups.size} ${if (groups.size == 1) "chapter" else "chapters"}"
                    }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 4.dp).semantics { liveRegion = LiveRegionMode.Polite })
                    if (search.searching) LinearProgressIndicator({ search.scanned.toFloat() / search.total.coerceAtLeast(1) }, Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(2.dp))
                    LazyColumn(state = list, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
                        groups.forEach { (chapter, hits) ->
                            item(key = "chapter:$chapter:${hits.first().index}") {
                                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 6.dp).semantics(mergeDescendants = true) { heading() }) {
                                    Text(chapter, style = MaterialTheme.typography.titleMedium.copy(fontFamily = Editorial, fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text("${hits.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 12.dp).semantics { contentDescription = "${hits.size} ${if (hits.size == 1) "match" else "matches"}" })
                                }
                            }
                            items(hits, key = { it.index }) { (index, hit) -> SearchRow(hit, index == search.current, session) { focusManager.clearFocus(); show(index) } }
                        }
                        if (search.truncated) item {
                            Text("Showing the first ${BookSearch.LIMIT} matches. Add a word to narrow the search.", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchMessage(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun SearchRow(hit: SearchHit, current: Boolean, session: ReaderSession, open: () -> Unit) {
    val emphasis = MaterialTheme.colorScheme.primary.copy(alpha = .22f)
    val ink = if (current) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    val text = remember(hit, emphasis, ink) {
        buildAnnotatedString { append(hit.before); withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = ink, background = emphasis)) { append(hit.match) }; append(hit.after) }
    }
    val page = remember(hit) { session.label(BookPlace(hit.range.start.resource, hit.range.start.offset)) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(12.dp))
        .background(if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
        .clickable(onClickLabel = "Go to this match", onClick = open).padding(horizontal = 12.dp, vertical = 10.dp)
        .semantics { if (current) stateDescription = "Showing now" }.testTag("reader-search-result")) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = if (current) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (page != null || current) Text(listOfNotNull(page, "Showing now".takeIf { current }).joinToString(" · "), style = MaterialTheme.typography.labelSmall,
            color = if (current) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Colour and note for one highlight. A new note is saved only on Save; removal is offered once it exists. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HighlightSheet(draft: HighlightDraft, ui: ReaderMarksUi, session: ReaderSession, dark: Boolean) {
    val scope = rememberCoroutineScope()
    val chapters = rememberChapters(ui.marks)
    var color by remember(draft) { mutableStateOf(draft.color) }
    var note by remember(draft) { mutableStateOf(draft.note) }
    val focus = remember { FocusRequester() }
    val close = { ui.editor = null }
    val surface = MaterialTheme.colorScheme.surfaceContainer
    ModalBottomSheet(close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp).imePadding().testTag("reader-highlight-sheet")) {
            val place = listOfNotNull(if (draft.highlight == null || draft.highlight.note.isBlank()) "Note" else "Edit note", chapters.title(draft.range.start)).joinToString(" · ")
            Text(place, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(14.dp)) {
                Row(Modifier.height(IntrinsicSize.Min).padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Box(Modifier.width(4.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(Color(color.tint(dark))))
                    Spacer(Modifier.width(12.dp))
                    Text(draft.text, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = Editorial), maxLines = 8, overflow = TextOverflow.Ellipsis)
                }
            }
            Text("Colour", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp, bottom = 2.dp))
            Row(Modifier.selectableGroup().offset(x = (-8).dp)) { HighlightColor.entries.forEach { option -> ColorSwatch(option, option == color, dark, surface) { color = option } } }
            OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth().padding(top = 8.dp).focusRequester(focus).testTag("reader-note-field"),
                label = { Text("Note") }, placeholder = { Text("What does this passage mean to you?") }, minLines = 3, maxLines = 8,
                keyboardOptions = KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences))
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                draft.highlight?.let { existing ->
                    TextButton({ close(); ui.remove(existing) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Remove highlight") }
                }
                Spacer(Modifier.weight(1f))
                TextButton(close) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button({
                    scope.launch {
                        val existing = draft.highlight
                        if (existing != null) ui.marks.update(existing, color, note) else ui.marks.highlight(draft.range, color, note)
                        close()
                    }
                }, Modifier.testTag("reader-note-save")) { Text("Save") }
            }
        }
    }
    LaunchedEffect(draft) { if (draft.focusNote) { delay(250); runCatching { focus.requestFocus() } } }
}

/**
 * The Contents sheet with its Bookmarks and Highlights tabs. [contents] renders the existing contents list;
 * [jump] goes to a place and closes the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderGuideSheet(ui: ReaderMarksUi, session: ReaderSession, dark: Boolean, jump: (ContentCursor) -> Unit, contents: @Composable () -> Unit) {
    val highlights by ui.marks.highlights.collectAsStateWithLifecycle()
    val bookmarks by ui.marks.bookmarks.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().testTag("reader-guide")) {
        Text(session.book.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 24.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        PrimaryTabRow(ui.guideTab, Modifier.padding(top = 8.dp), containerColor = Color.Transparent, divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }) {
            listOf("Contents", "Bookmarks", "Highlights").forEachIndexed { index, label ->
                Tab(ui.guideTab == index, { ui.guideTab = index }, text = { Text(label, maxLines = 1) },
                    selectedContentColor = MaterialTheme.colorScheme.primary, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("reader-tab-${label.lowercase()}"))
            }
        }
        when (ui.guideTab) {
            0 -> contents()
            1 -> BookmarksTab(ui, session, bookmarks, jump)
            else -> HighlightsTab(ui, session, highlights, dark, jump)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BookmarksTab(ui: ReaderMarksUi, session: ReaderSession, bookmarks: List<ReaderBookmark>, jump: (ContentCursor) -> Unit) {
    val visible by session.controller.visible.collectAsStateWithLifecycle()
    val chapters = rememberChapters(ui.marks)
    val here = remember(visible, bookmarks) { ui.marks.onPage(visible, bookmarks) }
    LazyColumn(contentPadding = PaddingValues(start = 24.dp, end = 12.dp, top = 12.dp, bottom = 24.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp)) {
        item {
            FilledTonalButton({ ui.toggleBookmark(visible) }, enabled = visible != null, modifier = Modifier.testTag("reader-bookmark-page")) {
                Icon(if (here.isEmpty()) Icons.Rounded.BookmarkAdd else Icons.Rounded.BookmarkRemove, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(if (here.isEmpty()) "Bookmark this page" else "Remove this page's bookmark")
            }
        }
        if (bookmarks.isEmpty()) item {
            Text("No bookmarks yet. Tap the ribbon at the top of a page, or add a bookmark while listening. Both appear here and in the Listening room.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp, end = 12.dp))
        }
        items(bookmarks, key = { it.place.id }) { mark ->
            val cursor = mark.cursor
            val page = cursor?.let { session.label(BookPlace(it.resource, it.offset)) }
            val title = cursor?.let(chapters::title) ?: if (mark.inEdition || mark.place.text == null) "Listening bookmark" else "Another edition"
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = cursor != null, onClickLabel = "Go to this bookmark") { cursor?.let(jump) }
                .padding(vertical = 12.dp).testTag("reader-bookmark-row"), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium.copy(fontFamily = Editorial, fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // A page that opens a chapter starts with its heading, which the row already shows.
                    val snippet = mark.snippet.removePrefix(title).trimStart()
                    if (snippet.isNotBlank()) Text(snippet, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = Editorial), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                        when {
                            cursor != null -> EstimatedPlace(page ?: "${(cursor.progression * 100).toInt()}%", mark.place.textConfidence, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            mark.place.text != null -> Text("Made in another edition", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else -> Text("No matching page yet", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        mark.listening?.let { time ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Headphones, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary)
                                Spacer(Modifier.width(4.dp))
                                EstimatedPlace(time, mark.place.audioConfidence, prefix = "")
                            }
                        }
                    }
                }
                IconButton({ ui.removeBookmark(mark) }) { Icon(Icons.Rounded.DeleteOutline, "Remove bookmark ${page ?: mark.listening ?: ""}".trim(), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun HighlightsTab(ui: ReaderMarksUi, session: ReaderSession, highlights: List<Highlight>, dark: Boolean, jump: (ContentCursor) -> Unit) {
    val chapters = rememberChapters(ui.marks)
    if (highlights.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(24.dp)) {
            Text("No highlights yet", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Text("Select text on the page, then choose Highlight or Note. Your highlights and notes stay on this phone.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val notes = highlights.count { it.note.isNotBlank() }
    LazyColumn(contentPadding = PaddingValues(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 24.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp)) {
        item {
            Text("${highlights.size} ${if (highlights.size == 1) "highlight" else "highlights"}" + if (notes > 0) " · $notes with ${if (notes == 1) "a note" else "notes"}" else "",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
        }
        items(highlights, key = { it.id }) { highlight ->
            var menu by remember { mutableStateOf(false) }
            val page = session.label(BookPlace(highlight.range.start.resource, highlight.range.start.offset))
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(12.dp))
                .clickable(onClickLabel = "Go to this highlight") { jump(highlight.range.start) }.padding(vertical = 12.dp).testTag("reader-highlight-row")) {
                Box(Modifier.width(4.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(Color(highlight.color.tint(dark)))
                    .semantics { contentDescription = "${highlight.color.label} highlight" })
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(highlight.text, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = Editorial), maxLines = 4, overflow = TextOverflow.Ellipsis)
                    if (highlight.note.isNotBlank()) Row(Modifier.padding(top = 4.dp)) {
                        Icon(Icons.Rounded.EditNote, null, Modifier.size(16.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        Text(highlight.note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                    Text(listOfNotNull(chapters.title(highlight.range.start), page).joinToString(" · "), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "Highlight options", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text(if (highlight.note.isBlank()) "Add note" else "Edit note") }, { menu = false; ui.edit(highlight) }, leadingIcon = { Icon(Icons.Rounded.EditNote, null) })
                        DropdownMenuItem({ Text("Change colour") }, { menu = false; ui.edit(highlight, focusNote = false) }, leadingIcon = { Icon(Icons.Rounded.Palette, null) })
                        DropdownMenuItem({ Text("Remove") }, { menu = false; ui.remove(highlight) }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) })
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
