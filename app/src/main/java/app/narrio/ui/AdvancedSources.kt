package app.narrio.ui

import app.narrio.data.recordingKey
import app.narrio.data.sameRecording
import app.narrio.data.preparingRecording
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.narrio.domain.*

/*
 * The recording chooser's Advanced view: every source's own section (release names, seeders, possible matches,
 * retry, "Also found by"), then how the pick plays (format and delivery) and the recording's own details and files.
 * Technical words belong here, not on the book page.
 */

/**
 * What Advanced knows, for sections added through [AdvancedSources]' `extraSections`: the book, the chooser's
 * current pick, a way to make another recording the pick (returning to the chooser's list), and Back.
 */
class AdvancedContext(val book: Audiobook, val picked: Audiobook?, val choose: (Audiobook) -> Unit, val back: () -> Unit)

/**
 * [extraSections] is the extension slot for more ways to find a recording (searching with different words, a pasted
 * link, the TorBox library, files on the phone, "Not this book"); its items follow the per-source sections.
 */
@Composable
fun AdvancedSources(
    book: Audiobook, picked: Audiobook?, current: CurrentRecording?, streamed: StreamedSourceSearch?, tally: SearchTally?, providers: Map<String, SourceProvider>,
    bestId: String?, connected: Boolean, format: String?, delivery: String?, chooseFormat: (String, String) -> Unit, actions: SourceActions,
    refreshDetails: (() -> Unit)?, metadataLoading: Boolean, back: () -> Unit, extraSections: LazyListScope.(AdvancedContext) -> Unit = {},
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    LazyColumn(Modifier.fillMaxWidth().testTag("advanced-sources"), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "advanced-heading") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(back, Modifier.testTag("advanced-back")) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to recordings") }
                Text("Advanced", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            }
            Text("Every release each source found. Indexed releases may have unverified narration, language, or abridgment, so check the release name and files. Choose one to listen to it.",
                style = MaterialTheme.typography.bodyMedium, color = muted)
        }
        if (streamed != null && tally != null) listeningSources(streamed, tally, providers, book, bestId, connected, wide = false,
            searchAnnouncement(streamed, null, tally, book), actions)
        extraSections(AdvancedContext(book, picked, actions.choose, back))
        if (picked != null) {
            item(key = "how-it-plays") { HowItPlays(picked, current, connected, format, delivery, chooseFormat) }
            item(key = "about-recording") { AboutRecording(picked, streamed, providers, format ?: current?.source?.format, refreshDetails, metadataLoading) }
        }
    }
}

/** Format and delivery for the pick, overriding the automatic choice. Each keeps its own listening place. */
@Composable
private fun HowItPlays(picked: Audiobook, current: CurrentRecording?, connected: Boolean, format: String?, delivery: String?, choose: (String, String) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val mine = current != null && sameRecording(picked, current.recording)
    val shownFormat = format ?: current?.source?.format?.takeIf { mine } ?: defaultFormat(picked, "")
    val deliveries = listOfNotNull("archive".takeIf { picked.provider == "archive" },
        "torbox".takeIf { picked.torrentUrl.isNotBlank() || picked.magnetUri.isNotBlank() || picked.provider == "torbox" || picked.provider == "knaben" })
    val shownDelivery = delivery ?: deliveries.firstOrNull().orEmpty()
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(12.dp))
        Text("How it plays", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text("Narrio chooses these for you. Each format and delivery keeps its own listening position.", style = MaterialTheme.typography.bodySmall, color = muted)
        picked.sources.forEach { source ->
            val cached = picked.provider == "archive" || source.format in picked.cachedFormats
            OptionRow(source.format == shownFormat, { choose(source.format, shownDelivery) }, Modifier.testTag("format:${source.format}")) {
                Text(source.label, style = MaterialTheme.typography.titleSmall)
                Text("${source.format} · ${source.parts.size} ${if (source.parts.size == 1) "file" else "parts"}${if (picked.provider != "archive") if (cached) " · Cached in TorBox" else " · Not cached in TorBox" else ""}",
                    style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
        if (deliveries.size > 1) deliveries.forEach { option ->
            OptionRow(option == shownDelivery, { choose(shownFormat.orEmpty(), option) }) {
                Text(if (option == "archive") "Internet Archive" else "TorBox", style = MaterialTheme.typography.titleSmall)
                Text(if (option == "archive") "Direct public recording" else if (!connected) "Connect TorBox to use this delivery" else "Streams through your TorBox account",
                    style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
    }
}

@Composable
private fun OptionRow(selected: Boolean, choose: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
        .selectable(selected, role = Role.RadioButton, onClick = choose).heightIn(min = 48.dp).padding(end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, null, Modifier.padding(horizontal = 12.dp))
        Column(Modifier.weight(1f), content = content)
    }
}

/** The pick's release name, where it was found, its health, its files, and Refresh book details. */
@Composable
private fun AboutRecording(picked: Audiobook, streamed: StreamedSourceSearch?, providers: Map<String, SourceProvider>, format: String?,
                           refreshDetails: (() -> Unit)?, metadataLoading: Boolean) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(top = 12.dp).testTag("about-recording"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(12.dp))
        Text("About this recording", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text("Release: ${picked.releaseTitle.ifBlank { picked.title }}", style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
        val finder = streamed?.groups?.firstOrNull { group -> group.recordings.any { it.id == picked.id } || group.possible.any { it.id == picked.id } }
        val also = streamed?.let { alsoFoundBy(it, picked.id, providers) }.orEmpty()
        Text(listOfNotNull("From ${finder?.let { providers[it.providerId]?.name ?: it.name } ?: providerLabel(picked)}", also.takeIf { it.isNotEmpty() }?.let { "also found by ${it.joinToString()}" },
            availabilityLabel(picked)).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = muted)
        qualityLabels(picked).takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = muted) }
        Text(narrationLabel(picked) + " · " + picked.language, style = MaterialTheme.typography.bodySmall, color = muted)
        val files = (picked.sources.firstOrNull { it.format == format } ?: picked.sources.firstOrNull())?.parts.orEmpty()
        if (files.isNotEmpty()) {
            Text("Files", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
            files.take(8).forEach { Text(it.name.substringAfterLast('/'), style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (files.size > 8) Text("And ${files.size - 8} more files", style = MaterialTheme.typography.bodySmall, color = muted)
        }
        if (refreshDetails != null) TextButton(refreshDetails, enabled = !metadataLoading) {
            if (metadataLoading) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
            Text(if (metadataLoading) "Fetching book details…" else "Refresh book details")
        }
    }
}
