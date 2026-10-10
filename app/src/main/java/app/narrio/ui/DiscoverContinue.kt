package app.narrio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.narrio.data.ShelfEntry
import app.narrio.domain.PositionOrigin

/**
 * Books in progress, newest place first. One book gets the full-width card; several scroll sideways as equal
 * compact cards, the next one peeking in so the row reads as more than one.
 */
@Composable
fun ContinueRow(items: List<ContinueItem>, resume: (ShelfEntry) -> Unit, details: (ShelfEntry) -> Unit, modifier: Modifier = Modifier, edge: Dp = 24.dp) {
    if (items.isEmpty()) return
    Column(modifier.testTag("continue")) {
        Text(continueHeading(items), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(14.dp))
        if (items.size == 1) items.single().let { ContinueCard(it, false, { resume(it.entry) }, { details(it.entry) }, Modifier.fillMaxWidth()) }
        else BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cardWidth = (maxWidth * .86f).coerceAtMost(340.dp)
            // The row scrolls under the page inset to the screen edge, while its first card lines up with the page.
            Row(Modifier.bleed(edge).horizontalScroll(rememberScrollState()).padding(horizontal = edge).testTag("continue-row"),
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items.forEach { item -> ContinueCard(item, true, { resume(item.entry) }, { details(item.entry) }, Modifier.width(cardWidth)) }
            }
        }
    }
}

/** Widens a child by [edge] on each side, so it can draw into the parent's padding. */
private fun Modifier.bleed(edge: Dp) = layout { measurable, constraints ->
    val extra = edge.roundToPx()
    val placeable = measurable.measure(if (constraints.hasBoundedWidth) constraints.copy(minWidth = constraints.maxWidth + extra * 2, maxWidth = constraints.maxWidth + extra * 2) else constraints)
    layout(if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width, placeable.height) { placeable.place(-extra, 0) }
}

/**
 * Where you were in one book, how far along, and one tap to keep reading or listening. Compact cards keep two title
 * lines and the progress track even when empty, so cards side by side share one height at any text size.
 */
@Composable
private fun ContinueCard(item: ContinueItem, compact: Boolean, resume: () -> Unit, details: () -> Unit, modifier: Modifier = Modifier) {
    val book = remember(item.entry.bookJson) { item.entry.book() }
    val place = item.place
    val reading = place.mode == PositionOrigin.READING
    val interaction = remember { MutableInteractionSource() }
    Surface(modifier = modifier.pressScale(interaction, .98f), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer, onClick = details, interactionSource = interaction) {
        Column {
            Row(Modifier.padding(if (compact) 14.dp else 16.dp), horizontalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 16.dp), verticalAlignment = Alignment.CenterVertically) {
                BookCover(book, if (compact) Modifier.width(56.dp).height(82.dp) else Modifier.width(64.dp).height(94.dp), sharedKey = "cover-${book.id}")
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(book.title, style = MaterialTheme.typography.titleMedium, minLines = if (compact) 2 else 1, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(book.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(place.label, Modifier.semantics { contentDescription = spokenPlace(place.label) }, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                FilledIconButton(resume, Modifier.size(if (compact) 48.dp else 52.dp)) {
                    Icon(if (reading) Icons.AutoMirrored.Rounded.MenuBook else Icons.Rounded.PlayArrow, "${if (reading) "Continue reading" else "Resume"} ${book.title}",
                        Modifier.size(if (reading) 24.dp else 28.dp))
                }
            }
            val progress = place.fraction
            if (progress != null) LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().height(3.dp), color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outlineVariant, gapSize = 0.dp, drawStopIndicator = {})
            else if (compact) Spacer(Modifier.height(3.dp))
        }
    }
}

/** Up to six recent searches under the empty, focused search field: tap to search again, or forget one or all. */
@Composable
fun RecentSearchList(recent: List<String>, pick: (String) -> Unit, remove: (String) -> Unit, clear: () -> Unit, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.fillMaxWidth().testTag("recent-searches")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Recent searches", Modifier.weight(1f).padding(start = 4.dp).semantics { heading() }, style = MaterialTheme.typography.labelLarge, color = muted)
            TextButton(clear, Modifier.semantics { contentDescription = "Clear all recent searches" }) { Text("Clear all") }
        }
        recent.forEach { query ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClickLabel = "Search again") { pick(query) }
                .heightIn(min = 48.dp).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.History, null, Modifier.size(20.dp), tint = muted)
                Spacer(Modifier.width(14.dp))
                Text(query, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton({ remove(query) }) { Icon(Icons.Rounded.Close, "Remove $query from recent searches", Modifier.size(20.dp), tint = muted) }
            }
        }
    }
}
