package app.narrio.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.narrio.data.ShelfEntry
import app.narrio.domain.*

@Composable
fun DiscoverScreen(vm: NarrioViewModel, modifier: Modifier = Modifier) {
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val category by vm.category.collectAsStateWithLifecycle()
    val shelf by vm.shelf.collectAsStateWithLifecycle()
    val playing by vm.playback.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val browse = query.isBlank() && category == "All"
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item(key = "heading") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                NarrioMark(); Spacer(Modifier.width(10.dp))
                Text("narrio", style = MaterialTheme.typography.headlineMedium)
            }
        }
        item(key = "search") {
            OutlinedTextField(query, onValueChange = { vm.search(it) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Search books or authors") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { AnimatedVisibility(query.isNotEmpty(), enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) { IconButton(onClick = { vm.search("") }) { Icon(Icons.Rounded.Close, "Clear search") } } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                shape = RoundedCornerShape(14.dp))
            AnimatedVisibility(query.isBlank(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                LazyRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("All", "Fiction", "Mystery", "Wonder", "Nonfiction")) { FilterChip(category == it, { vm.search(cat = it) }, { Text(it) },
                        leadingIcon = { AnimatedVisibility(category == it, enter = expandHorizontally() + fadeIn(), exit = shrinkHorizontally() + fadeOut()) { Icon(Icons.Rounded.Check, null, Modifier.size(FilterChipDefaults.IconSize)) } }) }
                }
            }
        }
        catalog.notice?.let { item(key = "notice") { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        catalog.error?.let { error -> item(key = "error") { RecoveryState("Couldn't load book metadata", error, { vm.search() }) } }
        if (browse) {
            // The current book already lives in the mini-player; offer the thread only when it isn't playing.
            shelf.firstOrNull { it.playedAt > 0 && it.sourceJson.isNotBlank() }?.takeIf { it.bookId != playing.book?.id }?.let { current -> item(key = "resume") {
                ResumeCard(current, { vm.resume(current) }, { vm.open(current.book()) }, Modifier.animateItem(fadeInSpec = androidx.compose.animation.core.tween(240, delayMillis = 160)))
            } }
        }
        if (catalog.loading && catalog.books.isEmpty()) {
            items(4, key = { "skeleton$it" }) { SkeletonBookRow(Modifier.animateItem()) }
        } else if (catalog.books.isNotEmpty()) {
            if (query.isNotBlank()) item(key = "results-title") {
                Text("${catalog.books.size} ${if (catalog.books.size == 1) "book" else "books"}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(catalog.books, key = { it.id }) { book -> BookRow(book, { vm.open(book) }, Modifier.animateItem()) }
        } else if (!catalog.loading && catalog.error == null) item(key = "empty") {
            EmptyState("No books found", "Try another title or author, or clear the category.", Icons.Rounded.Search)
        }
    }
}

@Composable
fun BookRow(book: Audiobook, open: () -> Unit, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = open),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        BookCover(book, Modifier.width(76.dp).height(112.dp), sharedKey = "cover-${book.id}")
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(book.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (book.provider == "catalog") Text(providerLabel(book), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            else Text(narrationLabel(book), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (book.durationMs > 0) Text(durationLabel(book.durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (book.provider != "catalog" && (book.provider != "archive" || book.cacheState != "unchecked")) Text(if (book.cacheState == "cached") "Ready in TorBox · ${book.cachedFormats.joinToString(" / ")}" else "${providerLabel(book)} · ${if (book.cacheState == "uncached") "Not cached" else "Cache not checked"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

/** A warm return to the last story: where you were, how far along, and one tap to keep listening. */
@Composable
private fun ResumeCard(entry: ShelfEntry, resume: () -> Unit, details: () -> Unit, modifier: Modifier = Modifier) {
    val book = remember(entry.bookJson) { entry.book() }
    val source = remember(entry.sourceJson) { entry.source() }
    val interaction = remember { MutableInteractionSource() }
    Column(modifier) {
        Text("Continue listening", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(14.dp))
        Surface(modifier = Modifier.fillMaxWidth().pressScale(interaction, .98f), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer, onClick = details, interactionSource = interaction) {
            Column {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    BookCover(book, Modifier.width(64.dp).height(94.dp), sharedKey = "cover-${book.id}")
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(book.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(placeLabel(entry, source), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                    }
                    FilledIconButton(resume, Modifier.size(52.dp)) { Icon(Icons.Rounded.PlayArrow, "Resume ${book.title}", Modifier.size(28.dp)) }
                }
                listenedFraction(entry, source)?.let { progress ->
                    LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().height(3.dp), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.outlineVariant, gapSize = 0.dp, drawStopIndicator = {})
                }
            }
        }
    }
}

private fun placeLabel(entry: ShelfEntry, source: AudioSource?): String {
    val index = source?.let { resumeIndex(it.parts, entry.partId) } ?: 0
    return "Part ${index + 1} of ${source?.parts?.size ?: 1} · ${formatTime(entry.positionMs)} in"
}

/** Whole-book progress when every part reports its length; otherwise unknown rather than guessed. */
private fun listenedFraction(entry: ShelfEntry, source: AudioSource?): Float? {
    val parts = source?.parts?.takeIf { it.isNotEmpty() && it.all { part -> part.durationMs > 0 } } ?: return null
    val index = resumeIndex(parts, entry.partId)
    val total = parts.sumOf { it.durationMs }
    return ((parts.take(index).sumOf { it.durationMs } + entry.positionMs).toFloat() / total).coerceIn(0f, 1f)
}

@Composable
fun LibraryScreen(vm: NarrioViewModel, modifier: Modifier = Modifier) {
    val shelf by vm.shelf.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val playing by vm.playback.collectAsStateWithLifecycle()
    var remove by remember { mutableStateOf<Audiobook?>(null) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item(key = "heading") {
            Text("My shelf", style = MaterialTheme.typography.displaySmall)
        }
        if (shelf.isEmpty()) item(key = "empty") {
            Spacer(Modifier.height(30.dp))
            EmptyState("Nothing saved yet", "Save a book or start listening. Your books, progress, and bookmarks stay here on this device.", Icons.Rounded.AutoStories)
            Spacer(Modifier.height(20.dp))
            Button({ vm.navigate(0) }, Modifier.fillMaxWidth()) { Text("Discover books") }
        }
        items(shelf, key = { it.bookId }) { entry ->
            val book = remember(entry.bookJson) { entry.book() }
            var menu by remember { mutableStateOf(false) }
            Column(Modifier.animateItem()) {
                ShelfRow(entry, book, playing.book?.id == entry.bookId && playing.playing, { vm.resume(entry) }, { vm.open(book) })
                downloads.filter { it.book.id == entry.bookId }.forEach { download -> Spacer(Modifier.height(14.dp)); OfflineStatus(vm, download) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        IconButton({ menu = true }) { Icon(Icons.Rounded.MoreHoriz, "More for ${book.title}") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Remove from shelf") }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) }, onClick = { menu = false; remove = book })
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
    remove?.let { book -> AlertDialog(onDismissRequest = { remove = null }, title = { Text("Remove from your shelf?") },
        text = { Text("This removes phone downloads, book text, saved progress, and bookmarks for ${book.title} on this device.") },
        confirmButton = { TextButton({ vm.remove(book); remove = null }) { Text("Remove") } }, dismissButton = { TextButton({ remove = null }) { Text("Keep book") } }) }
}

@Composable
private fun ShelfRow(entry: ShelfEntry, book: Audiobook, nowPlaying: Boolean, resume: () -> Unit, details: () -> Unit) {
    val source = remember(entry.sourceJson) { entry.source() }
    BookRow(book, details) {
        if (entry.sourceJson.isNotBlank()) IconButton(resume) {
            Crossfade(nowPlaying, label = "now playing") { live ->
                if (live) NarrationPulse(true, Modifier.semantics { contentDescription = "Now playing ${book.title}" })
                else Icon(Icons.Rounded.PlayArrow, "Resume ${book.title}", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
    if (entry.playedAt > 0) {
        Spacer(Modifier.height(12.dp))
        Text(placeLabel(entry, source), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        listenedFraction(entry, source)?.let { progress ->
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().height(3.dp), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.outlineVariant, gapSize = 0.dp, drawStopIndicator = {})
        }
    }
    if (entry.state == "preparing" || entry.state == "ready") {
        Spacer(Modifier.height(12.dp))
        Text(if (entry.state == "ready") "TorBox source ready" else "Preparing in TorBox", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun RecoveryState(title: String, message: String, retry: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(14.dp)).padding(20.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp)); Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(retry) { Text("Try again") }
    }
}

@Composable
fun EmptyState(title: String, message: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(20.dp)); Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp)); Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
