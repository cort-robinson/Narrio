package app.narrio.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.narrio.data.AddonManager
import app.narrio.data.DeviceSourceProviderSettings
import app.narrio.data.InstalledAddon
import app.narrio.data.text
import app.narrio.domain.SourceProvider
import app.narrio.domain.SourceProviderKind
import app.narrio.domain.SourceProviderSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Settings → Sources & add-ons: one place for where Narrio looks. Audiobook and ebook sources (built in and add-ons)
 * can be turned off and reordered; ebook websites and book info can be turned off. Import, refresh, and remove keep
 * their explicit network actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddonSettings(manager: AddonManager, sources: SourceProviderSettings, ebookSources: SourceProviderSettings, connected: Boolean,
                  connectTorBox: () -> Unit, changed: () -> Unit, back: () -> Unit) {
    val installed by manager.installed.collectAsStateWithLifecycle()
    val statuses by manager.status.collectAsStateWithLifecycle()
    val providers by sources.providers.collectAsStateWithLifecycle()
    val ebookProviders by ebookSources.providers.collectAsStateWithLifecycle()
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
    fun say(text: String) { message = text; error = false }
    // Confirmations fade after a moment; errors stay until the next action.
    LaunchedEffect(message, error) { if (message != null && !error) { kotlinx.coroutines.delay(6_000); message = null } }
    val audiobookOrder = remember { ReorderState() }
    val ebookOrder = remember { ReorderState() }
    val needTorBox = providers.count { it.enabled && it.requiresTorBox }
    BackHandler(onBack = back)
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Sources & add-ons") }, navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to settings") } })
        // Results of an action stay in view (and are announced) wherever the list is scrolled.
        Box(Modifier.fillMaxWidth().height(2.dp)) { if (working != null) LinearProgressIndicator(Modifier.fillMaxWidth()) }
        AnimatedVisibility(message != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Text(message.orEmpty(), Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 24.dp, vertical = 10.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }.testTag("addon-message"), style = MaterialTheme.typography.bodySmall,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
        }
        LazyColumn(Modifier.fillMaxSize().imePadding().testTag("addon-options"), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 32.dp)) {
            item(key = "intro") {
                Text("Choose where Narrio looks for recordings, ebooks, and book details.", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("Enabled sources receive your search terms directly. Torrent releases use your TorBox connection; ebook websites open inside Narrio when you choose them. Add-ons never receive your TorBox key or listening history. Turning off or removing a source keeps saved books and progress.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!connected && needTorBox > 0) {
                    Spacer(Modifier.height(16.dp))
                    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(14.dp)).padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 4.dp)) {
                        Text("TorBox isn't connected", style = MaterialTheme.typography.titleSmall)
                        Text("$needTorBox audiobook ${plural(needTorBox, "source")} ${if (needTorBox == 1) "needs" else "need"} it to search. Free public recordings still work.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp, end = 8.dp))
                        TextButton(connectTorBox, Modifier.offset(x = (-12).dp)) { Text("Connect TorBox") }
                    }
                }
            }
            item(key = "audiobook-heading") {
                SectionHeading("Audiobook sources", "All sources that are on search together. This order sets the order of sources on a book's page and breaks ties. Drag a source by its handle, or use its menu to move it.")
            }
            providerRows("provider", providers, audiobookOrder, sources, installed, statuses, connected, working, ::say, ::run, manager)
            item(key = "ebook-heading") {
                SectionHeading("Ebook sources", "All sources that are on search together when you find an ebook. This order sets the order of sources there and breaks ties. Ebook websites open inside Narrio when you choose them, and files on this phone are always available.")
            }
            providerRows("ebook-provider", ebookProviders, ebookOrder, ebookSources, installed, statuses, connected, working, ::say, ::run, manager)
            addonSection("Ebook websites", null, installed.filter { it.purpose == "Ebook sources" && !it.source && !it.searchedInApp }, statuses, working, connected,
                toggle = { addon, value -> run("save add-on") { manager.enable(addon.id, value); "${addon.name} ${if (value) "on" else "off"}." } },
                refresh = { addon -> run("refresh add-on") { manager.refresh(addon.id); "${addon.name} refreshed." } },
                remove = { addon -> run("remove add-on") { manager.remove(addon.id); "${addon.name} removed. Reimport its URL to restore it." } })
            addonSection("Book info", "Titles, descriptions, and covers. Google Books is a built-in fallback.", installed.filter { it.purpose == "Book metadata" }, statuses, working, connected,
                toggle = { addon, value -> run("save add-on") { manager.enable(addon.id, value); "${addon.name} ${if (value) "on" else "off"}." } },
                refresh = { addon -> run("refresh add-on") { manager.refresh(addon.id); "${addon.name} refreshed." } },
                remove = { addon -> run("remove add-on") { manager.remove(addon.id); "${addon.name} removed. Reimport its URL to restore it." } })
            item(key = "import") {
                SectionHeading("Add a source", "Paste the HTTPS link to an add-on's manifest. Imported add-ons join the list above that fits them.")
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Add-on manifest URL") }, singleLine = true, enabled = working == null)
                Spacer(Modifier.height(8.dp))
                Button({ run("import add-on") { val addon = manager.install(url); url = ""; "${addon.name} imported." } }, enabled = url.isNotBlank() && working == null) { Text("Import add-on") }
            }
        }
    }
}

@Composable
private fun SectionHeading(title: String, help: String) {
    Column(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(6.dp))
        Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** [help] null lists the add-ons under the section above, without a heading or an empty message. */
private fun androidx.compose.foundation.lazy.LazyListScope.addonSection(
    title: String, help: String?, addons: List<InstalledAddon>, statuses: Map<String, String>, working: String?, connected: Boolean,
    toggle: (InstalledAddon, Boolean) -> Unit, refresh: (InstalledAddon) -> Unit, remove: (InstalledAddon) -> Unit,
) {
    if (help == null) { if (addons.isEmpty()) return }
    else item(key = "heading:$title") { SectionHeading(title, help) }
    if (addons.isEmpty()) item(key = "empty:$title") { Text("No add-ons installed for ${title.lowercase()}.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    items(addons, key = { "addon:${title}:${it.id}" }) { addon ->
        SourceRow(
            name = addon.name,
            subtitle = listOf("Add-on", addon.manifest.text("version").takeIf(String::isNotBlank)?.let { "Version $it" }).filterNotNull().joinToString(" · "),
            status = if (!addon.enabled) "Off" else if (addon.ebookSearch) "Opens inside Narrio" else statuses[addon.id],
            warning = if (addon.source && addon.contentType == "ebook" && !connected) "Needs TorBox" else null,
            enabled = addon.enabled, toggleEnabled = working == null, onToggle = { toggle(addon, it) }, tag = "addon:${addon.id}",
            refresh = { refresh(addon) }, remove = { remove(addon) }, busy = working != null,
            description = addon.manifest.text("description"),
        )
    }
}

/** Reordering moves a local copy while the finger is down; the order is saved once, when it lifts. */
private class ReorderState {
    var dragging by mutableStateOf<String?>(null)
    var dragOffset by mutableFloatStateOf(0f)
    var draft by mutableStateOf<List<SourceProvider>>(emptyList())
    val heights = mutableStateMapOf<String, Int>()
}

/** One reorderable list of sources: a switch per row, a drag handle, and Move up/down in the menu and for TalkBack. */
private fun androidx.compose.foundation.lazy.LazyListScope.providerRows(
    key: String, providers: List<SourceProvider>, order: ReorderState, sources: SourceProviderSettings, installed: List<InstalledAddon>,
    statuses: Map<String, String>, connected: Boolean, working: String?, say: (String) -> Unit, run: (String, suspend () -> String) -> Unit, manager: AddonManager,
) {
    fun move(id: String, index: Int) {
        val name = providers.firstOrNull { it.id == id }?.name ?: return
        val target = index.coerceIn(0, providers.lastIndex)
        if (providers.indexOfFirst { it.id == id } == target) return
        // Source settings invalidate cached results and restart an open search on their own.
        sources.move(id, target)
        say("$name moved to ${target + 1} of ${providers.size}.")
    }
    val shown = if (order.dragging != null) order.draft else providers
    items(shown, key = { "$key:${it.id}" }) { provider ->
        val haptics = LocalHapticFeedback.current
        val index = shown.indexOf(provider)
        val lifted = order.dragging == provider.id
        val addonId = provider.id.removePrefix("addon:").takeIf { provider.kind == SourceProviderKind.ADDON }
        val addon = installed.firstOrNull { it.id == addonId }
        Box(Modifier.then(if (lifted) Modifier.zIndex(1f) else Modifier.animateItem())
            .graphicsLayer { translationY = if (lifted) order.dragOffset else 0f }
            .onSizeChanged { order.heights[provider.id] = it.height }) {
            SourceRow(
                name = provider.name,
                subtitle = providerSubtitle(provider, addon),
                status = providerStatus(provider, addon, statuses),
                warning = if (provider.requiresTorBox && !connected) "Needs TorBox" else null,
                enabled = provider.enabled,
                toggleEnabled = working == null && order.dragging == null,
                onToggle = { value -> sources.setEnabled(provider.id, value); say("${provider.name} ${if (value) "on" else "off"}.") },
                tag = provider.id,
                lifted = lifted,
                handle = {
                    Box(Modifier.size(48.dp).pointerInput(provider.id) {
                        detectDragGestures(
                            onDragStart = { order.draft = providers; order.dragging = provider.id; order.dragOffset = 0f; haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                            onDrag = { change, amount ->
                                change.consume()
                                order.dragOffset += amount.y
                                val draft = order.draft
                                val current = draft.indexOfFirst { it.id == provider.id }
                                val next = draft.getOrNull(current + 1); val previous = draft.getOrNull(current - 1)
                                val down = next?.let { order.heights[it.id] } ?: 0; val up = previous?.let { order.heights[it.id] } ?: 0
                                if (next != null && order.dragOffset > down / 2f) {
                                    order.draft = draft.toMutableList().apply { add(current + 1, removeAt(current)) }; order.dragOffset -= down
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                } else if (previous != null && order.dragOffset < -up / 2f) {
                                    order.draft = draft.toMutableList().apply { add(current - 1, removeAt(current)) }; order.dragOffset += up
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            },
                            onDragEnd = { move(provider.id, order.draft.indexOfFirst { it.id == provider.id }); order.dragging = null; order.dragOffset = 0f },
                            onDragCancel = { order.dragging = null; order.dragOffset = 0f },
                        )
                    }.semantics { hideFromAccessibility() }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.DragHandle, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                moveUp = if (index > 0) ({ move(provider.id, index - 1) }) else null,
                moveDown = if (index < shown.lastIndex) ({ move(provider.id, index + 1) }) else null,
                refresh = addon?.let { { run("refresh add-on") { manager.refresh(it.id); "${it.name} refreshed." } } },
                remove = addon?.let { { run("remove add-on") { manager.remove(it.id); "${it.name} removed. Reimport its URL to restore it." } } },
                busy = working != null,
            )
        }
    }
}

private fun providerSubtitle(provider: SourceProvider, addon: InstalledAddon?): String = when (provider.id) {
    DeviceSourceProviderSettings.ARCHIVE -> "Built in · Free public recordings from LibriVox"
    DeviceSourceProviderSettings.LIBRARY -> "Built in · Recordings already in your TorBox"
    DeviceSourceProviderSettings.RECORDING_FILES -> "Built in · Ebook files that come with a recording"
    DeviceSourceProviderSettings.TORBOX_EBOOKS -> "Built in · Ebooks already in your TorBox"
    DeviceSourceProviderSettings.GUTENBERG -> "Built in · Free public-domain ebooks"
    else -> listOfNotNull(if (provider.kind == SourceProviderKind.BUILT_IN) "Built in" else "Add-on",
        addon?.manifest?.text("version")?.takeIf(String::isNotBlank)?.let { "Version $it" }).joinToString(" · ")
}

private fun providerStatus(provider: SourceProvider, addon: InstalledAddon?, statuses: Map<String, String>): String? = when {
    !provider.enabled -> "Off"
    provider.lastStatus != null -> provider.lastStatus
    addon != null -> statuses[addon.id]
    else -> null
}

/**
 * One source: its name and kind, what it last reported, and its switch. The whole text area toggles, so the target
 * is the row rather than the switch alone. Moving and removing live in the row's menu and TalkBack actions.
 */
@Composable
private fun SourceRow(
    name: String, subtitle: String, status: String?, warning: String?, enabled: Boolean, toggleEnabled: Boolean, onToggle: (Boolean) -> Unit, tag: String,
    lifted: Boolean = false, handle: (@Composable () -> Unit)? = null, moveUp: (() -> Unit)? = null, moveDown: (() -> Unit)? = null,
    refresh: (() -> Unit)? = null, remove: (() -> Unit)? = null, busy: Boolean = false, description: String = "",
) {
    var menu by remember { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth()
        .then(if (lifted) Modifier.shadow(8.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh) else Modifier)
        .semantics {
            customActions = listOfNotNull(
                moveUp?.let { CustomAccessibilityAction("Move up") { it(); true } },
                moveDown?.let { CustomAccessibilityAction("Move down") { it(); true } },
            )
        }.testTag("source-row:$tag")) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
            if (handle != null) handle() else Spacer(Modifier.width(0.dp))
            Row(Modifier.weight(1f).toggleable(enabled, enabled = toggleEnabled, role = Role.Switch, onValueChange = onToggle).padding(vertical = 12.dp).testTag("source-toggle:$tag"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = muted)
                    if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 3)
                    warning?.let {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Rounded.LinkOff, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    status?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary) }
                }
                Switch(enabled, null, enabled = toggleEnabled)
            }
            if (moveUp != null || moveDown != null || refresh != null || remove != null) Box {
                IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "More for $name") }
                DropdownMenu(menu, { menu = false }) {
                    moveUp?.let { DropdownMenuItem(text = { Text("Move up") }, leadingIcon = { Icon(Icons.Rounded.ArrowUpward, null) }, onClick = { menu = false; it() }) }
                    moveDown?.let { DropdownMenuItem(text = { Text("Move down") }, leadingIcon = { Icon(Icons.Rounded.ArrowDownward, null) }, onClick = { menu = false; it() }) }
                    refresh?.let { DropdownMenuItem(text = { Text("Refresh $name") }, leadingIcon = { Icon(Icons.Rounded.Refresh, null) }, enabled = !busy, onClick = { menu = false; it() }) }
                    remove?.let { DropdownMenuItem(text = { Text("Remove $name") }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) }, enabled = !busy, onClick = { menu = false; it() }) }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
