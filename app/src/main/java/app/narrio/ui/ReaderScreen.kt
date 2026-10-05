package app.narrio.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.narrio.MainActivity
import app.narrio.domain.*
import app.narrio.reader.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.use
import kotlin.math.roundToInt

/** The full reader for one book. The page is the room; controls appear on a centre tap and leave again. */
@Composable
fun ReaderScreen(appVm: NarrioViewModel, bookId: String, onClose: () -> Unit) {
    val vm: ReaderViewModel = viewModel(key = "reader:$bookId", factory = ReaderViewModel.factory(bookId))
    val state by vm.state.collectAsStateWithLifecycle()
    val startJump by vm.startJump.collectAsStateWithLifecycle()
    LaunchedEffect(startJump, state) {
        val jump = startJump ?: return@LaunchedEffect
        val session = (state as? ReaderState.Ready)?.session ?: return@LaunchedEffect
        val destination = jump.destination ?: return@LaunchedEffect
        val navigationVersion = session.navigationVersion
        appVm.announceJump(PositionJump(bookId, jump.from ?: PositionOrigin.LISTENING, readingPlace(destination, null).label, jump.confidence) {
            jump.previous?.let { previous -> session.controller.stopFollowing(); appVm.undoReadingJump(session, previous, navigationVersion) }
        })
        vm.clearStartJump()
    }
    DisposableEffect(vm) { vm.ensureOpen(); onDispose { vm.release() } }
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("reader")) {
        when (val current = state) {
            is ReaderState.Ready -> ReaderRoom(appVm, vm, current.session, onClose)
            is ReaderState.Failed -> Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
                RecoveryState("This book couldn't be opened", current.message) { vm.open() }
                TextButton(onClose, Modifier.padding(top = 8.dp)) { Text("Close the reader") }
            }
            else -> OpeningBook()
        }
    }
}

@Composable
private fun OpeningBook() {
    Column(Modifier.fillMaxSize().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
        Spacer(Modifier.height(16.dp))
        Text("Opening the book", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ReaderRoom(appVm: NarrioViewModel, vm: ReaderViewModel, session: ReaderSession, onClose: () -> Unit) {
    val controller = session.controller
    val book = session.book
    val settings by vm.settings.collectAsStateWithLifecycle()
    val appearance by appVm.appearance.collectAsStateWithLifecycle()
    val playback by appVm.playback.collectAsStateWithLifecycle()
    val location by session.location.collectAsStateWithLifecycle()
    val ready by controller.ready.collectAsStateWithLifecycle()
    val returnPoint by controller.returnPoint.collectAsStateWithLifecycle()
    val layout by book.layout.collectAsStateWithLifecycle()
    val contents by book.contents.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = LocalActivity.current
    var controls by rememberSaveable { mutableStateOf(false) }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var footnote by remember { mutableStateOf<ReaderEvent.Footnote?>(null) }
    var image by remember { mutableStateOf<Content.ImageElement?>(null) }
    var outsideLink by remember { mutableStateOf<AbsoluteUrl?>(null) }

    // Page colours follow the target Appearance scheme, not the 450 ms dissolve, so the book relays out once.
    val dark = appearance.mode.isDark(androidx.compose.foundation.isSystemInDarkTheme())
    val scheme = remember(appearance, dark) { colourSchemeFor(appearance, dark) }
    val colors = ReaderColors(scheme.background.toArgb(), scheme.onBackground.toArgb(), dark)

    LaunchedEffect(controller) {
        controller.events.collect { event ->
            when (event) {
                ReaderEvent.ToggleControls -> controls = !controls
                is ReaderEvent.Footnote -> footnote = event
                is ReaderEvent.Image -> image = event.element
                is ReaderEvent.ExternalLink -> outsideLink = event.url
                is ReaderEvent.Moved -> if (!event.jump) controls = false
                else -> Unit
            }
        }
    }
    // Immersive while reading: system bars return with the controls.
    if (activity != null) DisposableEffect(controls) {
        val insets = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (controls) insets.show(WindowInsetsCompat.Type.systemBars()) else insets.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { insets.show(WindowInsetsCompat.Type.systemBars()) }
    }
    // Volume keys turn pages, except while something is playing: then they still control the volume.
    val volumeTurns = settings.volumeKeys && !playback.playing
    DisposableEffect(activity, volumeTurns) {
        val host = activity as? MainActivity
        host?.keyInterceptor = { event ->
            val forward = event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
            if (volumeTurns && (forward || event.keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
                if (event.action == KeyEvent.ACTION_DOWN) { if (forward) controller.next() else controller.previous() }
                true
            } else false
        }
        onDispose { host?.keyInterceptor = null }
    }

    BoxWithConstraints(Modifier.fillMaxSize().semantics {
        customActions = listOf(
            CustomAccessibilityAction(if (controls) "Hide reader controls" else "Show reader controls") { controls = !controls; true },
            CustomAccessibilityAction("Next page") { controller.next(); true },
            CustomAccessibilityAction("Previous page") { controller.previous(); true },
        )
    }) {
        // Unfolded in landscape (or any similarly wide and tall window), pages sit side by side like an open book.
        // Height separates an unfolded inner display (about 830 x 690 dp) from a phone on its side (about 915 x 410 dp).
        val spread = maxWidth > maxHeight && maxWidth >= 720.dp && maxHeight >= 560.dp
        val preferences = remember(settings, colors, spread) { settings.toEpubPreferences(colors, spread) }
        val top = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding()
        val bottom = WindowInsets.navigationBarsIgnoringVisibility.asPaddingValues().calculateBottomPadding()
        val margin = if (spread) 40.dp else 0.dp
        EpubReaderView(controller, preferences, spread, Modifier.fillMaxSize()
            .padding(top = top + 36.dp, bottom = bottom + 34.dp, start = margin, end = margin)
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
            .testTag("reader-page"))

        // Running head and folio, like a printed page; they give way to the controls.
        val quiet = MaterialTheme.colorScheme.onSurfaceVariant
        AnimatedVisibility(!controls && ready, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
            Text(location.chapter ?: book.title, style = MaterialTheme.typography.labelSmall, color = quiet, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = top + 12.dp).padding(horizontal = 48.dp))
        }
        AnimatedVisibility(!controls && ready && layout != null, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
            Row(Modifier.fillMaxWidth().padding(bottom = bottom + 10.dp).padding(horizontal = 28.dp + margin).semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(folioPlace(location), style = MaterialTheme.typography.labelSmall, color = quiet)
                timeLeft(location)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = quiet) }
            }
        }
        if (!ready) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { OpeningBook() }

        AnimatedVisibility(controls, Modifier.align(Alignment.TopCenter),
            enter = slideInVertically(tween(Motion.MEDIUM, easing = Motion.Emphasized)) { -it } + fadeIn(),
            exit = slideOutVertically(tween(Motion.SHORT, easing = Motion.EmphasizedAccelerate)) { -it } + fadeOut()) {
            ReaderTopBar(book.title, book.author, dark, onClose, { sheet = "contents" }, { sheet = "text" }) {
                appVm.updateAppearance(appearance.copy(mode = if (dark) ThemeMode.DAY else ThemeMode.NIGHT))
            }
        }
        AnimatedVisibility(controls, Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(Motion.MEDIUM, easing = Motion.Emphasized)) { it } + fadeIn(),
            exit = slideOutVertically(tween(Motion.SHORT, easing = Motion.EmphasizedAccelerate)) { it } + fadeOut()) {
            ReaderBottomBar(session, location, contents, layout != null)
        }
        AnimatedVisibility(returnPoint != null && ready, Modifier.align(Alignment.BottomCenter).padding(bottom = bottom + if (controls) 168.dp else 56.dp),
            enter = scaleIn(Motion.responsive(), initialScale = .8f) + fadeIn(), exit = scaleOut(targetScale = .8f) + fadeOut()) {
            val label = returnPoint?.let { session.label(BookPlace(it.resource, it.offset)) }
            FilledTonalButton(controller::goBack, Modifier.testTag("reader-return")) {
                Icon(Icons.AutoMirrored.Rounded.Undo, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(if (label != null) "Back to $label" else "Back to where you were")
            }
        }
    }

    if (sheet == "text") ModalBottomSheet({ sheet = null }, containerColor = MaterialTheme.colorScheme.surfaceContainer, scrimColor = Color.Black.copy(alpha = .12f)) {
        TypographySheet(settings, appearance, dark, vm::update, appVm::updateAppearance)
    }
    if (sheet == "contents") ModalBottomSheet({ sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        ContentsSheet(session, location) { place -> sheet = null; controls = false; controller.jumpTo(place) }
    }
    footnote?.let { note ->
        ModalBottomSheet({ footnote = null }, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp).testTag("reader-footnote")) {
                Text("Note", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(14.dp)) {
                    Text(remember(note.html) { AnnotatedString.fromHtml(note.html) }, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = Editorial),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(18.dp))
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton({ footnote = null; controller.jumpTo(note.target) }) { Text("Go to note") }
                    TextButton({ footnote = null }) { Text("Done") }
                }
            }
        }
    }
    image?.let { element -> ImageViewer(session, element) { image = null } }
    outsideLink?.let { url ->
        AlertDialog(onDismissRequest = { outsideLink = null }, title = { Text("Leave the book?") },
            text = { Text("This link opens ${Uri.parse(url.toString()).host ?: "a page"} outside Narrio. Your place in the book is kept.") },
            confirmButton = { TextButton({ outsideLink = null; runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.toString()))) } }) { Text("Open link") } },
            dismissButton = { TextButton({ outsideLink = null }) { Text("Stay here") } })
    }
}

private fun folioPlace(location: ReaderLocation) = buildString {
    location.pageLabel?.let { append("p. $it · ") }
    append("${(location.progression * 100).roundToInt()}%")
}

private fun timeLeft(location: ReaderLocation): String? {
    val minutes = location.minutesLeftInChapter ?: return null
    val text = if (minutes <= 1) "1 min left in chapter" else "$minutes min left in chapter"
    return if (location.paceMeasured) text else "About $text"
}

@Composable
private fun ReaderTopBar(title: String, author: String, dark: Boolean, close: () -> Unit, contents: () -> Unit, text: () -> Unit, toggleMode: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.statusBarsPadding().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)).padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(close) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close the reader") }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (author.isNotBlank()) Text(author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(contents, Modifier.testTag("reader-contents")) { Icon(Icons.AutoMirrored.Rounded.List, "Contents") }
            IconButton(text, Modifier.testTag("reader-text-settings").semantics { contentDescription = "Text and page settings" }) {
                Text("Aa", style = MaterialTheme.typography.titleLarge.copy(fontFamily = Editorial))
            }
            IconButton(toggleMode) { Icon(if (dark) Icons.Rounded.LightMode else Icons.Rounded.DarkMode, if (dark) "Day pages" else "Night pages") }
        }
    }
}

@Composable
private fun ReaderBottomBar(session: ReaderSession, location: ReaderLocation, contents: List<ContentsEntry>, measured: Boolean) {
    val controller = session.controller
    val marks = remember(contents, measured) { contents.filter { it.depth == 0 }.mapNotNull { entry -> entry.place?.let { place -> session.fraction(place)?.let { it to entry } } } }
    var preview by remember { mutableStateOf<Double?>(null) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Box(contentAlignment = Alignment.TopCenter) {
        Column(Modifier.navigationBarsPadding().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
            .widthIn(max = 720.dp).fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(location.chapter ?: session.book.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                Text(location.pageLabel?.let { page -> "p. $page" + (location.lastPageLabel?.let { " of $it" } ?: "") } ?: "${(location.progression * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                ChapterScrubber(location.progression, marks.map { it.first }, enabled = measured,
                    onPreview = { preview = it }, onCommit = { fraction -> preview = null; session.placeAt(fraction)?.let(controller::jumpTo) })
                preview?.let { fraction ->
                    val chapter = marks.lastOrNull { it.first <= fraction + 1e-9 }?.second?.title
                    val place = session.placeAt(fraction)?.let(session::label)
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val width = 200.dp
                        val x = (maxWidth * fraction.toFloat() - width / 2).coerceIn(0.dp, (maxWidth - width).coerceAtLeast(0.dp))
                        Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(14.dp), shadowElevation = 6.dp,
                            modifier = Modifier.offset { IntOffset(x.roundToPx(), (-64).dp.roundToPx()) }.width(width)) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Text(chapter ?: session.book.title, style = MaterialTheme.typography.titleMedium.copy(fontFamily = Editorial), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                place?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val here = marks.indexOfLast { it.first <= location.progression + 1e-9 }
                TextButton({ marks.getOrNull(if (here >= 0 && location.progression - marks[here].first > .002) here else here - 1)?.second?.place?.let(controller::jumpTo) },
                    enabled = measured && here >= 0) { Icon(Icons.Rounded.ChevronLeft, null, Modifier.size(18.dp)); Text("Previous") }
                Text(timeLeft(location).orEmpty(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                TextButton({ marks.getOrNull(here + 1)?.second?.place?.let(controller::jumpTo) }, enabled = measured && here + 1 < marks.size) {
                    Text("Next"); Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp))
                }
            }
        }
        }
    }
}

/** A quiet progress track with chapter marks; dragging previews the destination, releasing jumps there. */
@Composable
private fun ChapterScrubber(progress: Double, marks: List<Double>, enabled: Boolean, onPreview: (Double?) -> Unit, onCommit: (Double) -> Unit) {
    var dragging by remember { mutableStateOf<Double?>(null) }
    val shown = dragging ?: progress
    val copper = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outlineVariant
    val tick = MaterialTheme.colorScheme.outline
    var width by remember { mutableStateOf(1f) }
    Canvas(Modifier.fillMaxWidth().height(44.dp).onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
        .semantics {
            contentDescription = "Position in book"
            progressBarRangeInfo = ProgressBarRangeInfo(shown.toFloat(), 0f..1f)
            if (enabled) setProgress { target -> onCommit(target.toDouble().coerceIn(0.0, 1.0)); true }
        }
        .then(if (!enabled) Modifier else Modifier
            .pointerInput(Unit) { detectTapGestures { offset -> onCommit((offset.x / width).toDouble().coerceIn(0.0, 1.0)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset -> dragging = (offset.x / width).toDouble().coerceIn(0.0, 1.0); onPreview(dragging) },
                    onDragEnd = { dragging?.let(onCommit); dragging = null; onPreview(null) },
                    onDragCancel = { dragging = null; onPreview(null) },
                ) { change, _ -> change.consume(); dragging = (change.position.x / width).toDouble().coerceIn(0.0, 1.0); onPreview(dragging) }
            })) {
        val y = size.height / 2
        val bar = 4.dp.toPx()
        drawRoundRect(track, Offset(0f, y - bar / 2), Size(size.width, bar), CornerRadius(bar / 2))
        drawRoundRect(copper, Offset(0f, y - bar / 2), Size(size.width * shown.toFloat(), bar), CornerRadius(bar / 2))
        marks.forEach { mark -> if (mark > .001) drawRoundRect(tick.copy(alpha = .7f), Offset(size.width * mark.toFloat() - 1.dp.toPx(), y - 4.dp.toPx()), Size(2.dp.toPx(), 8.dp.toPx()), CornerRadius(1.dp.toPx())) }
        val thumb = if (dragging != null) 32.dp.toPx() else 24.dp.toPx()
        drawRoundRect(copper, Offset(size.width * shown.toFloat() - 2.dp.toPx(), y - thumb / 2), Size(4.dp.toPx(), thumb), CornerRadius(2.dp.toPx()))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypographySheet(settings: ReaderSettings, appearance: AppearanceSettings, dark: Boolean, update: (ReaderSettings.() -> ReaderSettings) -> Unit, updateAppearance: (AppearanceSettings) -> Unit) {
    val context = LocalContext.current
    val dyslexic = remember { FontFamily(Font("readium/fonts/OpenDyslexic-Regular.otf", context.assets)) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp).testTag("reader-typography")) {
        Text("Text", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 24.dp), modifier = Modifier.fillMaxWidth()) {
            items(ReaderFont.entries) { font ->
                val selected = settings.font == font
                val family = when (font) {
                    ReaderFont.PUBLISHER -> FontFamily.Serif
                    ReaderFont.NEWSREADER -> Editorial
                    ReaderFont.NARRIO, ReaderFont.MANROPE -> Humanist
                    ReaderFont.ANDROID -> FontFamily.Default
                    ReaderFont.OPEN_DYSLEXIC -> dyslexic
                }
                Surface(color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, shape = RoundedCornerShape(12.dp),
                    border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.size(84.dp, 76.dp).selectable(selected, role = Role.RadioButton) { update { copy(font = font) } }.semantics { contentDescription = "${font.label}: ${font.description}" }) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text("Aa", style = MaterialTheme.typography.headlineSmall.copy(fontFamily = family))
                        Text(font.label, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        SheetLabel("Size")
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ update { smaller() } }, enabled = settings.fontScale > ReaderSettings.MIN_SCALE, modifier = Modifier.size(64.dp, 48.dp)) {
                Text("A", style = MaterialTheme.typography.titleMedium.copy(fontFamily = Editorial), modifier = Modifier.semantics { contentDescription = "Smaller text" })
            }
            Text("${(settings.fontScale * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
            IconButton({ update { larger() } }, enabled = settings.fontScale < ReaderSettings.MAX_SCALE, modifier = Modifier.size(64.dp, 48.dp)) {
                Text("A", style = MaterialTheme.typography.headlineSmall.copy(fontFamily = Editorial), modifier = Modifier.semantics { contentDescription = "Larger text" })
            }
        }
        SheetLabel("Line spacing")
        Segments(ReaderSpacing.entries, settings.spacing, { it.label }) { value -> update { withAdvanced { copy(spacing = value) } } }
        SheetLabel("Margins")
        Segments(ReaderMargins.entries, settings.margins, { it.label }) { value -> update { withAdvanced { copy(margins = value) } } }
        SheetLabel("Layout")
        Segments(listOf(false, true), settings.scroll, { if (it) "Scroll" else "Pages" }) { value -> update { copy(scroll = value) } }
        Spacer(Modifier.height(8.dp))
        SwitchRow("Justify text", "Even edges on both sides", settings.justify && !settings.publisherStyles) { value -> update { withAdvanced { copy(justify = value) } } }
        SwitchRow("Hyphenation", "Break long words at line ends", settings.hyphenate && !settings.publisherStyles) { value -> update { withAdvanced { copy(hyphenate = value) } } }
        SwitchRow("Publisher styles", if (settings.publisherStyles) "The book's own spacing and alignment" else "Your spacing and alignment choices", settings.publisherStyles) { value -> update { copy(publisherStyles = value) } }
        SwitchRow("Volume keys turn pages", "Only while nothing is playing", settings.volumeKeys) { value -> update { copy(volumeKeys = value) } }
        SheetLabel("Colours · ${appearance.paletteName}")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ThemePalette.entries.filter { it != ThemePalette.CUSTOM || appearance.custom != null }.forEach { palette ->
                val option = appearance.copy(palette = palette)
                val colours = option.colours(dark)
                val selected = appearance.palette == palette
                Box(Modifier.size(44.dp).clip(CircleShape)
                    .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .selectable(selected, role = Role.RadioButton) { updateAppearance(option) }
                    .semantics { contentDescription = if (palette == ThemePalette.CUSTOM) appearance.custom?.name ?: "Custom" else palette.label }) {
                    Canvas(Modifier.fillMaxSize().padding(if (selected) 5.dp else 1.dp).clip(CircleShape)) {
                        drawRect(Color(0xFF000000.toInt() or colours.background))
                        drawCircle(Color(0xFF000000.toInt() or colours.accent), radius = size.minDimension * .28f, center = Offset(size.width * .64f, size.height * .64f))
                    }
                }
            }
        }
        SheetLabel("Mode")
        Segments(ThemeMode.entries, appearance.mode, { it.label }) { mode -> updateAppearance(appearance.copy(mode = mode)) }
        Text("Colours and mode are your Appearance settings, shared with the rest of Narrio.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Segments(options: List<T>, selected: T, label: (T) -> String, choose: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(option == selected, { choose(option) }, SegmentedButtonDefaults.itemShape(index, options.size)) { Text(label(option), maxLines = 1) }
        }
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(checked, role = Role.Switch, onValueChange = change), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, null)
    }
}

@Composable
private fun ContentsSheet(session: ReaderSession, location: ReaderLocation, open: (BookPlace) -> Unit) {
    val contents by session.book.contents.collectAsStateWithLifecycle()
    val visible by session.controller.visible.collectAsStateWithLifecycle()
    val current = visible?.first?.let { here -> contents.indexOfLast { entry -> entry.place?.let { session.book.compare(it, BookPlace(here.resource, here.offset)) }?.let { it <= 0 } == true } } ?: -1
    val list = rememberLazyListState((current - 2).coerceAtLeast(0))
    Column(Modifier.fillMaxWidth().testTag("reader-contents-sheet")) {
        Text(session.book.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 24.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("Contents", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (contents.isEmpty()) Text("Preparing the contents…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
        LazyColumn(state = list, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp)) {
            itemsIndexed(contents) { index, entry ->
                val reading = index == current
                val place = entry.place
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (reading) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .clickable(enabled = place != null, onClickLabel = "Go to ${entry.title}") { place?.let(open) }
                    .padding(start = 12.dp + (entry.depth * 18).dp, end = 12.dp, top = 10.dp, bottom = 10.dp)
                    .semantics { if (reading) stateDescription = "Reading now" },
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.title, style = (if (entry.depth == 0) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge).copy(fontFamily = Editorial),
                        color = if (reading) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    val label = place?.let(session::label)
                    if (reading || label != null) Text(listOfNotNull("Reading".takeIf { reading }, label).joinToString(" · "), style = MaterialTheme.typography.labelMedium,
                        color = if (reading) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
    }
}

/** A full-screen view of a tapped image: pinch or double-tap to zoom, drag to look around. */
@Composable
private fun ImageViewer(session: ReaderSession, element: Content.ImageElement, close: () -> Unit) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, element) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val link = session.book.publication.linkWithHref(element.embeddedLink.url()) ?: element.embeddedLink
                val bytes = session.book.publication.get(link)?.use { it.read().getOrNull() } ?: return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) }
                var sample = 1
                while (bounds.outWidth / sample > 4096 || bounds.outHeight / sample > 4096) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()
        }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(close, DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .94f)).testTag("reader-image")) {
            bitmap?.let { image ->
                Image(image.asImageBitmap(), element.caption ?: "Image from the book", contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(16.dp)
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                offset = if (scale == 1f) Offset.Zero else offset + pan
                            }
                        }
                        .pointerInput(Unit) { detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; offset = Offset.Zero }) }
                        .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y })
            } ?: CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            element.caption?.let { caption ->
                Text(caption, style = MaterialTheme.typography.bodyMedium, color = Color.White, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp))
            }
            FilledTonalIconButton(close, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp)) { Icon(Icons.Rounded.Close, "Close image") }
        }
    }
}
