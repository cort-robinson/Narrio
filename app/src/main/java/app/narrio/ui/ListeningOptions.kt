package app.narrio.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import app.narrio.data.SourceQuality
import app.narrio.domain.*

/**
 * Listening options for a catalog book: every recording found for it, the audio format of the chosen one, and
 * the ways to listen, all on one sheet. The recording's own page stays available for its files and details.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListeningOptionsSheet(vm: NarrioViewModel, book: Audiobook, search: SourceSearchState, connected: Boolean, busy: Boolean, dismiss: () -> Unit) {
    val choice = search.choice
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(sheetState, dismiss)
    // Distinct versions lead; every match shows when there are no verified versions or the pick is among the rest.
    var showAll by remember(book.id) { mutableStateOf(search.versions.isEmpty() || choice != null && choice !in search.versions) }
    // Format follows the chosen recording: the remembered one, else a ready one, else its first.
    var format by remember(book.id, choice?.id) {
        mutableStateOf(choice?.let { recording ->
            val saved = vm.savedFormat(book.id)
            recording.sources.firstOrNull { it.format == saved }?.format
                ?: recording.sources.firstOrNull { SourceQuality.ready(recording) && (recording.provider == "archive" || it.format in recording.cachedFormats) }?.format
                ?: recording.sources.firstOrNull()?.format
        })
    }
    val source = choice?.sources?.firstOrNull { it.format == format }
    val ready = choice != null && source != null && (choice.provider == "archive" || format in choice.cachedFormats)
    val needsTorBox = choice != null && choice.provider != "archive"
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val shown = if (showAll) search.results else search.versions
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer, sheetState = sheetState) {
        val view = LocalView.current
        val dark = ThemeContrast.foreground(MaterialTheme.colorScheme.background.toArgb()) == 0xFFFFFF
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
        } }
        LazyColumn(Modifier.fillMaxWidth().testTag("listening-options"), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("Listening options", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(6.dp))
                Text(if (choice == null) "No recording matched closely enough to choose for you. Pick the one that is this book."
                     else "Pick the recording and audio format for ${book.title}. Narrio remembers your choice.", style = MaterialTheme.typography.bodyMedium, color = muted)
            }
            item {
                Text("Recording", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                if (showAll) Text("Closest matches first. Check the release name and narrator before listening.", style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(top = 4.dp))
            }
            items(shown, key = { "recording:${it.id}" }) { recording ->
                RecordingOption(recording, book, selected = recording.id == choice?.id, possible = recording in search.possible, enabled = !busy) { vm.chooseVersion(recording) }
            }
            if (search.results.size > search.versions.size) item {
                TextButton({ showAll = !showAll }) {
                    Text(if (showAll) "Show only distinct versions" else "Show all ${search.results.size} matches")
                }
            }
            if (choice != null) {
                item {
                    Text("Audio format", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                    Spacer(Modifier.height(4.dp))
                    Text(when {
                        choice.provider == "archive" -> "Free public recording · streams from the Internet Archive."
                        choice.cacheState == "cached" -> "Ready in TorBox · cached formats stream right away."
                        else -> "Not cached in TorBox yet · ask TorBox to prepare it, then listen from your shelf."
                    }, style = MaterialTheme.typography.bodySmall, color = muted)
                }
                items(choice.sources, key = { "format:${it.id}" }) { option ->
                    val cached = choice.provider == "archive" || option.format in choice.cachedFormats
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (option.format == format) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .selectable(option.format == format, enabled = !busy, role = Role.RadioButton) { format = option.format }.heightIn(min = 48.dp).padding(end = 12.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(option.format == format, null, Modifier.padding(horizontal = 12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(option.label, style = MaterialTheme.typography.titleSmall)
                            Text("${option.format} · ${option.parts.size} ${if (option.parts.size == 1) "file" else "parts"}${if (choice.provider != "archive") if (cached) " · Cached" else " · Needs preparation" else ""}",
                                style = MaterialTheme.typography.bodySmall, color = muted)
                        }
                    }
                }
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        when {
                            needsTorBox && !connected -> Button({ close { vm.requestTorBoxConnect() } }, Modifier.fillMaxWidth()) { Text("Connect TorBox to listen") }
                            else -> Button({ close { vm.listenToChoice(format) } }, Modifier.fillMaxWidth().testTag("listen-choice"), enabled = ready && !busy) {
                                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Listen")
                            }
                        }
                        AnimatedVisibility(needsTorBox && connected && !ready, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("This source may take minutes or hours to become available. Pick a ready recording to listen immediately.", style = MaterialTheme.typography.bodySmall, color = muted)
                                OutlinedButton({ close { format?.let { vm.prepareChoice(it) } } }, Modifier.fillMaxWidth(), enabled = !busy && format != null) { Text("Prepare in TorBox") }
                            }
                        }
                        OutlinedButton({ close { format?.let { vm.downloadChoice(it) } } }, Modifier.fillMaxWidth(), enabled = ready && !busy && (!needsTorBox || connected)) {
                            Icon(Icons.Rounded.Download, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Download to phone")
                        }
                        source?.takeIf { it.parts.sumOf { part -> part.sizeBytes } > 0 }?.let {
                            Text("Phone storage: ${sizeLabel(it.parts.sumOf { part -> part.sizeBytes })} for this format", style = MaterialTheme.typography.bodySmall, color = muted)
                        }
                        TextButton({ close { vm.chooseRecording(choice) } }, enabled = !busy) { Text("About this recording") }
                    }
                }
            }
        }
    }
}

/** One recording to choose: its release, narrator and kind, provider and formats, and whether it is ready. */
@Composable
private fun RecordingOption(recording: Audiobook, book: Audiobook, selected: Boolean, possible: Boolean, enabled: Boolean, choose: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = choose).padding(end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, null, Modifier.padding(horizontal = 12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(recording.releaseTitle.ifBlank { recording.title }, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(versionLabel(recording, book), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            Text(listOf(providerLabel(recording), recording.sources.joinToString(" / ") { it.format }).filter(String::isNotBlank).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(when { recording.cacheState == "cached" -> Icons.Rounded.Bolt; recording.provider == "archive" -> Icons.Rounded.Headphones; else -> Icons.Rounded.CloudDownload }, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Text(availabilityLabel(recording) + if (possible) " · Possible match" else "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
