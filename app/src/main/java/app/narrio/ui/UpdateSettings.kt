package app.narrio.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.narrio.BuildConfig
import app.narrio.updates.*

@Composable
fun UpdateSettings(updates: AppUpdates) {
    val state by updates.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Local and debug builds have no update channel; their version shows under About instead.
    if (state.channel == null) return
    UpdateSettingsContent(state, BuildConfig.VERSION_NAME, updates::setAutomatic, updates::checkNow,
        updates::downloadNow, { updates.installNow(context) }, {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
        })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpdateSettingsContent(state: UpdateState, installedVersion: String, automatic: (Boolean) -> Unit,
                          check: () -> Unit, download: () -> Unit, install: () -> Unit, allow: () -> Unit,
                          automaticInstallSupported: Boolean = android.os.Build.VERSION.SDK_INT >= 31) {
    val busy = state.phase in listOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.INSTALLING)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // The release channel and version live under About; here it's only whether Narrio is current.
        if (state.channel == null) {
            Text("Local and debug builds update through your development tools.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        // The switch is the preference itself. Android's install permission is asked for only when there's an update to
        // install, beside that update, so an up-to-date app never shows a warning.
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Automatic updates", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Switch(state.automatic, automatic, Modifier.semantics { contentDescription = "Automatic app updates" })
        }
        if (state.available != null) {
            Text(if (state.automatic && automaticInstallSupported) "Downloads use Wi-Fi. Updates install while Narrio is closed and playback is paused. Android may ask you to confirm."
                else if (state.automatic) "Downloads use Wi-Fi. When an update is ready, pause playback and tap Install update. Android will ask you to confirm."
                else "Automatic checks, downloads, and installation are off. You can check for updates here.",
                style = MaterialTheme.typography.bodyMedium, color = muted)
            if (state.permissionNeeded) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Android needs to let Narrio install this update.", style = MaterialTheme.typography.bodyMedium)
                FilledTonalButton(allow, enabled = !busy) { Text("Allow updates") }
            }
        }
        val status = when (state.phase) {
            UpdatePhase.CHECKING -> "Checking for updates…"
            UpdatePhase.DOWNLOADING -> "Downloading ${state.available?.version} · ${(state.progress * 100).toInt()}%"
            UpdatePhase.INSTALLING -> "Installing update…"
            UpdatePhase.CONFIRMATION -> "Android needs your confirmation to finish the update."
            UpdatePhase.READY -> "${state.available?.version} is ready to install."
            // After a failed check, the error below says what happened.
            UpdatePhase.IDLE -> state.available?.let { "${it.version} is available." }
                ?: if (state.error != null) null else if (state.lastChecked > 0) "You're up to date." else "Not checked yet."
        }
        // Checking is a row like the others in Settings, with where things stand beneath it.
        val canCheck = !busy && state.phase != UpdatePhase.CONFIRMATION
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = canCheck, role = Role.Button, onClick = check)
            .heightIn(min = 48.dp).padding(vertical = 6.dp).semantics(mergeDescendants = true) { }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Check for updates", style = MaterialTheme.typography.titleSmall, color = if (canCheck) MaterialTheme.colorScheme.onSurface else muted)
                status?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall, color = muted) }
            }
            // Visibly something to tap, like the other rows in Settings.
            Icon(Icons.Rounded.Refresh, null, tint = if (canCheck) MaterialTheme.colorScheme.primary else muted)
        }
        if (state.phase == UpdatePhase.DOWNLOADING) LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
        if (state.phase == UpdatePhase.CHECKING || state.phase == UpdatePhase.INSTALLING) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        if (state.waitingForPlayback) Text("Pause playback before installing. Your shelf and listening progress stay on this device.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (state.available != null && state.phase == UpdatePhase.IDLE) {
                FilledTonalButton(download, enabled = !busy) { Text("Download update") }
            }
            if (!state.permissionNeeded && (state.phase == UpdatePhase.READY || state.phase == UpdatePhase.CONFIRMATION)) {
                FilledTonalButton(install, enabled = !state.waitingForPlayback) {
                    Text(if (state.phase == UpdatePhase.CONFIRMATION) "Confirm update" else "Install update")
                }
            }
        }
        if (state.available != null && state.phase == UpdatePhase.IDLE) Text("Downloading now can use mobile data.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
