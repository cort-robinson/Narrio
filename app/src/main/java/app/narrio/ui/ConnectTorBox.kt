package app.narrio.ui

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.narrio.data.ProviderException
import app.narrio.domain.ThemeContrast
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Connecting TorBox: [prompt] shows the sheet over the current screen; [error] says why the last attempt failed. */
data class TorBoxConnectState(val prompt: Boolean = false, val connecting: Boolean = false, val error: String? = null)

/**
 * One TorBox connection attempt at a time. [save] checks the key with TorBox and stores it; [connected] runs once it
 * has. Dismissing the sheet cancels a running attempt, so a closed sheet never saves the key later, and an attempt
 * that is no longer current never touches [state].
 */
class TorBoxConnector(private val scope: CoroutineScope, private val save: suspend (String) -> Unit, private val connected: suspend () -> Unit) {
    val state = MutableStateFlow(TorBoxConnectState())
    private var attempt: Job? = null

    fun request() = state.update { it.copy(prompt = true, error = null) }
    fun dismiss() { attempt?.cancel(); attempt = null; state.value = TorBoxConnectState() }
    fun clearError() = state.update { if (it.error == null) it else it.copy(error = null) }

    /** A second submit while one is running is ignored; a failure stays under the key field. */
    fun connect(key: String) {
        val trimmed = key.trim()
        if (trimmed.isBlank() || attempt?.isActive == true) return
        state.update { it.copy(connecting = true, error = null) }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext.job
            try {
                save(trimmed)
                if (attempt !== self) return@launch
                attempt = null; state.value = TorBoxConnectState()
                connected()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (attempt === self) { attempt = null; state.update { it.copy(connecting = false, error = connectError(error)) } }
            }
        }
        attempt = job
        job.start()
    }
}

internal fun connectError(error: Exception) = if (error is ProviderException) error.message.orEmpty() else "Couldn't save the key on this phone. Try again."

/**
 * Connect TorBox over whatever is on screen, opened from a book, Discover, or a source list. The book stays
 * selected; once connected, its page looks again with TorBox (see [NarrioViewModel.connect]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectTorBoxSheet(vm: NarrioViewModel) {
    val state by vm.torBox.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var visible by remember { mutableStateOf(false) }
    // Connecting closes the sheet with its usual slide instead of snapping it away.
    LaunchedEffect(state.prompt) { if (state.prompt) visible = true else if (visible) { sheetState.hide(); visible = false } }
    if (!visible) return
    ModalBottomSheet(vm::dismissTorBoxConnect, Modifier.testTag("connect-torbox-sheet"), sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        val view = LocalView.current
        val dark = ThemeContrast.foreground(MaterialTheme.colorScheme.background.toArgb()) == 0xFFFFFF
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
        } }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Connect TorBox", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
            Text("TorBox finds and streams audiobooks for you, including most popular books. Free LibriVox recordings play without it.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TorBoxConnectForm(state, vm::connect, vm::clearTorBoxError)
        }
    }
}

/**
 * The API key field shared by the sheet and Settings: paste fills it, Done connects, and a failed attempt says why
 * right under the key. The key is never saved in UI state; only the encrypted credential outlives the field.
 */
@Composable
fun TorBoxConnectForm(state: TorBoxConnectState, connect: (String) -> Unit, edited: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(false) }
    // API keys have no spaces, so pasted line breaks and padding never make a key fail.
    fun change(value: String) { key = value.filterNot(Char::isWhitespace); edited() }
    fun submit() { if (key.isNotBlank() && !state.connecting) connect(key) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(key, ::change, Modifier.fillMaxWidth().testTag("torbox-key"), label = { Text("TorBox API key") }, singleLine = true,
            enabled = !state.connecting, isError = state.error != null,
            visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            trailingIcon = {
                if (key.isEmpty()) IconButton({ scope.launch { clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.let { change(it.toString()) } } }) {
                    Icon(Icons.Rounded.ContentPaste, "Paste API key")
                } else IconButton({ shown = !shown }) { Icon(if (shown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (shown) "Hide API key" else "Show API key") }
            },
            supportingText = state.error?.let { error -> { Text(error, Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("torbox-error")) } },
            shape = RoundedCornerShape(12.dp))
        Button(::submit, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("torbox-connect"), enabled = key.isNotBlank() && !state.connecting) {
            if (state.connecting) { CircularProgressIndicator(Modifier.size(18.dp), LocalContentColor.current, strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)) }
            Text(if (state.connecting) "Connecting…" else "Connect TorBox", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        TextButton({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://torbox.app/settings"))) }, Modifier.offset(x = (-12).dp)) {
            Text("Find my API key"); Spacer(Modifier.width(6.dp)); Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp))
        }
        Text("Your plan needs API access. The key is protected by Android Keystore and never synced, backed up, or included in logs.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Settings' TorBox section: the key form until connected, then one compact row. Disconnecting asks first. */
@Composable
fun TorBoxSettings(vm: NarrioViewModel, connected: Boolean) {
    val state by vm.torBox.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    if (connected) Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(14.dp))
        .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp).testTag("torbox-connected"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.secondary)
        Column(Modifier.weight(1f)) {
            Text("TorBox", style = MaterialTheme.typography.titleSmall)
            Text("Connected on this phone", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton({ confirm = true }, Modifier.heightIn(min = 48.dp)) { Text("Disconnect") }
    } else Column {
        Text("TorBox", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("Connect TorBox to listen beyond free LibriVox recordings: it finds, prepares, and streams audiobooks, and lets you browse the audio in your account.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        TorBoxConnectForm(state, vm::connect, vm::clearTorBoxError)
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Disconnect TorBox?") },
        text = { Text("Your API key is removed from this phone and unfinished TorBox downloads pause. Downloaded audio, your shelf, and progress stay.") },
        confirmButton = { TextButton({ confirm = false; vm.disconnect() }) { Text("Disconnect") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Keep connected") } })
}

/** When Discover's TorBox hint shows: only while disconnected, and not within [QUIET_DAYS] days of Not now. */
internal object TorBoxNudge {
    const val KEY = "torboxNudgeDismissedAt"
    const val QUIET_DAYS = 30L
    private const val QUIET_MS = QUIET_DAYS * 24 * 60 * 60 * 1000
    // A clock set back before the dismissal shows the hint again rather than hiding it for good.
    fun visible(connected: Boolean, dismissedAtMs: Long, nowMs: Long) = !connected && (dismissedAtMs <= 0 || nowMs - dismissedAtMs !in 0 until QUIET_MS)
}

/** Discover's remembered Not now; it lives on the device with the other preferences. */
@Stable
class TorBoxNudgeState(private val preferences: SharedPreferences) {
    private var dismissedAt by mutableLongStateOf(preferences.getLong(TorBoxNudge.KEY, 0L))
    fun visible(connected: Boolean) = TorBoxNudge.visible(connected, dismissedAt, System.currentTimeMillis())
    fun dismiss() { dismissedAt = System.currentTimeMillis(); preferences.edit().putLong(TorBoxNudge.KEY, dismissedAt).apply() }
}

@Composable
fun rememberTorBoxNudge(preferences: SharedPreferences) = remember(preferences) { TorBoxNudgeState(preferences) }

/** Most chart books need TorBox to play; says so once at the top of browsing, with the way to connect. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TorBoxNudgeCard(connect: () -> Unit, dismiss: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(14.dp))
        .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 6.dp).testTag("torbox-nudge")) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.Link, null, Modifier.padding(top = 2.dp).size(20.dp), tint = MaterialTheme.colorScheme.secondary)
            Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Most of these books need TorBox to listen.", style = MaterialTheme.typography.titleSmall)
                Text("Free LibriVox recordings work without it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        FlowRow(Modifier.padding(top = 8.dp, start = 32.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(connect) { Text("Connect TorBox") }
            TextButton(dismiss) { Text("Not now") }
        }
    }
}
