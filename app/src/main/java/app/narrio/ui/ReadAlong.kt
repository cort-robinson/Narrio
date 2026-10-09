package app.narrio.ui

import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import app.narrio.domain.*
import app.narrio.playback.ListeningState
import app.narrio.playback.SyncPhase
import app.narrio.reader.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/*
 * Read along (together mode): the reader becomes the listening surface. Narration moves through the page as a
 * soft wash on the narrated sentence, pages turn with it, and the player's own controls gather into a tray
 * (phone), a side panel (unfolded), or the lower half (tabletop). Estimated places never look confident.
 */

/** The window's hinge, provided by the app shell; null without a folding feature. */
val LocalFold = compositionLocalOf<FoldingFeature?> { null }

/** Height of controls docked at the window's bottom edge, so the app's snackbars (Undo) rise above them. */
val LocalSnackbarLift = staticCompositionLocalOf { mutableStateOf(0.dp) }

private const val NARRATED = "narration"
private const val WORD = "narration-word"
private const val SELECTED = "narration-selected"

/** Where read along's controls sit for the current window and posture. */
enum class ReadAlongArrangement { TRAY, PANEL, TABLETOP }

/** What read along knows right now; state is owned by [rememberReadAlong]. */
@Stable
class ReadAlong internal constructor(val session: ReaderSession) {
    var place by mutableStateOf<NarratedPlace?>(null)
        internal set
    var status by mutableStateOf(ReadAlongStatus("Waiting for the narration"))
        internal set
    /** True while this book's recording is the one loaded in the player. */
    var playingHere by mutableStateOf(false)
        internal set
    /** Fix the timing: taps choose the sentence being heard instead of seeking. */
    var matching by mutableStateOf(false)
    var selected by mutableStateOf<TextPassage?>(null)
    var document by mutableStateOf<BookText?>(null)
        internal set
    var binding by mutableStateOf<TextBinding?>(null)
        internal set
    internal var entered by mutableStateOf(false)
    var following by mutableStateOf(false)
        internal set

    /** Following paused by the reader's own page turn, with narration to return to. */
    val canReturn: Boolean get() = entered && !following && !matching && place != null && status.highlight

    /** Back to narration: following resumes and turns to the narrated page. */
    fun backToNarration() { session.controller.startFollowing() }
}

/**
 * Runs read along for [session]: maps the media clock to the page, draws narration, follows it, handles sentence
 * taps, and decides who leads on entry. [startJump] is the reader's own opening jump, folded into read along's
 * single Undo so entering never shows two.
 */
@Composable
fun rememberReadAlong(vm: NarrioViewModel, reader: ReaderViewModel, request: Long, session: ReaderSession, words: Boolean, fromListening: Boolean, copper: Int, sage: Int,
                      startJump: SyncJump<ContentCursor>?): ReadAlong =
    // A reopened session (the reader moving between shells) gets fresh effects, not ones keyed to the old page.
    key(session) { readAlongFor(vm, reader, request, session, words, fromListening, copper, sage, startJump) }

@Composable
private fun readAlongFor(vm: NarrioViewModel, reader: ReaderViewModel, request: Long, session: ReaderSession, words: Boolean, fromListening: Boolean, copper: Int, sage: Int,
                         startJump: SyncJump<ContentCursor>?): ReadAlong {
    val controller = session.controller
    val state = remember(session) { ReadAlong(session) }
    val playback by vm.playback.collectAsStateWithLifecycle()
    val text by vm.bookText.collectAsStateWithLifecycle()
    val sync by vm.readingSync.collectAsStateWithLifecycle()
    val narration by vm.narrationSync.collectAsStateWithLifecycle()
    val following by controller.following.collectAsStateWithLifecycle()
    val visible by controller.visible.collectAsStateWithLifecycle()
    val ready by controller.ready.collectAsStateWithLifecycle()
    val animations = animationsEnabled()
    val bookId = session.book.bookId
    val playingHere = playback.book?.id == bookId && playback.source != null
    val document = text.document?.takeIf { text.bookId == bookId && it.id == session.book.editionId }
    val sameEdition = !playingHere || text.loading || text.document == null || text.document?.id == session.book.editionId
    val pairing = sync.pairing.takeIf { sync.bookId == bookId && sync.sourceId == playback.source?.id } ?: PairingStatus.UNCHECKED
    state.playingHere = playingHere
    state.following = following
    state.document = document
    state.binding = document?.let { doc -> playback.source?.let { source -> playback.part?.let { part ->
        text.bindings.firstOrNull { it.documentId == doc.id && it.sourceId == source.id && it.partId == part.id } ?: defaultTextBinding(doc, source, playback.partIndex)
    } } }

    // The service publishes about once a second; between publications the clock advances at the playback speed so
    // the word mark keeps time with the voice. It never runs more than 1.5 s ahead of the last publication.
    val clock by produceState(playback.positionMs, playback.positionMs, playback.partIndex, playback.playing, playback.buffering, playback.speed, words) {
        value = playback.positionMs
        if (!playback.playing || playback.buffering) return@produceState
        val started = SystemClock.elapsedRealtime()
        while (true) {
            delay(if (words) 120 else 350)
            value = playback.positionMs + ((SystemClock.elapsedRealtime() - started) * playback.speed).toLong().coerceAtMost(1_500)
        }
    }
    LaunchedEffect(document, playback.source, playback.partIndex, playback.durationMs, text.bindings, clock, words, playingHere, pairing) {
        val source = playback.source
        state.place = if (!playingHere || document == null || source == null || pairing == PairingStatus.MISMATCH) null
        else withContext(Dispatchers.Default) {
            Narration.mapped(document, source, playback.partIndex, playback.durationMs, text.bindings, clock)?.let { Narration.place(document, it, words) }
        }
    }
    state.status = if (!playingHere) ReadAlongStatus("Narration isn't playing", highlight = false)
        else ReadAlongStatuses.of(pairing, sameEdition, state.place, narration.phase, sync.correcting)

    // Narration marks. Stable ids keep the sentence mark in place while only the word moves.
    val place = state.place
    val show = place != null && state.status.highlight && !state.matching
    LaunchedEffect(controller, place?.passageId, place?.confidence, show, copper, sage, animations) {
        controller.setDecorations(NARRATED, if (!show || place == null) emptyList() else {
            val exact = place.confidence == MappingConfidence.EXACT
            listOf(TextDecoration("narrated:${place.passageId}:${place.confidence}", place.sentence,
                if (exact) TextDecoration.Style.NARRATED else TextDecoration.Style.ESTIMATED, if (exact) copper else sage, animated = animations))
        })
    }
    LaunchedEffect(controller, place?.word, show, copper, animations) {
        val word = place?.word
        controller.setDecorations(WORD, if (!show || word == null) emptyList() else listOf(TextDecoration("word:${word.start.resource}:${word.start.offset}", word, TextDecoration.Style.WORD, copper, animated = animations)))
    }
    LaunchedEffect(controller, state.selected, state.matching, sage) {
        val passage = state.selected?.takeIf { state.matching }
        val doc = document
        controller.setDecorations(SELECTED, if (passage == null || doc == null) emptyList() else {
            val start = ContentCursor(doc.id, passage.resource, passage.offset, doc.normalizationVersion)
            listOf(TextDecoration("selected:${passage.id}", CursorRange(start, start.copy(offset = passage.offset + passage.text.length)), TextDecoration.Style.NARRATED, sage, animated = animations))
        })
    }
    // The screen can be recreated around the same session (rotation moves it between shells), so leaving read along
    // itself is handled by [leaveReadAlong] when the reader turns it off, not here.
    DisposableEffect(controller) {
        controller.textTaps = true
        vm.narrationSyncVisible(true)
        onDispose {
            controller.textTaps = false
            vm.narrationSyncVisible(false)
        }
    }

    // Auto-follow: keep narration on screen while following. Returning to the narrated page by hand resumes it.
    LaunchedEffect(place?.cursor, following, ready, show) {
        val target = place?.cursor ?: return@LaunchedEffect
        if (ready && show && following) controller.follow(target, animated = animations)
    }
    LaunchedEffect(visible, place?.passageId, state.entered, state.matching) {
        val target = place ?: return@LaunchedEffect
        if (state.entered && !following && !state.matching && state.status.highlight && visible?.contains(target.cursor) == true) controller.startFollowing()
    }

    // Entry: who leads, and one Undo for a large move. Once per read along, not again when the screen is recreated.
    LaunchedEffect(session) {
        val clearStartJump = reader::clearStartJump
        // Recreated around the same request: carry on with the narration, without another entry or Undo.
        if (reader.readAlongStartedFor == request) { clearStartJump(); controller.startFollowing(); state.entered = true; return@LaunchedEffect }
        controller.ready.first { it }
        val audioLeads = ReadAlongDecisions.audioLeads(fromListening, vm.playback.value.let { it.playing && it.book?.id == bookId })
        val previous = startJump?.previous ?: controller.cursor.value
        clearStartJump()
        if (audioLeads) {
            val narrated = withTimeoutOrNull(4_000) { snapshotFlow { state.place }.filterNotNull().first() }
            if (narrated != null) {
                val version = session.navigationVersion
                if (previous != null && ReadAlongDecisions.offersUndo(previous, narrated.cursor)) {
                    val label = session.label(BookPlace(narrated.cursor.resource, narrated.cursor.offset)) ?: readingPlace(narrated.cursor, null).label
                    vm.announceJump(PositionJump(bookId, PositionOrigin.LISTENING, label, narrated.confidence) {
                        controller.stopFollowing(); vm.undoReadingJump(session, previous, version)
                    })
                }
                controller.startFollowing()
                controller.follow(narrated.cursor, animated = false)
            } else controller.startFollowing()
        } else {
            vm.listenFromPage(session).join()
            controller.startFollowing()
        }
        reader.readAlongStartedFor = request
        state.entered = true
    }

    // A tap on text plays from its sentence (keeping play/pause); while fixing the timing it chooses the sentence.
    LaunchedEffect(controller) {
        // A tap on a highlight opens it; the page can report the same tap too, which must not also seek.
        var decorationTapAt = 0L
        controller.events.collect { event ->
            if (event is ReaderEvent.DecorationActivated) { decorationTapAt = SystemClock.uptimeMillis(); return@collect }
            if (event !is ReaderEvent.TextTapped || SystemClock.uptimeMillis() - decorationTapAt < 600) return@collect
            val doc = state.document ?: return@collect
            if (state.matching) { state.selected = Narration.passageAt(doc, event.cursor); return@collect }
            if (!state.playingHere) { vm.messages.tryEmit("Start the narration from this page with Listen from here."); return@collect }
            val start = Narration.sentenceStart(doc, event.cursor) ?: return@collect
            if (vm.seekFromText(start)) controller.startFollowing()
            else vm.messages.tryEmit(if (state.status.highlight) "This sentence isn't matched to the narration yet." else state.status.text + ".")
        }
    }
    return state
}

/** Turning read along off: the narrated place becomes the reader's, and narration marks leave the page. */
fun leaveReadAlong(reader: ReaderViewModel, controller: ReaderController) {
    reader.readAlongStartedFor = null
    controller.leaveFollowing()
    listOf(NARRATED, WORD, SELECTED).forEach(controller::clearDecorations)
}

/**
 * Lays out the page and read along's controls. The page is always the first child, so turning read along on or
 * off never recreates the navigator. A reported separating hinge stays empty. [docked] keeps a tray under the page
 * without read along, for the mini-player while another book or this one plays.
 */
@Composable
fun ReadAlongLayout(together: Boolean, modifier: Modifier = Modifier, docked: Boolean = false, page: @Composable (ReadAlongArrangement?) -> Unit, controls: @Composable (ReadAlongArrangement) -> Unit) {
    val fold = LocalFold.current
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
        val width = constraints.maxWidth; val height = constraints.maxHeight
        val bounds = fold?.bounds
        val hingeTop = bounds?.let { it.top - origin.y.toInt() }
        val hingeBottom = bounds?.let { it.bottom - origin.y.toInt() }
        val hingeLeft = bounds?.let { it.left - origin.x.toInt() }
        val hingeRight = bounds?.let { it.right - origin.x.toInt() }
        val tabletop = fold != null && fold.orientation == FoldingFeature.Orientation.HORIZONTAL && fold.state == FoldingFeature.State.HALF_OPENED &&
            hingeTop != null && hingeTop in (height / 5)..(height * 4 / 5)
        val vertical = fold != null && fold.isSeparating && fold.orientation == FoldingFeature.Orientation.VERTICAL &&
            hingeLeft != null && hingeLeft in (width / 4)..(width * 3 / 4)
        val arrangement = when {
            !together -> if (docked) ReadAlongArrangement.TRAY else null
            tabletop -> ReadAlongArrangement.TABLETOP
            maxWidth >= 600.dp -> ReadAlongArrangement.PANEL
            else -> ReadAlongArrangement.TRAY
        }
        val minGap = with(density) { 12.dp.roundToPx() }
        Layout({ page(arrangement); if (arrangement != null) controls(arrangement) }) { measurables, _ ->
            val pageMeasurable = measurables[0]
            val controlsMeasurable = measurables.getOrNull(1)
            layout(width, height) {
                when (arrangement) {
                    null -> pageMeasurable.measure(Constraints.fixed(width, height)).place(0, 0)
                    ReadAlongArrangement.TRAY -> {
                        val tray = controlsMeasurable!!.measure(Constraints(maxWidth = width, minWidth = width, maxHeight = height / 2))
                        pageMeasurable.measure(Constraints.fixed(width, height - tray.height)).place(0, 0)
                        tray.place(0, height - tray.height)
                    }
                    ReadAlongArrangement.PANEL -> {
                        val (pageWidth, panelX) = if (vertical) hingeLeft!! to hingeRight!!.coerceAtLeast(hingeLeft + 1)
                            else (width - (width * .38f).toInt().coerceIn(with(density) { 300.dp.roundToPx() }, with(density) { 380.dp.roundToPx() })).let { it to it }
                        pageMeasurable.measure(Constraints.fixed(pageWidth, height)).place(0, 0)
                        controlsMeasurable!!.measure(Constraints.fixed(width - panelX, height)).place(panelX, 0)
                    }
                    ReadAlongArrangement.TABLETOP -> {
                        val top = hingeTop!!
                        val below = maxOf(hingeBottom!!, top + minGap)
                        pageMeasurable.measure(Constraints.fixed(width, top)).place(0, 0)
                        controlsMeasurable!!.measure(Constraints.fixed(width, (height - below).coerceAtLeast(0))).place(0, below)
                    }
                }
            }
        }
    }
}

/**
 * The reader's docked mini-player while audio plays without read along. Opening it leaves the reader for the
 * Listening room; snackbars rise above it as they do above read along's tray.
 */
@Composable
fun ReaderMiniPlayer(vm: NarrioViewModel) {
    val lift = LocalSnackbarLift.current
    val density = LocalDensity.current
    DisposableEffect(lift) { onDispose { lift.value = 0.dp } }
    MiniPlayer(vm, Modifier.onSizeChanged { lift.value = with(density) { it.height.toDp() } }, aboveSystemBar = true) {
        vm.closeReader(); vm.playerOpen.value = true
    }
}

/** The phone's tray: speed · −10 · play · +30 · sleep, under a line of narration status. */
@Composable
fun ReadAlongTray(vm: NarrioViewModel, readAlong: ReadAlong, playback: ListeningState, options: () -> Unit, modifier: Modifier = Modifier, above: Dp = 0.dp) {
    var speedOpen by remember { mutableStateOf(false) }
    var sleepOpen by remember { mutableStateOf(false) }
    val progress = playback.progress
    val lift = LocalSnackbarLift.current
    val density = LocalDensity.current
    var height by remember { mutableStateOf(0.dp) }
    // Snackbars rise above the tray, and above whatever the page shows just over it ([above]).
    LaunchedEffect(height, above) { lift.value = height + above }
    DisposableEffect(lift) { onDispose { lift.value = 0.dp } }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth().testTag("read-along-tray")
        .onSizeChanged { height = with(density) { it.height.toDp() } }) {
        Column(Modifier.navigationBarsIgnoringVisibilityPadding()) {
            if (readAlong.playingHere && playback.hasLength) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(2.dp).clearAndSetSemantics { },
                color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.outlineVariant, gapSize = 0.dp, drawStopIndicator = {})
            AnimatedContent(when { !readAlong.playingHere -> 0; readAlong.matching -> 1; else -> 2 }, label = "tray",
                transitionSpec = { fadeIn(tween(Motion.MEDIUM, easing = Motion.EmphasizedDecelerate)).togetherWith(fadeOut(tween(Motion.SHORT))) }) { mode ->
                when (mode) {
                    0 -> ListenHere(vm, readAlong, Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
                    1 -> MatchRow(vm, readAlong, playback, Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                    else -> Column {
                        StatusRow(readAlong, playback, options, Modifier.padding(start = 20.dp, end = 12.dp, top = 6.dp))
                        Row(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                            ToolSlot(Icons.Rounded.Speed, { speedOpen = true }) { FitLabel(speedLabel(playback.speed)) }
                            SkipButton(false, 52.dp, 28.dp) { vm.graph.playback.service?.skip(it) }
                            PlayButton(playback, 56.dp, 30.dp) { vm.graph.playback.service?.toggle() }
                            SkipButton(true, 52.dp, 28.dp) { vm.graph.playback.service?.skip(it) }
                            ToolSlot(Icons.Rounded.Bedtime, { sleepOpen = true }, active = playback.sleep.active) { FitLabel(sleepLabel(playback)) }
                        }
                    }
                }
            }
        }
    }
    if (speedOpen) SpeedDialog(vm, playback) { speedOpen = false }
    if (sleepOpen) SleepDialog(vm, playback) { sleepOpen = false }
}

/** Unfolded: the book, the player, and its chapters beside one column of text. */
@Composable
fun ReadAlongPanel(vm: NarrioViewModel, readAlong: ReadAlong, playback: ListeningState, options: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow).testTag("read-along-panel")) {
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.fillMaxSize().statusBarsIgnoringVisibilityPadding().navigationBarsIgnoringVisibilityPadding().padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
            playback.book?.takeIf { readAlong.playingHere }?.let { book ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    BookCover(book, Modifier.width(52.dp).height(76.dp))
                    Column(Modifier.weight(1f)) {
                        Text(book.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(book.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    ReadAlongOptionsButton(options)
                }
                Spacer(Modifier.height(16.dp))
            }
            ReadAlongControls(vm, readAlong, playback, options, chapters = null)
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Chapters(vm, readAlong, Modifier.weight(1f))
        }
    }
}

/** Tabletop: text above the hinge; the player in the lower half, centred and scrolling on its own. */
@Composable
fun ReadAlongDeck(vm: NarrioViewModel, readAlong: ReadAlong, playback: ListeningState, options: () -> Unit, chapters: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow).testTag("read-along-deck"), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsIgnoringVisibilityPadding().padding(horizontal = 24.dp, vertical = 16.dp)) {
            ReadAlongControls(vm, readAlong, playback, options, chapters)
        }
    }
}

/** The larger player used by the panel and the deck. */
@Composable
private fun ReadAlongControls(vm: NarrioViewModel, readAlong: ReadAlong, playback: ListeningState, options: () -> Unit, chapters: (() -> Unit)?) {
    var speedOpen by remember { mutableStateOf(false) }
    var sleepOpen by remember { mutableStateOf(false) }
    if (!readAlong.playingHere) { ListenHere(vm, readAlong); return }
    if (readAlong.matching) { MatchRow(vm, readAlong, playback); return }
    var scrub by remember { mutableStateOf<Long?>(null) }
    val confidence = readAlong.place?.confidence ?: MappingConfidence.UNMAPPED
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(playback.part?.title ?: "Ready to listen", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (chapters != null) ReadAlongOptionsButton(options)
        }
        StatusRow(readAlong, playback, options, showTime = false)
        SeekSlider(playback.positionMs, playback.durationMs, { vm.graph.playback.service?.seek(it) }, { scrub = it })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            EstimatedPlace(formatTime(scrub ?: playback.positionMs), if (scrub != null) MappingConfidence.EXACT else confidence, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (playback.durationMs > 0) "−${formatTime((playback.durationMs - (scrub ?: playback.positionMs)).coerceAtLeast(0))}" else "Loading length",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            SkipButton(false, 56.dp, 32.dp) { vm.graph.playback.service?.skip(it) }
            PlayButton(playback, 72.dp, 38.dp) { vm.graph.playback.service?.toggle() }
            SkipButton(true, 56.dp, 32.dp) { vm.graph.playback.service?.skip(it) }
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ToolSlot(Icons.Rounded.Speed, { speedOpen = true }) { FitLabel(speedLabel(playback.speed)) }
            ToolSlot(Icons.Rounded.Bedtime, { sleepOpen = true }, active = playback.sleep.active) { FitLabel(sleepLabel(playback)) }
            if (chapters != null) ToolSlot(Icons.AutoMirrored.Rounded.FormatListBulleted, chapters) { FitLabel("Chapters") }
        }
        AnimatedVisibility(playback.error != null) { RecoveryState("Playback stopped", playback.error.orEmpty()) { vm.graph.playback.service?.retry() } }
    }
    if (speedOpen) SpeedDialog(vm, playback) { speedOpen = false }
    if (sleepOpen) SleepDialog(vm, playback) { sleepOpen = false }
}

@Composable
private fun ReadAlongOptionsButton(options: () -> Unit) {
    IconButton(options, Modifier.testTag("read-along-options")) { Icon(Icons.Rounded.Tune, "Read along options", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
}

/** Narration pulse, part and time, and the sync status; the status opens read along's options. */
@Composable
private fun StatusRow(readAlong: ReadAlong, playback: ListeningState, options: () -> Unit, modifier: Modifier = Modifier, showTime: Boolean = true) {
    val status = readAlong.status
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        if (showTime) {
            NarrationPulse(playback.playing, Modifier.size(12.dp, 10.dp))
            Spacer(Modifier.width(8.dp))
            Text("Part ${playback.partIndex + 1} · ${formatTime(playback.positionMs)}", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.weight(1f))
        }
        // After a page turn by hand, the status gives way to the way back; it sits in the player, above any snackbar.
        if (readAlong.canReturn) FilledTonalButton(readAlong::backToNarration, Modifier.testTag("back-to-narration"), contentPadding = PaddingValues(horizontal = 14.dp)) {
            Icon(Icons.Rounded.MyLocation, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Back to narration")
        } else Text((if (status.estimated) "≈ " else "") + status.text, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (status.highlight) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Read along options", onClick = options)
                .padding(horizontal = 8.dp, vertical = 6.dp).testTag("read-along-status")
                .semantics { contentDescription = (if (status.estimated) "Estimated: " else "") + status.text; liveRegion = LiveRegionMode.Polite })
    }
}

/** Narration for this book isn't loaded: start it at the page on screen. */
@Composable
private fun ListenHere(vm: NarrioViewModel, readAlong: ReadAlong, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Start the narration where you're reading.", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FilledTonalButton({ vm.listenFromPage(readAlong.session) }, Modifier.testTag("listen-from-here")) {
            Icon(Icons.Rounded.Headphones, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Listen from here")
        }
    }
}

/** Fix the timing: choose the sentence you hear, then match it to the current audio time. */
@Composable
private fun MatchRow(vm: NarrioViewModel, readAlong: ReadAlong, playback: ListeningState, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text(if (readAlong.selected == null) "Tap the sentence you hear now." else "Match this sentence to ${formatTime(playback.positionMs)}?",
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ readAlong.matching = false; readAlong.selected = null; readAlong.session.controller.startFollowing() }) { Text("Cancel") }
            Spacer(Modifier.weight(1f))
            FilledTonalButton({
                val binding = readAlong.binding; val passage = readAlong.selected
                if (binding != null && passage != null) vm.matchTextLine(binding, passage.id)
                readAlong.matching = false; readAlong.selected = null; readAlong.session.controller.startFollowing()
            }, enabled = readAlong.selected != null && readAlong.binding != null && playback.durationMs > 0) { Text("Match at ${formatTime(playback.positionMs)}") }
        }
    }
}

/** The book's chapters; a tap plays from the chapter's first sentence, or turns there when it isn't narrated. */
@Composable
private fun Chapters(vm: NarrioViewModel, readAlong: ReadAlong, modifier: Modifier = Modifier) {
    val session = readAlong.session
    val book = session.book
    val contents by book.contents.collectAsStateWithLifecycle()
    val visible by session.controller.visible.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val here = readAlong.place?.cursor ?: visible?.first
    val current = here?.let { cursor -> contents.indexOfLast { entry -> entry.place?.let { book.compare(it, BookPlace(cursor.resource, cursor.offset)) }?.let { it <= 0 } == true } } ?: -1
    val list = rememberLazyListState((current - 1).coerceAtLeast(0))
    LazyColumn(modifier.fillMaxWidth().testTag("read-along-chapters"), state = list, contentPadding = PaddingValues(vertical = 8.dp)) {
        itemsIndexed(contents.filter { it.depth == 0 }) { _, entry ->
            val index = contents.indexOf(entry)
            val now = index == current
            val place = entry.place
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                .background(if (now) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .clickable(enabled = place != null, onClickLabel = "Listen from ${entry.title}") {
                    val target = place ?: return@clickable
                    scope.launch {
                        val start = readAlong.document?.let { Narration.sentenceStart(it, book.cursor(target.resource, target.offset)) }
                        if (readAlong.playingHere && start != null && vm.seekFromText(start)) session.controller.startFollowing()
                        else { session.controller.stopFollowing(); session.controller.jumpTo(target) }
                    }
                }
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .semantics { if (now) stateDescription = if (readAlong.place != null) "Narrating" else "Reading now" },
                verticalAlignment = Alignment.CenterVertically) {
                Text(entry.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge.copy(fontFamily = Editorial),
                    color = if (now) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val label = if (now && readAlong.place != null) "Narrating" else place?.let(session::label)
                label?.let { Text(it, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 12.dp),
                    color = if (now) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

/** Read along's options: the word mark, narration sync, timing fixes, and leaving. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadAlongSheet(vm: NarrioViewModel, readAlong: ReadAlong, words: Boolean, setWords: (Boolean) -> Unit, fromListening: Boolean, dismiss: () -> Unit) {
    val narration by vm.narrationSync.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    var chaptersOpen by remember { mutableStateOf(false) }
    val document = readAlong.document
    val binding = readAlong.binding
    val timed = document?.timedSourceId?.isNotBlank() == true
    ModalBottomSheet(dismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp).testTag("read-along-sheet")) {
            Text("Read along", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            val status = readAlong.status
            Text((if (status.estimated) "≈ " else "") + status.text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary)
            Text(when {
                !status.highlight && readAlong.playingHere -> "Reading and listening keep separate places. Choose the edition that matches this recording to read along."
                status.estimated -> "The highlight is placed from the chapter's pace until narration sync confirms it, so it may run ahead or behind."
                readAlong.place != null -> "The page turns with the narration. Turn it yourself to read ahead; Back to narration returns."
                else -> "The highlight appears once this part of the recording is placed in the book."
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(words, role = Role.Switch, onValueChange = setWords).testTag("word-highlight"), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text("Highlight each word", style = MaterialTheme.typography.titleSmall)
                    Text("Only where the narration is matched word by word", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(words, null)
            }
            if (narration.phase == SyncPhase.NEEDS_MODEL) TextButton(vm::allowSyncModelDownload) {
                Icon(Icons.Rounded.GraphicEq, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text("Sync with narration · ${sizeLabel(narration.modelBytes)} download")
            }
            if (readAlong.playingHere && document != null && !timed) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Text("Timing", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                SheetAction(Icons.Rounded.AdsClick, "Fix the timing", if (binding == null) "Choose this part's chapter first" else "Tap the sentence you hear, then match it to the audio", enabled = binding != null) {
                    readAlong.matching = true; readAlong.selected = null; readAlong.session.controller.stopFollowing(); dismiss()
                }
                if ((playback.source?.parts?.size ?: 1) > 1) SheetAction(Icons.AutoMirrored.Rounded.MenuBook, "Choose this part's chapter",
                    binding?.chapterId?.let { id -> document.chapters.firstOrNull { it.id == id }?.title } ?: if (binding?.chapterId == WHOLE_BOOK) "Whole book" else "Not placed yet") { chaptersOpen = true }
                if (binding?.anchors?.isNotEmpty() == true) SheetAction(Icons.Rounded.RestartAlt, "Reset timing for this part", "Clears matches for this part and syncs it again") { vm.resetTextTiming(); dismiss() }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            if (fromListening) SheetAction(Icons.Rounded.Headphones, "Back to the Listening room", "Keeps listening; the reader remembers this page") { dismiss(); vm.back() }
            SheetAction(Icons.Rounded.HeadsetOff, "Stop reading along", "Keep reading here; narration keeps its own place") { dismiss(); vm.setReadAlong(false) }
        }
    }
    if (chaptersOpen && document != null) AlertDialog(onDismissRequest = { chaptersOpen = false }, title = { Text("Text for this audio part") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                itemsIndexed(document.chapters) { _, chapter ->
                    val chosen = chapter.id == binding?.chapterId
                    Text(chapter.title + if (chosen) " · selected" else "", style = MaterialTheme.typography.bodyLarge,
                        color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { vm.selectTextChapter(chapter.id); chaptersOpen = false }.padding(12.dp))
                }
            }
        }, confirmButton = { TextButton({ chaptersOpen = false }) { Text("Done") } })
}

@Composable
private fun SheetAction(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String, enabled: Boolean = true, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, role = Role.Button, onClick = action).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        val tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f)
        Icon(icon, null, tint = tint)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The Listening room's way into read along: Read along when the book has an ebook, otherwise a way to find one
 * using the book's Find ebook sheet.
 */
@Composable
fun ReadAlongEntry(vm: NarrioViewModel, book: Audiobook, dense: Boolean, modifier: Modifier = Modifier) {
    val formatsFlow = remember(book.id) { vm.readingLibrary.value.observeBook(book) }
    val formats by formatsFlow.collectAsStateWithLifecycle(null)
    val search by vm.ebookSearch.collectAsStateWithLifecycle()
    val providers by vm.ebookProviderSettings.providers.collectAsStateWithLifecycle()
    val connected by vm.connected.collectAsStateWithLifecycle()
    val addons by vm.graph.addons.installed.collectAsStateWithLifecycle()
    val searchLinks = remember(book.title, book.author, addons) { vm.graph.addons.ebookSearchLinks(book) }
    var sheet by remember { mutableStateOf(false) }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.importEbook(it) }
    val ebook = formats?.ebook == true
    val find = { sheet = true; vm.openEbookSearch(book) }
    if (dense) IconButton(if (ebook) vm::readAlong else find, modifier.testTag("read-along")) {
        Icon(if (ebook) Icons.AutoMirrored.Rounded.MenuBook else Icons.Rounded.Search, if (ebook) "Read along" else "Find the ebook to read along", tint = MaterialTheme.colorScheme.primary)
    } else Row(modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (ebook) FilledTonalButton(vm::readAlong, Modifier.testTag("read-along")) {
            Icon(Icons.AutoMirrored.Rounded.MenuBook, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Read along")
        } else OutlinedButton(find, Modifier.testTag("find-ebook-to-read-along"), enabled = formats != null) {
            Icon(Icons.Rounded.Search, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Find the ebook")
        }
        Text(when {
            formats == null -> ""
            !ebook -> "Add the edition of this recording to read along."
            formats?.pairing == PairingStatus.MISMATCH -> "The ebook doesn't match this narration."
            else -> "The page turns with the narration."
        }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
    }
    val current = formats
    if (sheet && current != null) EbookSheet(book, current, search, providers, connected, "Add and read along", EbookActions(
        add = { vm.addEbook(book, it) }, addAndOpen = { vm.addEbook(book, it) { vm.readAlong() } }, retry = vm::retryEbookSource,
        searchAgain = { vm.findEbooks(book, force = true) },
        chooseFile = { vm.beginEbookImport(book); file.launch(arrayOf("application/epub+zip", "text/plain", "application/octet-stream")) },
        activate = { vm.chooseEdition(book, it.id) }, remove = { vm.removeEbookEdition(book, it) }, openSearch = { vm.openEbookWebsite(book, it) },
        sourceSettings = { sheet = false; vm.openSourceSettings() }, connectTorBox = vm::requestTorBoxConnect,
        searchWith = { vm.searchEbooksWith(book, it) },
    ), dismiss = { sheet = false }, searchLinks = searchLinks)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Modifier.navigationBarsIgnoringVisibilityPadding() = windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Modifier.statusBarsIgnoringVisibilityPadding() = windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
