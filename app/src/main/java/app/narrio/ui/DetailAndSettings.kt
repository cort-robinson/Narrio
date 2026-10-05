package app.narrio.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.narrio.domain.*
import app.narrio.BuildConfig
import app.narrio.data.OfflineBook
import app.narrio.data.SourceQuality
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailPane(vm: NarrioViewModel, book: Audiobook, compact: Boolean, modifier: Modifier = Modifier, listState: LazyListState = rememberLazyListState()) {
    val currentSelection by vm.selection.collectAsStateWithLifecycle()
    // While this pane animates away, the selection may already belong to another book (or none).
    val selected = currentSelection.takeIf { it.book?.id == book.id } ?: SelectionState(book)
    val preparation by vm.preparation.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val shelf by vm.shelf.collectAsStateWithLifecycle()
    val connected by vm.connected.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val sourceSearch by vm.sourceSearch.collectAsStateWithLifecycle()
    val ebookSearch by vm.ebookSearch.collectAsStateWithLifecycle()
    val installedAddons by vm.graph.addons.installed.collectAsStateWithLifecycle()
    val ebookSearchLinks = remember(book.title, book.author, installedAddons) { vm.graph.addons.ebookSearchLinks(book) }
    val loadedFormats by vm.detailFormats.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var sourcePicker by remember(book.id) { mutableStateOf(false) }
    var ebookSheet by remember(book.id) { mutableStateOf(false) }
    val ebookFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.importEbook(it) }
    var expandedDescription by remember(book.id) { mutableStateOf(false) }
    var showVersions by remember(book.id) { mutableStateOf(false) }
    var showResults by remember(book.id) { mutableStateOf(false) }
    // A page that is leaving (or being reused mid-exit) never carries its source sheet with it.
    LaunchedEffect(currentSelection.book?.id) { if (currentSelection.book?.id != book.id) sourcePicker = false }
    val catalogBook = book.provider == "catalog"
    val saved = shelf.any { it.bookId == book.id }
    val formats = loadedFormats?.takeIf { it.bookId == book.id } ?: BookFormats(book.id, audio = !catalogBook && book.sources.isNotEmpty())
    val search = sourceSearch.takeIf { it.book?.id == book.id }
    // An ebook-only book asks before looking for a recording.
    val awaitingAudioSearch = catalogBook && formats.ebook && !formats.audio && search?.searched != true && search?.loading != true
    val openEbooks = { ebookSheet = true; vm.openEbookSearch(book) }
    LaunchedEffect(book.id, preparation?.torrentId, preparation?.ready, connected) {
        if (preparation != null && preparation?.ready != true && connected) while (isActive) { delay(15_000); vm.refreshPreparation(book) }
    }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Column(modifier.fillMaxSize()) {
    TopAppBar(title = { Text(if (catalogBook) "The book" else "The recording", style = MaterialTheme.typography.titleMedium) },
        navigationIcon = { if (compact) IconButton({ vm.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to books") } },
        actions = {
            TextButton({ vm.save(book) }, Modifier.padding(end = 8.dp), enabled = !saved) {
                AnimatedContent(saved, transitionSpec = { (scaleIn(Motion.responsive(), initialScale = .4f) + fadeIn()).togetherWith(scaleOut(targetScale = .4f) + fadeOut()) }, label = "saved") { done ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (done) Icons.Rounded.LibraryAddCheck else Icons.Rounded.LibraryAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (done) "Saved" else "Save")
                    }
                }
            }
        },
        windowInsets = WindowInsets(0), scrollBehavior = scroll,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer))
    LazyColumn(Modifier.weight(1f).fillMaxWidth().nestedScroll(scroll.nestedScrollConnection).testTag("book-details"), listState, contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item {
            val identity: @Composable ColumnScope.() -> Unit = {
                Text(book.title, style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(8.dp)); Text(book.author, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                if (!catalogBook || book.narratorFromCatalog) Text(narrationLabel(book), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                    if (!catalogBook) {
                        Text(book.language, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(durationLabel(book.durationMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // A book added from a file names a catalog only when one actually supplied its details.
                    if (!catalogBook || book.metadataSource.isNotBlank()) Text(providerLabel(book), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!catalogBook && book.provider != "archive") Text("Narration, language, and abridgment of this release remain unverified.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // Wide panes put the book beside its identity instead of stretching a phone column.
                if (maxWidth >= 560.dp) Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    BookCover(book, Modifier.width(210.dp).height(306.dp), large = true, sharedKey = "cover-${book.id}")
                    Column(Modifier.weight(1f), content = identity)
                } else Column {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { BookCover(book, Modifier.width(190.dp).height(278.dp), large = true, sharedKey = "cover-${book.id}") }
                    Spacer(Modifier.height(24.dp))
                    identity()
                }
            }
        }
        if (selected.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        selected.error?.let { item { RecoveryState("Couldn't load this edition", it) { vm.open(book) } } }
        item {
            FormatActions(formats, audioSlot = { slot, leading ->
                when {
                    awaitingAudioSearch -> OutlinedButton({ vm.findSources(book, force = true) }, slot.testTag("find-audiobook-action")) { SlotLabel(Icons.Rounded.Search, "Find audiobook") }
                    catalogBook -> CatalogListenButton(vm, book, sourceSearch, busy, slot, leading) { showResults = true }
                    else -> ModeButton({ sourcePicker = true }, slot.testTag("listen-action"), leading, enabled = !selected.loading && book.sources.isNotEmpty() && !busy) { SlotLabel(Icons.Rounded.PlayArrow, "Listen") }
                }
            }, read = { vm.read(book) }, findEbook = openEbooks)
            SharedPlaceCaption(formats, Modifier.padding(top = 14.dp))
            if (awaitingAudioSearch) {
                Spacer(Modifier.height(12.dp))
                Text(if (connected) "Find audiobook checks LibriVox, your TorBox library, and indexed releases for a recording of this title and author."
                     else "Find audiobook checks LibriVox for a free public recording of this title and author. Connect TorBox for more sources.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (catalogBook) {
                Spacer(Modifier.height(12.dp))
                SourceStatus(vm, book, sourceSearch.takeIf { it.book?.id == book.id } ?: SourceSearchState(book), connected,
                    showVersions = { showVersions = !showVersions; showResults = false }, showResults = { showResults = !showResults; showVersions = false })
            } else if (book.detailsLoaded && book.sources.isEmpty()) {
                Spacer(Modifier.height(12.dp)); Text(if (book.provider == "knaben") "No cached audio files found for this release. Try another ready source. File formats appear once a source is available in TorBox." else "This edition has no compatible audio files. Try another recording.", style = MaterialTheme.typography.bodyMedium)
                if (book.provider == "knaben" && connected && preparation == null) OutlinedButton({ vm.prepareUncached(book, "") }, enabled = !busy) { Text("Prepare this release in TorBox") }
                if (book.provider == "knaben" && !connected) OutlinedButton({ vm.navigate(2) }) { Text("Connect TorBox to check availability") }
            } else if (connected) {
                Spacer(Modifier.height(12.dp))
                Text(when (book.cacheState) { "cached" -> "Ready in TorBox · ${book.cachedFormats.joinToString(" / ")}"; "uncached" -> "Not cached in TorBox · Pick a ready source to listen immediately"; else -> "TorBox cache hasn't been checked" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!catalogBook && sourceSearch.book?.provider == "catalog") TextButton({ vm.back() }) { Text("Choose another recording") }
        }
        if (formats.ebook) item { EditionSummary(book, formats, openEbooks) }
        if (catalogBook && sourceSearch.book?.id == book.id && !sourceSearch.loading) {
            val choice = sourceSearch.choice
            if (showVersions && sourceSearch.versions.size > 1) {
                item { Text("Versions", style = MaterialTheme.typography.titleMedium) }
                items(sourceSearch.versions, key = { "version:${it.id}" }) { recording ->
                    SourceRow(recording, book, Modifier.animateItem(), selected = recording.id == choice?.id) { vm.chooseVersion(recording); showVersions = false }
                }
            }
            if (showResults && sourceSearch.results.isNotEmpty()) {
                item {
                    Text("Search results", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("Closest matches first. Check the release name and narrator before listening.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(sourceSearch.results, key = { "result:${it.id}" }) { recording ->
                    SourceRow(recording, book, Modifier.animateItem(), possible = recording in sourceSearch.possible) { vm.chooseRecording(recording) }
                }
            }
        }
        downloads.filter { it.book.id == book.id }.forEach { download -> item { OfflineStatus(vm, download) } }
        preparation?.let { prep -> item {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(14.dp)).padding(20.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (prep.ready) Icons.Rounded.CheckCircle else Icons.Rounded.CloudDownload, null, tint = MaterialTheme.colorScheme.primary)
                    Text(prep.state, style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(12.dp))
                if (!prep.ready) {
                    val progress by animateFloatAsState(prep.progress, tween(Motion.LONG, easing = Motion.Emphasized), label = "preparation")
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Text("${(prep.progress * 100).toInt()}%${if (prep.downloadBytesPerSecond > 0) " · ${sizeLabel(prep.downloadBytesPerSecond)}/s" else ""}${if (prep.etaSeconds > 0) " · About ${durationLabel(prep.etaSeconds * 1000)} remaining" else " · ETA unavailable"}", style = MaterialTheme.typography.bodyMedium)
                    prep.seeds?.let { Text("$it connected ${if (it == 1L) "seed" else "seeds"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Spacer(Modifier.height(8.dp))
                }
                Text(if (prep.ready) "Your source is ready. Choose Listen to begin." else if (book.cacheState == "cached") "TorBox is making the cached source available in your account. You can leave and return from your shelf." else "TorBox is fetching this uncached source. Audio isn't being downloaded to your phone. You can leave and return from your shelf; cached releases can stream now.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton({ vm.refreshPreparation(book) }, enabled = !busy && connected) { Text("Check availability") }
            }
        } }
        item { Column(Modifier.animateContentSize(tween(Motion.MEDIUM, easing = Motion.Emphasized))) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(24.dp))
            Text("About this book", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Text(book.description.ifBlank { if (catalogBook) "This catalog hasn't provided a description for the book." else "Open the recording to load its description and available sources." }, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (expandedDescription) Int.MAX_VALUE else 9, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (book.description.length > 500) TextButton({ expandedDescription = !expandedDescription }) { Text(if (expandedDescription) "Read less" else "Read more") }
            if (book.metadataSource.isNotBlank() && book.metadataUrl.isNotBlank()) TextButton({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(book.metadataUrl))) }) { Text("Details from ${book.metadataSource}") }
            if (book.releaseTitle.isNotBlank() && book.releaseTitle != book.title) Text("Release: ${book.releaseTitle}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            if (book.provider != "archive" && !catalogBook) TextButton({ vm.refreshMetadata(book) }, enabled = !selected.metadataLoading) {
                if (selected.metadataLoading) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                Text(if (selected.metadataLoading) "Fetching book details\u2026" else "Refresh book details")
            }
        } }
        if (book.sources.isNotEmpty()) item {
            Text("Available audio", style = MaterialTheme.typography.headlineSmall)
            book.sources.forEach { source ->
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.GraphicEq, null, tint = MaterialTheme.colorScheme.secondary)
                    Column { Text(source.label, style = MaterialTheme.typography.titleSmall); Text("${source.format} · ${source.parts.size} ${if (source.parts.size == 1) "file" else "parts"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
    }
    if (sourcePicker) SourcePicker(vm, book, connected, busy) { sourcePicker = false }
    if (ebookSheet) EbookSheet(book, formats, ebookSearch, connected, retry = { vm.findEbooks(book) }, add = { vm.addEbook(book, it) },
        chooseFile = { vm.beginEbookImport(book); ebookFile.launch(arrayOf("application/epub+zip", "text/plain", "application/octet-stream")) },
        activate = { vm.chooseEdition(book, it.id) }, dismiss = { ebookSheet = false }, searchLinks = ebookSearchLinks,
        openSearch = { vm.openEbookWebsite(book, it) })
}

/** The leading format is the filled action; the other available format is tonal. */
@Composable
fun ModeButton(onClick: () -> Unit, modifier: Modifier, leading: Boolean, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    if (leading) Button(onClick, modifier, enabled = enabled, content = content)
    else FilledTonalButton(onClick, modifier, enabled = enabled, content = content)
}

@Composable
private fun CatalogListenButton(vm: NarrioViewModel, book: Audiobook, search: SourceSearchState, busy: Boolean, modifier: Modifier, leading: Boolean, review: () -> Unit) {
    val current = search.takeIf { it.book?.id == book.id }
    val choice = current?.choice
    when {
        current == null || current.loading || !current.searched -> ModeButton({}, modifier, leading, enabled = false) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("Finding audio…", maxLines = 1)
        }
        choice != null -> ModeButton({ vm.listenToChoice() }, modifier.testTag("listen-action"), leading, enabled = !busy) {
            val ready = SourceQuality.ready(choice)
            SlotLabel(if (ready) Icons.Rounded.PlayArrow else Icons.Rounded.CloudDownload, if (ready) "Listen" else "Review and prepare")
        }
        current.results.isNotEmpty() -> ModeButton(review, modifier, leading) { SlotLabel(Icons.Rounded.Search, "Choose a recording") }
        else -> ModeButton({ vm.findSources(book, force = true) }, modifier, leading) { SlotLabel(Icons.Rounded.Refresh, "Search again") }
    }
}

/** Explains what was chosen automatically, or why nothing was, with only the next useful actions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceStatus(vm: NarrioViewModel, book: Audiobook, search: SourceSearchState, connected: Boolean, showVersions: () -> Unit, showResults: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val choice = search.choice
    when {
        search.loading || !search.searched -> Text(if (connected) "Checking LibriVox, your TorBox library, and indexed releases for this title and author." else "Checking LibriVox for a free public recording of this title and author.",
            style = MaterialTheme.typography.bodySmall, color = muted)
        choice != null -> {
            Text(listOf(availabilityLabel(choice), choice.sources.joinToString(" / ") { it.format }, versionLabel(choice, book)).filter(String::isNotBlank).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            Text(choice.releaseTitle.ifBlank { choice.title }, style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(top = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (search.versions.size > 1) TextButton(showVersions) { Text("${search.versions.size} versions") }
                TextButton({ vm.chooseRecording(choice) }) { Text("Listening options") }
                if (search.results.size > 1) TextButton(showResults) { Text("Not the right recording?") }
            }
        }
        else -> {
            Text(when {
                search.results.isNotEmpty() -> "No recording matched this title and author closely enough to choose automatically. Review ${search.results.size} search ${if (search.results.size == 1) "result" else "results"} to pick one."
                connected -> "No recording of this title and author was found in LibriVox, your TorBox library, or indexed releases."
                else -> "No free public recording of this title and author was found."
            }, style = MaterialTheme.typography.bodyMedium)
            search.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(top = 4.dp)) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (search.results.isNotEmpty() || search.error != null) TextButton({ vm.findSources(book, force = true) }) { Text("Search again") }
                if (!connected) TextButton({ vm.navigate(2) }) { Text("Connect TorBox for more sources") }
            }
        }
    }
}

@Composable
private fun SourceRow(recording: Audiobook, book: Audiobook, modifier: Modifier, selected: Boolean = false, possible: Boolean = false, action: () -> Unit) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = action).padding(vertical = 8.dp, horizontal = if (selected) 8.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(recording.releaseTitle.ifBlank { recording.title }, style = MaterialTheme.typography.titleSmall)
                Text(versionLabel(recording, book), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                Text(listOf(providerLabel(recording), recording.sources.joinToString(" / ") { it.format }).filter(String::isNotBlank).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(when { recording.cacheState == "cached" -> Icons.Rounded.Bolt; recording.provider == "archive" -> Icons.Rounded.Headphones; else -> Icons.Rounded.CloudDownload }, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(availabilityLabel(recording) + if (possible) " · Possible match" else "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            Icon(if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.ChevronRight, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

private fun availabilityLabel(recording: Audiobook) = when {
    recording.cacheState == "cached" -> "Ready in TorBox"
    recording.provider == "archive" -> "Free public recording"
    recording.filesVerified -> "Needs TorBox preparation"
    else -> "Audio files not checked"
}

/** Narrator, dramatization/abridgment, and language: what distinguishes one version from another. */
private fun versionLabel(recording: Audiobook, book: Audiobook): String {
    val edition = SourceQuality.edition(recording)
    return listOf(edition.narrator.takeIf(String::isNotBlank)?.let { "Read by $it" } ?: "Narrator not listed", edition.kind,
        edition.language.takeUnless { it.isBlank() || it.equals(book.language, true) }.orEmpty()).filter(String::isNotBlank).joinToString(" · ")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourcePicker(vm: NarrioViewModel, book: Audiobook, connected: Boolean, busy: Boolean, dismiss: () -> Unit) {
    val preferred by vm.preferredFormat.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    var chosen by remember(book.id) { mutableStateOf(book.sources.firstOrNull { it.format == preferred } ?: book.sources.firstOrNull { it.format in book.cachedFormats } ?: book.sources.first()) }
    var delivery by remember(book.id) { mutableStateOf(if (book.provider != "archive" || connected && chosen.format in book.cachedFormats) "torbox" else "archive") }
    val ready = delivery == "archive" || chosen.format in book.cachedFormats
    val downloaded = downloads.firstOrNull { it.book.id == book.id && it.source.format == chosen.format }
    fun choose(source: AudioSource) { chosen = source; vm.chooseFormat(book, source.format) }
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        val view = LocalView.current
        val dark = ThemeContrast.foreground(MaterialTheme.colorScheme.background.toArgb()) == 0xFFFFFF
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
        } }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("How to listen", style = MaterialTheme.typography.headlineMedium)
            Text("${book.narrator} · ${book.language}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            book.sources.forEach { source ->
                Row(Modifier.fillMaxWidth().selectableRow(chosen.id == source.id) { choose(source) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(chosen.id == source.id, { choose(source) })
                    Column(Modifier.weight(1f)) { Text(source.label, style = MaterialTheme.typography.titleSmall); Text("${source.format} · ${source.parts.size} ${if (source.parts.size == 1) "file" else "parts"}${if (source.format in book.cachedFormats) " · Cached" else ""}", style = MaterialTheme.typography.bodySmall) }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (book.provider == "archive") {
                Row(Modifier.fillMaxWidth().selectableRow(delivery == "archive") { delivery = "archive" }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(delivery == "archive", { delivery = "archive" }); Column(Modifier.weight(1f)) { Text("Internet Archive", style = MaterialTheme.typography.titleSmall); Text("Direct public recording · Ready to stream", style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (book.torrentUrl.isNotBlank() || book.magnetUri.isNotBlank() || book.provider == "torbox") Row(Modifier.fillMaxWidth().selectableRow(delivery == "torbox") { delivery = "torbox" }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(delivery == "torbox", { delivery = "torbox" }); Column(Modifier.weight(1f)) { Text("TorBox", style = MaterialTheme.typography.titleSmall); Text(if (!connected) "Connect your account to use this source" else if (chosen.format in book.cachedFormats) "Cached · Ready to stream" else "Not cached · Requires cloud preparation", style = MaterialTheme.typography.bodySmall) }
            }
            Text("Streaming doesn't save the book to your phone. Each audio format and delivery source keeps its own listening position.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button({ dismiss(); if (delivery == "torbox" && !connected) vm.navigate(2) else vm.start(book, chosen, delivery) }, Modifier.fillMaxWidth(), enabled = !busy && (ready || !connected && delivery == "torbox")) {
                Text(if (delivery == "torbox" && !connected) "Connect TorBox" else if (delivery == "torbox") "Stream now" else "Start listening")
            }
            if (delivery == "torbox" && connected && !ready) {
                Text("This source may take minutes or hours to become available. Choose a cached release to listen immediately.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton({ dismiss(); vm.prepareUncached(book, chosen.format) }, Modifier.fillMaxWidth(), enabled = !busy) { Text("Prepare in TorBox") }
            }
            if (downloaded?.complete == true) OutlinedButton({ dismiss(); vm.start(book, downloaded.source, downloaded.source.delivery) }, Modifier.fillMaxWidth(), enabled = !busy) { Icon(Icons.Rounded.OfflinePin, null); Spacer(Modifier.width(8.dp)); Text("Play offline") }
            else OutlinedButton({ dismiss(); vm.download(book, chosen, delivery) }, Modifier.fillMaxWidth(), enabled = ready && !busy && (delivery != "torbox" || connected) && downloaded == null) {
                Icon(Icons.Rounded.Download, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(if (downloaded != null) "Download on your shelf" else "Download to phone")
            }
            if (chosen.parts.sumOf { it.sizeBytes } > 0) Text("Phone storage: ${sizeLabel(chosen.parts.sumOf { it.sizeBytes })} for this format", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (book.provider == "knaben") {
                Text("Files in this release", style = MaterialTheme.typography.titleSmall)
                chosen.parts.take(8).forEach { Text(it.name.substringAfterLast('/'), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (chosen.parts.size > 8) Text("And ${chosen.parts.size - 8} more files", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Modifier.selectableRow(selected: Boolean, action: () -> Unit): Modifier = this
    .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, RoundedCornerShape(12.dp))
    .clickable(onClick = action).padding(end = 12.dp, top = 4.dp, bottom = 4.dp)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OfflineStatus(vm: NarrioViewModel, download: OfflineBook) {
    var remove by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(14.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(if (download.complete) Icons.Rounded.OfflinePin else Icons.Rounded.Download, null, tint = MaterialTheme.colorScheme.secondary)
            Text(download.label, style = MaterialTheme.typography.titleSmall)
        }
        Text("${download.source.format} · ${download.completedFiles} of ${download.source.parts.size} files${if (download.bytesDownloaded > 0) " · ${sizeLabel(download.bytesDownloaded)}" else ""}${if (download.totalBytes > 0 && !download.complete) " of ${sizeLabel(download.totalBytes)}" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!download.complete && download.totalBytes > 0) {
            val progress by animateFloatAsState(download.progress, tween(Motion.LONG, easing = Motion.Emphasized), label = "download")
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text("${(download.progress * 100).toInt()}% saved to phone", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (download.complete) FilledTonalButton({ vm.start(download.book, download.source, download.source.delivery) }) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Play offline") }
            else TextButton({ if (download.paused || download.failed) vm.resumeDownload(download) else vm.pauseDownload(download) }) { Text(if (download.failed) "Retry download" else if (download.paused) "Resume download" else "Pause download") }
            TextButton({ remove = true }) { Text("Remove download") }
        }
    }
    if (remove) AlertDialog(onDismissRequest = { remove = false }, title = { Text("Remove phone download?") }, text = { Text("Audio files for this ${download.source.format} source will be removed. Your book, bookmarks, and listening progress stay on your shelf.") }, confirmButton = { TextButton({ vm.removeDownload(download); remove = false }) { Text("Remove audio") } }, dismissButton = { TextButton({ remove = false }) { Text("Keep download") } })
}

@Composable
fun SettingsScreen(vm: NarrioViewModel, modifier: Modifier = Modifier) {
    val connected by vm.connected.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val appearance by vm.appearance.collectAsStateWithLifecycle()
    val wifiOnly by vm.wifiOnly.collectAsStateWithLifecycle()
    var key by remember { mutableStateOf("") }
    var appearanceOpen by rememberSaveable { mutableStateOf(false) }
    var addonsOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(connected) { if (connected) key = "" }
    AnimatedContent(appearanceOpen, modifier, transitionSpec = { Motion.sharedAxisX(targetState) }, label = "settings page") { open ->
        if (addonsOpen) AddonSettings(vm.graph.addons, vm::addonsChanged, { addonsOpen = false })
        else if (open) AppearanceScreen(appearance, vm::updateAppearance, { appearanceOpen = false })
        else SettingsHome(vm, connected, busy, appearance, wifiOnly, key, { key = it }, { addonsOpen = true }) { appearanceOpen = true }
    }
}

@Composable
private fun SettingsHome(vm: NarrioViewModel, connected: Boolean, busy: Boolean, appearance: AppearanceSettings, wifiOnly: Boolean, key: String, setKey: (String) -> Unit, openAddons: () -> Unit, openAppearance: () -> Unit) {
    val context = LocalContext.current
    val backgroundAlignment by vm.backgroundAlignment.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().imePadding().testTag("settings-options"), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.displaySmall) }
        item { AppearanceEntry(appearance, openAppearance) }
        item {
            OutlinedButton(openAddons, Modifier.fillMaxWidth()) {
                Text("Add-ons · Book metadata, audio & ebooks")
            }
        }
        item { UpdateSettings(vm.graph.updates) }
        item {
            Text("Reading & listening", style = MaterialTheme.typography.headlineSmall)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Sync narration in the background", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Switch(backgroundAlignment, vm::setBackgroundAlignment, Modifier.testTag("background-alignment"))
            }
            Text("Align downloaded audio on this phone. Streaming alignment waits for Wi-Fi and charging. The English narration model downloads once over Wi-Fi.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Text("TorBox", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Crossfade(connected, label = "account") { Icon(if (it) Icons.Rounded.CheckCircle else Icons.Rounded.CloudQueue, null, tint = MaterialTheme.colorScheme.secondary) }
                Text(if (connected) "Connected on this device" else "Connect your delivery account", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(12.dp))
            Text("Use your TorBox API key to deliver recordings and browse the audio in your account. Your plan needs API access.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            if (!connected) {
                OutlinedTextField(key, setKey, Modifier.fillMaxWidth(), label = { Text("TorBox API key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), shape = RoundedCornerShape(12.dp))
                Spacer(Modifier.height(12.dp))
                Button({ vm.connect(key) }, Modifier.fillMaxWidth(), enabled = key.isNotBlank() && !busy) { if (busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)) }; Text(if (busy) "Connecting…" else "Connect TorBox") }
                TextButton({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://torbox.app/settings"))) }) { Text("Find my API key") }
            } else OutlinedButton({ vm.disconnect() }) { Text("Disconnect and remove key") }
            Text("Protected by Android Keystore. Never synced, backed up, or included in logs.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant); Spacer(Modifier.height(24.dp))
            Text("Downloads", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Text("Use Download to phone on a recording to save its chosen audio format. Manage downloads on your shelf. Streaming plays over the internet without saving the book.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Download only on Wi-Fi", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Switch(wifiOnly, vm::setWifiOnly)
            }
            Text(if (wifiOnly) "Downloads wait for an unmetered connection." else "Downloads can use mobile data, including large whole-book files.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant); Spacer(Modifier.height(24.dp))
            Text("About Narrio", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Text("Narrio ${BuildConfig.VERSION_NAME} · Native Android\n\nSearch identifies books from catalog metadata. Opening a book looks for public-domain LibriVox recordings and, with TorBox connected, your TorBox library and enabled source add-ons and TorBox search. The best match with ready audio is chosen automatically. Other versions appear only when the narrator, language, or edition differs. When no recording matches confidently, you can review the search results yourself. Matching releases with verified audio files and available seeders can be explicitly prepared in TorBox. Indexed releases may have unverified narration, language, or abridgment; inspect the release and files before listening. Availability depends on the provider and TorBox cache.\n\nSaved books, downloads, progress, and bookmarks stay on this device. Downloading to your phone is optional.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Text("Matched book details and cover art come from enabled catalog add-ons, with Google Books as a built-in fallback. Manage providers in Add-ons. Catalog narrator information does not verify the release's recording. Book names go directly to these metadata providers; your TorBox key and listening history stay private.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            TextButton({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://librivox.org/pages/about-librivox/"))) }) { Text("About the LibriVox recordings") }
            Text("Retrieved covers belong to their respective rights holders. Fallbacks use original garden artwork created with ImageGen and Narrio's graphic designs. Typography: Newsreader and Manrope, SIL Open Font License.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
