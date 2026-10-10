package app.narrio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.narrio.data.TorBoxItem
import app.narrio.data.TorBoxKind
import app.narrio.data.TorBoxLibrary
import app.narrio.domain.*

/** One download in the listener's TorBox account; not-ready Usenet and web downloads can't be chosen yet. */
@Composable
fun TorBoxLibraryRow(item: TorBoxItem, attaching: Boolean, enabled: Boolean, onChoose: () -> Unit, modifier: Modifier = Modifier) {
    val choosable = enabled && (item.ready || item.kind == TorBoxKind.TORRENT)
    val detail = listOfNotNull(item.kind.label, "${item.audioFiles} audio ${if (item.audioFiles == 1) "file" else "files"}",
        item.sizeBytes.takeIf { it > 0 }?.let(::sizeLabel), item.createdAt.take(10).takeIf(String::isNotBlank)).joinToString(" · ")
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).heightIn(min = 56.dp)
        .clickable(enabled = choosable, onClickLabel = "Use for this book", onClick = onChoose).padding(horizontal = 4.dp, vertical = 6.dp)
        .testTag("torbox-item:${item.kind}:${item.id}"), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (item.ready) Icons.Rounded.Bolt else Icons.Rounded.CloudDownload, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (item.ready) "Ready to listen" else if (item.kind == TorBoxKind.TORRENT) "${item.state} · you can follow its progress" else "${item.state} · choose it once it's ready",
                style = MaterialTheme.typography.labelMedium, color = if (item.ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (attaching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

/** Every TorBox download with audio, newest first, searchable by name. Choosing one makes it this book's recording. */
@Composable
fun TorBoxLibrarySheet(vm: NarrioViewModel, book: Audiobook, chosen: (Audiobook) -> Unit, dismiss: () -> Unit) {
    val advanced = vm.advanced
    val state by advanced.library.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(state.items, query) { TorBoxLibrary.search(state.items, query) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    AdvancedSheet(dismiss, "torbox-library") { _ ->
        item {
            Text("Your TorBox library", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(6.dp))
            Text("Downloads with audio, newest first. The one you choose becomes ${book.title}'s recording; check its narrator and files before listening.",
                style = MaterialTheme.typography.bodyMedium, color = muted)
        }
        item {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().testTag("torbox-library-search"), singleLine = true,
                label = { Text("Search your downloads") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Rounded.Close, "Clear search") } },
                shape = RoundedCornerShape(14.dp))
        }
        when {
            state.loading -> item { Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)); Text("Reading your TorBox library…")
            } }
            state.error != null && state.items.isEmpty() -> item { RecoveryState("Couldn't read your library", state.error!!) { advanced.loadLibrary(force = true) } }
            state.loaded && shown.isEmpty() -> item {
                Text(if (query.isBlank()) "No downloads with audio in your TorBox account." else "No downloads match “$query”.",
                    style = MaterialTheme.typography.bodyMedium, color = muted, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            else -> {
                state.error?.let { message -> item { Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) } }
                items(shown, key = { "${it.kind}:${it.id}" }) { item ->
                    TorBoxLibraryRow(item, state.attaching == item, state.attaching == null, onChoose = { advanced.chooseFromLibrary(book, item, chosen) })
                }
            }
        }
        if (state.loaded) item { TextButton({ advanced.loadLibrary(force = true) }, Modifier.heightIn(min = 48.dp)) { Text("Refresh") } }
    }
}
