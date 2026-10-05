package app.narrio.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.narrio.domain.*
import app.narrio.playback.ListeningState
import app.narrio.playback.SyncPhase

/** A listening surface, not a paginated ebook reader. Audio remains owned by the service. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowAlongScreen(vm: NarrioViewModel, state: ListeningState, modifier: Modifier = Modifier) {
    val textState by vm.bookText.collectAsStateWithLifecycle()
    val book = state.book ?: return
    val document = textState.document?.takeIf { textState.bookId == book.id }
    var sourcesOpen by rememberSaveable(book.id) { mutableStateOf(false) }
    var chaptersOpen by rememberSaveable(book.id) { mutableStateOf(false) }
    var removeOpen by remember { mutableStateOf(false) }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.importBookText(it) }
    val import: () -> Unit = { vm.beginTextImport(); chooser.launch(arrayOf("application/epub+zip", "text/plain", "text/vtt", "application/octet-stream")) }
    val lookup: () -> Unit = {
        sourcesOpen = true
        vm.clearTextError()
        if (!textState.searched) vm.searchBookText(book.title.replace(Regex("\\s*\\((?:version|dramatic).*?\\)", RegexOption.IGNORE_CASE), ""))
    }
    val binding = remember(document, textState.bindings, state.source, state.partIndex) {
        document?.let { doc -> state.source?.let { source ->
            textState.bindings.firstOrNull { it.documentId == doc.id && it.sourceId == source.id && it.partId == state.part?.id }
                ?: defaultTextBinding(doc, source, state.partIndex)
        } }
    }
    val timeline = remember(document, binding, state.durationMs) { if (document != null && binding != null) FollowAlongTiming.timeline(document, binding, state.durationMs) else null }
    val passages = remember(document, binding) {
        if (document == null) emptyList() else binding?.let { FollowAlongTiming.passages(document, it.chapterId) } ?: document.chapters.firstOrNull()?.passages.orEmpty()
    }
    val active = timeline?.activeIndex(state.positionMs)
    var following by rememberSaveable(document?.id, state.part?.id, binding?.chapterId) { mutableStateOf(true) }
    var adjusting by rememberSaveable(document?.id, state.part?.id, binding?.chapterId) { mutableStateOf(false) }
    var selectedLine by remember(document?.id, state.part?.id, binding?.chapterId) { mutableStateOf<Int?>(null) }
    val list = key(document?.id, state.part?.id, binding?.chapterId) { rememberLazyListState() }
    val dragged by list.interactionSource.collectIsDraggedAsState()
    val context = LocalContext.current
    val animations = animationsEnabled()
    val sync by vm.narrationSync.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { vm.followAlongVisible(true); onDispose { vm.followAlongVisible(false) } }
    val automatic by vm.followAlongAuto.collectAsStateWithLifecycle()
    LaunchedEffect(book.id, document == null, textState.loading, automatic) { if (document == null && !textState.loading && automatic) vm.autoFindBookText() }
    LaunchedEffect(dragged) { if (dragged) following = false }
    LaunchedEffect(active, following, adjusting, binding?.chapterId) {
        if (active != null && following && !adjusting) {
            val offset = -(list.layoutInfo.viewportSize.height / 4)
            if (animations) list.animateScrollToItem(active, offset) else list.scrollToItem(active, offset)
        }
    }

    Column(modifier.fillMaxSize()) {
        if (textState.loading || textState.working) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Loading book text" })
        if (document == null) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item {
                    Text("Read as you listen", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(12.dp))
                    Text(when {
                        textState.finding -> "Looking for this book's ebook. It syncs with the narration once found."
                        textState.autoMissed -> "No matching ebook was found automatically. Search by another title, or choose your own EPUB or text file."
                        else -> "Add the same edition of the book to follow the narration passage by passage."
                    }, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (textState.finding) item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(textState.findingStep.ifBlank { "Finding the ebook" } + "…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                item { Button(lookup, enabled = !textState.working && !textState.finding) { Icon(Icons.Rounded.Search, null); Spacer(Modifier.width(8.dp)); Text("Find book text") } }
                item { OutlinedButton(import, enabled = !textState.working && !textState.finding) { Icon(Icons.Rounded.UploadFile, null); Spacer(Modifier.width(8.dp)); Text("Choose a text file") } }
                item { Text("EPUB or UTF-8 text · saved on this device\nWebVTT timing tracks work with the current audio part.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (state.source?.textFiles?.isNotEmpty() == true) item {
                    Text("This audio source includes book text", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    state.source.textFiles.forEach { candidate -> TextSourceRow(candidate, !textState.working && !textState.finding) { vm.fetchBookText(candidate) } }
                }
                textState.error?.let { message -> item { TextError(message, vm::clearTextError) } }
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(document.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(when {
                            document.timedSourceId.isNotBlank() -> if (binding == null) "Timing track for another audio part" else "Supplied timestamps · this audio part"
                            binding == null && sync.phase == SyncPhase.LISTENING -> "Finding this part in the book…"
                            binding == null -> "Choose a chapter for this audio part"
                            timeline == null -> "Waiting for audio length"
                            timeline.aligned && FollowAlongTiming.synced(binding, state.positionMs) -> "Synced with narration"
                            sync.phase == SyncPhase.LISTENING -> "Syncing with narration…"
                            sync.phase == SyncPhase.DOWNLOADING_MODEL -> "Preparing narration sync · ${(sync.progress * 100).toInt()}%"
                            sync.phase == SyncPhase.UNSUPPORTED -> "Estimated timing · narration sync supports English"
                            timeline.aligned -> "Estimated between synced passages"
                            else -> "Estimated timing"
                        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    IconButton({ sourcesOpen = true; vm.clearTextError() }) { Icon(Icons.Rounded.MoreHoriz, "Manage book text") }
                }
                if (document.timedSourceId.isBlank()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton({ chaptersOpen = true }, Modifier.weight(1f)) {
                            Icon(Icons.AutoMirrored.Rounded.MenuBook, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                            Text(if (binding?.chapterId == WHOLE_BOOK) "Whole book" else document.chapters.firstOrNull { it.id == binding?.chapterId }?.title ?: "Choose chapter", maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.Rounded.ExpandMore, null)
                        }
                        IconButton({ adjusting = !adjusting; selectedLine = active; following = false }, enabled = binding != null && timeline != null) {
                            Icon(Icons.Rounded.Tune, if (adjusting) "Cancel timing adjustment" else "Adjust timing", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text(when {
                        adjusting -> "Tap the line you hear, then match it to the current audio."
                        timeline?.aligned == true -> "Highlighting follows the recognized narration. Adjust timing if a line is off."
                        else -> "Highlighting follows the chapter's pace until the narration is recognized. Adjust timing if it drifts."
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (sync.phase == SyncPhase.NEEDS_MODEL) {
                        sync.message.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        TextButton(vm::allowSyncModelDownload) {
                            Icon(Icons.Rounded.GraphicEq, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                            Text("Sync with narration · ${sizeLabel(sync.modelBytes)} download")
                        }
                    }
                }
                textState.error?.let { TextError(it, vm::clearTextError) }
            }
            if (document.timedSourceId.isNotBlank() && binding == null) {
                Column(Modifier.weight(1f).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("This timing track belongs to another audio part", style = MaterialTheme.typography.headlineSmall)
                    Text("Return to the part it was added to, or choose a track for the part playing now.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(lookup) { Text("Choose book text") }
                }
            } else if (binding == null) {
                Column(Modifier.weight(1f).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Match this part to its chapter", style = MaterialTheme.typography.headlineSmall)
                    Text(if (sync.phase == SyncPhase.LISTENING || sync.phase == SyncPhase.DOWNLOADING_MODEL) "Listening to this part to find where it begins in the book. You can also choose its chapter."
                        else "The audio parts and ebook chapters have different layouts. Choose the text narrated in this part to begin estimated follow along.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button({ chaptersOpen = true }) { Text("Choose chapter") }
                }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(state = list, modifier = Modifier.fillMaxSize().semantics { contentDescription = "Book passages" }, contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        itemsIndexed(passages, key = { _, line -> line.id }) { index, line ->
                            val current = index == active
                            val selected = adjusting && selectedLine == index
                            // The highlight glides from line to line instead of blinking.
                            val ink by animateColorAsState(if (selected) MaterialTheme.colorScheme.secondary else if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                tween(Motion.LONG, easing = Motion.Emphasized), label = "passage")
                            Text(line.text, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = if (current || selected) FontWeight.SemiBold else FontWeight.Normal),
                                color = ink,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                                    if (current) stateDescription = if (timeline?.timed == true) "Current passage" else "Current passage, estimated timing"
                                    if (selected) this.selected = true
                                }.clickable(enabled = timeline != null, onClickLabel = if (adjusting) "Select this line" else if (timeline?.timed == true) "Listen from this passage" else "Seek to this passage using estimated timing") {
                                    if (adjusting) selectedLine = index else { vm.seekTextLine(binding, index); following = true }
                                })
                        }
                    }
                    androidx.compose.animation.AnimatedVisibility(!following && !adjusting && active != null, Modifier.align(Alignment.BottomCenter), enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                        FilledTonalButton({ following = true }, Modifier.padding(16.dp)) {
                            Icon(Icons.Rounded.MyLocation, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Back to current line")
                        }
                    }
                }
                if (adjusting) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton({ adjusting = false; following = true }) { Text("Cancel") }
                        Spacer(Modifier.weight(1f))
                        FilledTonalButton({ selectedLine?.let { vm.matchTextLine(binding, passages[it].id) }; adjusting = false; following = true }, enabled = selectedLine != null && state.durationMs > 0) {
                            Text("Match at ${formatTime(state.positionMs)}")
                        }
                    }
                }
            }
        }
    }

    if (sourcesOpen) ModalBottomSheet(onDismissRequest = { sourcesOpen = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        TextSourcesSheet(vm, state, textState, { sourcesOpen = false; import() }, { vm.fetchBookText(it); sourcesOpen = false }, {
            sourcesOpen = false; removeOpen = true
        })
    }
    if (chaptersOpen && document != null) ModalBottomSheet(onDismissRequest = { chaptersOpen = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(if (state.source?.parts?.size == 1) "Jump to a text chapter" else "Text for this audio part", style = MaterialTheme.typography.headlineMedium)
                Text(state.part?.title.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.source?.parts?.size == 1) Text("Chapter jumps use estimated timing across the whole recording.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { TextButton({ vm.selectTextChapter(WHOLE_BOOK); chaptersOpen = false }, Modifier.fillMaxWidth()) { Text("Whole book${if (binding?.chapterId == WHOLE_BOOK) " · selected" else ""}") } }
            items(document.chapters, key = { it.id }) { chapter -> TextButton({ vm.selectTextChapter(chapter.id); chaptersOpen = false }, Modifier.fillMaxWidth()) {
                Text(chapter.title + if (chapter.id == binding?.chapterId) " · selected" else "", Modifier.fillMaxWidth())
            } }
            if (binding?.anchors?.isNotEmpty() == true) item { OutlinedButton({ vm.resetTextTiming(); chaptersOpen = false }, Modifier.fillMaxWidth()) { Text("Reset timing for this part") } }
        }
    }
    if (removeOpen) AlertDialog(onDismissRequest = { removeOpen = false }, title = { Text("Remove book text?") }, text = { Text("The ebook file and its timing matches will be removed from this device. Your audiobook, bookmarks, and listening progress stay saved.") },
        confirmButton = { TextButton({ vm.removeBookText(); removeOpen = false }) { Text("Remove text") } }, dismissButton = { TextButton({ removeOpen = false }) { Text("Keep text") } })
}

@Composable
private fun TextError(message: String, dismiss: () -> Unit) {
    Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
    TextButton(dismiss) { Text("Dismiss") }
}

@Composable
private fun TextSourceRow(candidate: BookTextSource, enabled: Boolean, select: () -> Unit) {
    TextButton(select, Modifier.fillMaxWidth(), enabled = enabled) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(candidate.title, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(listOf(candidate.author, candidate.language, candidate.format).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp)); Icon(Icons.Rounded.Download, "Use this book text")
    }
}

@Composable
private fun TextSourcesSheet(vm: NarrioViewModel, state: ListeningState, textState: BookTextState, import: () -> Unit, select: (BookTextSource) -> Unit, remove: () -> Unit) {
    var query by rememberSaveable(state.book?.id) { mutableStateOf(state.book?.title?.replace(Regex("\\s*\\(.*?\\)"), "").orEmpty()) }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 600.dp).imePadding(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Book text", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text("Choose the same language and edition as your recording. A matching title alone doesn't guarantee matching narration.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { OutlinedButton(import, Modifier.fillMaxWidth(), enabled = !textState.working) { Icon(Icons.Rounded.UploadFile, null); Spacer(Modifier.width(8.dp)); Text("Choose EPUB, text, or VTT") } }
        item {
            val auto by vm.followAlongAuto.collectAsStateWithLifecycle()
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(auto, role = Role.Switch, onValueChange = vm::setFollowAlongAuto), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text("Find and sync automatically", style = MaterialTheme.typography.titleSmall)
                    Text("Looks for a matching ebook in this recording, TorBox, and Project Gutenberg, then recognizes the narration on this phone to keep the text in step. Audio isn't uploaded.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(auto, null)
            }
        }
        if (state.source?.textFiles?.isNotEmpty() == true) {
            item { Text("From this audio source", style = MaterialTheme.typography.titleMedium) }
            items(state.source.textFiles, key = { it.id }) { TextSourceRow(it, !textState.working) { select(it) } }
        }
        item {
            HorizontalDivider(); Spacer(Modifier.height(16.dp))
            Text("Find a public ebook", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text("Project Gutenberg catalog via Gutendex. Search by title or author; newer books may need a file from your device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(query, { query = it }, label = { Text("Book title or author") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                trailingIcon = { IconButton({ vm.searchBookText(query) }, enabled = query.isNotBlank() && !textState.searching) { Icon(Icons.Rounded.Search, "Search public ebooks") } })
        }
        if (textState.searching) item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Text("Finding book text…") } }
        if (textState.searched && !textState.searching && textState.results.isEmpty() && textState.error == null) item { Text("No public ebook found. Try the title without release details, search the author, or choose your own file.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(textState.results, key = { it.id }) { candidate -> TextSourceRow(candidate, !textState.working) { select(candidate) } }
        textState.error?.let { message -> item { TextError(message, vm::clearTextError) } }
        textState.document?.let { document ->
            item {
                HorizontalDivider(); Spacer(Modifier.height(12.dp))
                Text("Saved on this device", style = MaterialTheme.typography.titleMedium)
                Text("${document.title}\n${document.attribution} · ${document.format}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(remove, enabled = !textState.working) { Text("Remove book text") }
            }
        }
    }
}
