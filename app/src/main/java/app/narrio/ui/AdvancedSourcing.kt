package app.narrio.ui

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.semantics.heading
import app.narrio.data.HiddenRelease
import app.narrio.data.WordsVariant
import app.narrio.domain.*

/*
 * Advanced sourcing pieces: each is a self-contained composable taking state and callbacks, so the Advanced view can
 * place them as it likes. [AdvancedSourcingSheet] hosts them all for a book until that view exists.
 */

/** Search sources with the listener's own words, with quick variants and a way back to the automatic search. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CustomSearchWords(
    active: String?, saved: String?, variants: List<WordsVariant>, searching: Boolean,
    onSearch: (String) -> Unit, onAutomatic: () -> Unit, modifier: Modifier = Modifier,
) {
    var words by rememberSaveable(saved) { mutableStateOf(active ?: saved.orEmpty()) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val focus = LocalFocusManager.current
    // The keyboard closes so the results it would cover can be read.
    val search = { value: String -> if (value.isNotBlank()) { focus.clearFocus(); onSearch(value) } }
    Column(modifier.fillMaxWidth().testTag("custom-search-words"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Search with your own words", style = MaterialTheme.typography.titleMedium)
        Text("Try the UK or US title, the series name and book number, or another spelling of the author. Releases that name your words but not this book's title are listed as possible matches for you to check.",
            style = MaterialTheme.typography.bodySmall, color = muted)
        OutlinedTextField(words, { words = it }, Modifier.fillMaxWidth().testTag("custom-words-field"), singleLine = true,
            label = { Text("Search words") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (words.isNotEmpty()) IconButton({ words = "" }) { Icon(Icons.Rounded.Close, "Clear search words") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search(words) }),
            shape = RoundedCornerShape(14.dp))
        if (variants.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            variants.forEach { variant ->
                FilterChip(selected = active == variant.words, onClick = { words = variant.words; search(variant.words) }, label = { Text(variant.label) },
                    modifier = Modifier.semantics { contentDescription = "${variant.label}: search for ${variant.words}" })
            }
        }
        Button({ search(words) }, Modifier.fillMaxWidth().testTag("custom-words-search"), enabled = words.isNotBlank() && !searching) {
            Text(if (searching && active != null) "Searching…" else "Search sources")
        }
        if (active != null) {
            Text("Showing sources for “$active”.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            TextButton(onAutomatic, Modifier.heightIn(min = 48.dp)) { Text("Back to the automatic search") }
        }
    }
}

/** Marks a release as not this book, so it never wins the best match again. */
@Composable
fun NotThisBookAction(release: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick, modifier.heightIn(min = 48.dp).semantics { contentDescription = "Not this book: hide $release" }) {
        Icon(Icons.Rounded.VisibilityOff, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Not this book")
    }
}

/** The compact form for a release row: an icon with the same spoken action. */
@Composable
fun NotThisBookIcon(release: String, onClick: () -> Unit) {
    IconButton(onClick, Modifier.semantics { contentDescription = "Not this book: hide $release" }.testTag("not-this-book:$release")) {
        Icon(Icons.Rounded.VisibilityOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "Hidden · N": releases marked Not this book, folded until opened, each with Unhide. */
@Composable
fun HiddenReleasesList(hidden: List<HiddenRelease>, onUnhide: (HiddenRelease) -> Unit, modifier: Modifier = Modifier) {
    if (hidden.isEmpty()) return
    var open by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().animateContentSize().testTag("hidden-releases")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = if (open) "Hide the list" else "Show the list") { open = !open }
            .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.VisibilityOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text("Hidden · ${hidden.size}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
        }
        if (open) {
            Text("You said these aren't this book. They stay out of the best match and the source lists.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            hidden.forEach { release ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(release.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (release.provider.isNotBlank()) Text(release.provider, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton({ onUnhide(release) }, Modifier.semantics { contentDescription = "Unhide ${release.title}" }) { Text("Unhide") }
                }
            }
        }
    }
}

/** Paste a magnet link or an https .torrent link to use that exact release. */
@Composable
fun PasteReleaseLink(state: LinkState, connected: Boolean, onAdd: (String) -> Unit, onConnect: () -> Unit, modifier: Modifier = Modifier) {
    var text by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val add = { if (text.isNotBlank() && connected) { focus.clearFocus(); onAdd(text) } }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.fillMaxWidth().testTag("paste-link"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Use a link", style = MaterialTheme.typography.titleMedium)
        Text("Paste a magnet link or an https link to a .torrent file. TorBox checks it: a ready release plays now, and others can Get it ready.",
            style = MaterialTheme.typography.bodySmall, color = muted)
        OutlinedTextField(text, { text = it.trim() }, Modifier.fillMaxWidth().testTag("link-field"), singleLine = true, enabled = !state.working,
            label = { Text("Magnet or .torrent link") }, leadingIcon = { Icon(Icons.Rounded.Link, null) },
            trailingIcon = {
                IconButton({ (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()?.let { text = it } }) { Icon(Icons.Rounded.ContentPaste, "Paste from clipboard") }
            },
            isError = state.error != null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { add() }), shape = RoundedCornerShape(14.dp))
        state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("link-error")) }
        if (!connected) OutlinedButton(onConnect, Modifier.fillMaxWidth()) { Text("Connect TorBox to use links") }
        else Button(add, Modifier.fillMaxWidth().testTag("link-add"), enabled = text.isNotBlank() && !state.working) {
            if (state.working) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("Checking the link…") }
            else Text("Use this release")
        }
    }
}

/** Add MP3/M4B/M4A files or a whole folder from the phone as this book's recording. */
@Composable
fun AddPhoneAudio(state: LocalImportState, onFiles: (List<Uri>) -> Unit, onFolder: (Uri?) -> Unit, modifier: Modifier = Modifier) {
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { onFiles(it) }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { onFolder(it) }
    Column(modifier.fillMaxWidth().testTag("phone-audio"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Audio files on this phone", style = MaterialTheme.typography.titleMedium)
        Text("Choose MP3, M4B, or M4A files, or a folder with the book's parts. They play in name order, work offline, and are never uploaded.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.working) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp))
            Text("Reading your files…", style = MaterialTheme.typography.bodyMedium)
        }
        state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton({ files.launch(arrayOf("audio/*")) }, Modifier.weight(1f).testTag("phone-files"), enabled = !state.working) {
                Icon(Icons.Rounded.AudioFile, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Choose files")
            }
            OutlinedButton({ folder.launch(null) }, Modifier.weight(1f).testTag("phone-folder"), enabled = !state.working) {
                Icon(Icons.Rounded.Folder, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Choose folder")
            }
        }
    }
}

/**
 * Switching recordings: start near the old place (approximate, "≈"), at this recording's own saved place, or from the
 * beginning. Near and Resume show only when they exist; the beginning is always offered and always honoured.
 */
@Composable
fun StartNearChoice(start: SwitchStart, chosen: StartChoice, onChoose: (StartChoice) -> Unit, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.fillMaxWidth().selectableGroup().testTag("start-near"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Where to start", style = MaterialTheme.typography.titleMedium)
        start.near?.let { near ->
            StartOption(chosen == StartChoice.NEAR, "Start near ${formatTime(near.toElapsedMs)} (≈)",
                "About where you were (${formatTime(near.fromElapsedMs)}) in the other recording. Recordings differ in pace and opening, so this is approximate.",
                "Start near ${formatTime(near.toElapsedMs)}, estimated") { onChoose(StartChoice.NEAR) }
        }
        if (start.resume != null) {
            StartOption(chosen == StartChoice.RESUME, "Resume where you stopped in this one", "Your saved place in this recording.", null) { onChoose(StartChoice.RESUME) }
        }
        StartOption(chosen == StartChoice.BEGINNING, "Start from the beginning", "Begin this recording at its start.", null) { onChoose(StartChoice.BEGINNING) }
        if (start.near == null) Text("Your place in the other recording can't be matched here, because the lengths of its files aren't known yet.",
            style = MaterialTheme.typography.bodySmall, color = muted)
    }
}

@Composable
private fun StartOption(selected: Boolean, title: String, detail: String, spoken: String?, choose: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
        .selectable(selected, role = Role.RadioButton, onClick = choose).heightIn(min = 48.dp).padding(end = 12.dp, top = 6.dp, bottom = 6.dp)
        .semantics(mergeDescendants = true) { if (spoken != null) contentDescription = "$spoken. $detail" }, verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, null, Modifier.padding(horizontal = 12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A sheet with Narrio's sheet colours and system-bar contrast. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdvancedSheet(dismiss: () -> Unit, tag: String, content: LazyListScope.(close: (() -> Unit) -> Unit) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(sheetState, dismiss)
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer, sheetState = sheetState) {
        val view = LocalView.current
        val dark = ThemeContrast.foreground(MaterialTheme.colorScheme.background.toArgb()) == 0xFFFFFF
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
        } }
        LazyColumn(Modifier.fillMaxWidth().testTag(tag), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) { content(close) }
    }
}

/** A section break in Advanced, matching its other sections. */
@Composable
private fun SectionBreak() { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant); Spacer(Modifier.height(12.dp)) }

/**
 * The recording chooser's Advanced additions (its `extraSections`): search with your own words, hidden releases, a
 * pasted link, the TorBox library, files on this phone, and choosing files in the picked recording. A release brought in
 * here becomes the chooser's pick, back in its list, where Listen, Get it ready, and Download work as for any match.
 */
fun LazyListScope.advancedSourcingSections(vm: NarrioViewModel, context: AdvancedContext) {
    val book = context.book
    val advanced = vm.advanced
    item(key = "own-words") {
        val search by vm.sourceSearch.collectAsStateWithLifecycle()
        val mine = search.takeIf { it.book?.id == book.id }
        Column(Modifier.padding(top = 12.dp)) {
            SectionBreak()
            CustomSearchWords(mine?.words, advanced.savedWords(book.id), advanced.variants(book), mine?.loading == true,
                onSearch = { advanced.searchWords(book, it) }, onAutomatic = { advanced.automaticSearch(book) })
        }
    }
    item(key = "hidden-releases") {
        val hidden by advanced.hidden.collectAsStateWithLifecycle()
        HiddenReleasesList(hidden[book.id].orEmpty(), { advanced.unhide(book, it.key) })
    }
    item(key = "paste-link") {
        val link by advanced.link.collectAsStateWithLifecycle()
        val connected by vm.connected.collectAsStateWithLifecycle()
        Column { SectionBreak(); PasteReleaseLink(link, connected, { advanced.addLink(book, it, context.choose) }, onConnect = { vm.requestTorBoxConnect() }) }
    }
    item(key = "torbox-library") {
        val connected by vm.connected.collectAsStateWithLifecycle()
        var open by rememberSaveable { mutableStateOf(false) }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionBreak()
            Text("From your TorBox library", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text("Pick any download in your account, even one the search didn't find.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton({ if (connected) { advanced.loadLibrary(); open = true } else vm.requestTorBoxConnect() }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("open-torbox-library")) {
                Icon(Icons.Rounded.CloudQueue, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (connected) "Choose from my TorBox" else "Connect TorBox to choose")
            }
        }
        if (open) TorBoxLibrarySheet(vm, book, chosen = { open = false; context.choose(it) }) { open = false }
    }
    item(key = "phone-audio") {
        val local by advanced.local.collectAsStateWithLifecycle()
        Column { SectionBreak(); AddPhoneAudio(local, { advanced.addPhoneFiles(book, it, context.choose) }, { advanced.addPhoneFolder(book, it, context.choose) }) }
    }
    val picked = context.picked
    if (picked != null && picked.sources.isNotEmpty()) item(key = "choose-files:${picked.id}") {
        // Files are chosen for the recording as it belongs to this book, as playback looks them up.
        val recording = remember(picked, book.id) { if (picked.id == book.id) picked else picked.forBook(book) }
        var files by remember(recording.id, recording.recordingId) { mutableStateOf(false) }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionBreak()
            Text("Files in this recording", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text("Choose which files play and in what order, such as one book from a series bundle.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            recording.sources.forEach { source ->
                OutlinedButton({ advanced.openFiles(recording, source); files = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("choose-files:${source.format}")) {
                    Icon(Icons.Rounded.Checklist, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text("Choose ${source.format} files · ${source.parts.size} ${if (source.parts.size == 1) "file" else "files"}")
                }
            }
        }
        if (files) ReleaseFilesSheet(vm) { advanced.closeFiles(); files = false }
    }
}

/**
 * The recording chooser's `startPositionChoice`: switching away from a recording with a saved place offers "Start near
 * X (≈)", this recording's own place, or "Start from the beginning". With only the beginning, the chooser's own notice shows.
 */
@Composable
fun AdvancedStartChoice(vm: NarrioViewModel, book: Audiobook, switch: RecordingSwitch) {
    val advanced = vm.advanced
    val target = remember(switch.to, book.id) { if (switch.to.id == book.id) switch.to else switch.to.forBook(book) }
    val source = target.sources.firstOrNull { it.format == defaultFormat(target, vm.savedFormat(book.id)) } ?: target.sources.firstOrNull()
    var start by remember(target.id, target.recordingId) { mutableStateOf<SwitchStart?>(null) }
    LaunchedEffect(target.id, target.recordingId, source?.id) {
        // With neither a near place nor one of its own, the beginning is the only start: the notice says so.
        val found = source?.let { runCatching { advanced.switchStart(target, it) }.getOrNull() }?.takeIf { it.near != null || it.resume != null }
        start = found
        // Each switch starts from its own default; until the options are known, playback's usual rule applies.
        advanced.startChoice.value = found?.default
    }
    // Leaving the choice (another pick, or closing the chooser) doesn't carry it to a later switch.
    DisposableEffect(target.id, target.recordingId) { onDispose { advanced.startChoice.value = null } }
    val chosen by advanced.startChoice.collectAsStateWithLifecycle()
    val options = start
    if (options == null) Text(switchNotice(switch), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("switch-notice"))
    else StartNearChoice(options, chosen ?: options.default, onChoose = { advanced.startChoice.value = it })
}
