package app.narrio.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import app.narrio.domain.*

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

/**
 * Find ebook and Choose another edition: editions already on this phone, matching ebooks from the existing
 * providers, and a file from the phone. Adding one keeps the sheet's place; a successful add closes it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EbookSheet(book: Audiobook, formats: BookFormats, search: EbookSearchState, connected: Boolean, retry: () -> Unit, add: (BookTextSource) -> Unit,
               chooseFile: () -> Unit, activate: (EbookEdition) -> Unit, dismiss: () -> Unit, searchLinks: List<EbookSearchLink> = emptyList(), openSearch: (EbookSearchLink) -> Unit = {}) {
    val state = search.takeIf { it.bookId == book.id } ?: EbookSearchState(book.id)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(sheetState, dismiss)
    // A successful add slides the sheet away, revealing Read in its place.
    LaunchedEffect(state.added) { if (state.added != null) close {} }
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer, sheetState = sheetState) {
        val view = LocalView.current
        val dark = ThemeContrast.foreground(MaterialTheme.colorScheme.background.toArgb()) == 0xFFFFFF
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
        } }
        val busy = state.adding != null
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        LazyColumn(Modifier.fillMaxWidth().testTag("ebook-sheet"), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(if (formats.ebook) "Choose an edition" else "Find an ebook", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                Text(if (formats.audio) "Pick the same edition as the recording when you can: a matching edition lets reading and listening share one place."
                     else "Pick the edition you want to read. It's saved on this phone with the book.", style = MaterialTheme.typography.bodyMedium, color = muted)
            }
            if (formats.editions.isNotEmpty()) {
                item { Text("On this phone", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                items(formats.editions, key = { "edition:${it.id}" }) { edition ->
                    val active = edition.id == formats.activeEdition?.id
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .selectable(active, enabled = !busy, role = Role.RadioButton) { activate(edition) }.heightIn(min = 48.dp).padding(end = 12.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(active, null, Modifier.padding(horizontal = 12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(edition.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(listOf(edition.format, edition.attribution).filter(String::isNotBlank).joinToString(" · ").ifBlank { "Saved on this phone" },
                                style = MaterialTheme.typography.bodySmall, color = muted)
                        }
                    }
                }
            }
            item {
                Text("Found for this book", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(4.dp))
                Text(checkedProviders(book, connected), style = MaterialTheme.typography.bodySmall, color = muted)
            }
            when {
                state.searching -> item {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(state.step.ifBlank { "Looking for matching ebooks" } + "…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                state.error != null && state.results.isEmpty() -> item {
                    Text(state.error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    TextButton(retry, enabled = !busy) { Text("Search again") }
                }
                state.searched && state.results.isEmpty() -> item {
                    Text(if (searchLinks.isEmpty()) "No ebook with this exact title and author turned up. Add your own EPUB or text file below."
                         else "No ebook with this exact title and author turned up here. Try an ebook website or add your own file below.", style = MaterialTheme.typography.bodyMedium)
                    if (state.incomplete) TextButton(retry, enabled = !busy) { Text("Search again") }
                }
                else -> {
                    items(state.results, key = { "found:${it.id}" }) { candidate -> CandidateRow(candidate, state.adding == candidate.id, enabled = !busy) { add(candidate) } }
                    if (state.incomplete) item {
                        Text("Some providers didn't respond, so this list may be incomplete.", style = MaterialTheme.typography.bodySmall, color = muted)
                        TextButton(retry, enabled = !busy) { Text("Search again") }
                    }
                }
            }
            if (state.error != null && state.results.isNotEmpty()) item {
                Text(state.error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (searchLinks.isNotEmpty()) item {
                Text("Search ebook websites", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(4.dp))
                Text("Choose an EPUB on the website. Narrio tries TorBox first, then saves the website download when needed. Verification stays inside the app.",
                    style = MaterialTheme.typography.bodySmall, color = muted)
                searchLinks.forEach { link ->
                    OutlinedButton({ openSearch(link) }, Modifier.fillMaxWidth(), enabled = !busy) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Search ${link.name}")
                    }
                }
            }
            item {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 8.dp))
                OutlinedButton(chooseFile, Modifier.fillMaxWidth(), enabled = !busy) {
                    if (state.adding == EbookSearchState.FILE) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.UploadFile, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp)); Text(if (state.adding == EbookSearchState.FILE) "Adding your file…" else "Choose an EPUB or text file")
                }
                Spacer(Modifier.height(8.dp))
                Text("Ebooks stay on this phone. Kindle, PDF, and DRM-protected files can't be opened.", style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
    }
}

private fun checkedProviders(book: Audiobook, connected: Boolean): String {
    val recording = book.sources.any { source -> source.textFiles.any { it.format != "VTT" } }
    return when {
        connected && recording -> "From this recording's files, your TorBox account, cached ebook releases, and Project Gutenberg."
        connected -> "From your TorBox account, cached ebook releases, and Project Gutenberg."
        recording -> "From this recording's files and Project Gutenberg. Connect TorBox in Settings to also check your account and cached ebook releases."
        else -> "From Project Gutenberg's public-domain books. Connect TorBox in Settings to also check your account and cached ebook releases."
    }
}

@Composable
private fun CandidateRow(candidate: BookTextSource, adding: Boolean, enabled: Boolean, add: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClickLabel = "Add this ebook", onClick = add).heightIn(min = 48.dp).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(candidate.title, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(listOf(candidate.author, candidate.language, candidate.format).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(candidate.attribution.ifBlank { "In this recording's files" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
        }
        if (adding) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else Icon(Icons.Rounded.Add, null, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
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
