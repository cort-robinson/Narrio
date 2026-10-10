package app.narrio.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
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
    val readAlongSync by vm.readAlongSync.collectAsStateWithLifecycle()
    // TorBox leads: it gates every source beyond LibriVox. Everyday listening settings follow, then the app itself.
    LazyColumn(Modifier.fillMaxSize().imePadding().testTag("settings-options"), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.displaySmall) }
        item(key = "torbox") { TorBoxSettings(vm, connected) }
        item {
            OutlinedButton(openAddons, Modifier.fillMaxWidth()) {
                Text("Sources & add-ons · Audiobooks, ebooks & book info")
            }
        }
        item {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant); Spacer(Modifier.height(24.dp))
            Text("Downloads", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Text("Use Download for offline on a book to save its recording to this phone. Manage downloads on the book or your shelf. Streaming plays over the internet without saving the book.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Download only on Wi-Fi", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Switch(wifiOnly, vm::setWifiOnly)
            }
            Text(if (wifiOnly) "Downloads wait for an unmetered connection." else "Downloads can use mobile data, including large whole-book files.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Text("Reading & listening", style = MaterialTheme.typography.headlineSmall)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Sync narration in the background", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Switch(backgroundAlignment, vm::setBackgroundAlignment, Modifier.testTag("background-alignment"))
            }
            Text("Align downloaded audio on this phone. Streaming alignment waits for Wi-Fi and charging. The English narration model downloads once over Wi-Fi.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Sync while reading along", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Switch(readAlongSync, vm::setReadAlongSync, Modifier.testTag("read-along-sync"))
            }
            Text("While read along is open, listens to the narration on this phone to keep the highlight in step. Audio isn't uploaded.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { AppearanceEntry(appearance, openAppearance) }
        item { UpdateSettings(vm.graph.updates) }
        item {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant); Spacer(Modifier.height(24.dp))
            Text("About Narrio", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Text("Narrio ${BuildConfig.VERSION_NAME} · Native Android\n\nSearch identifies books from catalog metadata. Opening a book looks for public-domain LibriVox recordings and, with TorBox connected, your TorBox library and enabled source add-ons. The best match with ready audio is chosen automatically. Other versions appear only when the narrator, language, or edition differs. When no recording matches confidently, you can review the search results yourself. Matching releases with verified audio files and available seeders can be explicitly prepared in TorBox. Indexed releases may have unverified narration, language, or abridgment; inspect the release and files before listening. Availability depends on the provider and TorBox cache.\n\nSaved books, downloads, progress, and bookmarks stay on this device. Downloading to your phone is optional.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Text("Matched book details and cover art come from enabled catalog add-ons, with Google Books as a built-in fallback. Manage providers in Add-ons. Catalog narrator information does not verify the release's recording. Book names go directly to these metadata providers; your TorBox key and listening history stay private.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            TextButton({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://librivox.org/pages/about-librivox/"))) }) { Text("About the LibriVox recordings") }
            Text("Retrieved covers belong to their respective rights holders. Fallbacks use original garden artwork created with ImageGen and Narrio's graphic designs. Typography: Newsreader and Manrope, SIL Open Font License.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
