package app.narrio.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.narrio.domain.*
import app.narrio.playback.ListeningState
import kotlinx.coroutines.flow.flowOf

@Composable
fun MiniPlayer(vm: NarrioViewModel, modifier: Modifier = Modifier) {
    val state by vm.playback.collectAsStateWithLifecycle()
    val book = state.book ?: return
    Surface(modifier.fillMaxWidth().clickable { vm.playerOpen.value = true }, color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
        Column {
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BookCover(book, Modifier.width(38.dp).height(52.dp))
                Column(Modifier.weight(1f)) {
                    Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (state.buffering) "Finding your place…" else "${state.part?.title ?: "Ready to listen"} · ${formatTime(state.positionMs)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton({ vm.graph.playback.service?.skip(30_000) }) { Icon(Icons.Rounded.Forward30, "Skip forward 30 seconds") }
                IconButton({ vm.graph.playback.service?.toggle() }) { if (state.buffering) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "Pause" else "Play", tint = MaterialTheme.colorScheme.primary) }
            }
            if (state.durationMs > 0) LinearProgressIndicator(progress = { (state.positionMs.toFloat() / state.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(2.dp), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.outlineVariant)
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
    val controls: @Composable () -> Unit = {
        Transport(vm, state, { speedOpen = true }, { sleepOpen = true }, { partsOpen = true }, { bookmarksOpen = true })
    }
    if (hingeY != null) {
        Column(modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().height(hingeY.dp.coerceAtLeast(0.dp)).clipToBounds().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                PlayerHeading(vm, compact)
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp)) {
                    val coverHeight = minOf(maxHeight, maxWidth * .55f * 1.45f).coerceAtLeast(1.dp)
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        BookCover(book, Modifier.width(coverHeight / 1.45f).height(coverHeight), large = true)
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
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp)) { controls() }
        }
    } else {
        LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            item { PlayerHeading(vm, compact) }
            item {
                BookCover(book, Modifier.width(if (compact) 204.dp else 188.dp).height(if (compact) 295.dp else 272.dp), large = true)
                Spacer(Modifier.height(24.dp))
                Text(book.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp)); Text(book.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
                Text(narrationLabel(book), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
            item { controls() }
        }
    }
    if (speedOpen) ChoiceDialog("Set the pace", { speedOpen = false }) {
        listOf(.5f, .75f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f).forEach { speed ->
            TextButton({ vm.graph.playback.service?.speed(speed); speedOpen = false }, Modifier.fillMaxWidth()) { Text("${speedLabel(speed)} speed${if (state.speed == speed) " · selected" else ""}") }
        }
    }
    if (sleepOpen) ChoiceDialog("Drift off gently", { sleepOpen = false }) {
        Text("Playback pauses when your timer ends.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 12.dp))
        listOf(15, 30, 45, 60, 90).forEach { minutes -> TextButton({ vm.graph.playback.service?.sleep(minutes); sleepOpen = false }, Modifier.fillMaxWidth()) { Text("In $minutes minutes") } }
        TextButton({ vm.graph.playback.service?.sleep(0, true); sleepOpen = false }, Modifier.fillMaxWidth()) { Text("At the end of this audio part") }
        TextButton({ vm.graph.playback.service?.sleep(0); sleepOpen = false }, Modifier.fillMaxWidth()) { Text("Turn timer off") }
    }
    if (partsOpen) ModalBottomSheet(onDismissRequest = { partsOpen = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
            item { Text(if (state.chapters.isNotEmpty()) "Chapters & audio parts" else "Audio parts", style = MaterialTheme.typography.headlineMedium); Spacer(Modifier.height(12.dp)); Text("File boundaries may differ from book chapters.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (state.chapters.isNotEmpty()) items(state.chapters) { chapter ->
                TextButton({ vm.graph.playback.service?.seek(chapter.startMs); partsOpen = false }, Modifier.fillMaxWidth()) { Text("${formatTime(chapter.startMs)}  ${chapter.title}", Modifier.fillMaxWidth()) }
            }
            itemsIndexed(state.source?.parts.orEmpty(), key = { _, part -> part.id }) { index, part ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (index == state.partIndex) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                    .clickable { vm.graph.playback.service?.part(index); partsOpen = false }.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("${index + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) { Text(part.title, style = MaterialTheme.typography.titleSmall); if (part.durationMs > 0) Text(formatTime(part.durationMs), style = MaterialTheme.typography.bodySmall) }
                    if (index == state.partIndex) Icon(Icons.Rounded.GraphicEq, "Current audio part", Modifier.size(20.dp))
                }
            }
        }
    }
    if (bookmarksOpen) {
        val bookmarkFlow = remember(book.id) { vm.graph.library.bookmarks(book.id) }
        val bookmarks by bookmarkFlow.collectAsStateWithLifecycle(emptyList())
        ModalBottomSheet(onDismissRequest = { bookmarksOpen = false }, containerColor = MaterialTheme.colorScheme.surfaceContainer, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.heightIn(max = 480.dp)) {
                item { Text("Moments to keep", style = MaterialTheme.typography.headlineMedium); Spacer(Modifier.height(12.dp)); FilledTonalButton({ vm.bookmark() }) { Icon(Icons.Rounded.BookmarkAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Bookmark this moment") } }
                if (bookmarks.isEmpty()) item { Text("Save a line, an idea, or a moment. Each bookmark remembers its exact audio part.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(bookmarks, key = { it.id }) { bookmark ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { vm.jumpBookmark(bookmark); bookmarksOpen = false }.padding(vertical = 12.dp)) { Text(formatTime(bookmark.positionMs), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary); Text(bookmark.label, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        IconButton({ vm.deleteBookmark(bookmark.id) }) { Icon(Icons.Rounded.DeleteOutline, "Delete bookmark at ${formatTime(bookmark.positionMs)}") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerHeading(vm: NarrioViewModel, compact: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (compact) IconButton({ vm.playerOpen.value = false }) { Icon(Icons.Rounded.KeyboardArrowDown, "Collapse player") }
        Text("Listening room", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.weight(1f))
        IconButton({ vm.bookmark() }) { Icon(Icons.Rounded.BookmarkAdd, "Bookmark this moment", tint = MaterialTheme.colorScheme.primary) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Transport(vm: NarrioViewModel, state: ListeningState, speed: () -> Unit, sleep: () -> Unit, parts: () -> Unit, bookmarks: () -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var slider by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(state.positionMs, state.partIndex) { if (!dragging) slider = state.positionMs.toFloat() }
    Column(Modifier.fillMaxWidth()) {
        Text(state.part?.title ?: "Ready to listen", style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text("Part ${state.partIndex + 1} of ${state.source?.parts?.size ?: 1} · ${if (state.source?.delivery == "torbox") "TorBox" else "Internet Archive"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = slider.coerceIn(0f, state.durationMs.coerceAtLeast(1).toFloat()), onValueChange = { dragging = true; slider = it },
            onValueChangeFinished = { vm.graph.playback.service?.seek(slider.toLong()); dragging = false },
            valueRange = 0f..state.durationMs.coerceAtLeast(1).toFloat(), enabled = state.durationMs > 0,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Listening position" })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(if (dragging) slider.toLong() else state.positionMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (state.durationMs > 0) "−${formatTime((state.durationMs - state.positionMs).coerceAtLeast(0))}" else "Loading length", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton({ vm.graph.playback.service?.skip(-30_000) }, Modifier.size(56.dp)) { Icon(Icons.Rounded.Replay30, "Rewind 30 seconds", Modifier.size(32.dp)) }
            FilledIconButton({ vm.graph.playback.service?.toggle() }, Modifier.size(82.dp), shape = CircleShape) {
                if (state.buffering) CircularProgressIndicator(Modifier.size(32.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 3.dp)
                else Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "Pause" else "Play", Modifier.size(42.dp))
            }
            IconButton({ vm.graph.playback.service?.skip(30_000) }, Modifier.size(56.dp)) { Icon(Icons.Rounded.Forward30, "Skip forward 30 seconds", Modifier.size(32.dp)) }
        }
        Spacer(Modifier.height(28.dp))
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(speed) { Text(speedLabel(state.speed)) }
            OutlinedButton(sleep) { Icon(Icons.Rounded.Bedtime, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(if (state.sleepAtEnd) "End of part" else if (state.sleepUntil > 0) "${((state.sleepUntil - System.currentTimeMillis()).coerceAtLeast(0) / 60_000) + 1}m" else "Sleep") }
            OutlinedButton(parts) { Icon(Icons.AutoMirrored.Rounded.FormatListBulleted, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Parts") }
            IconButton(bookmarks) { Icon(Icons.Rounded.Bookmarks, "Open bookmarks") }
        }
        state.error?.let { Spacer(Modifier.height(16.dp)); RecoveryState("Let's find your place again", it) { vm.graph.playback.service?.retry() } }
        Spacer(Modifier.height(24.dp))
        Text("Let the rest of the world wait.", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun speedLabel(speed: Float) = "${speed.toString().removeSuffix(".0")}×"
@Composable
private fun ChoiceDialog(title: String, dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title, style = MaterialTheme.typography.headlineMedium) }, text = { Column(Modifier.verticalScroll(rememberScrollState()), content = content) }, confirmButton = { TextButton(dismiss) { Text("Done") } })
}
