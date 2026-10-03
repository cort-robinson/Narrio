package app.narrio.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.narrio.domain.*
import app.narrio.BuildConfig
import app.narrio.data.OfflineBook
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailPane(vm: NarrioViewModel, compact: Boolean, modifier: Modifier = Modifier) {
    val selected by vm.selection.collectAsStateWithLifecycle()
    val preparation by vm.preparation.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val shelf by vm.shelf.collectAsStateWithLifecycle()
    val connected by vm.connected.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var sourcePicker by remember(selected.book?.id) { mutableStateOf(false) }
    var expandedDescription by remember(selected.book?.id) { mutableStateOf(false) }
    val book = selected.book ?: return
    val saved = shelf.any { it.bookId == book.id }
    LaunchedEffect(book.id, preparation?.torrentId, preparation?.ready, connected) {
        if (preparation != null && preparation?.ready != true && connected) while (isActive) { delay(15_000); vm.refreshPreparation(book) }
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (compact) IconButton({ vm.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to books") }
                Text("The recording", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                IconButton({ vm.save(book) }) { Icon(if (saved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, if (saved) "Saved to shelf" else "Save to shelf") }
            }
            Spacer(Modifier.height(20.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { BookCover(book, Modifier.width(190.dp).height(278.dp), large = true) }
            Spacer(Modifier.height(24.dp))
            Text(book.title, style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp)); Text(book.author, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Text(narrationLabel(book), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                Text(book.language, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(durationLabel(book.durationMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(providerLabel(book), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (book.provider != "archive") Text("Narration, language, and abridgment of this release remain unverified.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
        }
        if (selected.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        selected.error?.let { item { RecoveryState("Couldn't load this edition", it) { vm.open(book) } } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button({ sourcePicker = true }, Modifier.weight(1f), enabled = !selected.loading && book.sources.isNotEmpty() && !busy) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Listen")
                }
                OutlinedButton({ vm.save(book) }, enabled = !saved) { Icon(if (saved) Icons.Rounded.Check else Icons.Rounded.BookmarkBorder, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (saved) "Saved" else "Save") }
            }
            if (book.detailsLoaded && book.sources.isEmpty()) {
                Spacer(Modifier.height(12.dp)); Text(if (book.provider == "knaben") "No cached audio files found for this release. Try another ready source. File formats appear once a source is available in TorBox." else "This edition has no compatible audio files. Try another recording.", style = MaterialTheme.typography.bodyMedium)
                if (book.provider == "knaben" && connected && preparation == null) OutlinedButton({ vm.prepareUncached(book, "") }, enabled = !busy) { Text("Prepare this release in TorBox") }
                if (book.provider == "knaben" && !connected) OutlinedButton({ vm.navigate(2) }) { Text("Connect TorBox to check availability") }
            } else if (connected) {
                Spacer(Modifier.height(12.dp))
                Text(when (book.cacheState) { "cached" -> "Ready in TorBox · ${book.cachedFormats.joinToString(" / ")}"; "uncached" -> "Not cached in TorBox · Pick a ready source to listen immediately"; else -> "TorBox cache hasn't been checked" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        downloads.filter { it.book.id == book.id }.forEach { download -> item { OfflineStatus(vm, download) } }
        preparation?.let { prep -> item {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(14.dp)).padding(20.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (prep.ready) Icons.Rounded.CheckCircle else Icons.Rounded.CloudDownload, null, tint = MaterialTheme.colorScheme.primary)
                    Text(prep.state, style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(12.dp))
                if (!prep.ready) {
                    LinearProgressIndicator(progress = { prep.progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Text("${(prep.progress * 100).toInt()}%${if (prep.downloadBytesPerSecond > 0) " · ${sizeLabel(prep.downloadBytesPerSecond)}/s" else ""}${if (prep.etaSeconds > 0) " · About ${durationLabel(prep.etaSeconds * 1000)} remaining" else " · ETA unavailable"}", style = MaterialTheme.typography.bodyMedium)
                    prep.seeds?.let { Text("$it connected ${if (it == 1L) "seed" else "seeds"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Spacer(Modifier.height(8.dp))
                }
                Text(if (prep.ready) "Your source is ready. Choose Listen to begin." else if (book.cacheState == "cached") "TorBox is making the cached source available in your account. You can leave and return from your shelf." else "TorBox is fetching this uncached source. Audio isn't being downloaded to your phone. You can leave and return from your shelf; cached releases can stream now.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton({ vm.refreshPreparation(book) }, enabled = !busy && connected) { Text("Check availability") }
            }
        } }
        item {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(24.dp))
            Text("Behind the story", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Text(book.description.ifBlank { "Open the recording to load its description and available sources." }, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (expandedDescription) Int.MAX_VALUE else 9, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (book.description.length > 500) TextButton({ expandedDescription = !expandedDescription }) { Text(if (expandedDescription) "Read less" else "Read more") }
            if (book.metadataSource.isNotBlank()) TextButton({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(book.metadataUrl))) }) { Text("Details from ${book.metadataSource}") }
            if (book.releaseTitle.isNotBlank() && book.releaseTitle != book.title) Text("Release: ${book.releaseTitle}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            if (book.provider != "archive") TextButton({ vm.refreshMetadata(book) }, enabled = !selected.metadataLoading) {
                if (selected.metadataLoading) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                Text(if (selected.metadataLoading) "Fetching book details\u2026" else "Refresh book details")
            }
        }
        if (book.sources.isNotEmpty()) item {
            Text("Available audio", style = MaterialTheme.typography.headlineSmall)
            book.sources.forEach { source ->
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.GraphicEq, null, tint = MaterialTheme.colorScheme.secondary)
                    Column { Text(source.label, style = MaterialTheme.typography.titleSmall); Text("${source.format} · ${source.parts.size} ${if (source.parts.size == 1) "file" else "parts"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
    if (sourcePicker) SourcePicker(vm, book, connected, busy) { sourcePicker = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourcePicker(vm: NarrioViewModel, book: Audiobook, connected: Boolean, busy: Boolean, dismiss: () -> Unit) {
    val preferred by vm.preferredFormat.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    var chosen by remember(book.id) { mutableStateOf(book.sources.firstOrNull { it.format == preferred } ?: book.sources.firstOrNull { it.format in book.cachedFormats } ?: book.sources.first()) }
    var delivery by remember(book.id) { mutableStateOf(if (book.provider != "archive" || connected && chosen.format in book.cachedFormats) "torbox" else "archive") }
    val ready = delivery == "archive" || chosen.format in book.cachedFormats
    val downloaded = downloads.firstOrNull { it.book.id == book.id && it.source.format == chosen.format }
    fun choose(source: AudioSource) { chosen = source; vm.chooseFormat(book, source.format) }
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        val view = LocalView.current
        val dark = MaterialTheme.colorScheme.background.luminance() < .5f
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
        } }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Choose how you listen.", style = MaterialTheme.typography.headlineMedium)
            Text("${book.narrator} · ${book.language}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            book.sources.forEach { source ->
                Row(Modifier.fillMaxWidth().selectableRow(chosen.id == source.id) { choose(source) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(chosen.id == source.id, { choose(source) })
                    Column(Modifier.weight(1f)) { Text(source.label, style = MaterialTheme.typography.titleSmall); Text("${source.format} · ${source.parts.size} ${if (source.parts.size == 1) "file" else "parts"}${if (source.format in book.cachedFormats) " · Cached" else ""}", style = MaterialTheme.typography.bodySmall) }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (book.provider == "archive") {
                Row(Modifier.fillMaxWidth().selectableRow(delivery == "archive") { delivery = "archive" }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(delivery == "archive", { delivery = "archive" }); Column(Modifier.weight(1f)) { Text("Internet Archive", style = MaterialTheme.typography.titleSmall); Text("Direct public recording · Ready to stream", style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (book.torrentUrl.isNotBlank() || book.magnetUri.isNotBlank() || book.provider == "torbox") Row(Modifier.fillMaxWidth().selectableRow(delivery == "torbox") { delivery = "torbox" }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(delivery == "torbox", { delivery = "torbox" }); Column(Modifier.weight(1f)) { Text("TorBox", style = MaterialTheme.typography.titleSmall); Text(if (!connected) "Connect your account to use this source" else if (chosen.format in book.cachedFormats) "Cached · Ready to stream" else "Not cached · Requires cloud preparation", style = MaterialTheme.typography.bodySmall) }
            }
            Text("Streaming doesn't save the book to your phone. Each audio format and delivery source keeps its own listening position.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button({ dismiss(); if (delivery == "torbox" && !connected) vm.navigate(2) else vm.start(book, chosen, delivery) }, Modifier.fillMaxWidth(), enabled = !busy && (ready || !connected && delivery == "torbox")) {
                Text(if (delivery == "torbox" && !connected) "Connect TorBox" else if (delivery == "torbox") "Stream now" else "Start listening")
            }
            if (delivery == "torbox" && connected && !ready) {
                Text("This source may take minutes or hours to become available. Choose a cached release to listen immediately.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton({ dismiss(); vm.prepareUncached(book, chosen.format) }, Modifier.fillMaxWidth(), enabled = !busy) { Text("Prepare in TorBox") }
            }
            if (downloaded?.complete == true) OutlinedButton({ dismiss(); vm.start(book, downloaded.source, downloaded.source.delivery) }, Modifier.fillMaxWidth(), enabled = !busy) { Icon(Icons.Rounded.OfflinePin, null); Spacer(Modifier.width(8.dp)); Text("Play offline") }
            else OutlinedButton({ dismiss(); vm.download(book, chosen, delivery) }, Modifier.fillMaxWidth(), enabled = ready && !busy && (delivery != "torbox" || connected) && downloaded == null) {
                Icon(Icons.Rounded.Download, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(if (downloaded != null) "Download on your shelf" else "Download to phone")
            }
            if (chosen.parts.sumOf { it.sizeBytes } > 0) Text("Phone storage: ${sizeLabel(chosen.parts.sumOf { it.sizeBytes })} for this format", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (book.provider == "knaben") {
                Text("Files in this release", style = MaterialTheme.typography.titleSmall)
                chosen.parts.take(8).forEach { Text(it.name.substringAfterLast('/'), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (chosen.parts.size > 8) Text("And ${chosen.parts.size - 8} more files", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Modifier.selectableRow(selected: Boolean, action: () -> Unit): Modifier = this
    .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(12.dp))
    .clickable(onClick = action).padding(end = 12.dp, top = 4.dp, bottom = 4.dp)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OfflineStatus(vm: NarrioViewModel, download: OfflineBook) {
    var remove by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(14.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(if (download.complete) Icons.Rounded.OfflinePin else Icons.Rounded.Download, null, tint = MaterialTheme.colorScheme.secondary)
            Text(download.label, style = MaterialTheme.typography.titleSmall)
        }
        Text("${download.source.format} · ${download.completedFiles} of ${download.source.parts.size} files${if (download.bytesDownloaded > 0) " · ${sizeLabel(download.bytesDownloaded)}" else ""}${if (download.totalBytes > 0 && !download.complete) " of ${sizeLabel(download.totalBytes)}" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!download.complete && download.totalBytes > 0) {
            LinearProgressIndicator(progress = { download.progress }, modifier = Modifier.fillMaxWidth())
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
    val busy by vm.busy.collectAsStateWithLifecycle()
    val theme by vm.theme.collectAsStateWithLifecycle()
    val wifiOnly by vm.wifiOnly.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var key by remember { mutableStateOf("") }
    LaunchedEffect(connected) { if (connected) key = "" }
    LazyColumn(modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        item { Text("Make it yours.", style = MaterialTheme.typography.displaySmall); Spacer(Modifier.height(8.dp)); Text("Your listening room, your rules.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            Text("TorBox", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(if (connected) Icons.Rounded.CheckCircle else Icons.Rounded.CloudQueue, null, tint = MaterialTheme.colorScheme.secondary)
                Text(if (connected) "Connected on this device" else "Connect your delivery account", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(12.dp))
            Text("Use your TorBox API key to deliver recordings and browse the audio in your account. Your plan needs API access.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            if (!connected) {
                OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("TorBox API key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), shape = RoundedCornerShape(12.dp))
                Spacer(Modifier.height(12.dp))
                Button({ vm.connect(key) }, Modifier.fillMaxWidth(), enabled = key.isNotBlank() && !busy) { if (busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)) }; Text(if (busy) "Connecting…" else "Connect TorBox") }
                TextButton({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://torbox.app/settings"))) }) { Text("Find my API key") }
            } else OutlinedButton({ vm.disconnect() }) { Text("Disconnect and remove key") }
            Text("Protected by Android Keystore. Never synced, backed up, or included in logs.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant); Spacer(Modifier.height(24.dp))
            Text("Take a story with you", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Text("Use Download to phone on a recording to save its chosen audio format. Manage downloads on your shelf. Streaming plays over the internet without saving the book.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Download only on Wi-Fi", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Switch(wifiOnly, vm::setWifiOnly)
            }
            Text(if (wifiOnly) "Downloads wait for an unmetered connection." else "Downloads can use mobile data, including large whole-book files.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant); Spacer(Modifier.height(24.dp))
            Text("The light in the room", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Night", "Day", "System").forEach { FilterChip(theme == it, { vm.setTheme(it) }, { Text(it) }) }
            }
        }
        item {
            Text("Built to unfold", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Text("The cover display keeps controls close. Unfold for a library and listening canvas side by side. A supported half-open posture places the art above the hinge and the controls below it. Playback stays with you when the window changes.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant); Spacer(Modifier.height(24.dp))
            Text("A small, honest first edition", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Text("Narrio ${BuildConfig.VERSION_NAME} · Native Android\n\nSearch audiobook releases from Knaben, public-domain LibriVox recordings, and your TorBox library. Connected search defaults to cached audio ready to stream. Indexed releases may have unverified narration, language, or abridgment; inspect the release and files before listening. Availability depends on the provider and TorBox cache.\n\nSaved books, downloads, progress, and bookmarks stay on this device. Downloading to your phone is optional.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Text("Matched book details and cover art come from Audible or Open Library. Catalog narrator information does not verify the release's recording. Book names go directly to these metadata providers; your TorBox key and listening history stay private.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            TextButton({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://librivox.org/pages/about-librivox/"))) }) { Text("About the LibriVox recordings") }
            Text("Retrieved covers belong to their respective rights holders. Fallbacks use original garden artwork created with ImageGen and Narrio's graphic designs. Typography: Newsreader and Manrope, SIL Open Font License.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
