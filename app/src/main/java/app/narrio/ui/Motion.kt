package app.narrio.ui

import android.provider.Settings
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Narrio's motion vocabulary. Movement explains where a screen came from, what changed, and what
 * is playing. Compose already scales every duration by Android's animator setting; looping
 * motion additionally checks [animationsEnabled] so "Remove animations" leaves a still room.
 */
object Motion {
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    const val SHORT = 150
    const val MEDIUM = 300
    const val LONG = 450

    /** Lively but settled: used for press feedback and controls that change shape. */
    fun <T> responsive() = spring<T>(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow)
    fun <T> settle() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow)

    /** Material fade-through for unrelated destinations. */
    fun fadeThrough(): ContentTransform =
        (fadeIn(tween(210, delayMillis = 90, easing = EmphasizedDecelerate)) + scaleIn(tween(210, delayMillis = 90, easing = EmphasizedDecelerate), initialScale = .94f))
            .togetherWith(fadeOut(tween(90, easing = EmphasizedAccelerate)))

    /** Material shared axis X: deeper content arrives from the trailing edge. */
    fun sharedAxisX(forward: Boolean): ContentTransform {
        val direction = if (forward) 1 else -1
        return (slideInHorizontally(tween(MEDIUM, easing = Emphasized)) { direction * it / 7 } + fadeIn(tween(210, delayMillis = 60, easing = EmphasizedDecelerate)))
            .togetherWith(slideOutHorizontally(tween(MEDIUM, easing = Emphasized)) { -direction * it / 7 } + fadeOut(tween(90, easing = EmphasizedAccelerate)))
    }

    /** The listening room rises from the mini-player and settles back into it. */
    fun rise(): ContentTransform =
        (slideInVertically(tween(LONG, easing = Emphasized)) { it / 4 } + fadeIn(tween(MEDIUM, easing = EmphasizedDecelerate)))
            .togetherWith(fadeOut(tween(SHORT, easing = EmphasizedAccelerate)) + scaleOut(tween(LONG, easing = Emphasized), targetScale = .94f))
            .apply { targetContentZIndex = 1f }

    fun fall(): ContentTransform =
        (fadeIn(tween(MEDIUM, delayMillis = 60, easing = EmphasizedDecelerate)) + scaleIn(tween(LONG, easing = Emphasized), initialScale = .94f))
            .togetherWith(slideOutVertically(tween(MEDIUM, easing = EmphasizedAccelerate)) { it / 4 } + fadeOut(tween(MEDIUM, easing = EmphasizedAccelerate)))
            .apply { targetContentZIndex = -1f }
}

@Composable
fun animationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
}

/**
 * Closes a modal sheet the way a swipe does: the action runs at once, the sheet slides away, and only then
 * leaves the composition. Removing a sheet directly cuts it from the screen mid-frame.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberSheetCloser(sheetState: SheetState, dismiss: () -> Unit): (() -> Unit) -> Unit {
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(dismiss)
    return remember(sheetState, scope) { { after: () -> Unit -> after(); scope.launch { sheetState.hide() }.invokeOnCompletion { latest() } } }
}

/** Shared-element plumbing for compact navigation. Absent scopes leave covers static. */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransition = staticCompositionLocalOf<SharedTransitionScope?> { null }
val LocalNavigationScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedCover(key: String?): Modifier {
    val shared = LocalSharedTransition.current
    val scope = LocalNavigationScope.current
    if (key == null || shared == null || scope == null) return this
    return with(shared) {
        this@sharedCover.sharedElement(rememberSharedContentState(key), scope,
            boundsTransform = { _, _ -> spring(dampingRatio = .86f, stiffness = Spring.StiffnessMediumLow) })
    }
}

/** A gentle press that makes rows and controls feel physical without moving their layout. */
@Composable
fun Modifier.pressScale(interaction: InteractionSource, pressed: Float = .97f): Modifier {
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) pressed else 1f, Motion.responsive(), label = "press")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Results that are about to be replaced fade back a step, so the list reads as "still here, updating". */
@Composable
fun Modifier.staleWhile(loading: Boolean): Modifier {
    val alpha by animateFloatAsState(if (loading) .55f else 1f, tween(Motion.MEDIUM, easing = Motion.Emphasized), label = "stale")
    return graphicsLayer { this.alpha = alpha }
}

/** Three narration bars: they move only while a voice is actually playing. */
@Composable
fun NarrationPulse(playing: Boolean, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    if (playing && animationsEnabled()) {
        val transition = rememberInfiniteTransition(label = "narration")
        val bars = listOf(520, 380, 610).mapIndexed { index, duration ->
            transition.animateFloat(.28f, 1f, infiniteRepeatable(tween(duration, easing = FastOutSlowInEasing), RepeatMode.Reverse, StartOffset(index * 140)), label = "bar$index")
        }
        PulseBars(modifier, color) { bars[it].value }
    } else PulseBars(modifier, color) { listOf(.45f, .8f, .35f)[it] }
}

@Composable
private fun PulseBars(modifier: Modifier, color: Color, height: (Int) -> Float) {
    Canvas(modifier.size(18.dp, 16.dp)) {
        val gap = size.width * .16f
        val bar = (size.width - gap * 2) / 3
        repeat(3) { index ->
            val h = size.height * height(index)
            drawRoundRect(color, Offset(index * (bar + gap), size.height - h), Size(bar, h), CornerRadius(bar / 2))
        }
    }
}

/** Book-shaped placeholders while metadata arrives. A soft light passes over them once a beat. */
@Composable
fun SkeletonBookRow(modifier: Modifier = Modifier) {
    val base = MaterialTheme.colorScheme.surfaceContainer
    val glow = MaterialTheme.colorScheme.surfaceContainerHighest
    val brush = if (animationsEnabled()) {
        val shift by rememberInfiniteTransition(label = "skeleton").animateFloat(-1f, 2f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "shift")
        Brush.linearGradient(listOf(base, glow, base), start = Offset(shift * 600f, 0f), end = Offset(shift * 600f + 600f, 200f))
    } else Brush.linearGradient(listOf(base, base))
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(76.dp, 112.dp).clip(RoundedCornerShape(8.dp)).background(brush))
        Column(Modifier.weight(1f).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.fillMaxWidth(.82f).height(16.dp).clip(RoundedCornerShape(6.dp)).background(brush))
            Box(Modifier.fillMaxWidth(.46f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(brush))
            Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(6.dp)).background(brush))
            Box(Modifier.fillMaxWidth(.7f).height(10.dp).clip(RoundedCornerShape(6.dp)).background(brush))
        }
    }
}

/** Palette, mode, and custom theme changes dissolve through the whole room instead of cutting. */
@Composable
fun animatedColorScheme(target: ColorScheme): ColorScheme {
    var from by remember { mutableStateOf(target) }
    var to by remember { mutableStateOf(target) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(target) {
        if (target == to) return@LaunchedEffect
        from = lerpScheme(from, to, progress.value)
        to = target
        progress.snapTo(0f)
        progress.animateTo(1f, tween(Motion.LONG, easing = Motion.Emphasized))
    }
    return if (progress.value >= 1f) to else lerpScheme(from, to, progress.value)
}

private fun lerpScheme(a: ColorScheme, b: ColorScheme, t: Float): ColorScheme {
    if (t <= 0f) return a
    if (t >= 1f) return b
    fun c(x: Color, y: Color) = lerp(x, y, t)
    return b.copy(
        primary = c(a.primary, b.primary), onPrimary = c(a.onPrimary, b.onPrimary),
        primaryContainer = c(a.primaryContainer, b.primaryContainer), onPrimaryContainer = c(a.onPrimaryContainer, b.onPrimaryContainer),
        inversePrimary = c(a.inversePrimary, b.inversePrimary),
        secondary = c(a.secondary, b.secondary), onSecondary = c(a.onSecondary, b.onSecondary),
        secondaryContainer = c(a.secondaryContainer, b.secondaryContainer), onSecondaryContainer = c(a.onSecondaryContainer, b.onSecondaryContainer),
        tertiary = c(a.tertiary, b.tertiary), onTertiary = c(a.onTertiary, b.onTertiary),
        tertiaryContainer = c(a.tertiaryContainer, b.tertiaryContainer), onTertiaryContainer = c(a.onTertiaryContainer, b.onTertiaryContainer),
        background = c(a.background, b.background), onBackground = c(a.onBackground, b.onBackground),
        surface = c(a.surface, b.surface), onSurface = c(a.onSurface, b.onSurface),
        surfaceVariant = c(a.surfaceVariant, b.surfaceVariant), onSurfaceVariant = c(a.onSurfaceVariant, b.onSurfaceVariant),
        surfaceTint = c(a.surfaceTint, b.surfaceTint), inverseSurface = c(a.inverseSurface, b.inverseSurface), inverseOnSurface = c(a.inverseOnSurface, b.inverseOnSurface),
        error = c(a.error, b.error), onError = c(a.onError, b.onError), errorContainer = c(a.errorContainer, b.errorContainer), onErrorContainer = c(a.onErrorContainer, b.onErrorContainer),
        outline = c(a.outline, b.outline), outlineVariant = c(a.outlineVariant, b.outlineVariant), scrim = c(a.scrim, b.scrim),
        surfaceBright = c(a.surfaceBright, b.surfaceBright), surfaceDim = c(a.surfaceDim, b.surfaceDim),
        surfaceContainer = c(a.surfaceContainer, b.surfaceContainer), surfaceContainerHigh = c(a.surfaceContainerHigh, b.surfaceContainerHigh),
        surfaceContainerHighest = c(a.surfaceContainerHighest, b.surfaceContainerHighest), surfaceContainerLow = c(a.surfaceContainerLow, b.surfaceContainerLow),
        surfaceContainerLowest = c(a.surfaceContainerLowest, b.surfaceContainerLowest),
    )
}
