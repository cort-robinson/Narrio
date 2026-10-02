package app.narrio.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.*
import androidx.lifecycle.compose.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.window.layout.*
import kotlinx.coroutines.flow.map

@Composable
fun NarrioApp(activity: ComponentActivity, vm: NarrioViewModel = viewModel()) {
    val theme by vm.theme.collectAsStateWithLifecycle()
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
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        vm.graph.playback.visible = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        vm.graph.offline.visible = vm.graph.playback.visible
        onDispose { vm.graph.playback.visible = false; vm.graph.offline.visible = false; lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(state.playing) {
        if (state.playing && Build.VERSION.SDK_INT >= 33 && !vm.graph.preferences.getBoolean("notificationAsked", false)) {
            vm.graph.preferences.edit().putBoolean("notificationAsked", true).apply()
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    BackHandler(enabled = selected.book != null || playerOpen || destination != 0) { if (playerOpen || selected.book != null) vm.back() else vm.navigate(0) }
    NarrioTheme(theme) {
        val dark = MaterialTheme.colorScheme.background == Color(0xFF101B1A)
        SideEffect { WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark } }
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
        val density = LocalDensity.current
        val fold = windowInfo?.displayFeatures?.filterIsInstance<FoldingFeature>()?.firstOrNull()
        val tabletop = fold?.orientation == FoldingFeature.Orientation.HORIZONTAL && fold.state == FoldingFeature.State.HALF_OPENED && playerOpen
        BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))) {
            val windowWidth = maxWidth
            val expanded = maxWidth >= 600.dp && !tabletop
            val statusTop = WindowInsets.statusBars.getTop(density)
            Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snackbar) },
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    if (!expanded && !playerOpen && selected.book == null) Column {
                        if (state.book != null) MiniPlayer(vm)
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                            navItems.forEachIndexed { index, item -> NavigationBarItem(destination == index, { vm.navigate(index) }, icon = { Icon(item.icon, item.label) }, label = { Text(item.label) }) }
                        }
                    }
                }) { padding ->
                Row(Modifier.fillMaxSize().padding(padding).then(if (expanded || playerOpen || selected.book != null) Modifier.navigationBarsPadding() else Modifier)) {
                    if (expanded) NavigationRail(containerColor = MaterialTheme.colorScheme.background, modifier = Modifier.width(80.dp), header = { Spacer(Modifier.height(16.dp)); NarrioMark(); Spacer(Modifier.height(32.dp)) }) {
                        navItems.forEachIndexed { index, item -> NavigationRailItem(destination == index, { vm.navigate(index) }, icon = { Icon(item.icon, item.label) }, label = { Text(item.label) }, modifier = Modifier.padding(bottom = 18.dp)) }
                    }
                    if (tabletop) {
                        val hingeY = with(density) { (fold.bounds.top - statusTop).toDp().value }
                        val gap = with(density) { fold.bounds.height().toDp().value }
                        PlayerScreen(vm, true, Modifier.weight(1f), hingeY, gap)
                    } else if (expanded) {
                        val available = windowWidth - 80.dp
                        val separating = fold?.isSeparating == true && fold.orientation == FoldingFeature.Orientation.VERTICAL
                        val leftWidth = if (separating) with(density) { fold.bounds.left.toDp() } - 80.dp else available * .53f
                        Column(Modifier.width(leftWidth.coerceIn(250.dp, available - 250.dp)).fillMaxHeight()) {
                            MainDestination(vm, destination, Modifier.weight(1f))
                            if (state.book != null) MiniPlayer(vm)
                        }
                        if (separating) Spacer(Modifier.width(with(density) { fold.bounds.width().toDp() }.coerceAtLeast(1.dp)))
                        else VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Box(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                            when {
                                playerOpen && state.book != null -> PlayerScreen(vm, false)
                                selected.book != null -> DetailPane(vm, false)
                                state.book != null -> PlayerScreen(vm, false)
                                else -> WelcomePane(vm)
                            }
                        }
                    } else when {
                        playerOpen && state.book != null -> PlayerScreen(vm, true)
                        selected.book != null -> DetailPane(vm, true)
                        else -> MainDestination(vm, destination)
                    }
                }
            }
        }
    }
}

private data class NavItem(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
private val navItems = listOf(NavItem("Discover", Icons.Rounded.Explore), NavItem("My shelf", Icons.Rounded.AutoStories), NavItem("Settings", Icons.Rounded.Tune))
@Composable
private fun MainDestination(vm: NarrioViewModel, destination: Int, modifier: Modifier = Modifier) {
    when (destination) { 1 -> LibraryScreen(vm, modifier); 2 -> SettingsScreen(vm, modifier); else -> DiscoverScreen(vm, modifier) }
}
@Composable
private fun WelcomePane(vm: NarrioViewModel) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        BookCover(NarrioViewModel.curated.first(), Modifier.width(190.dp).height(278.dp), true)
        Spacer(Modifier.height(32.dp))
        Text("A place to get lost.", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(12.dp))
        Text("Pick a story on the left. Your recording and listening controls will open here.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        FilledTonalButton({ vm.open(vm.catalog.value.books.firstOrNull() ?: NarrioViewModel.curated.first()) }) { Text("Meet The Secret Garden") }
    }
}
