package app.narrio.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.narrio.data.AppleBooks
import app.narrio.data.ShelfEntry
import app.narrio.domain.*

@Composable
fun DiscoverScreen(vm: NarrioViewModel, modifier: Modifier = Modifier) {
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val category by vm.category.collectAsStateWithLifecycle()
    val shelf by vm.shelf.collectAsStateWithLifecycle()
    val formats by vm.shelfFormats.collectAsStateWithLifecycle()
    val playing by vm.playback.collectAsStateWithLifecycle()
    val connected by vm.connected.collectAsStateWithLifecycle()
    val torBoxNudge = rememberTorBoxNudge(vm.graph.preferences)
    val recent by vm.recentSearches.collectAsStateWithLifecycle()
    var searchFocused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val browse = query.isBlank() && category == "All"
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item(key = "heading") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                NarrioMark(); Spacer(Modifier.width(10.dp))
                Text("narrio", style = MaterialTheme.typography.headlineMedium)
            }
        }
        item(key = "search") {
            OutlinedTextField(query, onValueChange = { vm.search(it) }, modifier = Modifier.fillMaxWidth().onFocusChanged { searchFocused = it.isFocused }.testTag("discover-search"), singleLine = true,
                placeholder = { Text("Search books or authors") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                // While a typed query loads, the field itself says so; the last results stay readable below it.
                trailingIcon = { AnimatedVisibility(query.isNotEmpty(), enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                    IconButton(onClick = { vm.search("") }) {
                        Crossfade(catalog.loading, label = "searching") { searching ->
                            if (searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Close, "Clear search")
                        }
                    }
                } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { vm.rememberSearch(); focus.clearFocus() }),
                shape = RoundedCornerShape(14.dp))
            AnimatedVisibility(searchFocused && query.isEmpty() && recent.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                RecentSearchList(recent, { vm.search(it); vm.rememberSearch(it); focus.clearFocus() }, vm::forgetSearch, vm::clearRecentSearches, Modifier.padding(top = 8.dp))
            }
            AnimatedVisibility(query.isBlank(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                LazyRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(AppleBooks.genres.keys.toList()) { FilterChip(category == it, { vm.search(cat = it) }, { Text(it) },
                        leadingIcon = { AnimatedVisibility(category == it, enter = expandHorizontally() + fadeIn(), exit = shrinkHorizontally() + fadeOut()) { Icon(Icons.Rounded.Check, null, Modifier.size(FilterChipDefaults.IconSize)) } }) }
                }
            }
        }
        if (query.isBlank() && torBoxNudge.visible(connected)) item(key = "torbox-nudge") { TorBoxNudgeCard(vm::requestTorBoxConnect, torBoxNudge::dismiss, Modifier.animateItem()) }
        catalog.notice?.let { item(key = "notice") { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        catalog.error?.let { error -> item(key = "error") { RecoveryState("Couldn't load book metadata", error, { vm.search() }) } }
        if (browse) {
            // A book already in the mini-player stays off the row unless reading it is its own thread.
            continueItems(shelf, formats, playing.book?.id).takeIf { it.isNotEmpty() }?.let { going -> item(key = "resume") {
                ContinueRow(going, vm::continueBook, { vm.open(it.book()) }, Modifier.animateItem(fadeInSpec = androidx.compose.animation.core.tween(240, delayMillis = 160)))
            } }
        }
        if (catalog.loading && catalog.books.isEmpty()) {
            items(4, key = { "skeleton$it" }) { SkeletonBookRow(Modifier.animateItem()) }
        } else if (catalog.books.isNotEmpty()) {
            if (query.isBlank()) item(key = "browse-title") {
                Text(browseTitle(category), Modifier.animateItem(), style = MaterialTheme.typography.headlineSmall)
            }
            if (query.isNotBlank()) item(key = "results-title") {
                AnimatedContent(if (catalog.loading) "Searching…" else "${catalog.books.size} ${if (catalog.books.size == 1) "book" else "books"}",
                    transitionSpec = { fadeIn().togetherWith(fadeOut()) }, label = "results title") {
                    Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(catalog.books, key = { it.id }) { book -> BookRow(book, { vm.rememberSearch(); vm.open(book) }, Modifier.animateItem().staleWhile(catalog.loading)) }
        } else if (!catalog.loading && catalog.error == null) item(key = "empty") {
            EmptyState("No books found", "Try another title or author, or clear the category.", Icons.Rounded.Search)
        }
    }
}

/** Names what the browse list holds, including what the playful "Wonder" category covers. */
internal fun browseTitle(category: String) = when (category) {
    "Fiction" -> "Popular fiction"
    "Thriller & mystery" -> "Popular thrillers & mysteries"
    "Horror" -> "Popular horror"
    "Wonder" -> "Popular fantasy & science fiction"
    "Romance" -> "Popular romance"
    "Comedy" -> "Popular comedy"
    "Classics" -> "Popular classics"
    "Kids & teens" -> "Popular with kids & teens"
    "Nonfiction" -> "Popular nonfiction"
    "Biography" -> "Popular biographies & memoirs"
    "History" -> "Popular history"
    "Science" -> "Popular science & nature"
    "Self-help" -> "Popular self-help"
    "Business" -> "Popular business & money"
    "Travel" -> "Popular travel & adventure"
    else -> "Popular audiobooks"
}

@Composable
fun BookRow(book: Audiobook, open: () -> Unit, modifier: Modifier = Modifier, recording: Boolean = true, extra: (@Composable ColumnScope.() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    val interaction = remember { MutableInteractionSource() }
    Row(modifier.fillMaxWidth().pressScale(interaction, .985f).clip(RoundedCornerShape(12.dp)).clickable(interaction, LocalIndication.current, onClick = open),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        BookCover(book, Modifier.width(76.dp).height(112.dp), sharedKey = "cover-${book.id}")
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(book.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // An ebook-only book has no narration or delivery to describe.
            if (recording) {
                if (book.provider == "catalog") Text(providerLabel(book), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                else Text(narrationLabel(book), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (recording && book.durationMs > 0) Text(durationLabel(book.durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (recording && book.provider != "catalog" && (book.provider != "archive" || book.cacheState != "unchecked")) Text(if (book.cacheState == "cached") "Ready in TorBox · ${book.cachedFormats.joinToString(" / ")}" else "${providerLabel(book)} · ${if (book.cacheState == "uncached") "Not cached" else "Cache not checked"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            extra?.invoke(this)
        }
        trailing?.invoke()
    }
}

@Composable
fun LibraryScreen(vm: NarrioViewModel, modifier: Modifier = Modifier) {
    val shelf by vm.shelf.collectAsStateWithLifecycle()
    val formats by vm.shelfFormats.collectAsStateWithLifecycle()
    val filter by vm.shelfFilter.collectAsStateWithLifecycle()
    val importing by vm.ebookImport.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val playing by vm.playback.collectAsStateWithLifecycle()
    val sort by vm.shelfSort.collectAsStateWithLifecycle()
    val typed by vm.shelfQuery.collectAsStateWithLifecycle()
    var remove by remember { mutableStateOf<Audiobook?>(null) }
    var showFinished by rememberSaveable { mutableStateOf(false) }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.importEbook(it) }
    val addEbook: () -> Unit = { vm.beginEbookImport(null); chooser.launch(arrayOf("application/epub+zip", "text/plain", "application/octet-stream")) }
    val items = rememberShelfItems(shelf, formats)
    // Search appears once the shelf outgrows a screen or two; a leftover query stays visible so it can be cleared.
    val searchable = shelf.size > SHELF_SEARCH_AT || typed.isNotEmpty()
    val query = if (searchable) typed.trim() else ""
    val visible = remember(items, filter, query, sort) { sortShelf(items.filter { filter.matches(it.formats) && matchesShelfQuery(it.book, query) }, sort) }
    val (finished, going) = remember(visible) { visible.partition { it.finished } }
    // Finished books fold away under their own heading, unless they're all that's left to show.
    val finishedCollapsible = going.isNotEmpty() && query.isEmpty()
    val finishedOpen = showFinished || !finishedCollapsible
    LazyColumn(modifier.fillMaxSize().testTag("shelf"), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item(key = "heading") {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.Center, itemVerticalAlignment = Alignment.CenterVertically) {
                Text("My shelf", style = MaterialTheme.typography.displaySmall)
                TextButton(addEbook, enabled = !importing.working) { Icon(Icons.Rounded.UploadFile, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Add ebook") }
            }
            if (shelf.isNotEmpty()) ShelfFilters(filter, vm::setShelfFilter, Modifier.padding(top = 12.dp))
            if (searchable) ShelfSearchField(typed, vm::setShelfQuery, Modifier.padding(top = 16.dp))
            if (shelf.size > 1) ShelfSortBar(visible.size, shelf.size, sort, vm::setShelfSort, Modifier.padding(top = 8.dp))
        }
        if (importing.working || importing.error != null) item(key = "import") { EbookImportStatus(importing, addEbook, vm::dismissEbookImport, Modifier.animateItem()) }
        if (shelf.isEmpty()) item(key = "empty") {
            Spacer(Modifier.height(30.dp))
            EmptyState("Nothing saved yet", "Save a book or start listening, or add an ebook file. Your books, progress, and bookmarks stay here on this device.", Icons.Rounded.AutoStories)
            Spacer(Modifier.height(20.dp))
            Button({ vm.navigate(0) }, Modifier.fillMaxWidth()) { Text("Discover books") }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(addEbook, Modifier.fillMaxWidth(), enabled = !importing.working) { Text("Add an EPUB or text file") }
        } else if (visible.isEmpty() && query.isNotEmpty()) item(key = "empty-search") {
            EmptyState("No books match", "Nothing on your shelf has “$query” in its title or author${if (filter == ShelfFilter.ALL) "" else " under ${filter.label}"}.", Icons.Rounded.SearchOff)
            Spacer(Modifier.height(12.dp))
            TextButton({ vm.setShelfQuery(""); vm.setShelfFilter(ShelfFilter.ALL) }, Modifier.fillMaxWidth()) { Text("Show all ${shelf.size} ${if (shelf.size == 1) "book" else "books"}") }
        } else if (visible.isEmpty()) item(key = "empty-filter") {
            val (title, message) = emptyFilterCopy(filter)
            EmptyState(title, message, if (filter == ShelfFilter.EBOOKS) Icons.AutoMirrored.Rounded.MenuBook else Icons.Rounded.AutoStories)
            Spacer(Modifier.height(12.dp))
            if (filter == ShelfFilter.EBOOKS) OutlinedButton(addEbook, Modifier.fillMaxWidth(), enabled = !importing.working) { Text("Add an EPUB or text file") }
            TextButton({ vm.setShelfFilter(ShelfFilter.ALL) }, Modifier.fillMaxWidth()) { Text("Show all ${shelf.size} ${if (shelf.size == 1) "book" else "books"}") }
        }
        val row: @Composable LazyItemScope.(ShelfItem) -> Unit = { (entry, book, bookFormats) ->
            Column(Modifier.animateItem()) {
                ShelfRow(entry, book, bookFormats, playing.book?.id == entry.bookId && playing.playing, { vm.continueBook(entry) }, { vm.open(book) },
                    { vm.setFinished(entry.bookId, it) }) { remove = book }
                downloads.filter { it.book.id == entry.bookId }.forEach { download -> Spacer(Modifier.height(14.dp)); OfflineStatus(vm, download) }
                Spacer(Modifier.height(20.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        items(going, key = { it.entry.bookId }, itemContent = row)
        if (finished.isNotEmpty()) {
            item(key = "finished-header") { FinishedHeader(finished.size, finishedOpen, finishedCollapsible, { showFinished = !showFinished }, Modifier.animateItem()) }
            if (finishedOpen) items(finished, key = { it.entry.bookId }, itemContent = row)
        }
    }
    remove?.let { book -> AlertDialog(onDismissRequest = { remove = null }, title = { Text("Remove from your shelf?") },
        text = { Text("This removes phone downloads, ebooks, saved progress, and bookmarks for ${book.title} on this device.") },
        confirmButton = { TextButton({ vm.remove(book); remove = null }) { Text("Remove") } }, dismissButton = { TextButton({ remove = null }) { Text("Keep book") } }) }
}

@Composable
private fun ShelfRow(entry: ShelfEntry, book: Audiobook, formats: BookFormats, nowPlaying: Boolean, resume: () -> Unit, details: () -> Unit, setFinished: (Boolean) -> Unit, removeBook: () -> Unit) {
    val finished = entry.finishedAt > 0
    val mode = formats.leadingMode
    var menu by remember { mutableStateOf(false) }
    BookRow(book, details, recording = formats.audio, extra = { FormatMarks(formats, Modifier.padding(top = 2.dp)) }) {
        // Continue and the overflow sit together at the row's edge, so a shelf entry is one line of controls.
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                mode == PositionOrigin.READING -> IconButton(resume) {
                    Icon(Icons.AutoMirrored.Rounded.MenuBook, "${if (formats.lastMode == PositionOrigin.READING) "Continue reading" else "Read"} ${book.title}", tint = MaterialTheme.colorScheme.primary)
                }
                entry.sourceJson.isNotBlank() -> IconButton(resume) {
                    Crossfade(nowPlaying, label = "now playing") { live ->
                        if (live) NarrationPulse(true, Modifier.semantics { contentDescription = "Now playing ${book.title}" })
                        else Icon(Icons.Rounded.PlayArrow, "Resume ${book.title}", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Box {
                IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "More for ${book.title}") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(if (finished) "Mark as not finished" else "Mark as finished") },
                        leadingIcon = { Icon(if (finished) Icons.Rounded.RemoveDone else Icons.Rounded.TaskAlt, null) }, onClick = { menu = false; setFinished(!finished) })
                    DropdownMenuItem(text = { Text("Remove from shelf") }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) }, onClick = { menu = false; removeBook() })
                }
            }
        }
    }
    if (finished) {
        Spacer(Modifier.height(12.dp))
        FinishedMark()
    } else placeSummary(formats)?.let { place ->
        Spacer(Modifier.height(12.dp))
        ShelfProgress(place)
    }
    shelfPreparationLabel(entry.state)?.let { label ->
        Spacer(Modifier.height(12.dp))
        Text(label, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.labelMedium,
            color = if (entry.state == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun RecoveryState(title: String, message: String, retry: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(14.dp)).padding(20.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp)); Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(retry) { Text("Try again") }
    }
}

@Composable
fun EmptyState(title: String, message: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(20.dp)); Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp)); Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
