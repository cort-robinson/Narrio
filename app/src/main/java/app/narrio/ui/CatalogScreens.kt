package app.narrio.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.narrio.R
import app.narrio.data.ShelfEntry
import app.narrio.domain.*

@Composable
fun DiscoverScreen(vm: NarrioViewModel, modifier: Modifier = Modifier) {
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val category by vm.category.collectAsStateWithLifecycle()
    val connected by vm.connected.collectAsStateWithLifecycle()
    val scope by vm.sourceScope.collectAsStateWithLifecycle()
    val shelf by vm.shelf.collectAsStateWithLifecycle()
    val browse = query.isBlank() && category == "All" && scope == "Public"
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
            Text("Good stories. A little more room to listen.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            OutlinedTextField(query, onValueChange = { vm.search(it) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Search books or authors") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { vm.search("") }) { Icon(Icons.Rounded.Close, "Clear search") } },
                shape = RoundedCornerShape(14.dp))
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(if (connected) listOf("Cached", "All sources", "Public", "TorBox") else listOf("Public", "All sources")) { FilterChip(scope == it, { vm.search(cat = "All", scope = it) }, { Text(when (it) { "TorBox" -> "My TorBox"; "Public" -> "Public books"; "Cached" -> "Ready to stream"; else -> it }) }) }
            }
            if (scope == "Public") LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf("All", "Fiction", "Mystery", "Wonder", "Nonfiction")) { FilterChip(category == it, { vm.search(cat = it) }, { Text(it) }) }
            }
        }
        if (catalog.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary) }
        catalog.notice?.let { item { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        catalog.error?.let { error -> item { RecoveryState(if (catalog.books.isEmpty()) "Couldn't load the catalog" else "Some sources are unavailable", error, { vm.search() }) } }
        if (browse && catalog.books.isNotEmpty()) {
            val featured = catalog.books.first()
            item { FeaturedBook(featured, { vm.open(featured) }) }
            val current = shelf.firstOrNull { it.playedAt > 0 && it.sourceJson.isNotBlank() }
            if (current != null) item {
                Text("Pick up the thread", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(14.dp))
                ShelfRow(current, { vm.resume(current) }, { vm.open(current.book()) })
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("A shelf of possibilities", style = MaterialTheme.typography.headlineSmall)
                    IconButton(onClick = { vm.search(cat = "Fiction") }) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Browse fiction") }
                }
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    items(catalog.books.drop(1), key = { it.id }) { book ->
                        Column(Modifier.width(140.dp).clickable { vm.open(book) }) {
                            BookCover(book, Modifier.fillMaxWidth().height(204.dp))
                            Spacer(Modifier.height(10.dp))
                            Text(book.title.substringBefore(" ("), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(book.author.removePrefix("Sir "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            item {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.Public, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(20.dp))
                    Column {
                        Text("Open books. Real voices.", style = MaterialTheme.typography.titleSmall)
                        Text("Discover public-domain recordings from LibriVox. Connect TorBox to search cached audiobook releases and stream through your account.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else if (catalog.books.isNotEmpty()) {
            item {
                Text(if (scope == "TorBox") "Audio in your TorBox" else if (scope == "Cached") "${catalog.books.size} ready to stream" else "${catalog.books.size} recordings", style = MaterialTheme.typography.headlineSmall)
                Text(when (scope) { "Cached" -> "Cache checked in TorBox · No phone download required"; "Public" -> "LibriVox · Public-domain catalog · Editions stay separate"; else -> "Choose the exact release. Indexed narration and language can be unverified." }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(catalog.books, key = { it.id }) { book -> BookRow(book, { vm.open(book) }) }
        } else if (!catalog.loading && catalog.error == null) item {
            EmptyState(if (scope == "Cached") "No cached releases found" else "No recordings yet", when (scope) { "TorBox" -> "Your TorBox account has no matching audio files. Try All sources."; "Cached" -> "Try another title or author. All sources can show uncached releases, and Public books can stream directly."; else -> "Try a title or author, or clear the category." }, Icons.Rounded.Search)
            if (scope == "Cached") OutlinedButton({ vm.search(scope = "All sources") }, Modifier.fillMaxWidth()) { Text("See all sources") }
        }
    }
}

@Composable
private fun FeaturedBook(book: Audiobook, open: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = 286.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
        Image(painterResource(R.drawable.secret_garden), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop, alignment = Alignment.Center)
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(Color(0xF40C2118), Color(0xC70C2118), Color(0x240C2118)))))
        Column(Modifier.padding(24.dp).fillMaxWidth(.8f)) {
            Text("A world\nworth wandering.", style = MaterialTheme.typography.headlineLarge, color = Color(0xFFF8EACC))
            Spacer(Modifier.height(26.dp))
            Text(book.title, style = MaterialTheme.typography.titleMedium, color = Color(0xFFF8EACC))
            Text(book.author, style = MaterialTheme.typography.bodySmall, color = Color(0xFFD2DEC7))
            Spacer(Modifier.height(14.dp))
            FilledTonalButton(open, colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFFE8AF79), contentColor = Color(0xFF342312))) {
                Icon(Icons.Rounded.Headphones, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Explore recording")
            }
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
            Text(narrationLabel(book), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (book.durationMs > 0) Text(durationLabel(book.durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (book.provider != "archive" || book.cacheState != "unchecked") Text(if (book.cacheState == "cached") "Ready in TorBox · ${book.cachedFormats.joinToString(" / ")}" else "${providerLabel(book)} · ${if (book.cacheState == "uncached") "Not cached" else "Cache not checked"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        text = { Text("This removes phone downloads, saved progress, and bookmarks for ${book.title} on this device.") },
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
