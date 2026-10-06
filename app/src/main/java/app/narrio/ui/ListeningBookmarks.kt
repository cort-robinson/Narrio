package app.narrio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.narrio.domain.Audiobook
import app.narrio.domain.MappingConfidence
import app.narrio.domain.formatTime
import app.narrio.domain.resumeIndex
import app.narrio.playback.BookmarkMapping
import app.narrio.playback.BookmarkPlace
import app.narrio.playback.ListeningState
import app.narrio.playback.inListeningOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class ListeningBookmark(val place: BookmarkPlace, val chapter: Int?)

/**
 * The Listening room's bookmarks: the same rows the reader lists. Time leads, with "≈" while it was mapped from a
 * reading bookmark; a reading bookmark without a matching time opens the reader at its page instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListeningBookmarksSheet(vm: NarrioViewModel, state: ListeningState, book: Audiobook, close: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val entries by remember(book.id) { vm.graph.library.bookmarks(book.id) }.collectAsStateWithLifecycle(emptyList())
    val readable by remember(book.id) { vm.canRead(book.id) }.collectAsStateWithLifecycle(false)
    val parts = state.source?.parts.orEmpty()
    val rows by produceState(emptyList<ListeningBookmark>(), entries, parts) {
        value = withContext(Dispatchers.IO) {
            val library = vm.readingLibrary.value as? RoomReadingLibrary
            BookmarkMapping(vm.graph, book.id).resolve(entries).inListeningOrder(parts)
                .map { place -> ListeningBookmark(place, place.text?.let { library?.chapterOf(book.id, it) }) }
        }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val leave = rememberSheetCloser(sheetState, close)
    ModalBottomSheet(onDismissRequest = close, containerColor = MaterialTheme.colorScheme.surfaceContainer, sheetState = sheetState) {
        LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.heightIn(max = 480.dp).testTag("listening-bookmarks")) {
            item {
                Text("Bookmarks", style = MaterialTheme.typography.headlineMedium); Spacer(Modifier.height(12.dp))
                FilledTonalButton({ haptics.performHapticFeedback(HapticFeedbackType.Confirm); vm.bookmark() }) { Icon(Icons.Rounded.BookmarkAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Add bookmark") }
            }
            if (entries.isEmpty()) item {
                Text("No bookmarks yet. Each one keeps the exact time, and bookmarks you add while reading appear here too.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(rows, key = { it.place.id }) { row ->
                val place = row.place
                val audio = place.audio
                val text = place.text
                val reading = text?.let { readingPlace(it, row.chapter) }
                val open: (() -> Unit)? = when {
                    audio != null -> { { leave { vm.jumpBookmark(book.id, audio) } } }
                    text != null && readable -> { { leave { vm.readAt(book.id, text) } } }
                    else -> null
                }
                Row(Modifier.fillMaxWidth().animateItem().testTag("listening-bookmark-row"), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(enabled = open != null, onClickLabel = if (audio != null) "Play from here" else "Open in the reader") { open?.invoke() }.padding(vertical = 12.dp)) {
                        if (audio != null) Row(verticalAlignment = Alignment.Bottom) {
                            EstimatedPlace(formatTime(audio.positionMs), place.audioConfidence, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                            if (parts.size > 1 && audio.sourceId == state.source?.id) Text("  Part ${resumeIndex(parts, audio.partId) + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else Text("Reading bookmark", style = MaterialTheme.typography.titleMedium)
                        // Listening bookmarks are labelled with their part, which the time line already names.
                        val partTitle = audio?.let { parts.getOrNull(resumeIndex(parts, it.partId))?.title }
                        if (place.entry.label.isNotBlank() && place.entry.label != partTitle) Text(place.entry.label, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (reading != null) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                            Icon(Icons.AutoMirrored.Rounded.MenuBook, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary)
                            Spacer(Modifier.width(4.dp))
                            EstimatedPlace(reading.label + if (audio == null && readable) " · Opens in the reader" else "", place.textConfidence)
                        }
                    }
                    val name = audio?.let { (if (place.audioConfidence == MappingConfidence.ESTIMATED) "about " else "") + formatTime(it.positionMs) } ?: reading?.label?.let(::spokenPlace).orEmpty()
                    IconButton({ vm.deleteBookmark(place.id) }) { Icon(Icons.Rounded.DeleteOutline, "Delete bookmark at $name") }
                }
            }
        }
    }
}
