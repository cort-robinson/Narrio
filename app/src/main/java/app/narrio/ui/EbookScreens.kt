package app.narrio.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import app.narrio.domain.*
import kotlinx.coroutines.launch

/*
 * Reading beside listening: the formats a book has, the action for each, and finding or adding the missing one.
 * Copy names the format and what the action does; nothing here implies a format is present before it is.
 */

/**
 * The two format slots on a book's page. The mode used last leads as the filled action; the other format is tonal,
 * or an outlined Find action when it's missing.
 */
@Composable
fun FormatActions(formats: BookFormats, audioSlot: @Composable (Modifier, Boolean) -> Unit, read: () -> Unit, findEbook: () -> Unit, modifier: Modifier = Modifier) {
    val readingFirst = formats.leadingMode == PositionOrigin.READING
    val readSlot: @Composable () -> Unit = {
        val slot = Modifier.fillMaxWidth()
        when {
            formats.ebook -> ModeButton(read, slot.testTag("read-action"), leading = readingFirst) { SlotLabel(Icons.AutoMirrored.Rounded.MenuBook, "Read") }
            else -> OutlinedButton(findEbook, slot.testTag("find-ebook-action")) { SlotLabel(Icons.Rounded.Search, "Find ebook") }
        }
    }
    val listenSlot: @Composable () -> Unit = { audioSlot(Modifier.fillMaxWidth(), !readingFirst) }
    SlotPair(if (readingFirst) readSlot else listenSlot, if (readingFirst) listenSlot else readSlot, modifier.fillMaxWidth())
}

/**
 * Two equal slots side by side when both labels fit at their natural width; otherwise stacked full width. Long
 * labels, narrow panes, and large text stack instead of truncating an action.
 */
@Composable
fun SlotPair(first: @Composable () -> Unit, second: @Composable () -> Unit, modifier: Modifier = Modifier) {
    Layout(listOf(first, second), modifier) { (a, b), constraints ->
        val gap = 12.dp.roundToPx()
        val half = (constraints.maxWidth - gap) / 2
        val one = a.first(); val two = b.first()
        if (one.maxIntrinsicWidth(constraints.maxHeight) <= half && two.maxIntrinsicWidth(constraints.maxHeight) <= half) {
            val slot = Constraints.fixedWidth(half)
            val left = one.measure(slot); val right = two.measure(slot)
            val height = maxOf(left.height, right.height)
            layout(constraints.maxWidth, height) { left.placeRelative(0, (height - left.height) / 2); right.placeRelative(half + gap, (height - right.height) / 2) }
        } else {
            val full = Constraints.fixedWidth(constraints.maxWidth)
            val top = one.measure(full); val bottom = two.measure(full)
            val between = 10.dp.roundToPx()
            layout(constraints.maxWidth, top.height + between + bottom.height) { top.placeRelative(0, 0); bottom.placeRelative(0, top.height + between) }
        }
    }
}

@Composable
fun SlotLabel(icon: ImageVector, label: String) {
    Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Where the shared place stands, and where the other mode opens when sync has mapped it. */
@Composable
fun SharedPlaceCaption(formats: BookFormats, modifier: Modifier = Modifier) {
    val place = placeSummary(formats) ?: return
    val other = counterpartPlace(formats)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val doing = if (place.mode == PositionOrigin.READING) "Reading" else "Listening"
        Text("$doing · ${place.label}", Modifier.semantics { contentDescription = "$doing at ${spokenPlace(place.label)}" },
            style = MaterialTheme.typography.labelMedium, color = muted)
        place.fraction?.let { progress ->
            LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().height(3.dp).clearAndSetSemantics { }, color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outlineVariant, gapSize = 0.dp, drawStopIndicator = {})
        }
        other?.let { EstimatedPlace(it.label, it.confidence, prefix = if (it.mode == PositionOrigin.READING) "Reading opens at " else "Listening starts at ") }
    }
}

/** The active edition, its match with the narration when there's a recording, and the way to change it. */
@Composable
fun EditionSummary(book: Audiobook, formats: BookFormats, chooseEdition: () -> Unit, modifier: Modifier = Modifier) {
    val edition = formats.activeEdition ?: return
    val pairing = pairingCopy(formats)
    Column(modifier.fillMaxWidth().testTag("edition-summary")) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.AutoMirrored.Rounded.MenuBook, null, Modifier.padding(top = 2.dp).size(20.dp), tint = MaterialTheme.colorScheme.secondary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(listOf("Ebook", edition.format, edition.attribution).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.titleSmall)
                // An edition named differently from the book (a translation, a collected volume) says so.
                if (edition.title.isNotBlank() && !edition.title.equals(book.title, ignoreCase = true))
                    Text(edition.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        pairing?.let { copy ->
            Spacer(Modifier.height(12.dp))
            PairingLine(formats.pairing, copy)
        }
        TextButton(chooseEdition, Modifier.padding(start = 20.dp)) { Text(if (formats.editions.size > 1) "Choose another edition (${formats.editions.size} on this phone)" else "Choose another edition") }
    }
}

@Composable
fun PairingLine(status: PairingStatus, copy: PairingCopy, modifier: Modifier = Modifier) {
    val (icon, tint) = when (status) {
        PairingStatus.MATCHES -> Icons.Rounded.CheckCircle to MaterialTheme.colorScheme.secondary
        PairingStatus.PARTIAL -> Icons.Rounded.Rule to MaterialTheme.colorScheme.primary
        PairingStatus.MISMATCH -> Icons.Rounded.ErrorOutline to MaterialTheme.colorScheme.error
        PairingStatus.UNCHECKED -> Icons.Rounded.Schedule to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(modifier.semantics(mergeDescendants = true) { }, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.padding(top = 1.dp).size(20.dp), tint = tint)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(copy.title, style = MaterialTheme.typography.labelLarge)
            Text(copy.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** What the ebook sheet can do; book details and the Listening room bind these to the view model. */
class EbookActions(
    /** Adds a found ebook; the sheet closes, revealing Read. */
    val add: (BookTextSource) -> Unit,
    /** Adds the best match and opens it, as Listen plays the best recording. */
    val addAndOpen: (BookTextSource) -> Unit,
    val retry: (String) -> Unit,
    val searchAgain: () -> Unit,
    val chooseFile: () -> Unit,
    val activate: (EbookEdition) -> Unit,
    val remove: (EbookEdition) -> Unit,
    val openSearch: (EbookSearchLink) -> Unit,
    val sourceSettings: () -> Unit,
    val connectTorBox: () -> Unit,
    /** Searches every ebook source with the reader's own words, kept for the book; blank returns to its details. */
    val searchWith: (String) -> Unit = {},
)

/**
 * Find ebook and Choose another edition, simple first: editions already on this phone, one best match with a single
 * action, Other choices as one flat list, and a file from the phone. Advanced holds each ebook source's own section,
 * searching with different words, and ebook websites. Adding one keeps the sheet's place; a successful add closes it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EbookSheet(book: Audiobook, formats: BookFormats, search: EbookSearchState, providers: List<SourceProvider>, connected: Boolean,
               openLabel: String, actions: EbookActions, dismiss: () -> Unit, searchLinks: List<EbookSearchLink> = emptyList()) {
    val state = search.takeIf { it.bookId == book.id } ?: EbookSearchState(book.id)
    var removing by remember { mutableStateOf<EbookEdition?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(sheetState, dismiss)
    // A successful add slides the sheet away, revealing Read in its place.
    LaunchedEffect(state.added) { if (state.added != null) close {} }
    val providersById = remember(providers) { providers.associateBy { it.id } }
    val recording = remember(book.sources) { book.sources.isNotEmpty() }
    // With a recording, choices say which likely follow the narration.
    val narrated = formats.audio || recording
    val streamed = state.streamed?.takeIf { it.book.id == book.id }
        ?: remember(state.searching, state.searched, state.searchError, providers, connected, recording) {
            pendingEbookSearch(book, providers, connected, recording, searching = !state.searched || state.searching, error = state.searchError)
        }
    val tally = remember(streamed, providersById, connected) { ebookTally(streamed, providersById, connected) }
    // As on book details: once the reader touches the sheet (or uses TalkBack), a better match waits instead of moving.
    val context = LocalContext.current
    val talkBack = remember(context) { (context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager).isTouchExplorationEnabled }
    var interacted by remember(book.id) { mutableStateOf(talkBack) }
    var pinned by remember(book.id) { mutableStateOf(PinnedEbook()) }
    LaunchedEffect(streamed.best, interacted, streamed.groups.size) {
        pinned = pinned.next(streamed.best, interacted) { id -> streamed.groups.any { group -> group.editions.any { it.id == id } } }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress) interacted = true }
    val scope = rememberCoroutineScope()
    val animate = animationsEnabled()
    // Simple first: other choices and the expert detail stay folded until asked for.
    var showChoices by rememberSaveable(book.id) { mutableStateOf(false) }
    var advanced by rememberSaveable(book.id) { mutableStateOf(false) }
    val choices = remember(streamed, pinned.shown, book) { ebookChoices(streamed, book, pinned.shown?.edition?.id) }
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer, sheetState = sheetState) {
        val view = LocalView.current
        val dark = ThemeContrast.foreground(MaterialTheme.colorScheme.background.toArgb()) == 0xFFFFFF
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
        } }
        val busy = state.adding != null
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        // Items before the best match: the intro, the phone's editions, and the reader's own search words.
        val bestIndex = 1 + (if (formats.editions.isNotEmpty()) 1 + formats.editions.size else 0) + (if (state.words.isNotBlank()) 1 else 0)
        fun scrollTo(index: Int) { scope.launch { if (animate) listState.animateScrollToItem(index) else listState.scrollToItem(index) } }
        val revealChoices = { showChoices = true; scrollTo(bestIndex + 1) }
        LazyColumn(Modifier.fillMaxWidth().testTag("ebook-sheet")
            .pointerInput(book.id) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); interacted = true } },
            listState, contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "intro") {
                Text(if (formats.ebook) "Choose an edition" else "Find an ebook", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                Text(if (narrated) "The edition likeliest to match the recording comes first, so reading and listening can share one place."
                     else "Pick the edition you want to read. It's saved on this phone with the book.", style = MaterialTheme.typography.bodyMedium, color = muted)
            }
            if (formats.editions.isNotEmpty()) {
                item(key = "phone-heading") { Text("On this phone", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                items(formats.editions, key = { "edition:${it.id}" }) { edition ->
                    val active = edition.id == formats.activeEdition?.id
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .selectable(active, enabled = !busy, role = Role.RadioButton) { actions.activate(edition) }.heightIn(min = 48.dp).padding(end = 12.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(active, null, Modifier.padding(horizontal = 12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(edition.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(listOf(edition.format, edition.attribution).filter(String::isNotBlank).joinToString(" · ").ifBlank { "Saved on this phone" },
                                style = MaterialTheme.typography.bodySmall, color = muted)
                        }
                        IconButton({ removing = edition }, enabled = !busy) { Icon(Icons.Rounded.DeleteOutline, "Remove ${edition.title} from this phone", tint = muted) }
                    }
                }
            }
            if (state.words.isNotBlank()) item(key = "words") { CustomWordsLine(state.words, busy, reset = { actions.searchWith("") }) }
            item(key = "best") {
                Column {
                    BestEbookCard(streamed, pinned, tally, providersById, book, narrated, choices.size, showChoices, state.adding, state.step, busy, openLabel, actions,
                        acceptBetter = { pinned = pinned.accept() }, toggleChoices = { if (showChoices) showChoices = false else revealChoices() },
                        reviewPossible = revealChoices, Modifier.padding(top = 8.dp))
                    Box(Modifier.size(1.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = ebookAnnouncement(streamed, pinned.shown, tally, book) })
                }
            }
            if (showChoices) {
                item(key = "choices-heading") {
                    Text(if (choices.isEmpty()) "No other choices yet." else "Other choices", style = MaterialTheme.typography.titleSmall,
                        color = if (choices.isEmpty()) muted else MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() }.testTag("ebook-other-choices"))
                }
                items(choices, key = { "choice:${it.edition.id}" }) { choice ->
                    EbookChoiceRow(choice, book, narrated, adding = state.adding == choice.edition.id, state.step, enabled = !busy, add = { actions.add(choice.edition) },
                        modifier = Modifier.animateItem())
                }
            }
            state.error?.let { error -> item(key = "error") {
                Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            } }
            item(key = "file") {
                // Any EPUB or text file works, so the universal fallback stays on the simple layer.
                TextButton(actions.chooseFile, Modifier.heightIn(min = 48.dp), enabled = !busy) {
                    if (state.adding == EbookSearchState.FILE) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.UploadFile, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp)); Text(if (state.adding == EbookSearchState.FILE) "Adding your file…" else "Choose an EPUB or text file")
                }
                Text("Ebooks stay on this phone. Kindle, PDF, and DRM-protected files can't be opened.", style = MaterialTheme.typography.bodySmall, color = muted,
                    modifier = Modifier.padding(start = 12.dp))
            }
            item(key = "advanced") {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(bottom = 4.dp))
                    ExpandRow("Advanced", advanced, { advanced = !advanced }, Modifier.testTag("ebook-advanced"), lead = "Can't find the right one?")
                }
            }
            if (advanced) {
                item(key = "search-words") { EbookSearchWords(book, state.words, busy, search = { words -> actions.searchWith(customEbookWords(words, book)); scrollTo(bestIndex) }) }
                item(key = "sources-heading") {
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("ebook-sources")) {
                        Text("Ebook sources", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                        Spacer(Modifier.height(4.dp))
                        Text(searchSummary(streamed.complete, tally), style = MaterialTheme.typography.labelMedium, color = muted, modifier = Modifier.testTag("ebook-sources-summary"))
                    }
                }
                item(key = "sources") {
                    Column(Modifier.fillMaxWidth().animateContentSize(tween(Motion.MEDIUM, easing = Motion.Emphasized))) {
                        streamed.groups.forEach { group ->
                            key(group.providerId) { EbookSection(group, streamed, providersById[group.providerId], pinned.shown?.edition?.id, state.adding, state.step, busy, connected, actions) }
                        }
                    }
                }
                if (searchLinks.isNotEmpty()) item(key = "websites") {
                    Text("Search ebook websites", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                    Spacer(Modifier.height(4.dp))
                    Text("Choose an EPUB on the website. Narrio tries TorBox first, then saves the website download when needed. Verification stays inside the app.",
                        style = MaterialTheme.typography.bodySmall, color = muted)
                    searchLinks.forEach { link ->
                        OutlinedButton({ actions.openSearch(link) }, Modifier.fillMaxWidth(), enabled = !busy) {
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Search ${link.name}")
                        }
                    }
                }
            }
        }
    }
    removing?.let { edition ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remove this ebook?") },
            text = { Text("${edition.title} and its narration timing are removed from this phone. Audio, bookmarks, and listening progress stay saved.") },
            confirmButton = { TextButton({ actions.remove(edition); removing = null }) { Text("Remove ebook") } },
            dismissButton = { TextButton({ removing = null }) { Text("Keep ebook") } })
    }
}

/**
 * The best ebook, why it was chosen, and one action that adds and opens it. While sources are still answering it may
 * improve, but once the reader has touched the sheet a better one waits behind "Better match found". [others] counts
 * the flat Other choices list that [toggleChoices] shows or hides.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BestEbookCard(search: StreamedEbookSearch, pinned: PinnedEbook, tally: SearchTally, providers: Map<String, SourceProvider>, book: Audiobook,
                          narrated: Boolean, others: Int, showingChoices: Boolean, adding: String?, step: String, busy: Boolean, openLabel: String,
                          actions: EbookActions, acceptBetter: () -> Unit, toggleChoices: () -> Unit, reviewPossible: () -> Unit, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val shown = pinned.shown
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp).animateContentSize(tween(Motion.MEDIUM, easing = Motion.Emphasized)).testTag("best-ebook")) {
        AnimatedVisibility(pinned.pending != null, enter = expandVertically(tween(Motion.MEDIUM, easing = Motion.Emphasized)) + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            BetterMatchFound(acceptBetter, Modifier.padding(bottom = 12.dp))
        }
        if (shown != null || !search.complete) {
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Best match", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                AnimatedVisibility(!search.complete && tally.active > 0, enter = fadeIn(), exit = fadeOut()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Working(Modifier.size(14.dp))
                        Text(if (shown == null) "Checking ${tally.active} ${plural(tally.active, "source")}" else "Checking ${tally.active - tally.answered} more",
                            style = MaterialTheme.typography.labelMedium, color = muted)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        when {
            shown != null -> AnimatedContent(shown, transitionSpec = { fadeIn(tween(Motion.MEDIUM, 90, Motion.EmphasizedDecelerate)).togetherWith(fadeOut(tween(90))) },
                contentKey = { it.edition.id }, label = "best ebook") { best ->
                Column {
                    val copy = bestEbookCopy(best, book, narrated)
                    Text(copy.headline, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("best-ebook-reasons"))
                    if (copy.detail.isNotBlank()) Text(copy.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 2.dp))
                    Text(best.edition.title, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    val also = ebookAlsoFoundBy(search, best.edition.id)
                    Text(listOfNotNull("From ${providers[best.providerId]?.name ?: best.edition.attribution}", also.takeIf { it.isNotEmpty() }?.let { "also found by ${it.joinToString()}" }).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, color = muted, modifier = Modifier.padding(top = 2.dp))
                }
            }
            !search.complete && tally.active > 0 -> CardSkeleton()
            else -> {
                val copy = ebookNoMatchCopy(search, tally)
                Text(copy.title, style = MaterialTheme.typography.titleMedium)
                Text(copy.message, style = MaterialTheme.typography.bodyMedium, color = muted, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
        val button = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        when {
            shown != null && adding == shown.edition.id -> Column {
                Button({}, button.testTag("best-ebook-action"), enabled = false) { WorkingLabel("Adding…") }
                AddingStep(step, Modifier.padding(top = 8.dp))
            }
            shown != null -> Button({ actions.addAndOpen(shown.edition) }, button.testTag("best-ebook-action"), enabled = !busy) { SlotLabel(Icons.AutoMirrored.Rounded.MenuBook, openLabel) }
            !search.complete && tally.active > 0 -> Button({}, button.testTag("best-ebook-action"), enabled = false) { WorkingLabel("Finding an ebook…") }
            else -> when (ebookNoMatchCopy(search, tally).kind) {
                NoMatchKind.POSSIBLE_ONLY -> FilledTonalButton(reviewPossible, button) { SlotLabel(Icons.Rounded.Search, "Review possible matches") }
                NoMatchKind.ALL_FAILED -> FilledTonalButton(actions.searchAgain, button) { SlotLabel(Icons.Rounded.Refresh, "Try again") }
                NoMatchKind.NEEDS_TORBOX -> FilledTonalButton(actions.connectTorBox, button) { SlotLabel(Icons.Rounded.Link, "Connect TorBox") }
                NoMatchKind.ALL_OFF -> FilledTonalButton(actions.sourceSettings, button) { SlotLabel(Icons.Rounded.Tune, "Open source settings") }
                else -> FilledTonalButton(actions.searchAgain, button) { SlotLabel(Icons.Rounded.Refresh, "Search again") }
            }
        }
        // Like the listening card, the secondary row keeps its height while searching so the sheet never shifts.
        if (shown == null && search.complete) Spacer(Modifier.height(14.dp))
        else Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            val turn by animateFloatAsState(if (showingChoices) 180f else 0f, tween(Motion.MEDIUM, easing = Motion.Emphasized), label = "choices")
            TextButton(toggleChoices, Modifier.testTag("ebook-other-choices-toggle").semantics { stateDescription = if (showingChoices) "Expanded" else "Collapsed" }) {
                Text(if (search.complete) "Other choices · $others" else "Other choices · $others so far")
                Icon(Icons.Rounded.ExpandMore, null, Modifier.padding(start = 4.dp).size(18.dp).rotate(turn))
            }
        }
    }
}

private const val SHOWN_EBOOKS = 3

@Composable
private fun EbookSection(group: EbookGroup, search: StreamedEbookSearch, provider: SourceProvider?, bestId: String?, adding: String?, step: String, busy: Boolean,
                         connected: Boolean, actions: EbookActions) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var showAll by rememberSaveable(group.providerId) { mutableStateOf(false) }
    var showPossible by rememberSaveable(group.providerId) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("ebook-section:${group.providerId}")) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(group.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            SectionStatus(group.status, ebookGroupStatusLabel(group, provider, connected), group.editions.isNotEmpty())
        }
        when (group.status) {
            SourceGroupStatus.SEARCHING, SourceGroupStatus.CHECKING -> if (group.editions.isEmpty() && group.possible.isEmpty()) ReleaseSkeleton()
            SourceGroupStatus.FAILED -> {
                Text(group.message?.takeIf(String::isNotBlank) ?: "This source didn't answer.", style = MaterialTheme.typography.bodySmall, color = muted)
                group.checkUrl?.let { url ->
                    TextButton({ actions.openSearch(EbookSearchLink(group.name, url)) }, Modifier.testTag("ebook-check:${group.providerId}"), enabled = !busy) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Open browser check")
                    }
                }
                TextButton({ actions.retry(group.providerId) }, Modifier.semantics { contentDescription = "Retry ${group.name}" }.testTag("ebook-retry:${group.providerId}")) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Retry")
                }
            }
            else -> Unit
        }
        val shown = if (showAll) group.editions else group.editions.take(SHOWN_EBOOKS)
        shown.forEach { candidate ->
            CandidateRow(candidate, best = candidate.id == bestId, adding = adding == candidate.id, step, enabled = !busy, also = ebookAlsoFoundBy(search, candidate.id)) { actions.add(candidate) }
        }
        if (group.editions.size > SHOWN_EBOOKS) TextButton({ showAll = !showAll }) {
            Text(if (showAll) "Show fewer" else "Show all ${group.editions.size} from ${group.name}")
        }
        if (group.possible.isNotEmpty()) {
            val turn by animateFloatAsState(if (showPossible) 180f else 0f, tween(Motion.MEDIUM, easing = Motion.Emphasized), label = "possible")
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClickLabel = if (showPossible) "Hide possible matches" else "Show possible matches") { showPossible = !showPossible }
                .heightIn(min = 48.dp).semantics { stateDescription = if (showPossible) "Expanded" else "Collapsed" }.testTag("ebook-possible:${group.providerId}"),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Possible matches · ${group.possible.size}", style = MaterialTheme.typography.labelLarge, color = muted, modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.ExpandMore, null, Modifier.rotate(turn), tint = muted)
            }
            AnimatedVisibility(showPossible, enter = expandVertically(tween(Motion.MEDIUM, easing = Motion.Emphasized)) + fadeIn(), exit = shrinkVertically(tween(Motion.MEDIUM, easing = Motion.Emphasized)) + fadeOut()) {
                Column {
                    Text("These might be this book. Check the title and author before adding one.", style = MaterialTheme.typography.bodySmall, color = muted)
                    group.possible.forEach { candidate ->
                        CandidateRow(candidate, best = false, adding = adding == candidate.id, step, enabled = !busy, also = ebookAlsoFoundBy(search, candidate.id)) { actions.add(candidate) }
                    }
                }
            }
        }
        if (group.editions.isNotEmpty() || group.possible.isNotEmpty() || group.status == SourceGroupStatus.FAILED) Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun CandidateRow(candidate: BookTextSource, best: Boolean, adding: Boolean, step: String, enabled: Boolean, also: List<String>, add: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClickLabel = "Add this ebook", onClick = add).heightIn(min = 48.dp).padding(vertical = 8.dp)
        .testTag("ebook:${candidate.id}"), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (best) Text("Best match", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(candidate.title, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(listOf(candidate.author, candidate.language, candidate.format).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(candidate.attribution.ifBlank { "In this recording's files" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            if (also.isNotEmpty()) Text("Also found by ${also.joinToString()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (adding) AddingStep(step)
        }
        if (adding) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else Icon(Icons.Rounded.Add, null, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** What a slow download is doing, such as waiting for a website's free server; nothing for a quick add. */
@Composable
private fun AddingStep(step: String, modifier: Modifier = Modifier) {
    if (step.isNotBlank()) Text(step, modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Format marks for a shelf row: quiet icons and words, no extra container around the row. */
@Composable
fun FormatMarks(formats: BookFormats, modifier: Modifier = Modifier) {
    if (!formats.audio && !formats.ebook) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (formats.audio) FormatMark(Icons.Rounded.Headphones, "Audiobook")
        if (formats.ebook) FormatMark(Icons.AutoMirrored.Rounded.MenuBook, "Ebook")
    }
}

@Composable
private fun FormatMark(icon: ImageVector, label: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One progress line and bar from the shared place: "Ch 12 · 43%" or "Part 3 of 12 · 43%". */
@Composable
fun ShelfProgress(place: PlaceSummary, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text(place.label, Modifier.semantics { contentDescription = "${if (place.mode == PositionOrigin.READING) "Read to" else "Listened to"} ${spokenPlace(place.label)}" },
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        place.fraction?.let { progress ->
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().height(3.dp).clearAndSetSemantics { }, color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outlineVariant, gapSize = 0.dp, drawStopIndicator = {})
        }
    }
}

@Composable
fun ShelfFilters(selected: ShelfFilter, choose: (ShelfFilter) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(modifier.testTag("shelf-filters"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(ShelfFilter.entries) { filter ->
            FilterChip(selected == filter, { choose(filter) }, { Text(filter.label) },
                leadingIcon = { AnimatedVisibility(selected == filter, enter = expandHorizontally() + fadeIn(), exit = shrinkHorizontally() + fadeOut()) { Icon(Icons.Rounded.Check, null, Modifier.size(FilterChipDefaults.IconSize)) } })
        }
    }
}

/** Adding a file as a new book: what's happening, or why it couldn't, with the next useful action. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EbookImportStatus(state: EbookImportState, chooseAnother: () -> Unit, dismiss: () -> Unit, modifier: Modifier = Modifier) {
    if (!state.working && state.error == null) return
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(14.dp)).padding(20.dp)
        .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
        if (state.working) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Adding your ebook", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text("Reading its title and author, then looking up a cover and description.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("Couldn't add this ebook", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(state.error.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow { TextButton(chooseAnother) { Text("Choose another file") }; TextButton(dismiss) { Text("Dismiss") } }
        }
    }
}
