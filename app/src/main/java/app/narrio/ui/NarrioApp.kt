package app.narrio.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.*
import androidx.lifecycle.compose.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.window.layout.*
import app.narrio.domain.Audiobook
import app.narrio.domain.ThemeContrast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Compact navigation states. Identity is by key so detail refreshes never restart a transition. */
private sealed interface Screen { val depth: Int }
private data object Home : Screen { override val depth = 0 }
private data class Details(val id: String, val catalog: Boolean) : Screen { override val depth = if (catalog) 1 else 2 }
private data object Listening : Screen { override val depth = 3 }

@Composable
fun NarrioApp(activity: ComponentActivity, vm: NarrioViewModel = viewModel()) {
    val appearance by vm.appearance.collectAsStateWithLifecycle()
    val selected by vm.selection.collectAsStateWithLifecycle()
    val playerOpen by vm.playerOpen.collectAsStateWithLifecycle()
    val state by vm.playback.collectAsStateWithLifecycle()
    val destination by vm.destination.collectAsStateWithLifecycle()
    val windowInfo by remember(activity) { WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).map { it as WindowLayoutInfo? } }.collectAsStateWithLifecycle(null)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) vm.graph.playback.visible = true
            if (event == Lifecycle.Event.ON_STOP) vm.graph.playback.visible = false
            vm.graph.offline.visible = vm.graph.playback.visible
            if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_STOP) vm.graph.updates.visibility(vm.graph.playback.visible)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        vm.graph.playback.visible = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        vm.graph.offline.visible = vm.graph.playback.visible
        vm.graph.updates.visibility(vm.graph.playback.visible)
        onDispose { vm.graph.playback.visible = false; vm.graph.offline.visible = false; vm.graph.updates.visibility(false); lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(state.playing) {
        if (state.playing && Build.VERSION.SDK_INT >= 33 && !vm.graph.preferences.getBoolean("notificationAsked", false)) {
            vm.graph.preferences.edit().putBoolean("notificationAsked", true).apply()
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    // Exiting detail content keeps rendering the book it showed, even after the selection clears.
    val recentBooks = remember { HashMap<String, Audiobook>() }
    selected.book?.let { recentBooks[it.id] = it }
    val bookFor: (String) -> Audiobook? = { id -> selected.book?.takeIf { it.id == id } ?: recentBooks[id] }
    // Each book keeps its scroll position, so returning from a recording lands back among its sources.
    val detailScroll = remember { object : LinkedHashMap<String, LazyListState>(16, .75f, true) { override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LazyListState>) = size > 12 } }
    val scrollFor: (String) -> LazyListState = { id -> detailScroll.getOrPut(id) { LazyListState() } }
    val backEnabled = selected.book != null || playerOpen || destination != 0
    val goBack: () -> Unit = { if (playerOpen || selected.book != null) vm.back() else vm.navigate(0) }

    NarrioTheme(appearance) {
        val dark = ThemeContrast.foreground(MaterialTheme.colorScheme.background.toArgb()) == 0xFFFFFF
        SideEffect { WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark } }
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
        val updates by vm.graph.updates.state.collectAsStateWithLifecycle()
        var announcedUpdate by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(-1L) }
        LaunchedEffect(updates.phase, updates.available?.code, state.playing, state.buffering) {
            val candidate = updates.available
            if (updates.phase == app.narrio.updates.UpdatePhase.READY && candidate != null &&
                candidate.code != announcedUpdate && !state.playing && !state.buffering) {
                announcedUpdate = candidate.code
                if (snackbar.showSnackbar("Narrio update ready", "Settings", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) vm.navigate(2)
            }
        }
        val density = LocalDensity.current
        val fold = windowInfo?.displayFeatures?.filterIsInstance<FoldingFeature>()?.firstOrNull()
        val tabletop = fold?.orientation == FoldingFeature.Orientation.HORIZONTAL && fold.state == FoldingFeature.State.HALF_OPENED && playerOpen
        var barHeight by remember { mutableStateOf(0.dp) }
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))) {
            val windowWidth = maxWidth
            val expanded = maxWidth >= 600.dp && !tabletop
            val statusTop = WindowInsets.statusBars.getTop(density)
            val current: Screen = when {
                playerOpen && state.book != null -> Listening
                selected.book != null -> selected.book!!.let { Details(it.id, it.provider == "catalog") }
                else -> Home
            }
            when {
                tabletop -> {
                    BackHandler(backEnabled, goBack)
                    val hingeY = with(density) { (fold!!.bounds.top - statusTop).toDp().value }
                    val gap = with(density) { fold!!.bounds.height().toDp().value }
                    PlayerScreen(vm, true, Modifier.fillMaxSize().navigationBarsPadding(), hingeY, gap)
                }
                expanded -> {
                    BackHandler(backEnabled, goBack)
                    // A landscape phone has width but little height: give an open player the whole canvas.
                    val fullPlayer = maxHeight < 480.dp && playerOpen && state.book != null
                    Row(Modifier.fillMaxSize().navigationBarsPadding()) {
                        NavigationRail(containerColor = MaterialTheme.colorScheme.background, modifier = Modifier.width(80.dp), header = { Spacer(Modifier.height(24.dp)) }) {
                            navItems.forEachIndexed { index, item -> NavigationRailItem(destination == index && !fullPlayer, { vm.navigate(index) }, icon = { Icon(item.icon, item.label) }, label = { Text(item.label) }, modifier = Modifier.padding(bottom = 18.dp)) }
                        }
                        AnimatedContent(fullPlayer, Modifier.weight(1f).fillMaxHeight(), transitionSpec = { if (targetState) Motion.rise() else Motion.fall() }, label = "full player") { full ->
                            if (full) PlayerScreen(vm, true, Modifier.fillMaxSize())
                            else ExpandedPanes(vm, windowWidth, fold, density, destination, bookFor, scrollFor, playerOpen, state.book != null, selected.book?.id)
                        }
                    }
                }
                else -> CompactShell(vm, current, destination, state.book != null, bookFor, scrollFor, backEnabled, goBack, barHeight) { barHeight = it }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).then(
                if (!expanded && !tabletop && current == Home) Modifier.padding(bottom = barHeight) else Modifier.navigationBarsPadding()))
        }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun CompactShell(vm: NarrioViewModel, current: Screen, destination: Int, hasPlayback: Boolean, bookFor: (String) -> Audiobook?, scrollFor: (String) -> LazyListState,
                         backEnabled: Boolean, goBack: () -> Unit, barHeight: Dp, onBarHeight: (Dp) -> Unit) {
    val seekState = remember { SeekableTransitionState(current) }
    LaunchedEffect(current) { seekState.animateTo(current) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // Predictive Back scrubs the real transition: the listener sees where Back leads before letting go.
    PredictiveBackHandler(backEnabled) { events ->
        val target = predictBack(vm, current)
        try {
            events.collect { event -> if (target != current) seekState.seekTo(event.progress, target) }
            goBack()
        } catch (cancelled: CancellationException) {
            scope.launch { seekState.animateTo(current) }
            throw cancelled
        }
    }
    val transition = rememberTransition(seekState, label = "screen")
    SharedTransitionLayout(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalSharedTransition provides this) {
            Box(Modifier.fillMaxSize()) {
                transition.AnimatedContent(Modifier.fillMaxSize(), transitionSpec = {
                    when {
                        targetState == Listening -> Motion.rise()
                        initialState == Listening -> Motion.fall()
                        else -> Motion.sharedAxisX(targetState.depth > initialState.depth)
                    }
                }, contentKey = { it }) { screen ->
                    CompositionLocalProvider(LocalNavigationScope provides this) {
                        when (screen) {
                            Home -> Box(Modifier.fillMaxSize().padding(bottom = barHeight)) { HomeDestinations(vm, destination) }
                            is Details -> bookFor(screen.id)?.let { DetailPane(vm, it, true, Modifier.navigationBarsPadding(), scrollFor(it.id)) }
                            Listening -> PlayerScreen(vm, true, Modifier.fillMaxSize().navigationBarsPadding())
                        }
                    }
                }
                transition.AnimatedVisibility({ it == Home }, Modifier.align(Alignment.BottomCenter),
                    enter = slideInVertically(androidx.compose.animation.core.tween(Motion.MEDIUM, easing = Motion.Emphasized)) { it } + fadeIn(),
                    exit = slideOutVertically(androidx.compose.animation.core.tween(Motion.MEDIUM, easing = Motion.EmphasizedAccelerate)) { it } + fadeOut()) {
                    CompositionLocalProvider(LocalNavigationScope provides this) {
                        Column(Modifier.onSizeChanged { onBarHeight(with(density) { it.height.toDp() }) }) {
                            AnimatedVisibility(hasPlayback, enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(), exit = shrinkVertically() + fadeOut()) { MiniPlayer(vm) }
                            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                                navItems.forEachIndexed { index, item -> NavigationBarItem(destination == index, { vm.navigate(index) }, icon = { Icon(item.icon, item.label) }, label = { Text(item.label) }) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Mirrors [NarrioViewModel.back] so a gesture can preview its destination before committing. */
private fun predictBack(vm: NarrioViewModel, current: Screen): Screen {
    val selected = vm.selection.value.book
    val origin = vm.sourceSearch.value.book
    return when {
        current == Listening -> selected?.let { Details(it.id, it.provider == "catalog") } ?: Home
        current is Details && origin?.provider == "catalog" && !current.catalog -> Details(origin.id, true)
        else -> Home
    }
}

@Composable
private fun ExpandedPanes(vm: NarrioViewModel, windowWidth: Dp, fold: FoldingFeature?, density: androidx.compose.ui.unit.Density, destination: Int,
                          bookFor: (String) -> Audiobook?, scrollFor: (String) -> LazyListState, playerOpen: Boolean, hasPlayback: Boolean, selectedId: String?) {
    Row(Modifier.fillMaxSize()) {
        val available = windowWidth - 80.dp
        val separating = fold?.isSeparating == true && fold.orientation == FoldingFeature.Orientation.VERTICAL
        val leftWidth = if (separating) with(density) { fold!!.bounds.left.toDp() } - 80.dp else available * .53f
        val pane = when {
            playerOpen && hasPlayback -> "player"
            selectedId != null -> "detail:$selectedId"
            hasPlayback -> "player"
            else -> "welcome"
        }
        Column(Modifier.width(leftWidth.coerceIn(250.dp, available - 250.dp)).fillMaxHeight()) {
            Box(Modifier.weight(1f)) { HomeDestinations(vm, destination) }
            // The mini-player earns its place only while a book's details cover the listening room.
            AnimatedVisibility(hasPlayback && pane != "player", enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(), exit = shrinkVertically() + fadeOut()) { MiniPlayer(vm) }
        }
        if (separating) Spacer(Modifier.width(with(density) { fold!!.bounds.width().toDp() }.coerceAtLeast(1.dp)))
        else VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        AnimatedContent(pane, Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow),
            transitionSpec = { Motion.fadeThrough() }, label = "secondary pane") { key ->
            when {
                key == "player" -> PlayerScreen(vm, false)
                key.startsWith("detail:") -> bookFor(key.removePrefix("detail:"))?.let { DetailPane(vm, it, false, listState = scrollFor(it.id)) }
                else -> WelcomePane(vm)
            }
        }
    }
}

@Composable
private fun HomeDestinations(vm: NarrioViewModel, destination: Int) {
    AnimatedContent(destination, transitionSpec = { Motion.fadeThrough() }, label = "destination") { MainDestination(vm, it) }
}

private data class NavItem(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
private val navItems = listOf(NavItem("Discover", Icons.Rounded.Explore), NavItem("My shelf", Icons.Rounded.AutoStories), NavItem("Settings", Icons.Rounded.Tune))
@Composable
private fun MainDestination(vm: NarrioViewModel, destination: Int, modifier: Modifier = Modifier) {
    when (destination) { 1 -> LibraryScreen(vm, modifier); 2 -> SettingsScreen(vm, modifier); else -> DiscoverScreen(vm, modifier) }
}
@Composable
private fun WelcomePane(vm: NarrioViewModel) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
    // The garden scales down on short panes so its invitation and action always stay visible.
    val coverHeight = (maxHeight * .42f).coerceIn(120.dp, 278.dp)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        BookCover(NarrioViewModel.curated.first(), Modifier.width(coverHeight / 1.46f).height(coverHeight), coverHeight > 200.dp)
        Spacer(Modifier.height(32.dp))
        Text("A place to get lost.", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(12.dp))
        Text("Pick a book on the left. Read its details, then find a listening source.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        FilledTonalButton({ vm.search("The Secret Garden") }) { Text("Find The Secret Garden") }
    }
    }
}
