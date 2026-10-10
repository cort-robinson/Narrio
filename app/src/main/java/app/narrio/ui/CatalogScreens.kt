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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
            OutlinedTextField(query, onValueChange = { vm.search(it) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Search books or authors") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                // While a typed query loads, the field itself says so; the last results stay readable below it.
                trailingIcon = { AnimatedVisibility(query.isNotEmpty(), enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                    IconButton(onClick = { vm.search("") }) {
                        Crossfade(catalog.loading, label = "searching") { searching ->
                            if (searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Close, "Clear search")
                        }
                    }
                } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                shape = RoundedCornerShape(14.dp))
            AnimatedVisibility(query.isBlank(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                LazyRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(AppleBooks.genres.keys.toList()) { FilterChip(category == it, { vm.search(cat = it) }, { Text(it) },
                        leadingIcon = { AnimatedVisibility(category == it, enter = expandHorizontally() + fadeIn(), exit = shrinkHorizontally() + fadeOut()) { Icon(Icons.Rounded.Check, null, Modifier.size(FilterChipDefaults.IconSize)) } }) }
                }
            }
        }
        catalog.notice?.let { item(key = "notice") { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        catalog.error?.let { error -> item(key = "error") { RecoveryState("Couldn't load book metadata", error, { vm.search() }) } }
        if (browse) {
            // The current book already lives in the mini-player; offer the thread only when it isn't playing.
            continueItem(shelf, formats)?.takeIf { (entry, place) -> place.mode == PositionOrigin.READING || entry.bookId != playing.book?.id }?.let { (current, place) -> item(key = "resume") {
                ResumeCard(current, place, { vm.continueBook(current) }, { vm.open(current.book()) }, Modifier.animateItem(fadeInSpec = androidx.compose.animation.core.tween(240, delayMillis = 160)))
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
            items(catalog.books, key = { it.id }) { book -> BookRow(book, { vm.open(book) }, Modifier.animateItem().staleWhile(catalog.loading)) }
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

/** The most recently moved shared place on the shelf, in whichever mode moved it. */
private fun continueItem(shelf: List<ShelfEntry>, formats: Map<String, BookFormats>): Pair<ShelfEntry, PlaceSummary>? = shelf
    .mapNotNull { entry -> formats[entry.bookId]?.let { book -> placeSummary(book)?.takeIf { book.available(it.mode) }?.let { place -> Triple(entry, place, book.position!!.updatedAtMs) } } }
    .maxByOrNull { it.third }?.let { it.first to it.second }

/** A warm return to the last story: where you were, how far along, and one tap to keep reading or listening. */
@Composable
private fun ResumeCard(entry: ShelfEntry, place: PlaceSummary, resume: () -> Unit, details: () -> Unit, modifier: Modifier = Modifier) {
    val book = remember(entry.bookJson) { entry.book() }
    val reading = place.mode == PositionOrigin.READING
    val interaction = remember { MutableInteractionSource() }
    Column(modifier) {
        Text(if (reading) "Continue reading" else "Continue listening", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(14.dp))
        Surface(modifier = Modifier.fillMaxWidth().pressScale(interaction, .98f), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer, onClick = details, interactionSource = interaction) {
            Column {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    BookCover(book, Modifier.width(64.dp).height(94.dp), sharedKey = "cover-${book.id}")
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(book.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(place.label, Modifier.semantics { contentDescription = spokenPlace(place.label) }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                    }
                    FilledIconButton(resume, Modifier.size(52.dp)) {
                        Icon(if (reading) Icons.AutoMirrored.Rounded.MenuBook else Icons.Rounded.PlayArrow, "${if (reading) "Continue reading" else "Resume"} ${book.title}", Modifier.size(if (reading) 24.dp else 28.dp))
                    }
                }
                place.fraction?.let { progress ->
                    LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().height(3.dp), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.outlineVariant, gapSize = 0.dp, drawStopIndicator = {})
                }
            }
        }
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
    var remove by remember { mutableStateOf<Audiobook?>(null) }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.importEbook(it) }
    val addEbook: () -> Unit = { vm.beginEbookImport(null); chooser.launch(arrayOf("application/epub+zip", "text/plain", "application/octet-stream")) }
    // Until formats arrive, a saved recording still counts as an audiobook.
    val formatsFor: (ShelfEntry) -> BookFormats = { entry -> formats[entry.bookId] ?: BookFormats(entry.bookId, audio = entry.sourceJson.isNotBlank()) }
    val visible = shelf.filter { filter.matches(formatsFor(it)) }
    LazyColumn(modifier.fillMaxSize().testTag("shelf"), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item(key = "heading") {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.Center, itemVerticalAlignment = Alignment.CenterVertically) {
                Text("My shelf", style = MaterialTheme.typography.displaySmall)
                TextButton(addEbook, enabled = !importing.working) { Icon(Icons.Rounded.UploadFile, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Add ebook") }
            }
            if (shelf.isNotEmpty()) ShelfFilters(filter, vm::setShelfFilter, Modifier.padding(top = 12.dp))
        }
        if (importing.working || importing.error != null) item(key = "import") { EbookImportStatus(importing, addEbook, vm::dismissEbookImport, Modifier.animateItem()) }
        if (shelf.isEmpty()) item(key = "empty") {
            Spacer(Modifier.height(30.dp))
            EmptyState("Nothing saved yet", "Save a book or start listening, or add an ebook file. Your books, progress, and bookmarks stay here on this device.", Icons.Rounded.AutoStories)
            Spacer(Modifier.height(20.dp))
            Button({ vm.navigate(0) }, Modifier.fillMaxWidth()) { Text("Discover books") }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(addEbook, Modifier.fillMaxWidth(), enabled = !importing.working) { Text("Add an EPUB or text file") }
        } else if (visible.isEmpty()) item(key = "empty-filter") {
            val (title, message) = emptyFilterCopy(filter)
            EmptyState(title, message, if (filter == ShelfFilter.EBOOKS) Icons.AutoMirrored.Rounded.MenuBook else Icons.Rounded.AutoStories)
            Spacer(Modifier.height(12.dp))
            if (filter == ShelfFilter.EBOOKS) OutlinedButton(addEbook, Modifier.fillMaxWidth(), enabled = !importing.working) { Text("Add an EPUB or text file") }
            TextButton({ vm.setShelfFilter(ShelfFilter.ALL) }, Modifier.fillMaxWidth()) { Text("Show all ${shelf.size} ${if (shelf.size == 1) "book" else "books"}") }
        }
        items(visible, key = { it.bookId }) { entry ->
            val book = remember(entry.bookJson) { entry.book() }
            Column(Modifier.animateItem()) {
                ShelfRow(entry, book, formatsFor(entry), playing.book?.id == entry.bookId && playing.playing, { vm.continueBook(entry) }, { vm.open(book) }) { remove = book }
                downloads.filter { it.book.id == entry.bookId }.forEach { download -> Spacer(Modifier.height(14.dp)); OfflineStatus(vm, download) }
                Spacer(Modifier.height(20.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
    remove?.let { book -> AlertDialog(onDismissRequest = { remove = null }, title = { Text("Remove from your shelf?") },
        text = { Text("This removes phone downloads, ebooks, saved progress, and bookmarks for ${book.title} on this device.") },
        confirmButton = { TextButton({ vm.remove(book); remove = null }) { Text("Remove") } }, dismissButton = { TextButton({ remove = null }) { Text("Keep book") } }) }
}

@Composable
private fun ShelfRow(entry: ShelfEntry, book: Audiobook, formats: BookFormats, nowPlaying: Boolean, resume: () -> Unit, details: () -> Unit, removeBook: () -> Unit) {
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
                    DropdownMenuItem(text = { Text("Remove from shelf") }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) }, onClick = { menu = false; removeBook() })
                }
            }
        }
    }
    placeSummary(formats)?.let { place ->
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
