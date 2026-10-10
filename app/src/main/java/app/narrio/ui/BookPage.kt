package app.narrio.ui

import app.narrio.data.recordingKey
import app.narrio.data.sameRecording
import app.narrio.data.preparingRecording
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.Rule
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.narrio.data.OfflineBook
import app.narrio.data.PreparationRecord
import app.narrio.domain.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/*
 * A book's one page, whichever way the listener arrives (Discover, search, the shelf, Continue, the mini-player):
 * who reads it and one Listen or Resume, the ebook, the phone copy, the description, then two rows into the
 * recording chooser and the ebook editions. Comparing releases happens in the chooser, never on the page.
 */


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailPane(vm: NarrioViewModel, book: Audiobook, compact: Boolean, modifier: Modifier = Modifier, listState: LazyListState = rememberLazyListState()) {
    val currentSelection by vm.selection.collectAsStateWithLifecycle()
    // While this pane animates away, the selection may already belong to another book (or none).
    val selected = currentSelection.takeIf { it.book?.id == book.id } ?: SelectionState(book)
    val preparation by vm.preparation.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val starting by vm.starting.collectAsStateWithLifecycle()
    val shelf by vm.shelf.collectAsStateWithLifecycle()
    val connected by vm.connected.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val listening by vm.listeningRecordings.collectAsStateWithLifecycle()
    val wifiOnly by vm.wifiOnly.collectAsStateWithLifecycle()
    val sourceSearch by vm.sourceSearch.collectAsStateWithLifecycle()
    val sourceProviders by vm.sourceProviderSettings.providers.collectAsStateWithLifecycle()
    val ebookSearch by vm.ebookSearch.collectAsStateWithLifecycle()
    val ebookProviders by vm.ebookProviderSettings.providers.collectAsStateWithLifecycle()
    val installedAddons by vm.graph.addons.installed.collectAsStateWithLifecycle()
    val ebookSearchLinks = remember(book.title, book.author, installedAddons) { vm.graph.addons.ebookSearchLinks(book) }
    val loadedFormats by vm.detailFormats.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // The chooser opens on its simple list, every match ("Review possible matches"), or Advanced.
    var chooser by remember(book.id) { mutableStateOf<ChooserView?>(null) }
    var ebookSheet by remember(book.id) { mutableStateOf(false) }
    val ebookFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.importEbook(it) }
    var expandedDescription by remember(book.id) { mutableStateOf(false) }
    // A page that is leaving (or being reused mid-exit) never carries its sheets with it.
    LaunchedEffect(currentSelection.book?.id) { if (currentSelection.book?.id != book.id) chooser = null }
    val entry = shelf.firstOrNull { it.bookId == book.id }
    val saved = entry != null
    val downloadsHere = downloads.filter { it.book.id == book.id }
    val search = sourceSearch.takeIf { it.book?.id == book.id }
    val played = listening[book.id]?.current
    // The recording being got ready, read again whenever the shelf row's preparation changes.
    val preparingHere by produceState<PreparationRecord?>(null, book.id, entry?.state, entry?.preparationId, entry?.pendingFormat) { value = vm.preparationRecord(book.id) }
    // The listener's recording, with what a fresh search says about it; its played audio and place stay as they were.
    val current = remember(book, entry, played, preparingHere, downloadsHere, search) {
        currentRecording(book, entry, played, downloadsHere, preparingHere)?.let { refreshed(it, search) }
    }
    val pending = remember(entry, current, preparingHere) { pendingRecording(entry, current, preparingHere) }
    val formats = loadedFormats?.takeIf { it.bookId == book.id } ?: BookFormats(book.id, audio = current != null)
    // An ebook-only book asks before looking for a recording.
    val awaitingAudioSearch = current == null && formats.ebook && !formats.audio && search?.searched != true && search?.loading != true
    // The ebook search knows which recording and audio the listener hears, to hint at a matching edition.
    val narration = current?.let { NarrationContext(it.recording, it.source) }
    val openEbooks = { ebookSheet = true; vm.openEbookSearch(book, narration) }
    val finished = (entry?.finishedAt ?: 0) > 0
    // A book without its own recording searches as it opens; one with a recording searches when the chooser opens.
    val searching = search?.searched == true || current == null && !awaitingAudioSearch
    val streamed = if (!searching) null else search?.streamed?.takeIf { it.book.id == book.id }
        ?: remember(search?.loading, search?.searched, search?.error, sourceProviders, connected) {
            pendingSourceSearch(book, sourceProviders, connected, searching = search == null || search.loading || !search.searched, error = search?.error)
        }
    // A release the listener picked in the chooser leads instead of the best match.
    val chosen = search?.chosenId?.let { id -> search.results.firstOrNull { it.id == id } }
    val providersById = remember(sourceProviders) { sourceProviders.associateBy { it.id } }
    val tally = streamed?.let { remember(it, providersById, connected) { tally(it, providersById, connected) } }
    // Once the listener touches the page (or listens with TalkBack), a better match waits in the chooser instead of moving Listen.
    val talkBack = remember(context) { (context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager).isTouchExplorationEnabled }
    var interacted by remember(book.id) { mutableStateOf(talkBack) }
    LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress) interacted = true }
    var pinned by remember(book.id) { mutableStateOf(PinnedBest()) }
    LaunchedEffect(streamed?.best, interacted, streamed?.groups?.size) {
        pinned = pinned.next(streamed?.best, interacted) { id -> streamed?.groups.orEmpty().any { group -> group.recordings.any { it.id == id } || group.possible.any { it.id == id } } }
    }
    // A book never listened to plays its best match (or the listener's pick); a book with a recording plays that.
    val best = if (current != null) null else chosen?.takeIf { it.id != pinned.shown?.recording?.id } ?: pinned.shown?.recording
    val onPhone: (Audiobook) -> Boolean = { recording -> downloadsHere.any { it.complete && sameRecording(it.book, recording) } }
    val openChooser: (ChooserView) -> Unit = { view -> interacted = true; chooser = view }
    // The offline row stands for the recording Listen plays, once it can play.
    val offlineTarget = current?.recording ?: best?.takeIf { playableNow(it, onPhone(it)) }
    val offlineShown = offlineTarget?.let { offlineDownload(it, current, downloadsHere) }
    LaunchedEffect(book.id, preparation?.torrentId, preparation?.ready, preparation?.problem, connected) {
        // Live progress while watching; background checks carry on after leaving. Failed or paused ones wait for the listener.
        if (preparation?.let { !it.ready && it.problem.isEmpty() } == true && connected) while (isActive) { delay(15_000); vm.refreshPreparation(book) }
    }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    // The bar names the book only once its own title has scrolled away.
    val titleGone by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    Column(modifier.fillMaxSize()) {
    TopAppBar(title = {
            AnimatedVisibility(titleGone, enter = fadeIn(), exit = fadeOut()) { Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
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
    // Loading shows in a fixed slot under the bar; the page never shifts when it begins or ends.
    Box(Modifier.fillMaxWidth().height(2.dp)) {
        androidx.compose.animation.AnimatedVisibility(visible = selected.loading, enter = fadeIn(), exit = fadeOut()) { LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), gapSize = 0.dp) }
    }
    LazyColumn(Modifier.weight(1f).fillMaxWidth().nestedScroll(scroll.nestedScrollConnection).testTag("book-details")
        .pointerInput(book.id) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); interacted = true } },
        listState, contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item(key = "identity") { BookIdentity(book, current?.recording ?: best) }
        selected.error?.let { item(key = "error") { RecoveryState("Couldn't load this book", it) { vm.open(book) } } }
        item(key = "actions") { Column(Modifier.animateContentSize(tween(Motion.MEDIUM, easing = Motion.Emphasized))) {
            val readingFirst = formats.leadingMode == PositionOrigin.READING
            val listen: @Composable () -> Unit = {
                ListenSlot(book, current, best, streamed, tally, connected, busy, starting, preparation, leading = !readingFirst, awaitingAudioSearch, onPhone,
                    announcement = streamed?.takeIf { current == null && tally != null }?.let { searchAnnouncement(it, pinned.shown, tally!!, book) },
                    finished = finished, actions = ListenActions(
                        listen = { vm.listenTo(it) }, getReady = { vm.getReady(it) }, change = { openChooser(ChooserView.LIST) },
                        review = { openChooser(ChooserView.ALL) }, findAudiobook = { vm.findSources(book.catalogIdentity(), force = true) },
                        searchAgain = { vm.findSources(book.catalogIdentity(), force = true) }, connectTorBox = vm::requestTorBoxConnect, sourceSettings = vm::openSourceSettings,
                        listenAgain = { vm.listenAgain(book.id) }, unfinish = { vm.setFinished(book.id, false) },
                    ))
            }
            val read: @Composable () -> Unit = { ReadSlot(book, formats, leading = readingFirst, read = { vm.read(book) }, change = openEbooks) }
            if (readingFirst) read() else listen()
            SharedPlaceCaption(formats, Modifier.padding(top = 10.dp))
            Spacer(Modifier.height(16.dp))
            if (readingFirst) listen() else read()
            if (awaitingAudioSearch) {
                Spacer(Modifier.height(8.dp))
                Text(if (connected) "Find audiobook looks for a free public recording and for recordings TorBox can play."
                     else "Find audiobook looks for a free public recording of this book. Connect TorBox for more.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OfflineRow(vm, book, offlineTarget, current, downloadsHere, wifiOnly, connected, busy, Modifier.padding(top = 12.dp))
        } }
        // Phone copies the offline row doesn't stand for (another recording or format) keep their own cards until removed.
        downloadsHere.filter { it !== offlineShown }.forEach { download -> item(key = "download:${download.source.id}") { OfflineStatus(vm, download, Modifier.animateItem()) } }
        preparation?.let { prep -> item(key = "preparation") {
            PreparationCard(prep, pending, busy, connected, check = { vm.refreshPreparation(book) }, listen = { vm.listenTo(it) },
                tryAnother = { vm.findRecordings(); openChooser(ChooserView.LIST) }, Modifier.animateItem())
        } }
        item(key = "about") { Column(Modifier.animateContentSize(tween(Motion.MEDIUM, easing = Motion.Emphasized))) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(24.dp))
            Text("About this book", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(12.dp))
            Text(book.description.ifBlank { "No description has been found for this book yet." }, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (expandedDescription) Int.MAX_VALUE else 9, overflow = TextOverflow.Ellipsis)
            if (book.description.length > 500) TextButton({ expandedDescription = !expandedDescription }) { Text(if (expandedDescription) "Read less" else "Read more") }
            if (book.metadataSource.isNotBlank() && book.metadataUrl.isNotBlank()) TextButton({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(book.metadataUrl))) }) { Text("Details from ${book.metadataSource}") }
        } }
        item(key = "more") {
            Column(Modifier.fillMaxWidth()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SummaryRow(Icons.Rounded.Headphones, "Recordings & sources", recordingsCount(search, streamed, tally, current), Modifier.testTag("recordings-row")) { openChooser(ChooserView.LIST) }
                SummaryRow(Icons.AutoMirrored.Rounded.MenuBook, "Ebook editions", ebookCount(ebookSearch.takeIf { it.bookId == book.id }, formats), Modifier.testTag("ebooks-row"), openEbooks)
            }
        }
    }
    }
    chooser?.let { view ->
        RecordingChooser(vm, book, current, search, streamed, tally, providersById, pinned, connected, busy, starting, preparation, entry, downloadsHere,
            preparingRecording = preparingRecording(entry, preparingHere),
            // Details refresh for the listener's recording, whichever way the page was opened.
            refreshDetails = current?.recording?.takeIf { it.provider != "archive" && !it.recordingId.startsWith("played:") }?.let { recording -> { vm.refreshMetadata(recording) } },
            metadataLoading = selected.metadataLoading, initialView = view,
            acceptBetter = { pinned = pinned.accept(); pinned.shown?.let { vm.chooseVersion(it.recording) } },
            dismiss = { chooser = null })
    }
    if (ebookSheet) EbookSheet(book, formats, ebookSearch, ebookProviders, connected, "Add and read", EbookActions(
        add = { vm.addEbook(book, it) }, addAndOpen = { vm.addEbook(book, it) { vm.read(book) } }, retry = vm::retryEbookSource,
        searchAgain = { vm.findEbooks(book, force = true) },
        chooseFile = { vm.beginEbookImport(book); ebookFile.launch(arrayOf("application/epub+zip", "text/plain", "application/octet-stream")) },
        activate = { vm.chooseEdition(book, it.id) }, remove = { vm.removeEbookEdition(book, it) }, openSearch = { vm.openEbookWebsite(book, it) },
        sourceSettings = { ebookSheet = false; vm.openSourceSettings() }, connectTorBox = vm::requestTorBoxConnect,
        searchWith = { vm.searchEbooksWith(book, it) }, linksFor = { vm.graph.addons.ebookSearchLinks(book, it) },
    ), dismiss = { ebookSheet = false }, searchLinks = ebookSearchLinks, narration = narration)
}

/** Cover, title, author, and who reads it: the listener's recording, else the page's best match or the catalog. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BookIdentity(book: Audiobook, recording: Audiobook?) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val identity: @Composable ColumnScope.() -> Unit = {
        Text(book.title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(8.dp)); Text(book.author, style = MaterialTheme.typography.bodyLarge, color = muted)
        val narrator = recording?.let(::recordingNarrator).orEmpty()
        // An unknown narrator is said once, in the line under Listen.
        val line = when {
            narrator.isNotBlank() -> "Read by $narrator"
            recording != null -> ""
            book.narratorFromCatalog && book.narrator.isNotBlank() -> "Read by ${book.narrator} in the catalog edition"
            else -> ""
        }
        if (line.isNotBlank()) { Spacer(Modifier.height(12.dp)); Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary) }
        val length = (recording?.durationMs?.takeIf { it > 0 } ?: book.durationMs).takeIf { it > 0 }
        if (length != null) Text(durationLabel(length), style = MaterialTheme.typography.labelMedium, color = muted, modifier = Modifier.padding(top = 8.dp))
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

/** What the listening slot can do; book details bind these to the view model. */
class ListenActions(
    val listen: (Audiobook) -> Unit,
    val getReady: (Audiobook) -> Unit,
    val change: () -> Unit,
    val review: () -> Unit,
    val findAudiobook: () -> Unit,
    val searchAgain: () -> Unit,
    val connectTorBox: () -> Unit,
    val sourceSettings: () -> Unit,
    /** A finished book: back to its start, or back among the books being listened to. */
    val listenAgain: () -> Unit = {},
    val unfinish: () -> Unit = {},
)

/**
 * One Listen or Resume and one line saying which recording it plays, with Change. The listener's own recording
 * always leads; a book never listened to plays its best match, which holds still once the page has been touched.
 */
@Composable
private fun ListenSlot(
    book: Audiobook, current: CurrentRecording?, best: Audiobook?, streamed: StreamedSourceSearch?, tally: SearchTally?, connected: Boolean, busy: Boolean,
    starting: Boolean, preparation: Preparation?, leading: Boolean, awaitingAudioSearch: Boolean, onPhone: (Audiobook) -> Boolean, announcement: String?, actions: ListenActions,
    finished: Boolean = false,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val button = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("listen-action")
    Column(Modifier.fillMaxWidth().testTag("listen-slot")) {
        when {
            awaitingAudioSearch -> OutlinedButton(actions.findAudiobook, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("find-audiobook-action")) { SlotLabel(Icons.Rounded.Search, "Find audiobook") }
            current != null -> {
                val copy = current.offline != null
                when {
                    starting -> ModeButton({}, button, leading, enabled = false) { WorkingLabel("Starting…") }
                    current.preparing && current.source == null && preparation?.ready != true -> ModeButton({}, button, leading, enabled = false) { WorkingLabel("Getting ready in TorBox…") }
                    !copy && needsTorBox(current.recording) && !connected ->
                        ModeButton(actions.connectTorBox, button, leading) { SlotLabel(Icons.Rounded.Link, if (current.started) "Connect TorBox to resume" else "Connect TorBox to listen") }
                    // Saved but never played, and not ready yet: getting it ready is the one thing to do.
                    current.source == null && !copy && !playableNow(current.recording, false) && preparation?.ready != true ->
                        ModeButton({ actions.getReady(current.recording) }, button, leading, enabled = !busy) { SlotLabel(Icons.Rounded.CloudDownload, "Get it ready") }
                    // A finished book never offers the last few seconds as a Resume.
                    finished -> ModeButton(actions.listenAgain, button, leading, enabled = !busy) { SlotLabel(Icons.Rounded.Replay, "Listen again") }
                    else -> ModeButton({ actions.listen(current.recording) }, button, leading, enabled = !busy) {
                        SlotLabel(if (copy) Icons.Rounded.OfflinePin else Icons.Rounded.PlayArrow, resumeLabel(current))
                    }
                }
                RecordingLine(recordingLine(current.recording, book, connected, copy).let { if (finished) "Finished · $it" else it }, actions.change)
                if (finished) TextButton(actions.unfinish, Modifier.heightIn(min = 48.dp).testTag("mark-not-finished")) { Text("Mark as not finished") }
            }
            best != null -> {
                val ready = playableNow(best, onPhone(best))
                when {
                    starting -> ModeButton({}, button, leading, enabled = false) { WorkingLabel("Starting…") }
                    !ready && !connected -> ModeButton(actions.connectTorBox, button, leading) { SlotLabel(Icons.Rounded.Link, "Connect TorBox to listen") }
                    !ready -> ModeButton({ actions.getReady(best) }, button, leading, enabled = !busy) { SlotLabel(Icons.Rounded.CloudDownload, "Get it ready") }
                    else -> ModeButton({ actions.listen(best) }, button, leading, enabled = !busy) { SlotLabel(Icons.Rounded.PlayArrow, "Listen") }
                }
                RecordingLine(recordingLine(best, book, connected, onPhone(best)), actions.change)
                if (!ready && connected) Text("Getting a recording ready can take from minutes to hours. Nothing downloads to your phone.",
                    style = MaterialTheme.typography.bodySmall, color = muted)
            }
            streamed != null && tally != null && !streamed.complete && tally.active > 0 -> {
                ModeButton({}, button, leading, enabled = false) { WorkingLabel("Finding audio…") }
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Working(Modifier.size(14.dp))
                    Text("Looking for recordings of this book", style = MaterialTheme.typography.bodyMedium, color = muted, modifier = Modifier.testTag("recording-summary"))
                }
            }
            streamed != null && tally != null -> {
                val copy = noMatchCopy(streamed, tally)
                when (copy.kind) {
                    NoMatchKind.POSSIBLE_ONLY -> ModeButton(actions.review, button, leading) { SlotLabel(Icons.Rounded.Search, "Review possible matches") }
                    NoMatchKind.ALL_FAILED -> ModeButton(actions.searchAgain, button, leading) { SlotLabel(Icons.Rounded.Refresh, "Try again") }
                    NoMatchKind.NEEDS_TORBOX -> ModeButton(actions.connectTorBox, button, leading) { SlotLabel(Icons.Rounded.Link, "Connect TorBox") }
                    NoMatchKind.ALL_OFF -> ModeButton(actions.sourceSettings, button, leading) { SlotLabel(Icons.Rounded.Tune, "Open source settings") }
                    else -> ModeButton(actions.searchAgain, button, leading) { SlotLabel(Icons.Rounded.Refresh, "Search again") }
                }
                Column(Modifier.padding(top = 10.dp).semantics(mergeDescendants = true) { }) {
                    Text(copy.title, style = MaterialTheme.typography.titleSmall)
                    Text(copy.message, style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(top = 2.dp))
                }
                if (copy.kind == NoMatchKind.NEEDS_TORBOX && tally.active > 0) TextButton(actions.searchAgain) { Text("Search again") }
            }
            else -> ModeButton({}, button, leading, enabled = false) { SlotLabel(Icons.Rounded.PlayArrow, "Listen") }
        }
        // Screen readers hear milestones only: searching, the first match, and the end of the search.
        if (announcement != null) Box(Modifier.size(1.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = announcement })
    }
}

/** "Ray Porter · Unabridged · Ready now", and Change, which opens the recording chooser. */
@Composable
private fun RecordingLine(text: String, change: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f).testTag("recording-summary"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(change, Modifier.testTag("change-recording").semantics { contentDescription = "Change recording" }) { Text("Change") }
    }
}

/** Read with one line for the active edition and Change, or Find ebook when the book has none. */
@Composable
private fun ReadSlot(book: Audiobook, formats: BookFormats, leading: Boolean, read: () -> Unit, change: () -> Unit) {
    val button = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    if (!formats.ebook) { OutlinedButton(change, button.testTag("find-ebook-action")) { SlotLabel(Icons.Rounded.Search, "Find ebook") }; return }
    ModeButton(read, button.testTag("read-action"), leading) { SlotLabel(Icons.AutoMirrored.Rounded.MenuBook, "Read") }
    val edition = formats.activeEdition ?: return
    val pairing = pairingCopy(formats)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("edition-summary"), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).semantics(mergeDescendants = true) { }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            // With a recording, the line says whether reading and listening share one place; otherwise where the ebook came from.
            if (pairing == null) Text(listOf("Ebook", edition.format, edition.attribution).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = muted)
            else {
                Text(listOf("Ebook", edition.format).filter(String::isNotBlank).joinToString(" · ") + " ·", style = MaterialTheme.typography.bodyMedium, color = muted)
                val (icon, tint) = when (formats.pairing) {
                    PairingStatus.MATCHES -> Icons.Rounded.CheckCircle to MaterialTheme.colorScheme.secondary
                    PairingStatus.PARTIAL -> Icons.AutoMirrored.Rounded.Rule to MaterialTheme.colorScheme.primary
                    PairingStatus.MISMATCH -> Icons.Rounded.ErrorOutline to MaterialTheme.colorScheme.error
                    PairingStatus.UNCHECKED -> Icons.Rounded.Schedule to muted
                }
                Icon(icon, null, Modifier.size(16.dp), tint = tint)
                Text(pairing.title, style = MaterialTheme.typography.bodyMedium, color = muted, modifier = Modifier.weight(1f, fill = false))
            }
        }
        TextButton(change, Modifier.testTag("change-ebook").semantics { contentDescription = "Change ebook edition" }) { Text("Change") }
    }
    // An edition named differently from the book (a translation, a collected volume) says so.
    if (edition.title.isNotBlank() && !edition.title.equals(book.title, ignoreCase = true))
        Text(edition.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/**
 * The phone copy of the recording Listen plays: "Download for offline · 412 MB", its progress while it saves, then
 * "On this phone" with Remove (and Play offline when Listen plays something else).
 */
@Composable
private fun OfflineRow(vm: NarrioViewModel, book: Audiobook, target: Audiobook?, current: CurrentRecording?, downloads: List<OfflineBook>, wifiOnly: Boolean,
                       connected: Boolean, busy: Boolean, modifier: Modifier = Modifier) {
    if (target == null) return
    val shown = offlineDownload(target, current, downloads)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when {
        shown != null && !shown.complete -> OfflineStatus(vm, shown, modifier)
        shown != null -> {
            val copy: OfflineBook = shown
            var remove by remember(copy.source.id) { mutableStateOf(false) }
            Row(modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("on-this-phone"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.OfflinePin, null, tint = MaterialTheme.colorScheme.secondary)
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) { }) {
                    Text("On this phone", style = MaterialTheme.typography.titleSmall)
                    Text(listOf(copy.source.format, copy.totalBytes.takeIf { it > 0 }?.let(::sizeLabel).orEmpty()).filter(String::isNotBlank).joinToString(" · ") + " · Plays without internet",
                        style = MaterialTheme.typography.bodySmall, color = muted)
                }
                if (current?.offline?.source?.id != copy.source.id) TextButton({ vm.start(copy.book, copy.source, copy.source.delivery) }, enabled = !busy) { Text("Play offline") }
                TextButton({ remove = true }) { Text("Remove") }
            }
            if (remove) AlertDialog(onDismissRequest = { remove = false }, title = { Text("Remove from this phone?") },
                text = { Text("The audio files are removed. Your book, bookmarks, and place stay on your shelf, and it can still stream.") },
                confirmButton = { TextButton({ vm.removeDownload(copy); remove = false }) { Text("Remove audio") } },
                dismissButton = { TextButton({ remove = false }) { Text("Keep it") } })
        }
        !connected && needsTorBox(target) -> Unit
        // Only audio that plays now can be saved; a recording still getting ready waits.
        current?.source?.takeIf { current.recording === target } == null && !playableNow(target, onPhone = false) -> Unit
        else -> {
            val format = current?.source?.format?.takeIf { current.recording === target } ?: defaultFormat(target, vm.savedFormat(book.id))
            val bytes = current?.source?.takeIf { current.recording === target }?.parts?.sumOf { it.sizeBytes }?.takeIf { it > 0 } ?: downloadBytes(target, format)
            Row(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = !busy, role = Role.Button, onClickLabel = "Download for offline") { vm.downloadRecording(target) }
                .heightIn(min = 48.dp).padding(vertical = 6.dp).testTag("download-offline"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.Download, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(listOfNotNull("Download for offline", bytes.takeIf { it > 0 }?.let(::sizeLabel)).joinToString(" · "), style = MaterialTheme.typography.titleSmall)
                    Text(if (wifiOnly) "Waits for Wi-Fi. Change this in Settings." else "Can use mobile data.", style = MaterialTheme.typography.bodySmall, color = muted)
                }
            }
        }
    }
}

/**
 * TorBox getting a recording ready: progress and time left, then Listen once it's ready; when it couldn't, the reason
 * and Try another recording, without progress.
 */
@Composable
private fun PreparationCard(prep: Preparation, pending: Audiobook?, busy: Boolean, connected: Boolean, check: () -> Unit, listen: (Audiobook) -> Unit,
                            tryAnother: () -> Unit, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(14.dp)).padding(20.dp).testTag("preparation")) {
        Row(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (prep.ready) Icons.Rounded.CheckCircle else if (prep.failed) Icons.Rounded.ErrorOutline else if (prep.paused) Icons.Rounded.PauseCircle else Icons.Rounded.CloudDownload, null,
                tint = if (prep.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            // Couldn't get it ready, or stopped checking: TorBox's outcome in its own words.
            Text(when { prep.ready -> "Ready to listen"; prep.problem.isNotEmpty() -> prep.state; else -> "Getting ready in TorBox" }, style = MaterialTheme.typography.titleMedium)
        }
        // A recording other than the one Resume plays says whose it is, so the two aren't mistaken for each other.
        pending?.let(::recordingNarrator)?.takeIf(String::isNotBlank)?.let {
            Text("$it's recording", style = MaterialTheme.typography.bodyMedium, color = muted, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(12.dp))
        if (!prep.ready && prep.problem.isEmpty()) {
            val progress by animateFloatAsState(prep.progress, tween(Motion.LONG, easing = Motion.Emphasized), label = "preparation")
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Text("${(prep.progress * 100).toInt()}%${if (prep.etaSeconds > 0) " · About ${durationLabel(prep.etaSeconds * 1000)} left" else ""}", style = MaterialTheme.typography.bodyMedium)
            // TorBox's own word for a stall or pause is worth showing; its usual "preparing" isn't.
            if (prep.state !in setOf("Preparing in TorBox", "Getting ready in TorBox", "Queued in TorBox", "Preparing files"))
                Text(prep.state, style = MaterialTheme.typography.bodySmall, color = muted)
            Spacer(Modifier.height(8.dp))
        }
        Text(preparationNote(prep, pending?.cacheState.orEmpty()), style = MaterialTheme.typography.bodyMedium, color = muted)
        if (prep.failed) OutlinedButton(tryAnother, Modifier.padding(top = 12.dp).heightIn(min = 48.dp)) { Text("Try another recording") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (prep.ready && pending != null) TextButton({ listen(pending) }, enabled = !busy) { Text("Listen to this recording") }
            if (!prep.ready) TextButton(check, enabled = !busy && connected) { Text("Check again") }
        }
    }
}

/** A row into a sheet: "Recordings & sources · 4 found ›". */
@Composable
private fun SummaryRow(icon: ImageVector, title: String, value: String, modifier: Modifier = Modifier, open: () -> Unit) {
    Column {
        Row(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = open).heightIn(min = 56.dp).padding(vertical = 8.dp)
            .semantics(mergeDescendants = true) { }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.secondary)
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** "4 found", "Searching", or, before a book with its own recording has looked, "Yours · find others". */
private fun recordingsCount(search: SourceSearchState?, streamed: StreamedSourceSearch?, tally: SearchTally?, current: CurrentRecording?): String {
    if (streamed == null || tally == null || search?.searched != true && current != null) return if (current != null) "Yours · find others" else "Find recordings"
    val count = tally.found + tally.possible
    return if (!streamed.complete) "Searching · $count so far" else "$count found"
}

private fun ebookCount(search: EbookSearchState?, formats: BookFormats): String = when {
    search?.searched == true && search.searching -> "Searching"
    search?.searched == true && search.streamed != null -> "${search.results.size} found"
    formats.editions.isNotEmpty() -> "${formats.editions.size} on this phone"
    else -> "Find an ebook"
}
