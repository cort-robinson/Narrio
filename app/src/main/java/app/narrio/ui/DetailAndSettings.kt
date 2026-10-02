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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.narrio.domain.*
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
    var sourcePicker by remember(selected.book?.id) { mutableStateOf(false) }
    var expandedDescription by remember(selected.book?.id) { mutableStateOf(false) }
    val book = selected.book ?: return
    val saved = shelf.any { it.bookId == book.id }
    LaunchedEffect(book.id, preparation?.torrentId, preparation?.ready) {
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
            Text("Read by ${book.narrator}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                Text(book.language, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(durationLabel(book.durationMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (book.provider == "torbox") "Your TorBox" else "LibriVox", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
                Spacer(Modifier.height(12.dp)); Text("This edition has no compatible audio files. Try another recording.", style = MaterialTheme.typography.bodyMedium)
            }
        }
        preparation?.let { prep -> item {
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(14.dp)).padding(20.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (prep.ready) Icons.Rounded.CheckCircle else Icons.Rounded.CloudDownload, null, tint = MaterialTheme.colorScheme.primary)
                    Text(prep.state, style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(12.dp))
                if (!prep.ready && prep.progress > 0) LinearProgressIndicator(progress = { prep.progress }, modifier = Modifier.fillMaxWidth())
                Text(if (prep.ready) "Your source is ready. Choose Listen to begin." else "You can leave this screen. The recording stays on your shelf while TorBox prepares it.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    if (sourcePicker) SourcePicker(book, connected, busy, { sourcePicker = false }, { source, delivery -> sourcePicker = false; vm.start(book, source, delivery) }, { sourcePicker = false; vm.navigate(2) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourcePicker(book: Audiobook, connected: Boolean, busy: Boolean, dismiss: () -> Unit, start: (AudioSource, String) -> Unit, settings: () -> Unit) {
    var chosen by remember { mutableStateOf(book.sources.first()) }
    var delivery by remember { mutableStateOf(if (book.provider == "torbox" || connected) "torbox" else "archive") }
    ModalBottomSheet(onDismissRequest = dismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Choose how you listen.", style = MaterialTheme.typography.headlineMedium)
            Text("${book.narrator} · ${book.language}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            book.sources.forEach { source ->
                Row(Modifier.fillMaxWidth().selectableRow(chosen.id == source.id) { chosen = source }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(chosen.id == source.id, { chosen = source })
                    Column(Modifier.weight(1f)) { Text(source.label, style = MaterialTheme.typography.titleSmall); Text("${source.format} · ${source.parts.size} ${if (source.parts.size == 1) "file" else "parts"}", style = MaterialTheme.typography.bodySmall) }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (book.provider != "torbox") {
                Row(Modifier.fillMaxWidth().selectableRow(delivery == "archive") { delivery = "archive" }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(delivery == "archive", { delivery = "archive" }); Column { Text("Internet Archive", style = MaterialTheme.typography.titleSmall); Text("Direct public recording · Ready to stream", style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (book.torrentUrl.isNotBlank() || book.provider == "torbox") Row(Modifier.fillMaxWidth().selectableRow(delivery == "torbox") { delivery = "torbox" }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(delivery == "torbox", { delivery = "torbox" }); Column { Text("TorBox", style = MaterialTheme.typography.titleSmall); Text(if (connected) "Through your account · May need preparation" else "Connect your account to use this source", style = MaterialTheme.typography.bodySmall) }
            }
            Text("Each audio format and delivery source keeps its own listening position. Narrio never guesses a timestamp across recordings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button({ if (delivery == "torbox" && !connected) settings() else start(chosen, delivery) }, Modifier.fillMaxWidth(), enabled = !busy) {
                Text(if (delivery == "torbox" && !connected) "Connect TorBox" else if (delivery == "torbox") "Listen through TorBox" else "Start listening")
            }
        }
    }
}

@Composable
private fun Modifier.selectableRow(selected: Boolean, action: () -> Unit): Modifier = this
    .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(12.dp))
    .clickable(onClick = action).padding(end = 12.dp, top = 4.dp, bottom = 4.dp)

@Composable
fun SettingsScreen(vm: NarrioViewModel, modifier: Modifier = Modifier) {
    val connected by vm.connected.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val theme by vm.theme.collectAsStateWithLifecycle()
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
            Text("Narrio 1.0 · Native Android\n\nDiscovery currently covers public-domain LibriVox recordings and audio already in your TorBox account. Newer commercial audiobook discovery requires a future source provider. Recordings retain their own narrator, language, and progress.\n\nSaved books and bookmarks live in Room on this device. V1 streams audio; it does not save offline downloads.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            TextButton({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://librivox.org/pages/about-librivox/"))) }) { Text("About the LibriVox recordings") }
            Text("Original garden artwork created with ImageGen. Typography: Newsreader and Manrope, SIL Open Font License. Other covers are Narrio's original graphic designs.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
