package app.narrio.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.narrio.data.AddonManager
import app.narrio.data.text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A settings extension: local choices, provider purpose, and explicit network actions. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddonSettings(manager: AddonManager, changed: () -> Unit, back: () -> Unit) {
    val installed by manager.installed.collectAsStateWithLifecycle()
    val statuses by manager.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf("") }
    var working by remember { mutableStateOf<String?>(null) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    fun run(label: String, action: suspend () -> String) {
        working = label; message = null
        scope.launch {
            try { message = action(); error = false; changed() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "Could not $label. Check the HTTPS manifest URL and try again. Only schema 1.0.0 JSON source, catalog, and browser ebook-search add-ons are supported."; error = true }
            finally { working = null }
        }
    }
    BackHandler(onBack = back)
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Add-ons") }, navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to settings") } })
        LazyColumn(Modifier.fillMaxSize().imePadding().testTag("addon-options"), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                Text("Choose where Narrio finds books, audio, and ebooks.", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("Enabled add-ons receive your search terms directly. Torrent releases use your TorBox connection; ebook websites open inside Narrio when you choose them. Add-ons never receive your TorBox key or listening history. Disabling or removing an add-on keeps saved books and progress.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Add-on manifest URL") }, singleLine = true, enabled = working == null)
                Spacer(Modifier.height(8.dp))
                Button({ run("import add-on") { val addon = manager.install(url); url = ""; "${addon.name} imported." } }, enabled = url.isNotBlank() && working == null) { Text("Import add-on") }
                if (working != null) { LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp)); Text("Working…", style = MaterialTheme.typography.bodySmall) }
                message?.let { Text(it, Modifier.padding(top = 8.dp), color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary) }
            }
            listOf("Book metadata", "Audiobook sources", "Ebook sources").forEach { purpose ->
                val group = installed.filter { it.purpose == purpose }
                item(key = purpose) { Text(purpose, style = MaterialTheme.typography.headlineSmall) }
                if (group.isEmpty()) item { Text("No add-ons installed for ${purpose.lowercase()}.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(group, key = { it.id }) { addon ->
                    Column {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(addon.name, style = MaterialTheme.typography.titleMedium)
                                Text("Version ${addon.manifest.text("version")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(addon.enabled, { value -> run("save add-on") { manager.enable(addon.id, value); "${addon.name} ${if (value) "enabled" else "disabled"}." } },
                                enabled = working == null, modifier = Modifier.semantics { contentDescription = "Enable ${addon.name}" })
                        }
                        Text(addon.manifest.text("description"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (!addon.enabled) "Disabled" else if (addon.ebookSearch) "Enabled · Opens inside Narrio" else statuses[addon.id] ?: "Enabled · Not checked yet", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton({ run("refresh add-on") { manager.refresh(addon.id); "${addon.name} refreshed." } }, enabled = working == null) { Text("Refresh ${addon.name}") }
                            TextButton({ run("remove add-on") { manager.remove(addon.id); "${addon.name} removed. Reimport its URL to restore it." } }, enabled = working == null) { Text("Remove ${addon.name}") }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}
