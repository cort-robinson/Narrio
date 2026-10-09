package app.narrio.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.narrio.domain.Audiobook

/*
 * The ebook sheet's simple layer and its Advanced section: one flat list of other choices with plain notes, and
 * searching every ebook source again with the reader's own words. Wording comes from EbookResultsModel.kt.
 */

/** A full-width row that shows or hides what follows it, such as Other choices or Advanced. */
@Composable
internal fun ExpandRow(label: String, expanded: Boolean, toggle: () -> Unit, modifier: Modifier = Modifier, lead: String? = null) {
    val turn by animateFloatAsState(if (expanded) 180f else 0f, tween(Motion.MEDIUM, easing = Motion.Emphasized), label = "expand")
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClickLabel = if (expanded) "Hide" else "Show", onClick = toggle)
        .heightIn(min = 48.dp).semantics(mergeDescendants = true) { stateDescription = if (expanded) "Expanded" else "Collapsed" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            lead?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = muted) }
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Icon(Icons.Rounded.ExpandMore, null, Modifier.rotate(turn), tint = MaterialTheme.colorScheme.primary)
    }
}

/** One other choice: what it is in plain words, its note, and where it's from in small text. Tapping adds it. */
@Composable
internal fun EbookChoiceRow(choice: EbookChoice, book: Audiobook, recording: Boolean, adding: Boolean, step: String, enabled: Boolean, add: () -> Unit,
                            modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val note = ebookChoiceNote(choice, recording)
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClickLabel = "Add this ebook", onClick = add).heightIn(min = 48.dp)
        .padding(vertical = 8.dp).testTag("ebook-choice:${choice.edition.id}"), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(choice.edition.title, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            ebookChoiceDetail(choice, book).takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = muted) }
            note?.let { ChoiceNoteLine(it) }
            Text(choice.source, style = MaterialTheme.typography.labelSmall, color = muted)
            if (adding && step.isNotBlank()) Text(step, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall, color = muted)
        }
        if (adding) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else Icon(Icons.Rounded.Add, null, tint = if (enabled) MaterialTheme.colorScheme.primary else muted)
    }
}

@Composable
private fun ChoiceNoteLine(note: ChoiceNote) {
    val (icon, tint) = when (note.kind) {
        ChoiceNoteKind.LIKELY -> Icons.Rounded.Headphones to MaterialTheme.colorScheme.secondary
        ChoiceNoteKind.CAUTION, ChoiceNoteKind.CHECK -> Icons.Rounded.Info to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(14.dp), tint = tint)
        Text(note.text, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

/** On the simple layer when the reader's own words are in use, so different results never look unexplained. */
@Composable
internal fun CustomWordsLine(words: String, busy: Boolean, reset: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().testTag("ebook-custom-words"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Rounded.Search, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Searching for “$words”", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        TextButton(reset, enabled = !busy) { Text("Use book details") }
    }
}

/**
 * Search every ebook source with different words: UK/US titles, subtitles, series names, translated titles, or another
 * spelling of the author. The words are kept for this book, including retries, until reset.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EbookSearchWords(book: Audiobook, words: String, busy: Boolean, search: (String) -> Unit, modifier: Modifier = Modifier) {
    val suggestions = remember(book.title, book.author, book.description) { ebookWordSuggestions(book) }
    var typed by rememberSaveable(book.id, words) { mutableStateOf(words.ifBlank { suggestions.bookDetails }) }
    val focus = LocalFocusManager.current
    fun run(value: String) { if (value.isNotBlank()) { focus.clearFocus(); search(value) } }
    Column(modifier.fillMaxWidth().testTag("ebook-search-words"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Search with different words", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text("Try another title, a series name, a translated title, or another spelling of the author. Every ebook source searches again.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(typed, { typed = it.take(200) }, Modifier.fillMaxWidth().testTag("ebook-search-words-field"), enabled = !busy, singleLine = true,
            label = { Text("Search words") },
            trailingIcon = { if (typed.isNotEmpty()) IconButton({ typed = "" }, enabled = !busy) { Icon(Icons.Rounded.Close, "Clear search words") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { run(typed) }))
        if (suggestions.choices.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            suggestions.choices.forEach { choice ->
                SuggestionChip({ typed = choice.words; run(choice.words) }, { Text(choice.label) }, enabled = !busy,
                    modifier = Modifier.semantics { contentDescription = "${choice.label}: search for ${choice.words}" })
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
            Button({ run(typed) }, Modifier.heightIn(min = 48.dp).testTag("ebook-search-words-action"), enabled = !busy && typed.isNotBlank()) {
                Icon(Icons.Rounded.Search, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Search")
            }
            if (words.isNotBlank()) TextButton({ typed = suggestions.bookDetails; search("") }, Modifier.heightIn(min = 48.dp), enabled = !busy) { Text("Reset to book details") }
        }
    }
}
