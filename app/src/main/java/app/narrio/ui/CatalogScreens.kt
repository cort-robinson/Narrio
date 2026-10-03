package app.narrio.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val browse = query.isBlank() && category == "All"
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                NarrioMark(); Spacer(Modifier.width(10.dp))
                Text("narrio", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { vm.navigate(2) }) { Icon(Icons.Rounded.Tune, "Open settings") }
            }
            Spacer(Modifier.height(20.dp))
            Text(if (browse) "Your next chapter." else "Find your next story.", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(8.dp))
            Text("Find the book. Explore its listening sources from the details.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            OutlinedTextField(query, onValueChange = { vm.search(it) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Search books or authors") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { vm.search("") }) { Icon(Icons.Rounded.Close, "Clear search") } },
                shape = RoundedCornerShape(14.dp))
            Spacer(Modifier.height(10.dp))
            if (query.isBlank()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf("All", "Fiction", "Mystery", "Wonder", "Nonfiction")) { FilterChip(category == it, { vm.search(cat = it) }, { Text(it) }) }
            }
        }
        if (catalog.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary) }
        catalog.notice?.let { item { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        catalog.error?.let { error -> item { RecoveryState("Couldn't load book metadata", error, { vm.search() }) } }
        if (browse) {
            shelf.firstOrNull { it.playedAt > 0 && it.sourceJson.isNotBlank() }?.let { current -> item {
                Text("Pick up the thread", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(14.dp))
                ShelfRow(current, { vm.resume(current) }, { vm.open(current.book()) })
            } }
        }
        if (catalog.books.isNotEmpty()) {
            item {
                Text(if (browse) "A shelf of possibilities" else "${catalog.books.size} ${if (catalog.books.size == 1) "book" else "books"}", style = MaterialTheme.typography.headlineSmall)
                Text("One result per book · Listening availability checked in details", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(catalog.books, key = { it.id }) { book -> BookRow(book, { vm.open(book) }) }
        } else if (!catalog.loading && catalog.error == null) item {
            EmptyState("No books found", "Try another title or author, or clear the category.", Icons.Rounded.Search)
        }
    }
}

@Composable
fun BookRow(book: Audiobook, open: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clickable(onClick = open), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        BookCover(book, Modifier.width(76.dp).height(112.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(book.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (book.provider == "catalog") {
                if (book.description.isNotBlank()) Text(book.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(providerLabel(book), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            } else Text(narrationLabel(book), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (book.durationMs > 0) Text(durationLabel(book.durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (book.provider != "catalog" && (book.provider != "archive" || book.cacheState != "unchecked")) Text(if (book.cacheState == "cached") "Ready in TorBox · ${book.cachedFormats.joinToString(" / ")}" else "${providerLabel(book)} · ${if (book.cacheState == "uncached") "Not cached" else "Cache not checked"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

@Composable
fun LibraryScreen(vm: NarrioViewModel, modifier: Modifier = Modifier) {
    val shelf by vm.shelf.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    var remove by remember { mutableStateOf<Audiobook?>(null) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item {
            Text("Your shelf.", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(8.dp))
            Text("Stories you keep. Moments you come back to.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (shelf.isEmpty()) item {
            Spacer(Modifier.height(30.dp))
            EmptyState("Make room for a good story.", "Save a recording or start listening. Your books, progress, and bookmarks will stay here on this device.", Icons.Rounded.AutoStories)
            Spacer(Modifier.height(20.dp))
            Button({ vm.navigate(0) }, Modifier.fillMaxWidth()) { Text("Discover books") }
        }
        items(shelf, key = { it.bookId }) { entry ->
            Column {
                ShelfRow(entry, { vm.resume(entry) }, { vm.open(entry.book()) })
                downloads.filter { it.book.id == entry.bookId }.forEach { download -> Spacer(Modifier.height(14.dp)); OfflineStatus(vm, download) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton({ vm.open(entry.book()) }) { Text(if (entry.state == "preparing" || entry.state == "ready") "Check source" else "Recording details") }
                    IconButton({ remove = entry.book() }) { Icon(Icons.Rounded.MoreHoriz, "Remove ${entry.book().title} from shelf") }
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
private fun ShelfRow(entry: ShelfEntry, resume: () -> Unit, details: () -> Unit) {
    val book = entry.book()
    BookRow(book, details) {
        if (entry.sourceJson.isNotBlank()) IconButton(resume) { Icon(Icons.Rounded.PlayArrow, "Resume ${book.title}", tint = MaterialTheme.colorScheme.primary) }
    }
    if (entry.playedAt > 0) {
        Spacer(Modifier.height(12.dp))
        val source = entry.source()
        val index = source?.let { resumeIndex(it.parts, entry.partId) } ?: 0
        Text("Part ${index + 1} of ${source?.parts?.size ?: 1} · ${formatTime(entry.positionMs)} in", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
