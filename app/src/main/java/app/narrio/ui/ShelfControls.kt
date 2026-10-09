package app.narrio.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.narrio.data.ShelfEntry
import app.narrio.domain.Audiobook

/** Shelves larger than this get a search field; smaller ones fit on a screen or two. */
const val SHELF_SEARCH_AT = 8

/** Shelf rows with decoded books, reusing a book until its saved metadata changes (progress saves don't). */
@Composable
fun rememberShelfItems(shelf: List<ShelfEntry>, formats: Map<String, BookFormats>): List<ShelfItem> {
    val books = remember { HashMap<String, Pair<String, Audiobook>>() }
    return remember(shelf, formats) {
        // Until formats arrive, a saved recording still counts as an audiobook.
        shelf.map { entry ->
            val book = books[entry.bookId]?.takeIf { it.first == entry.bookJson }?.second ?: entry.book().also { books[entry.bookId] = entry.bookJson to it }
            ShelfItem(entry, book, formats[entry.bookId] ?: BookFormats(entry.bookId, audio = entry.sourceJson.isNotBlank()))
        }
    }
}

@Composable
fun ShelfSearchField(query: String, change: (String) -> Unit, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    OutlinedTextField(query, change, modifier.fillMaxWidth().testTag("shelf-search"), singleLine = true,
        placeholder = { Text("Search your shelf") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = { if (query.isNotEmpty()) IconButton({ change("") }) { Icon(Icons.Rounded.Close, "Clear shelf search") } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        shape = RoundedCornerShape(14.dp))
}

/** How many books are shown, and the order they're in. The count is announced as a search narrows the shelf. */
@Composable
fun ShelfSortBar(shown: Int, total: Int, sort: ShelfSort, choose: (ShelfSort) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(if (shown == total) "$total ${books(total)}" else "$shown of $total ${books(total)}", Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            TextButton({ open = true }, Modifier.semantics { contentDescription = "Sort shelf by ${sort.label}" }.testTag("shelf-sort")) {
                Icon(Icons.AutoMirrored.Rounded.Sort, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(sort.label)
            }
            DropdownMenu(open, { open = false }) {
                ShelfSort.entries.forEach { option ->
                    DropdownMenuItem(text = { Text(option.label) }, onClick = { open = false; choose(option) },
                        modifier = Modifier.semantics { selected = option == sort },
                        trailingIcon = { if (option == sort) Icon(Icons.Rounded.Check, null) })
                }
            }
        }
    }
}

private fun books(count: Int) = if (count == 1) "book" else "books"

/** The Finished section's heading; it folds the section away unless [collapsible] is false (nothing else to show). */
@Composable
fun FinishedHeader(count: Int, open: Boolean, collapsible: Boolean, toggle: () -> Unit, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(Motion.MEDIUM, easing = Motion.Emphasized), label = "finished")
    val action = if (collapsible) Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClickLabel = if (open) "Hide finished books" else "Show finished books", onClick = toggle)
        .semantics { stateDescription = if (open) "Expanded" else "Collapsed" } else Modifier
    Row(modifier.fillMaxWidth().then(action).heightIn(min = 48.dp).semantics(mergeDescendants = true) { heading() }.testTag("finished-section"),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.TaskAlt, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.width(10.dp))
        Text("Finished · $count", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        if (collapsible) Icon(Icons.Rounded.ExpandMore, null, Modifier.rotate(turn), tint = muted)
    }
}

/** Takes a finished row's progress line: the book is done, whatever its last place said. */
@Composable
fun FinishedMark(modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.TaskAlt, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary)
        Text("Finished", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
