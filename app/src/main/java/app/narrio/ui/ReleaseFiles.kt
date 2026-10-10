package app.narrio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.narrio.data.FileChoices
import app.narrio.domain.*

/** What the files editor can do; [AdvancedSourcing] supplies them. */
data class ReleaseFilesActions(
    val toggle: (String) -> Unit, val move: (String, Int) -> Unit, val onlyBook: () -> Unit, val all: () -> Unit,
    val natural: () -> Unit, val save: () -> Unit, val reset: () -> Unit,
)

/** The editor's header: what this is, the quick choices, and the current count. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReleaseFilesHeader(state: ReleaseFilesState, actions: ReleaseFilesActions, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Choose files", style = MaterialTheme.typography.headlineMedium)
        Text("Tick the files that are this book and put them in listening order. Playback and phone downloads use your choice; your place stays with each file.",
            style = MaterialTheme.typography.bodyMedium, color = muted)
        if (state.loading) Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp))
            Text("Listing every file in this release…", style = MaterialTheme.typography.bodyMedium)
        }
        state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        if (!state.loading && state.files.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(state.chosen == state.bookFiles.toSet(), actions.onlyBook, label = { Text("Only this book's files") }, modifier = Modifier.testTag("only-book-files"))
                FilterChip(state.chosen.size == state.files.size, actions.all, label = { Text("All files") })
                AssistChip(actions.natural, label = { Text("Name order") }, leadingIcon = { Icon(Icons.Rounded.SortByAlpha, null, Modifier.size(18.dp)) })
            }
            Text("${state.chosen.size} of ${state.files.size} files chosen", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("files-count"))
        }
    }
}

/** One file: a checkbox row with Move up and Move down (also offered to TalkBack as custom actions). */
@Composable
fun ReleaseFileRow(part: AudioPart, index: Int, count: Int, chosen: Boolean, actions: ReleaseFilesActions, modifier: Modifier = Modifier) {
    val key = FileChoices.fileKey(part)
    val name = part.name.substringAfterLast('/')
    val folder = part.name.substringBeforeLast('/', "")
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(if (chosen) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
        .heightIn(min = 56.dp).testTag("file-row:${part.name}")
        .semantics(mergeDescendants = true) {
            customActions = listOfNotNull(
                CustomAccessibilityAction("Move up") { actions.move(key, -1); true }.takeIf { index > 0 },
                CustomAccessibilityAction("Move down") { actions.move(key, 1); true }.takeIf { index < count - 1 },
            )
        }, verticalAlignment = Alignment.CenterVertically) {
        TriStateCheckbox(if (chosen) ToggleableState.On else ToggleableState.Off, { actions.toggle(key) },
            Modifier.semantics { contentDescription = "Play ${part.name}" })
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val detail = listOfNotNull(folder.takeIf(String::isNotBlank), part.durationMs.takeIf { it > 0 }?.let(::formatTime),
                part.sizeBytes.takeIf { it > 0 }?.let(::sizeLabel)).joinToString(" · ")
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton({ actions.move(key, -1) }, enabled = index > 0) { Icon(Icons.Rounded.KeyboardArrowUp, "Move ${part.name} up") }
        IconButton({ actions.move(key, 1) }, enabled = index < count - 1) { Icon(Icons.Rounded.KeyboardArrowDown, "Move ${part.name} down") }
    }
}

/** Choose files in the release opened with [AdvancedSourcing.openFiles]. */
@Composable
fun ReleaseFilesSheet(vm: NarrioViewModel, dismiss: () -> Unit) {
    val advanced = vm.advanced
    val state by advanced.files.collectAsStateWithLifecycle()
    val actions = remember(advanced) {
        ReleaseFilesActions(advanced::toggleFile, advanced::moveFile, advanced::onlyBookFiles, advanced::allFiles, advanced::naturalOrder,
            advanced::saveFiles, advanced::resetFiles)
    }
    AdvancedSheet(dismiss, "release-files") { close ->
        item { ReleaseFilesHeader(state, actions) }
        itemsIndexed(state.files, key = { _, part -> part.id }) { index, part ->
            ReleaseFileRow(part, index, state.files.size, FileChoices.fileKey(part) in state.chosen, actions, Modifier.animateItem())
        }
        if (!state.loading && state.files.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button({ actions.save(); close {} }, Modifier.fillMaxWidth().testTag("save-files"), enabled = state.chosen.isNotEmpty() && !state.saving) {
                    Text("Use ${state.chosen.size} ${if (state.chosen.size == 1) "file" else "files"}")
                }
                if (state.saved) TextButton(actions.reset, Modifier.heightIn(min = 48.dp)) { Text("Use the files Narrio found") }
            }
        }
    }
}
