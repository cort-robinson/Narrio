package app.narrio.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.narrio.domain.*
import app.narrio.playback.ListeningState
import kotlinx.coroutines.launch

@Composable
fun MiniPlayer(vm: NarrioViewModel, modifier: Modifier = Modifier) {
    val state by vm.playback.collectAsStateWithLifecycle()
    val book = state.book ?: return
    val interaction = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val lift = remember { Animatable(0f) }
    val open = { vm.playerOpen.value = true }
    var armed by remember { mutableStateOf(false) }
    val progress by animateFloatAsState(if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f, tween(1000, easing = LinearEasing), label = "mini progress")
    // A short upward flick opens the listening room, the same way the player can be pulled back down.
    // Pulling it down past the threshold closes Now playing; the shell offers Undo.
    Surface(open, modifier.fillMaxWidth().pressScale(interaction, .985f)
        .semantics { customActions = listOf(CustomAccessibilityAction("Close player") { vm.dismissPlayback(); true }) }
        .draggable(rememberDraggableState { delta ->
            scope.launch {
                lift.snapTo((lift.value + delta).coerceIn(-160f, 160f))
                val past = lift.value > 96f
                if (past != armed) { armed = past; if (past) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) }
            }
        }, Orientation.Vertical,
            onDragStopped = { velocity ->
                armed = false
                when {
                    lift.value > 96f || (lift.value > 24f && velocity > 1800f) -> { vm.dismissPlayback(); lift.snapTo(0f) }
                    lift.value < -72f || velocity < -1400f -> { open(); lift.animateTo(0f, Motion.responsive()) }
                    else -> lift.animateTo(0f, Motion.responsive())
                }
            })
        .graphicsLayer { translationY = lift.value * .35f; alpha = 1f - (lift.value / 160f).coerceIn(0f, 1f) * .6f },
        color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp, interactionSource = interaction) {
        Column {
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BookCover(book, Modifier.width(38.dp).height(52.dp), sharedKey = "now-${book.id}")
                Column(Modifier.weight(1f)) {
                    Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AnimatedVisibility(state.playing, enter = expandHorizontally() + fadeIn(), exit = shrinkHorizontally() + fadeOut()) { NarrationPulse(true, Modifier.size(12.dp, 10.dp)) }
                        Text(if (state.buffering) "Buffering…" else "${state.part?.title ?: "Ready to listen"} · ${formatTime(state.positionMs)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                SkipButton(true, 48.dp, 24.dp) { vm.graph.playback.service?.skip(30_000) }
                IconButton({ haptics.performHapticFeedback(if (state.playing) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn); vm.graph.playback.service?.toggle() }) {
                    PlayGlyph(state, 24.dp, MaterialTheme.colorScheme.primary, 20.dp)
                }
            }
            if (state.durationMs > 0) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(2.dp), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.outlineVariant, gapSize = 0.dp, drawStopIndicator = {})
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(vm: NarrioViewModel, compact: Boolean, modifier: Modifier = Modifier, hingeY: Float? = null, hingeGap: Float = 0f) {
    val state by vm.playback.collectAsStateWithLifecycle()
    val book = state.book ?: return
    var speedOpen by remember { mutableStateOf(false) }
    var sleepOpen by remember { mutableStateOf(false) }
    var partsOpen by remember { mutableStateOf(false) }
    var bookmarksOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // Pull the room down to tuck it back into the mini-player.
    val pull = remember { Animatable(0f) }
    val threshold = with(density) { 120.dp.toPx() }
    var armed by remember { mutableStateOf(false) }
    val pullDown = if (compact && hingeY == null) Modifier.draggable(rememberDraggableState { delta ->
        scope.launch {
            pull.snapTo((pull.value + delta).coerceAtLeast(0f))
            val past = pull.value > threshold
            if (past != armed) { armed = past; if (past) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) }
        }
    }, Orientation.Vertical, onDragStopped = { velocity ->
        if (pull.value > threshold || velocity > 2200f) vm.playerOpen.value = false else pull.animateTo(0f, Motion.responsive())
    }) else Modifier
    // Short windows (a phone in landscape) fold the tabs into the heading row to keep play in view.
    val heading: @Composable (Boolean) -> Unit = { dense ->
        if (dense) Row(pullDown, verticalAlignment = Alignment.CenterVertically) {
            if (compact) IconButton({ vm.playerOpen.value = false }) { Icon(Icons.Rounded.KeyboardArrowDown, "Collapse player") }
            Text("Now playing", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            ReadAlongEntry(vm, book, dense = true)
            BookmarkNow(vm)
            ClosePlayback(vm)
        } else Column(pullDown) {
            PlayerHeading(vm, compact)
            ReadAlongEntry(vm, book, dense = false, modifier = Modifier.padding(start = if (compact) 12.dp else 0.dp, bottom = 4.dp))
        }
    }
    val controls: @Composable (Boolean) -> Unit = { dense ->
        Transport(vm, state, { speedOpen = true }, { sleepOpen = true }, { partsOpen = true }, { bookmarksOpen = true }, dense)
    }
    if (hingeY != null) {
        // Tabletop: artwork and words above the hinge, hands-on controls below it.
        Column(modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().height(hingeY.dp.coerceAtLeast(0.dp)).clipToBounds().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                heading(false)
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp)) {
                    val coverHeight = minOf(maxHeight, maxWidth * .55f * 1.45f).coerceAtLeast(1.dp)
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        ListeningCover(book, state.playing, Modifier.width(coverHeight / 1.45f).height(coverHeight))
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            Text(book.title, style = MaterialTheme.typography.headlineLarge)
                            Spacer(Modifier.height(12.dp))
                            Text(book.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            Text(narrationLabel(book), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }
            Spacer(Modifier.height(hingeGap.dp.coerceAtLeast(12.dp)))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp)) { controls(false) }
        }
    } else {
        // Compact: an opaque room that rises over the catalog rather than a ghost layered on top of it.
        BoxWithConstraints(modifier.fillMaxSize()) {
        val dense = maxHeight < 480.dp
        Column(Modifier.fillMaxSize().then(if (compact) Modifier.background(MaterialTheme.colorScheme.background) else Modifier).graphicsLayer {
            translationY = pull.value
            val drag = (pull.value / (threshold * 4)).coerceIn(0f, 1f)
            alpha = 1f - drag * .5f
            scaleX = 1f - drag * .08f; scaleY = scaleX
        }) {
            Column(Modifier.padding(horizontal = if (compact) 16.dp else 24.dp).padding(top = if (dense) 0.dp else 8.dp)) { heading(dense) }
            Box(Modifier.weight(1f)) { AudioStage(book, state, compact, pullDown) { controls(dense) } }
        }
        }
    }
    if (speedOpen) SpeedDialog(vm, state) { speedOpen = false }
    if (sleepOpen) SleepDialog(vm, state) { sleepOpen = false }
    if (partsOpen) ModalBottomSheet(onDismissRequest = { partsOpen = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        // Open where the listener is, not at the top of a 40-part list.
        val list = rememberLazyListState(initialFirstVisibleItemIndex = if (state.chapters.isNotEmpty()) 0 else (state.partIndex - 1).coerceAtLeast(0) + 1)
        val currentChapter = state.chapters.indexOfLast { it.startMs <= state.positionMs }
        LazyColumn(state = list, contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
            item { Text(if (state.chapters.isNotEmpty()) "Chapters & audio parts" else "Audio parts", style = MaterialTheme.typography.headlineMedium); Spacer(Modifier.height(12.dp)); Text("File boundaries may differ from book chapters.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (state.chapters.isNotEmpty()) itemsIndexed(state.chapters) { index, chapter ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { vm.graph.playback.service?.seek(chapter.startMs); partsOpen = false }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(formatTime(chapter.startMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(chapter.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = if (index == currentChapter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    if (index == currentChapter) NarrationPulse(state.playing)
                }
            }
            itemsIndexed(state.source?.parts.orEmpty(), key = { _, part -> part.id }) { index, part ->
                val current = index == state.partIndex
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                    .clickable { vm.graph.playback.service?.part(index); partsOpen = false }.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("${index + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) { Text(part.title, style = MaterialTheme.typography.titleSmall); if (part.durationMs > 0) Text(formatTime(part.durationMs), style = MaterialTheme.typography.bodySmall) }
                    if (current) NarrationPulse(state.playing, Modifier.semantics { contentDescription = "Current audio part" })
                }
            }
        }
    }
    if (bookmarksOpen) ListeningBookmarksSheet(vm, state, book) { bookmarksOpen = false }
}

/**
 * The audio stage restructures for its window instead of scrolling the play button away:
 * side by side on short wide canvases, a fitted column on tall ones, and a scrolling column only when
 * neither fits (small windows or very large text).
 */
@Composable
private fun AudioStage(book: Audiobook, state: ListeningState, compact: Boolean, pullDown: Modifier, controls: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth; val height = maxHeight
        val wide = width >= 560.dp && width > height * 1.15f
        when {
            wide -> Row(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                val coverHeight = minOf(height - 16.dp, width * .4f * 1.45f).coerceAtLeast(120.dp)
                ListeningCover(book, state.playing, pullDown.width(coverHeight / 1.45f).height(coverHeight))
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
                    TitleBlock(book, 2)
                    Spacer(Modifier.height(20.dp))
                    controls()
                }
            }
            height < 480.dp -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp)) {
                // A short, narrow pane (a landscape phone's split view): the book becomes a byline beside its cover.
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    ListeningCover(book, state.playing, pullDown.width(64.dp).height(93.dp))
                    TitleBlock(book, 1)
                }
                Spacer(Modifier.height(12.dp))
                controls()
            }
            else -> {
                // Controls are measured first; the cover takes whatever height remains so play stays in view.
                // Only when even a small cover cannot fit (short windows, very large text) does the stage scroll.
                val density = LocalDensity.current
                var controlsHeight by remember { mutableStateOf(430.dp) }
                val cap = minOf(width * .82f * 1.45f, 470.dp)
                val space = height - controlsHeight - 40.dp
                val coverHeight = minOf(space, cap).coerceAtLeast(if (compact) 150.dp else 180.dp)
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                    Box(Modifier.fillMaxWidth().then(pullDown).padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
                        ListeningCover(book, state.playing, Modifier.width(coverHeight / 1.45f).height(coverHeight))
                    }
                    Column(Modifier.onSizeChanged { controlsHeight = with(density) { it.height.toDp() } }) {
                        TitleBlock(book, 2)
                        Spacer(Modifier.height(16.dp))
                        controls()
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TitleBlock(book: Audiobook, maxLines: Int) {
    Column(Modifier.fillMaxWidth()) {
        Text(book.title, style = MaterialTheme.typography.headlineMedium, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp)); Text(book.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(narrationLabel(book), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 4.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** The book leans in while the narrator reads and settles back when you pause. */
@Composable
private fun ListeningCover(book: Audiobook, playing: Boolean, modifier: Modifier) {
    val scale by animateFloatAsState(if (playing) 1f else .9f, spring(dampingRatio = .7f, stiffness = Spring.StiffnessLow), label = "cover scale")
    val lift by animateDpAsState(if (playing) 22.dp else 4.dp, spring(stiffness = Spring.StiffnessLow), label = "cover lift")
    BookCover(book, modifier.graphicsLayer {
        scaleX = scale; scaleY = scale
        shadowElevation = lift.toPx(); shape = RoundedCornerShape(8.dp)
    }, large = true, sharedKey = "now-${book.id}")
}

@Composable
private fun PlayerHeading(vm: NarrioViewModel, compact: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (compact) IconButton({ vm.playerOpen.value = false }) { Icon(Icons.Rounded.KeyboardArrowDown, "Collapse player") }
        Text("Now playing", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.weight(1f))
        BookmarkNow(vm)
        ClosePlayback(vm)
    }
}

/** Stops listening and clears Now playing; the shelf keeps the book and its place. */
@Composable
private fun ClosePlayback(vm: NarrioViewModel) {
    val haptics = LocalHapticFeedback.current
    IconButton({ haptics.performHapticFeedback(HapticFeedbackType.Reject); vm.dismissPlayback() }) { Icon(Icons.Rounded.Close, "Close player") }
}

/** Bookmarking gives a small pop and a confirming tick: the moment has been kept. */
@Composable
private fun BookmarkNow(vm: NarrioViewModel) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val pop = remember { Animatable(1f) }
    IconButton({
        haptics.performHapticFeedback(HapticFeedbackType.Confirm); vm.bookmark()
        scope.launch { pop.snapTo(1.35f); pop.animateTo(1f, spring(dampingRatio = .4f, stiffness = Spring.StiffnessMedium)) }
    }) { Icon(Icons.Rounded.BookmarkAdd, "Bookmark this moment", Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value }, tint = MaterialTheme.colorScheme.primary) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Transport(vm: NarrioViewModel, state: ListeningState, speed: () -> Unit, sleep: () -> Unit, parts: () -> Unit, bookmarks: () -> Unit, dense: Boolean = false) {
    val sync by vm.readingSync.collectAsStateWithLifecycle()
    val confidence = sync.confidence.takeIf { sync.bookId == state.book?.id && sync.sourceId == state.source?.id } ?: MappingConfidence.UNMAPPED
    var dragging by remember { mutableStateOf(false) }
    var slider by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(state.positionMs, state.partIndex) { if (!dragging) slider = state.positionMs.toFloat() }
    Column(Modifier.fillMaxWidth()) {
        Text(state.part?.title ?: "Ready to listen", style = MaterialTheme.typography.titleSmall, maxLines = if (dense) 1 else 2, overflow = TextOverflow.Ellipsis)
        if (!dense) Spacer(Modifier.height(4.dp))
        if (!dense) Text("Part ${state.partIndex + 1} of ${state.source?.parts?.size ?: 1} · ${if (state.source?.delivery == "torbox") "TorBox" else "Internet Archive"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = slider.coerceIn(0f, state.durationMs.coerceAtLeast(1).toFloat()), onValueChange = { dragging = true; slider = it },
            onValueChangeFinished = { vm.graph.playback.service?.seek(slider.toLong()); dragging = false },
            valueRange = 0f..state.durationMs.coerceAtLeast(1).toFloat(), enabled = state.durationMs > 0,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Listening position" })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            EstimatedPlace(formatTime(if (dragging) slider.toLong() else state.positionMs), if (dragging) MappingConfidence.EXACT else confidence, Modifier.testTag("mapped-audio-position"), color = if (dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (state.durationMs > 0) "−${formatTime((state.durationMs - (if (dragging) slider.toLong() else state.positionMs)).coerceAtLeast(0))}" else "Loading length", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(if (dense) 4.dp else 16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            SkipButton(false, 56.dp, 32.dp) { vm.graph.playback.service?.skip(-30_000) }
            PlayButton(state, if (dense) 64.dp else 82.dp, if (dense) 34.dp else 42.dp) { vm.graph.playback.service?.toggle() }
            SkipButton(true, 56.dp, 32.dp) { vm.graph.playback.service?.skip(30_000) }
        }
        Spacer(Modifier.height(if (dense) 4.dp else 20.dp))
        // A quiet tool tray: four equal slots that never wrap into an orphaned row.
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ToolSlot(Icons.Rounded.Speed, speed) { AnimatedContent(speedLabel(state.speed), transitionSpec = { (slideInVertically { it } + fadeIn()).togetherWith(slideOutVertically { -it } + fadeOut()) }, label = "speed") { FitLabel(it) } }
            ToolSlot(Icons.Rounded.Bedtime, sleep, active = state.sleepAtEnd || state.sleepUntil > 0) {
                FitLabel(sleepLabel(state))
            }
            ToolSlot(Icons.AutoMirrored.Rounded.FormatListBulleted, parts) { FitLabel("Parts") }
            ToolSlot(Icons.Rounded.Bookmarks, bookmarks) { FitLabel("Bookmarks") }
        }
        AnimatedVisibility(state.error != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column { Spacer(Modifier.height(16.dp)); RecoveryState("Playback stopped", state.error.orEmpty()) { vm.graph.playback.service?.retry() } }
        }
    }
}

/** Playback speed choices, shared by the Listening room and read along. */
@Composable
internal fun SpeedDialog(vm: NarrioViewModel, state: ListeningState, dismiss: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    ChoiceDialog("Playback speed", dismiss) {
        Column(Modifier.selectableGroup()) {
            listOf(.5f, .75f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f).forEach { speed ->
                OptionRow("${speedLabel(speed)} speed", state.speed == speed) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); vm.graph.playback.service?.speed(speed); dismiss() }
            }
        }
    }
}

/** Sleep timer choices, shared by the Listening room and read along. */
@Composable
internal fun SleepDialog(vm: NarrioViewModel, state: ListeningState, dismiss: () -> Unit) {
    ChoiceDialog("Sleep timer", dismiss) {
        Text(when {
            state.sleepAtEnd -> "Playback pauses at the end of this audio part."
            state.sleepUntil > 0 -> "Playback pauses in about ${sleepMinutes(state)} minutes."
            else -> "Choose when playback pauses."
        }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
        Column(Modifier.selectableGroup()) {
            listOf(15, 30, 45, 60, 90).forEach { minutes -> OptionRow("In $minutes minutes", false) { vm.graph.playback.service?.sleep(minutes); dismiss() } }
            OptionRow("At the end of this audio part", state.sleepAtEnd) { vm.graph.playback.service?.sleep(0, true); dismiss() }
            OptionRow("Turn timer off", !state.sleepAtEnd && state.sleepUntil == 0L) { vm.graph.playback.service?.sleep(0); dismiss() }
        }
    }
}

internal fun sleepMinutes(state: ListeningState) = ((state.sleepUntil - System.currentTimeMillis()).coerceAtLeast(0) / 60_000) + 1

/** The sleep slot's short label: "Sleep", minutes left, or the end of the part. */
internal fun sleepLabel(state: ListeningState) = if (state.sleepAtEnd) "End of part" else if (state.sleepUntil > 0) "${sleepMinutes(state)}m" else "Sleep"

/** Circle while paused, a softened square while playing: the control's shape tells you the state. */
@Composable
internal fun PlayButton(state: ListeningState, size: Dp, iconSize: Dp, toggle: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current
    val corner by animateIntAsState(if (state.playing) 30 else 50, Motion.responsive(), label = "play shape")
    FilledIconButton({ haptics.performHapticFeedback(if (state.playing) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn); toggle() },
        Modifier.size(size).pressScale(interaction, .9f), shape = RoundedCornerShape(corner), interactionSource = interaction) {
        PlayGlyph(state, iconSize, MaterialTheme.colorScheme.onPrimary, iconSize * .75f)
    }
}

@Composable
private fun PlayGlyph(state: ListeningState, iconSize: Dp, tint: Color, spinner: Dp) {
    val glyph = when { state.buffering -> 0; state.playing -> 1; else -> 2 }
    AnimatedContent(glyph, transitionSpec = {
        (scaleIn(Motion.responsive(), initialScale = .4f) + fadeIn(tween(Motion.SHORT))).togetherWith(scaleOut(tween(Motion.SHORT), targetScale = .4f) + fadeOut(tween(Motion.SHORT)))
    }, contentAlignment = Alignment.Center, label = "play glyph") { shown ->
        when (shown) {
            0 -> CircularProgressIndicator(Modifier.size(spinner), color = tint, strokeWidth = 3.dp)
            1 -> Icon(Icons.Rounded.Pause, "Pause", Modifier.size(iconSize), tint = tint)
            else -> Icon(Icons.Rounded.PlayArrow, "Play", Modifier.size(iconSize), tint = tint)
        }
    }
}

/** Skips give a small turn in their direction, so thirty seconds feels like a physical nudge. */
@Composable
internal fun SkipButton(forward: Boolean, size: Dp, iconSize: Dp, skip: () -> Unit) {
    val turn = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    IconButton({
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); skip()
        scope.launch { turn.animateTo(if (forward) 1f else -1f, tween(90, easing = Motion.EmphasizedDecelerate)); turn.animateTo(0f, spring(dampingRatio = .45f, stiffness = Spring.StiffnessLow)) }
    }, Modifier.size(size)) {
        Icon(if (forward) Icons.Rounded.Forward30 else Icons.Rounded.Replay30, if (forward) "Skip forward 30 seconds" else "Rewind 30 seconds",
            Modifier.size(iconSize).graphicsLayer { rotationZ = turn.value * 30f })
    }
}

@Composable
internal fun RowScope.ToolSlot(icon: androidx.compose.ui.graphics.vector.ImageVector, open: () -> Unit, active: Boolean = false, label: @Composable () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val tint by animateColorAsState(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, label = "tool")
    Column(Modifier.weight(1f).fillMaxHeight().heightIn(min = 56.dp).pressScale(interaction, .94f).clip(RoundedCornerShape(14.dp))
        .clickable(interaction, LocalIndication.current, role = Role.Button, onClick = open).padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
        Icon(icon, null, Modifier.size(22.dp), tint = tint)
        CompositionLocalProvider(LocalContentColor provides tint, LocalTextStyle provides MaterialTheme.typography.labelMedium) { label() }
    }
}

/** Tool labels shrink a step or two at large text sizes rather than truncating mid-word. */
@Composable
internal fun FitLabel(text: String) {
    val style = LocalTextStyle.current
    BasicText(text, style = style.copy(color = LocalContentColor.current), maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = (style.fontSize.value * .7f).sp, maxFontSize = style.fontSize, stepSize = .5.sp))
}

@Composable
private fun OptionRow(label: String, selected: Boolean, choose: () -> Unit) {
    val background by animateColorAsState(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, label = "option")
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(background)
        .selectable(selected, role = Role.RadioButton, onClick = choose).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, null)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

internal fun speedLabel(speed: Float) = "${speed.toString().removeSuffix(".0")}×"
@Composable
private fun ChoiceDialog(title: String, dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title, style = MaterialTheme.typography.headlineMedium) }, text = { Column(Modifier.verticalScroll(rememberScrollState()), content = content) }, confirmButton = { TextButton(dismiss) { Text("Done") } })
}
