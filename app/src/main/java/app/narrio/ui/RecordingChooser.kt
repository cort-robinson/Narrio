package app.narrio.ui

import app.narrio.data.recordingKey
import app.narrio.data.sameRecording
import app.narrio.data.preparingRecording
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import app.narrio.data.OfflineBook
import app.narrio.data.ShelfEntry
import app.narrio.data.SourceQuality
import app.narrio.domain.*

/*
 * Choose a recording: one sheet for the one decision. Rows are distinct versions in the listener's words (who reads
 * it, the kind, whether it plays now); the listener's own recording leads, tagged "Listening now". Format and
 * delivery are chosen automatically. Release names, seeders, and every source's own results live in Advanced.
 */

/** Where the chooser opens: its simple list, every match, or Advanced. */
enum class ChooserView { LIST, ALL, ADVANCED }

/**
 * [startPositionChoice] replaces the "starts from the beginning" notice when switching away from a recording with a
 * saved place, so a start-near-your-place option can sit there. [advancedSections] adds sections to Advanced (see
 * [AdvancedSources]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingChooser(
    vm: NarrioViewModel, book: Audiobook, current: CurrentRecording?, search: SourceSearchState?, streamed: StreamedSourceSearch?, tally: SearchTally?,
    providers: Map<String, SourceProvider>, pinned: PinnedBest, connected: Boolean, busy: Boolean, starting: Boolean, preparation: Preparation?,
    entry: ShelfEntry?, downloads: List<OfflineBook>, preparingRecording: Audiobook?, refreshDetails: (() -> Unit)?, metadataLoading: Boolean, initialView: ChooserView,
    acceptBetter: () -> Unit, dismiss: () -> Unit,
    startPositionChoice: (@Composable (RecordingSwitch) -> Unit)? = null,
    advancedSections: LazyListScope.(AdvancedContext) -> Unit = {},
) {
    // A book with its own recording looks for the others only now.
    LaunchedEffect(book.id) { vm.findRecordings() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(sheetState, dismiss)
    var advanced by remember { mutableStateOf(initialView == ChooserView.ADVANCED) }
    var showAll by remember { mutableStateOf(initialView == ChooserView.ALL) }
    // Until the listener picks a row, the pick follows their recording, their earlier choice, or the best match.
    var pickedKey by remember { mutableStateOf<String?>(null) }
    val chosen = search?.chosenId?.let { id -> search.results.firstOrNull { it.id == id } }
    val everything = chooserRows(current?.recording, search, showAll = true)
    val automatic = (current?.recording ?: chosen ?: pinned.shown?.recording)?.let { wanted -> everything.firstOrNull { sameRecording(it, wanted) } ?: wanted } ?: everything.firstOrNull()
    val picked = pickedKey?.let { key -> everything.firstOrNull { recordingKey(it) == key } } ?: automatic
    // The pick always shows, even when it isn't one of the distinct versions.
    val shown = chooserRows(current?.recording, search, showAll).let { rows -> if (picked != null && rows.none { sameRecording(it, picked) }) rows + picked else rows }.distinctBy { it.id }
    val hidden = hiddenMatches(current?.recording, search)
    // Format and delivery are automatic; Advanced (or a choice between ready formats) overrides them for this pick.
    var format by remember(picked?.let(::recordingKey)) { mutableStateOf<String?>(null) }
    var delivery by remember(picked?.let(::recordingKey)) { mutableStateOf<String?>(null) }
    val pick: (Audiobook) -> Unit = { recording -> pickedKey = recordingKey(recording); if (current == null || !sameRecording(recording, current.recording)) vm.chooseVersion(recording) }
    // A release chosen in Advanced becomes the pick, shown among every match when it isn't a distinct version.
    val actions = SourceActions(choose = { recording -> pick(recording); if (search?.versions?.contains(recording) != true) showAll = true; advanced = false },
        retry = vm::retrySource, searchAgain = { vm.findSources(book.catalogIdentity(), force = true) },
        connectTorBox = { close { vm.requestTorBoxConnect() } }, sourceSettings = { close { vm.openSourceSettings() } },
        notThisBook = { recording -> vm.advanced.hide(book, recording, providerLabel(recording)) })
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer, sheetState = sheetState) {
        // Inside the sheet's own window, Back leaves Advanced before it closes the sheet.
        BackHandler(advanced) { advanced = false }
        val view = LocalView.current
        val dark = ThemeContrast.foreground(MaterialTheme.colorScheme.background.toArgb()) == 0xFFFFFF
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
        } }
        AnimatedContent(advanced, transitionSpec = { Motion.sharedAxisX(targetState) }, label = "chooser view") { inAdvanced ->
            if (inAdvanced) AdvancedSources(book, picked, current, streamed, tally, providers, pinned.shown?.recording?.id, connected, format, delivery,
                chooseFormat = { chosenFormat, chosenDelivery -> format = chosenFormat; delivery = chosenDelivery }, actions = actions,
                refreshDetails = refreshDetails?.takeIf { current != null && picked != null && sameRecording(picked, current.recording) }, metadataLoading = metadataLoading,
                back = { advanced = false }, extraSections = advancedSections)
            else LazyColumn(Modifier.fillMaxWidth().testTag("recording-chooser"), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "intro") {
                    Text("Choose a recording", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                    Spacer(Modifier.height(6.dp))
                    Text("Each one is a different reading of ${book.title}. Narrio picks the audio format and how it plays.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // A better match than the one Listen plays waits here, never moving the page under the listener.
                if (current == null && pinned.pending != null && (chosen == null || chosen.id == pinned.shown?.recording?.id)) item(key = "better") {
                    BetterMatchFound({ acceptBetter(); pickedKey = null }, Modifier.padding(top = 4.dp))
                }
                items(shown, key = { "recording:${it.id}" }) { recording ->
                    val mine = current != null && sameRecording(recording, current.recording)
                    val phone = mine && current.offline != null || downloads.any { it.complete && sameRecording(it.book, recording) } || onThisPhone(recording)
                    RecordingRow(recording, book, selected = picked?.let { sameRecording(it, recording) } == true,
                        tag = when {
                            mine -> listOfNotNull("Listening now", current.fraction?.takeIf { current.started }?.let { "${(it * 100).toInt().coerceIn(1, 99)}%" }).joinToString(" · ")
                            search?.possible?.any { it.id == recording.id } == true -> "Might be this book"
                            current == null && recording.id == pinned.shown?.recording?.id -> "Best match"
                            else -> null
                        },
                        status = readiness(recording, connected, phone), ready = phone || SourceQuality.ready(recording), enabled = !busy,
                        release = recording.releaseTitle.ifBlank { recording.title }.takeIf { showAll && !mine }) { pick(recording) }
                }
                item(key = "progress") { SearchProgress(streamed, tally, current != null, hidden, showAll, { showAll = !showAll }) }
                if (picked != null) item(key = "actions") {
                    ChooserActions(vm, book, picked, current, entry, preparingRecording, downloads, connected, busy, starting, preparation, format, delivery, { format = it }, startPositionChoice, close)
                }
                item(key = "advanced") {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClickLabel = "Open advanced sources") { advanced = true }
                        .heightIn(min = 56.dp).padding(vertical = 8.dp).semantics(mergeDescendants = true) { }.testTag("advanced-entry"),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Rounded.Tune, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.weight(1f)) {
                            Text("Can't find the right one? Advanced", style = MaterialTheme.typography.titleSmall)
                            Text("Every source's results, release names, formats, and files", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** One recording: who reads it, its kind and language, how it plays, and a tag such as "Listening now · 42%". */
@Composable
private fun RecordingRow(recording: Audiobook, book: Audiobook, selected: Boolean, tag: String?, status: String, ready: Boolean, enabled: Boolean,
                         release: String? = null, choose: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = choose).heightIn(min = 56.dp).padding(end = 12.dp, top = 8.dp, bottom = 8.dp)
        .testTag("recording:${recording.id}"), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, null, Modifier.padding(horizontal = 12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            tag?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
            Text(recordingNarrator(recording).ifBlank { "Narrator not listed" }, style = MaterialTheme.typography.titleSmall)
            val facts = recordingKind(recording, book) + listOfNotNull(recording.sources.map { it.format }.distinct().joinToString(" / ").takeIf(String::isNotBlank),
                (recording.releaseSizeBytes.takeIf { it > 0 } ?: recording.sources.maxOfOrNull { source -> source.parts.sumOf { it.sizeBytes } })?.takeIf { it > 0 }?.let(::sizeLabel))
            if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = muted)
            // Every match can share a narrator and format; their release names tell them apart.
            release?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(when { status == "On this phone" -> Icons.Rounded.OfflinePin; ready -> Icons.Rounded.Bolt; else -> Icons.Rounded.Schedule }, null, Modifier.size(16.dp),
                    tint = if (ready) MaterialTheme.colorScheme.primary else muted)
                Text(status, style = MaterialTheme.typography.labelMedium, color = if (ready) MaterialTheme.colorScheme.primary else muted)
            }
        }
    }
}

/** While sources answer: "Looking for more recordings"; then nothing found, or the Show all toggle. */
@Composable
private fun SearchProgress(streamed: StreamedSourceSearch?, tally: SearchTally?, hasOwn: Boolean, hidden: Int, showAll: Boolean, toggle: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().animateContentSize().semantics { liveRegion = LiveRegionMode.Polite }) {
        when {
            streamed == null || tally == null -> Unit
            !streamed.complete && tally.active > 0 -> Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Working(Modifier.size(16.dp)); Text(if (hasOwn) "Looking for other recordings…" else "Looking for recordings…", style = MaterialTheme.typography.bodyMedium, color = muted)
            }
            tally.found + tally.possible == 0 -> noMatchCopy(streamed, tally).let { copy ->
                Text(if (hasOwn) "No other recordings found" else copy.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                Text(copy.message, style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
        if (hidden > 0 || showAll) TextButton(toggle, Modifier.testTag("show-all-matches")) {
            Text(if (showAll) "Show only different versions" else "Show all matches · $hidden more")
        }
    }
}

/**
 * The pick's one action: Resume or Listen, Get it ready (one tap), or Connect TorBox; a format choice only when it
 * offers several ready ones; Download for offline; and, when leaving a recording with a saved place, what happens to
 * each place. A format the listener picked explicitly is never swapped for another: when it can't play now, the
 * action gets it ready instead.
 */
@Composable
private fun ChooserActions(
    vm: NarrioViewModel, book: Audiobook, picked: Audiobook, current: CurrentRecording?, entry: ShelfEntry?, preparingRecording: Audiobook?,
    downloads: List<OfflineBook>, connected: Boolean, busy: Boolean, starting: Boolean, preparation: Preparation?, format: String?, delivery: String?,
    chooseFormat: (String) -> Unit, startPositionChoice: (@Composable (RecordingSwitch) -> Unit)?, close: (() -> Unit) -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val mine = current != null && sameRecording(picked, current.recording)
    val copies = downloads.filter { sameRecording(it.book, picked) }
    val phoneFormats = copies.filter { it.complete }.map { it.source.format }
    val onPhone = phoneFormats.isNotEmpty() || mine && current.offline != null || onThisPhone(picked)
    val pendingThis = preparingRecording?.takeIf { sameRecording(it, picked) }
    val preparingThis = pendingThis != null && entry?.state == "preparing" && preparation?.ready != true
    val preparedThis = pendingThis != null && (entry?.state == "ready" || preparation?.ready == true && entry?.state == "preparing")
    val formats = readyFormats(pendingThis?.takeIf { preparedThis } ?: picked, phoneFormats)
    val automatic = current?.source?.format?.takeIf { mine } ?: defaultFormat(picked, vm.savedFormat(book.id), phoneFormats)
    val shownFormat = format ?: automatic
    // Only a change from the automatic format is passed on, so the listener's own recording resumes its own audio.
    val override = format?.takeIf { it != automatic }
    // An explicit format plays only when that format is ready (or on the phone); it never falls back to another.
    val ready = if (override != null) override in formats || preparedThis
        else onPhone || SourceQuality.ready(picked) || preparedThis || mine && current.source != null
    // Whether the pick picks up a place of its own, so the notice can say so.
    val resumesAt by produceState<PlaceSummary?>(null, book.id, picked) { value = if (mine) null else vm.savedPlace(book.id, picked) }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (formats.size > 1 || override != null) {
            Text("Format", style = MaterialTheme.typography.labelLarge, color = muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (formats + listOfNotNull(override)).distinct().forEach { option -> FilterChip(option == shownFormat, { chooseFormat(option) }, { Text(option) }, Modifier.heightIn(min = 48.dp)) }
            }
        }
        if (current != null && current.started && !mine) {
            val switch = RecordingSwitch(current.recording, current.place?.let { listeningPlace(it, current.source) }, picked, resumesAt)
            startPositionChoice?.invoke(switch) ?: Text(switchNotice(switch), style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.testTag("switch-notice"))
        }
        val button = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("chooser-listen")
        val getReadyLabel = override?.let { "Get $it ready" } ?: "Get it ready"
        when {
            starting -> Button({}, button, enabled = false) { WorkingLabel("Starting…") }
            preparingThis || mine && current.preparing && current.source == null && preparation?.ready != true -> Button({}, button, enabled = false) { WorkingLabel("Getting ready in TorBox…") }
            !onPhone && needsTorBox(picked) && !connected -> Button({ close { vm.requestTorBoxConnect() } }, button) { SlotLabel(Icons.Rounded.Link, "Connect TorBox to listen") }
            mine && ready -> Button({ close { vm.listenTo(picked, override, delivery) } }, button, enabled = !busy) { SlotLabel(Icons.Rounded.PlayArrow, if (override == null) resumeLabel(current) else "Listen in $override") }
            ready -> Button({ close { vm.listenTo(picked, override, delivery) } }, button, enabled = !busy) { SlotLabel(Icons.Rounded.PlayArrow, "Listen to this recording") }
            else -> {
                Button({ close { vm.getReady(picked, override ?: format) } }, button, enabled = !busy) { SlotLabel(Icons.Rounded.CloudDownload, getReadyLabel) }
                Text("TorBox can take from minutes to hours to get it ready. Nothing downloads to your phone, and its progress shows on the book's page.",
                    style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
        val saving = copies.firstOrNull { !it.complete }
        when {
            saving != null -> Text("Saving to this phone · ${(saving.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = muted)
            onPhone && override == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Rounded.OfflinePin, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                Text("On this phone · plays without internet", style = MaterialTheme.typography.bodySmall, color = muted)
            }
            ready && (connected || !needsTorBox(picked)) -> {
                val bytes = current?.source?.takeIf { mine && override == null }?.parts?.sumOf { it.sizeBytes }?.takeIf { it > 0 } ?: downloadBytes(picked, shownFormat)
                OutlinedButton({ close { vm.downloadRecording(picked, override, delivery) } }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("chooser-download"), enabled = !busy) {
                    SlotLabel(Icons.Rounded.Download, listOfNotNull("Download for offline", bytes.takeIf { it > 0 }?.let(::sizeLabel)).joinToString(" · "))
                }
            }
        }
    }
}
