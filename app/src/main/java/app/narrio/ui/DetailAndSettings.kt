package app.narrio.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.narrio.domain.*
import app.narrio.BuildConfig
import app.narrio.data.OfflineBook
import app.narrio.data.SourceQuality

/** The leading format is the filled action; the other available format is tonal. */
@Composable
fun ModeButton(onClick: () -> Unit, modifier: Modifier, leading: Boolean, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    if (leading) Button(onClick, modifier, enabled = enabled, content = content)
    else FilledTonalButton(onClick, modifier, enabled = enabled, content = content)
}

internal fun availabilityLabel(recording: Audiobook) = when {
    recording.cacheState == "cached" -> "Ready in TorBox"
    recording.provider == "archive" -> "Free public recording"
    recording.filesVerified -> "Needs TorBox preparation"
    else -> "Audio files not checked"
}

/** Narrator, dramatization/abridgment, and language: what distinguishes one version from another. */
internal fun versionLabel(recording: Audiobook, book: Audiobook): String {
    val edition = SourceQuality.edition(recording)
    return listOf(edition.narrator.takeIf(String::isNotBlank)?.let { "Read by $it" } ?: "Narrator not listed", edition.kind,
        edition.language.takeUnless { it.isBlank() || it.equals(book.language, true) }.orEmpty()).filter(String::isNotBlank).joinToString(" · ")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OfflineStatus(vm: NarrioViewModel, download: OfflineBook, modifier: Modifier = Modifier) {
    var remove by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(14.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(if (download.complete) Icons.Rounded.OfflinePin else Icons.Rounded.Download, null, tint = MaterialTheme.colorScheme.secondary)
            Text(download.label, style = MaterialTheme.typography.titleSmall)
        }
        Text("${download.source.format} · ${download.completedFiles} of ${download.source.parts.size} files${if (download.bytesDownloaded > 0) " · ${sizeLabel(download.bytesDownloaded)}" else ""}${if (download.totalBytes > 0 && !download.complete) " of ${sizeLabel(download.totalBytes)}" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!download.complete && download.totalBytes > 0) {
            val progress by animateFloatAsState(download.progress, tween(Motion.LONG, easing = Motion.Emphasized), label = "download")
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text("${(download.progress * 100).toInt()}% saved to phone", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (download.complete) FilledTonalButton({ vm.start(download.book, download.source, download.source.delivery) }) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Play offline") }
            else TextButton({ if (download.paused || download.failed) vm.resumeDownload(download) else vm.pauseDownload(download) }) { Text(if (download.failed) "Retry download" else if (download.paused) "Resume download" else "Pause download") }
            TextButton({ remove = true }) { Text("Remove download") }
        }
    }
    if (remove) AlertDialog(onDismissRequest = { remove = false }, title = { Text("Remove phone download?") }, text = { Text("Audio files for this ${download.source.format} source will be removed. Your book, bookmarks, and listening progress stay on your shelf.") }, confirmButton = { TextButton({ vm.removeDownload(download); remove = false }) { Text("Remove audio") } }, dismissButton = { TextButton({ remove = false }) { Text("Keep download") } })
}

@Composable
fun SettingsScreen(vm: NarrioViewModel, modifier: Modifier = Modifier) {
    val connected by vm.connected.collectAsStateWithLifecycle()
    val appearance by vm.appearance.collectAsStateWithLifecycle()
    val wifiOnly by vm.wifiOnly.collectAsStateWithLifecycle()
    var appearanceOpen by rememberSaveable { mutableStateOf(false) }
    // Held by the view model so "Open source settings" can land here directly.
    val addonsOpen by vm.sourceSettingsOpen.collectAsStateWithLifecycle()
    // Every sub-page, not only Appearance, slides in and back out along the same axis.
    val page = when { addonsOpen -> "addons"; appearanceOpen -> "appearance"; else -> "home" }
    // Leaving Settings cancels a connection its form started, so the key is never saved later; the sheet keeps its own.
    DisposableEffect(vm) { onDispose { if (!vm.torBox.value.prompt) vm.dismissTorBoxConnect() } }
    AnimatedContent(page, modifier, transitionSpec = { Motion.sharedAxisX(targetState != "home") }, label = "settings page") { shown ->
        when (shown) {
            "addons" -> AddonSettings(vm.graph.addons, vm.sourceProviderSettings, vm.ebookProviderSettings, connected, vm::requestTorBoxConnect, vm::addonsChanged, { vm.sourceSettingsOpen.value = false })
            "appearance" -> AppearanceScreen(appearance, vm::updateAppearance, { appearanceOpen = false })
            else -> SettingsHome(vm, connected, appearance, wifiOnly, { vm.sourceSettingsOpen.value = true }) { appearanceOpen = true }
        }
    }
}

@Composable
private fun SettingsHome(vm: NarrioViewModel, connected: Boolean, appearance: AppearanceSettings, wifiOnly: Boolean, openAddons: () -> Unit, openAppearance: () -> Unit) {
    val context = LocalContext.current
    val backgroundAlignment by vm.backgroundAlignment.collectAsStateWithLifecycle()
    // The one highlights switch is on only when both of its settings are, so it never shows On over a setting that's off.
    val readAlongSync by vm.readAlongSync.collectAsStateWithLifecycle()
    var aboutOpen by rememberSaveable { mutableStateOf(false) }
    val updates by vm.graph.updates.state.collectAsStateWithLifecycle()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    // TorBox leads: it gates every source beyond LibriVox. Where books come from and how the app looks follow as rows,
    // then everyday switches, then the app itself.
    LazyColumn(Modifier.fillMaxSize().imePadding().testTag("settings-options"), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.displaySmall) }
        item(key = "torbox") {
            Text("Where books come from", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TorBoxSettings(vm, connected)
                SettingsRow(Icons.Rounded.ManageSearch, "Where Narrio searches", "Sites for recordings, ebooks, and book details", openAddons, Modifier.testTag("sources-entry"))
            }
        }
        // Listening's two everyday switches share one heading.
        item {
            Text("Listening", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            SettingSwitch("Download only on Wi-Fi", if (wifiOnly) "Saves your mobile data." else "Downloads can use mobile data.", wifiOnly, vm::setWifiOnly)
            Spacer(Modifier.height(12.dp))
            // One switch for highlighting: preparing it in the background and keeping it in step while reading along.
            SettingSwitch("Read-along highlights", "Highlights words as they're read. Streamed books get them while charging on Wi-Fi.",
                backgroundAlignment && readAlongSync, { on -> vm.setBackgroundAlignment(on); vm.setReadAlongSync(on) }, Modifier.testTag("background-alignment"))
        }
        item {
            Text("Display", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            AppearanceEntry(appearance, openAppearance)
        }
        // Local builds have no update channel and show their version under About instead.
        if (updates.channel != null) item { UpdateSettings(vm.graph.updates) }
        item {
            Text("About Narrio", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Text("Your shelf, progress, and bookmarks stay on this phone.", style = MaterialTheme.typography.bodyMedium, color = muted)
            TextButton({ aboutOpen = !aboutOpen }, Modifier.offset(x = (-12).dp)) { Text(if (aboutOpen) "Hide credits and details" else "Credits and details") }
            AnimatedVisibility(aboutOpen) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium, color = muted)
                    Text("Search identifies books from catalog details. Opening a book looks for LibriVox recordings and, with TorBox connected, your TorBox library and enabled sources, then picks the best match with audio ready to play. Other versions appear only when the narrator, language, or edition differs. Some releases don't confirm their narrator, language, or abridgment, so check the release and files before listening.",
                        style = MaterialTheme.typography.bodySmall, color = muted)
                    Text("Book details and covers come from your catalog sources, with Google Books as a fallback. Book names go to those sources; your TorBox key and listening history stay private. Covers belong to their rights holders. Fallback covers use original garden artwork made with ImageGen. Type: Newsreader and Manrope, SIL Open Font License.",
                        style = MaterialTheme.typography.bodySmall, color = muted)
                    TextButton({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://librivox.org/pages/about-librivox/"))) }, Modifier.offset(x = (-12).dp)) { Text("About the LibriVox recordings") }
                }
            }
        }
    }
}

/** A row into a settings page, flat like the switches: icon, title, one line saying what's inside, and a chevron. */
@Composable
internal fun SettingsRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String, open: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = open).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Rounded.ChevronRight, null)
    }
}

/** One switch with its title and a one-line consequence; the whole row toggles it (and carries [modifier]'s tag). */
@Composable
private fun SettingSwitch(title: String, detail: String, checked: Boolean, change: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).toggleable(checked, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = change).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, null)
    }
}
