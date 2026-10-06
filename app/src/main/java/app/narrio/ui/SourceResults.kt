package app.narrio.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.narrio.data.SourceQuality
import app.narrio.domain.*

/*
 * Book details, listening by source: one Best match leads for listeners who just want to press Listen, and each
 * source keeps its own section that fills in as that provider answers. Copy stays in the listener's terms.
 */

/** What the listener can do from the sources area; book details bind these to the view model. */
class SourceActions(
    val listen: (Audiobook) -> Unit,
    val prepare: (Audiobook) -> Unit,
    val open: (Audiobook) -> Unit,
    val options: (Audiobook?) -> Unit,
    val retry: (String) -> Unit,
    val searchAgain: () -> Unit,
    val connectTorBox: () -> Unit,
    val sourceSettings: () -> Unit,
    val showSources: () -> Unit,
)

/**
 * The listening slot on a catalog book: the best match, why it was chosen, and one primary action. While the
 * search runs it may improve, but once the listener has touched the page a better match waits behind a quiet
 * "Better match found" instead of replacing what they are about to press.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BestMatchCard(
    search: StreamedSourceSearch, pinned: PinnedBest, chosen: Audiobook?, tally: SearchTally, providers: Map<String, SourceProvider>, book: Audiobook,
    connected: Boolean, busy: Boolean, starting: Boolean, leading: Boolean, actions: SourceActions, acceptBetter: () -> Unit, useBest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
        .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp).animateContentSize(tween(Motion.MEDIUM, easing = Motion.Emphasized)).testTag("best-match")) {
        AnimatedVisibility(pinned.pending != null && chosen?.let { it.id != pinned.shown?.recording?.id } != true, enter = expandVertically(tween(Motion.MEDIUM, easing = Motion.Emphasized)) + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            BetterMatchFound(acceptBetter, Modifier.padding(bottom = 12.dp))
        }
        // An explicit pick from Listening options leads; it carries no ranking reasons, so its own labels describe it.
        val picked = chosen?.takeIf { it.id != pinned.shown?.recording?.id }
        val shown = picked?.let { recording -> BestMatch(recording, emptyList(), search.groups.firstOrNull { group -> group.recordings.any { it.id == recording.id } || group.possible.any { it.id == recording.id } }?.providerId.orEmpty()) }
            ?: pinned.shown
        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Text(if (picked != null) "Your choice" else "Best match", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            AnimatedVisibility(!search.complete && tally.active > 0, enter = fadeIn(), exit = fadeOut()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Working(Modifier.size(14.dp))
                    val left = tally.active - tally.answered
                    Text(if (shown == null) "Checking ${tally.active} ${plural(tally.active, "source")}" else "Still checking $left", style = MaterialTheme.typography.labelMedium, color = muted)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        when {
            shown != null -> AnimatedContent(shown, transitionSpec = { fadeIn(tween(Motion.MEDIUM, 90, Motion.EmphasizedDecelerate)).togetherWith(fadeOut(tween(90))) },
                contentKey = { it.recording.id }, label = "best match") { best ->
                Column {
                    val copy = bestMatchCopy(best, book)
                    Text(copy.headline, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("best-match-reasons"))
                    if (copy.detail.isNotBlank()) Text(copy.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 2.dp))
                    Text(best.recording.releaseTitle.ifBlank { best.recording.title }, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    val also = alsoFoundBy(search, best.recording.id, providers)
                    Text(listOfNotNull("From ${providers[best.providerId]?.name ?: providerLabel(best.recording)}", also.takeIf { it.isNotEmpty() }?.let { "also found by ${it.joinToString()}" }).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, color = muted, modifier = Modifier.padding(top = 2.dp))
                }
            }
            !search.complete && tally.active > 0 -> CardSkeleton()
            else -> {
                val copy = noMatchCopy(search, tally)
                Text(copy.title, style = MaterialTheme.typography.titleMedium)
                Text(copy.message, style = MaterialTheme.typography.bodyMedium, color = muted, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
        val button = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        when {
            starting -> ModeButton({}, button.testTag("listen-action"), leading, enabled = false) { WorkingLabel("Starting…") }
            shown != null && needsPreparing(shown) && !connected -> ModeButton(actions.connectTorBox, button.testTag("listen-action"), leading) { SlotLabel(Icons.Rounded.Link, "Connect TorBox to listen") }
            shown != null && needsPreparing(shown) -> ModeButton({ actions.prepare(shown.recording) }, button.testTag("listen-action"), leading, enabled = !busy) { SlotLabel(Icons.Rounded.CloudDownload, "Prepare in TorBox") }
            shown != null -> ModeButton({ actions.listen(shown.recording) }, button.testTag("listen-action"), leading, enabled = !busy) { SlotLabel(Icons.Rounded.PlayArrow, "Listen") }
            !search.complete && tally.active > 0 -> ModeButton({}, button.testTag("listen-action"), leading, enabled = false) { WorkingLabel("Finding audio…") }
            else -> when (noMatchCopy(search, tally).kind) {
                NoMatchKind.POSSIBLE_ONLY -> ModeButton(actions.showSources, button, leading) { SlotLabel(Icons.Rounded.Search, "Review possible matches") }
                NoMatchKind.ALL_FAILED -> ModeButton(actions.searchAgain, button, leading) { SlotLabel(Icons.Rounded.Refresh, "Try again") }
                NoMatchKind.NEEDS_TORBOX -> ModeButton(actions.connectTorBox, button, leading) { SlotLabel(Icons.Rounded.Link, "Connect TorBox") }
                NoMatchKind.ALL_OFF -> ModeButton(actions.sourceSettings, button, leading) { SlotLabel(Icons.Rounded.Tune, "Open source settings") }
                else -> ModeButton(actions.searchAgain, button, leading) { SlotLabel(Icons.Rounded.Refresh, "Search again") }
            }
        }
        // The secondary row keeps its height while searching, so the page below never shifts when results land.
        FlowRow(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalArrangement = Arrangement.Center) {
            val count = tally.found + tally.possible
            when {
                picked != null && pinned.shown != null -> {
                    TextButton(useBest) { Text("Use the best match") }
                    TextButton({ actions.options(picked) }, Modifier.testTag("listening-options-action")) { Text("Format and download") }
                }
                shown != null -> {
                    TextButton(actions.showSources) { Text(if (search.complete) "Other choices · $count found" else "Other choices · $count so far") }
                    TextButton({ actions.options(shown.recording) }, Modifier.testTag("listening-options-action")) { Text("Format and download") }
                }
                search.complete && noMatchCopy(search, tally).kind == NoMatchKind.NEEDS_TORBOX && tally.active > 0 -> TextButton(actions.searchAgain) { Text("Search again") }
            }
        }
    }
}

@Composable
private fun BetterMatchFound(show: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.secondaryContainer)
        .clickable(onClickLabel = "Show the better match", role = Role.Button, onClick = show).heightIn(min = 48.dp).padding(horizontal = 12.dp)
        .semantics { liveRegion = LiveRegionMode.Polite }.testTag("better-match"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val content = MaterialTheme.colorScheme.onSecondaryContainer
        Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp), tint = content)
        Text("Better match found", style = MaterialTheme.typography.labelLarge, color = content, modifier = Modifier.weight(1f))
        Text("Show", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/** Roughly the height of a found best match, so the card doesn't grow when the first one arrives. */
@Composable
private fun CardSkeleton() {
    Column(Modifier.clearAndSetSemantics { }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SkeletonLine(.78f, 18.dp); SkeletonLine(.92f, 12.dp); SkeletonLine(.5f, 12.dp)
    }
}

@Composable
private fun SkeletonLine(fraction: Float, height: androidx.compose.ui.unit.Dp) {
    val pulse = if (animationsEnabled()) {
        val transition = rememberInfiniteTransition(label = "skeleton")
        transition.animateFloat(.06f, .14f, infiniteRepeatable(tween(900, easing = Motion.Emphasized), RepeatMode.Reverse), label = "skeleton").value
    } else .1f
    Box(Modifier.fillMaxWidth(fraction).height(height).clip(RoundedCornerShape(height / 2)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = pulse)))
}

/** A spinner, or a still glyph when animations are removed. */
@Composable
private fun Working(modifier: Modifier) {
    if (animationsEnabled()) CircularProgressIndicator(modifier, strokeWidth = 2.dp)
    else Icon(Icons.Rounded.HourglassTop, null, modifier, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A slot that is busy on the listener's behalf: a small spinner and what it is doing. */
@Composable
fun RowScope.WorkingLabel(text: String) {
    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text(text, maxLines = 1)
}

/** Provider names for a release's other finders; ids map to names when the provider is known. */
internal fun alsoFoundBy(search: StreamedSourceSearch, recordingId: String, providers: Map<String, SourceProvider>): List<String> =
    search.groups.firstNotNullOfOrNull { it.alsoFoundBy[recordingId] }.orEmpty().map { providers[it]?.name ?: it }.distinct()

/**
 * The sections under the best match: a heading with the search's progress, then one section per source in the
 * listener's priority order. [wide] panes put two sections side by side.
 */
fun LazyListScope.listeningSources(
    search: StreamedSourceSearch, tally: SearchTally, providers: Map<String, SourceProvider>, book: Audiobook, bestId: String?,
    connected: Boolean, wide: Boolean, announcement: String, actions: SourceActions,
) {
    item(key = "sources-heading") {
        Column(Modifier.animateItem().fillMaxWidth().testTag("listening-sources")) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(24.dp))
            Text("Listening sources", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(4.dp))
            Text(searchSummary(search, tally), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("sources-summary"))
            // Screen readers hear milestones only: searching, the first best match, and the end of the search.
            Box(Modifier.size(1.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = announcement })
        }
    }
    val groups = search.groups
    if (wide) groups.chunked(2).forEach { pair ->
        item(key = "sources:" + pair.joinToString("|") { it.providerId }) {
            Row(Modifier.animateItem().fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                pair.forEach { group -> SourceSection(group, search, providers, book, bestId, connected, actions, Modifier.weight(1f)) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    } else groups.forEach { group ->
        item(key = "source:${group.providerId}") { SourceSection(group, search, providers, book, bestId, connected, actions, Modifier.animateItem()) }
    }
}

private const val SHOWN_RELEASES = 3

@Composable
private fun SourceSection(group: SourceGroup, search: StreamedSourceSearch, providers: Map<String, SourceProvider>, book: Audiobook, bestId: String?,
                          connected: Boolean, actions: SourceActions, modifier: Modifier = Modifier) {
    val provider = providers[group.providerId]
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var showAll by rememberSaveable(group.providerId) { mutableStateOf(false) }
    var showPossible by rememberSaveable(group.providerId) { mutableStateOf(false) }
    val checking = group.status == SourceGroupStatus.CHECKING
    Column(modifier.fillMaxWidth().animateContentSize(tween(Motion.MEDIUM, easing = Motion.Emphasized)).testTag("source-section:${group.providerId}")) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val status = groupStatusLabel(group, provider, connected)
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(group.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            SectionStatus(group.status, status, group.recordings.isNotEmpty())
        }
        when (group.status) {
            SourceGroupStatus.SEARCHING, SourceGroupStatus.WAITING, SourceGroupStatus.CHECKING ->
                if (group.recordings.isEmpty() && group.possible.isEmpty() && group.status != SourceGroupStatus.WAITING) ReleaseSkeleton()
            SourceGroupStatus.FAILED -> {
                Text(group.message?.takeIf(String::isNotBlank) ?: "This source didn't answer.", style = MaterialTheme.typography.bodySmall, color = muted)
                TextButton({ actions.retry(group.providerId) }, Modifier.semantics { contentDescription = "Retry ${group.name}" }.testTag("retry:${group.providerId}")) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Retry")
                }
            }
            SourceGroupStatus.SKIPPED -> if (provider?.requiresTorBox == true && !connected && provider.enabled)
                Text("Connect TorBox to search ${group.name}.", style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(bottom = 12.dp))
            SourceGroupStatus.DONE -> Unit
        }
        val shown = if (showAll) group.recordings else group.recordings.take(SHOWN_RELEASES)
        shown.forEach { recording ->
            ReleaseRow(recording, book, recording.id == bestId, checking, alsoFoundBy(search, recording.id, providers)) { actions.open(recording) }
        }
        if (group.recordings.size > SHOWN_RELEASES) TextButton({ showAll = !showAll }) {
            Text(if (showAll) "Show fewer" else "Show all ${group.recordings.size} from ${group.name}")
        }
        if (group.possible.isNotEmpty()) {
            val turn by animateFloatAsState(if (showPossible) 180f else 0f, tween(Motion.MEDIUM, easing = Motion.Emphasized), label = "possible")
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClickLabel = if (showPossible) "Hide possible matches" else "Show possible matches") { showPossible = !showPossible }
                .heightIn(min = 48.dp).semantics { stateDescription = if (showPossible) "Expanded" else "Collapsed" }.testTag("possible:${group.providerId}"),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Possible matches · ${group.possible.size}", style = MaterialTheme.typography.labelLarge, color = muted, modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.ExpandMore, null, Modifier.rotate(turn), tint = muted)
            }
            AnimatedVisibility(showPossible, enter = expandVertically(tween(Motion.MEDIUM, easing = Motion.Emphasized)) + fadeIn(), exit = shrinkVertically(tween(Motion.MEDIUM, easing = Motion.Emphasized)) + fadeOut()) {
                Column {
                    Text("These might be this book. Check the release name and narrator before listening.", style = MaterialTheme.typography.bodySmall, color = muted)
                    group.possible.forEach { recording ->
                        ReleaseRow(recording, book, recording.id == bestId, checking, alsoFoundBy(search, recording.id, providers)) { actions.open(recording) }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Status at the section's edge; it crossfades in place, so a section changing state never reflows its heading. */
@Composable
private fun SectionStatus(status: SourceGroupStatus, label: String, found: Boolean) {
    AnimatedContent(status to label, transitionSpec = { fadeIn(tween(Motion.MEDIUM)).togetherWith(fadeOut(tween(Motion.SHORT))) }, label = "section status") { (state, text) ->
        val color = when {
            state == SourceGroupStatus.FAILED -> MaterialTheme.colorScheme.error
            state == SourceGroupStatus.DONE && found -> MaterialTheme.colorScheme.secondary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        val icon: ImageVector? = when (state) {
            SourceGroupStatus.DONE -> if (found) Icons.Rounded.CheckCircle else null
            SourceGroupStatus.FAILED -> Icons.Rounded.ErrorOutline
            SourceGroupStatus.WAITING -> Icons.Rounded.Schedule
            SourceGroupStatus.SKIPPED -> if (text == "Off in settings") Icons.Rounded.ToggleOff else Icons.Rounded.LinkOff
            else -> null
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (state == SourceGroupStatus.SEARCHING || state == SourceGroupStatus.CHECKING) Working(Modifier.size(14.dp))
            else icon?.let { Icon(it, null, Modifier.size(16.dp), tint = color) }
            Text(text, style = MaterialTheme.typography.labelMedium, color = color)
        }
    }
}

/** One release-row-sized placeholder while a source searches. */
@Composable
private fun ReleaseSkeleton() {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp).clearAndSetSemantics { }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SkeletonLine(.85f, 14.dp); SkeletonLine(.55f, 12.dp)
    }
}

/**
 * One release: its name, narrator and kind, then how it plays and its quality (format, size, language, seeders)
 * in one scannable line. Choosing it opens the recording's own page, as before.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReleaseRow(recording: Audiobook, book: Audiobook, best: Boolean, checking: Boolean, also: List<String>, open: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Open this recording", role = Role.Button, onClick = open)
        .heightIn(min = 48.dp).padding(vertical = 10.dp).testTag("release:${recording.id}"), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (best) Text("Best match", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(recording.releaseTitle.ifBlank { recording.title }, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(versionLabel(recording, book), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val unchecked = checking && recording.provider != "archive" && recording.cacheState == "unchecked"
                val ready = SourceQuality.ready(recording)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(when { unchecked -> Icons.Rounded.HourglassTop; recording.cacheState == "cached" -> Icons.Rounded.Bolt; recording.provider == "archive" -> Icons.Rounded.Headphones; else -> Icons.Rounded.CloudDownload },
                        null, Modifier.size(14.dp), tint = if (ready && !unchecked) MaterialTheme.colorScheme.primary else muted)
                    Text(if (unchecked) "Checking TorBox…" else availabilityLabel(recording), style = MaterialTheme.typography.labelMedium,
                        color = if (ready && !unchecked) MaterialTheme.colorScheme.primary else muted)
                }
                qualityLabels(recording).forEach { Text(it, style = MaterialTheme.typography.labelMedium, color = muted) }
            }
            if (also.isNotEmpty()) Text("Also found by ${also.joinToString()}", style = MaterialTheme.typography.labelSmall, color = muted)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = muted)
    }
}

/** Format and parts, size, and seeders: the facts that tell releases apart at a glance. */
internal fun qualityLabels(recording: Audiobook): List<String> {
    val sources = recording.sources
    val formats = when (sources.size) {
        0 -> ""
        1 -> sources[0].let { "${it.format} · ${it.parts.size} ${if (it.parts.size == 1) "file" else "parts"}" }
        else -> sources.joinToString(" / ") { it.format }
    }
    val bytes = recording.releaseSizeBytes.takeIf { it > 0 } ?: sources.maxOfOrNull { source -> source.parts.sumOf { it.sizeBytes } } ?: 0
    return listOfNotNull(formats.takeIf(String::isNotBlank), bytes.takeIf { it > 0 }?.let(::sizeLabel),
        recording.seeders.takeIf { it > 0 && recording.provider != "archive" }?.let { "$it ${if (it == 1L) "seeder" else "seeders"}" })
}

