package app.narrio.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import app.narrio.domain.formatTime
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Precise seeking: drag along the bar as usual, then slide up for finer control. Each band higher moves the place
 * more slowly than the finger, down to about a second per finger-width, so a chapter's opening line can be found
 * again inside a ten-hour recording.
 */
internal object PreciseSeek {
    /** Heights above the bar, in dp, where each finer band begins. */
    private val BANDS = floatArrayOf(56f, 120f, 184f)
    /** Each band is at least this much slower than following the finger, and never coarser than its ceiling (ms per dp). */
    private val FACTORS = floatArrayOf(1f, 2f, 4f, 8f)
    private val CEILINGS = floatArrayOf(Float.MAX_VALUE, 6_000f, 1_000f, 100f)
    val labels = listOf("Seeking", "Slower seeking", "Precise seeking", "Fine seeking")
    val bands get() = FACTORS.size
    private val STEPS = longArrayOf(1_000, 5_000, 10_000, 30_000, 60_000, 300_000, 600_000, 1_800_000, 3_600_000)

    fun band(upDp: Float): Int = BANDS.count { upDp >= it }

    /** Milliseconds moved per dp of finger travel in [band], when following the finger moves [fullMsPerDp]. */
    fun msPerDp(band: Int, fullMsPerDp: Float): Float = min(fullMsPerDp / FACTORS[band], CEILINGS[band])

    /** The finest ruler step that stays at least [minDp] apart at this rate. */
    fun step(msPerDp: Float, minDp: Float): Long = STEPS.firstOrNull { it / msPerDp >= minDp } ?: STEPS.last()

    /** The coarsest labelled step: a multiple of [step] at least [minDp] apart. */
    fun major(step: Long, msPerDp: Float, minDp: Float): Long? = STEPS.firstOrNull { it % step == 0L && it / msPerDp >= minDp }

    /** The next step down from [step], or null at a second. */
    fun finer(step: Long): Long? = STEPS.indexOf(step).takeIf { it > 0 }?.let { STEPS[it - 1] }

    /** "+1:05" or "−0:12" from where seeking began. */
    fun delta(ms: Long): String = (if (ms < 0) "−" else "+") + formatTime(abs(ms))
}

private class Scrub(val startMs: Float) {
    var band by mutableIntStateOf(0)
    var liftPx by mutableFloatStateOf(0f)
    var msPerDp by mutableFloatStateOf(1f)
}

/**
 * The listening position slider with precise seeking. The Material slider keeps its look and its accessibility
 * actions; touch is handled here so the finger can leave the bar while seeking. [scrubbing] reports the place being
 * chosen (null once it is released) so the times beside the bar can follow it.
 */
@Composable
internal fun SeekSlider(positionMs: Long, durationMs: Long, seek: (Long) -> Unit, scrubbing: (Long?) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val duration = durationMs.coerceAtLeast(1).toFloat()
    var value by remember { mutableFloatStateOf(positionMs.toFloat()) }
    var active by remember { mutableStateOf(false) }
    var scrub by remember { mutableStateOf<Scrub?>(null) }
    var anchor by remember { mutableStateOf(Rect.Zero) }
    LaunchedEffect(positionMs, durationMs) { if (!active) value = positionMs.toFloat() }
    val latestPosition by rememberUpdatedState(positionMs)
    val latestSeek by rememberUpdatedState(seek)
    val latestScrubbing by rememberUpdatedState(scrubbing)
    Box(modifier.fillMaxWidth().onGloballyPositioned { anchor = it.boundsInWindow() }) {
        Slider(value = value.coerceIn(0f, duration), onValueChange = { active = true; value = it; scrubbing(it.toLong()) },
            onValueChangeFinished = { seek(value.toLong()); active = false; scrubbing(null) },
            valueRange = 0f..duration, enabled = durationMs > 0, interactionSource = interaction,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Listening position" })
        // Drawn over the slider so it takes the touch; the slider still answers TalkBack and keyboard adjustments.
        Box(Modifier.matchParentSize().pointerInput(durationMs) {
            if (durationMs <= 0) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                val inset = 10.dp.toPx()
                val track = (size.width - inset * 2).coerceAtLeast(1f)
                val fullMsPerDp = duration / track * density
                val drag = DragInteraction.Start()
                // A press away from the thumb jumps there, as a slider does; a press on it keeps the exact place.
                val jumped = abs(down.position.x - (inset + value / duration * track)) > 24.dp.toPx()
                if (jumped) value = ((down.position.x - inset) / track).coerceIn(0f, 1f) * duration
                val origin = value
                active = true
                latestScrubbing(value.toLong())
                interaction.tryEmit(drag)
                var current: Scrub? = null
                var last = down.position
                var tick = Long.MIN_VALUE
                var released = false
                try {
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) { released = true; break }
                        val up = (down.position.y - change.position.y).toDp().value
                        if (current == null && (change.position - down.position).getDistance() > viewConfiguration.touchSlop)
                            current = Scrub(origin).also { scrub = it }
                        current?.let { s ->
                            val band = PreciseSeek.band(up)
                            if (band != s.band) { s.band = band; tick = Long.MIN_VALUE; haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) }
                            s.msPerDp = PreciseSeek.msPerDp(band, fullMsPerDp)
                            s.liftPx = (down.position.y - change.position.y).coerceAtLeast(0f)
                            value = (value + (change.position.x - last.x).toDp().value * s.msPerDp).coerceIn(0f, duration)
                            // A tick for every mark on the ruler that passes under the needle.
                            val step = PreciseSeek.step(s.msPerDp, RULER_MIN_DP)
                            val index = floor(value / step).toLong()
                            if (tick != Long.MIN_VALUE && index != tick) haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                            tick = index
                            latestScrubbing(value.toLong())
                        }
                        last = change.position
                        change.consume()
                    }
                } finally {
                    if (released && (jumped || value != origin)) {
                        if (current != null) haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                        latestSeek(value.toLong())
                    } else value = latestPosition.toFloat()
                    active = false; scrub = null
                    latestScrubbing(null)
                    interaction.tryEmit(DragInteraction.Stop(drag))
                }
            }
        })
        SeekLens(scrub, value, duration, anchor)
    }
}

private const val RULER_MIN_DP = 8f

/** The card that rises above the bar while seeking, staying above the finger as it slides up. */
@Composable
private fun SeekLens(scrub: Scrub?, valueMs: Float, durationMs: Float, anchor: Rect) {
    val visible = remember { MutableTransitionState(false) }
    visible.targetState = scrub != null
    var shown by remember { mutableStateOf<Scrub?>(null) }
    scrub?.let { shown = it }
    val s = shown ?: return
    if (!visible.currentState && !visible.targetState) return
    val density = LocalDensity.current
    var cardHeight by remember { mutableFloatStateOf(0f) }
    // The card follows the finger up, but never past the top of the window.
    val room = with(density) { anchor.top - cardHeight - 72.dp.toPx() }.coerceAtLeast(0f)
    val lift by animateFloatAsState(min(s.liftPx, room), spring(stiffness = Spring.StiffnessHigh), label = "lens lift")
    val above = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
                IntOffset((anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
                    anchorBounds.top - popupContentSize.height)
        }
    }
    Popup(above, properties = PopupProperties(focusable = false, clippingEnabled = false)) {
        // Room around the card for its shadow, which the popup window would otherwise clip into a box.
        val width = with(density) { anchor.width.toDp() }.coerceIn(280.dp, 360.dp)
        Box(Modifier.width(width + 48.dp).height(520.dp).padding(horizontal = 24.dp), contentAlignment = Alignment.BottomCenter) {
            AnimatedVisibility(visible,
                enter = fadeIn(tween(Motion.SHORT)) + scaleIn(Motion.responsive(), initialScale = .85f, transformOrigin = TransformOrigin(.5f, 1f)) + slideInVertically(Motion.responsive()) { it / 4 },
                exit = fadeOut(tween(Motion.SHORT, easing = Motion.EmphasizedAccelerate)) + scaleOut(tween(Motion.SHORT), targetScale = .9f, transformOrigin = TransformOrigin(.5f, 1f)),
                modifier = Modifier.padding(bottom = 12.dp, top = 24.dp).graphicsLayer { translationY = -lift }.onGloballyPositioned { cardHeight = it.size.height.toFloat() }) {
                LensCard(s, valueMs, durationMs)
            }
        }
    }
}

@Composable
private fun LensCard(s: Scrub, valueMs: Float, durationMs: Float) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(24.dp), color = colors.surfaceContainerHighest, shadowElevation = 12.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedContent(s.band, Modifier.weight(1f), transitionSpec = {
                    val finer = targetState > initialState
                    (slideInVertically(Motion.responsive()) { if (finer) -it else it } + fadeIn()).togetherWith(slideOutVertically { if (finer) it else -it } + fadeOut())
                }, label = "seek band") { Text(PreciseSeek.labels[it], style = MaterialTheme.typography.labelLarge, color = colors.primary) }
                PrecisionMeter(s.band)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(formatTime(valueMs.toLong()), Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"))
                Text(PreciseSeek.delta(valueMs.toLong() - s.startMs.toLong()), Modifier.padding(bottom = 4.dp),
                    style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"), color = colors.secondary)
            }
            Spacer(Modifier.height(10.dp))
            SeekRuler(valueMs, durationMs, s.msPerDp, Modifier.fillMaxWidth().height(52.dp))
            Spacer(Modifier.height(8.dp))
            val finest = s.band == PreciseSeek.bands - 1
            AnimatedContent(finest, label = "seek hint", transitionSpec = { fadeIn(tween(Motion.MEDIUM)).togetherWith(fadeOut(tween(Motion.SHORT))) }) { atFinest ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(if (atFinest) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.KeyboardArrowUp, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                    Text(if (atFinest) "Slide down to seek faster" else "Slide up to seek more precisely", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

/** Four rising bars, like signal strength: how fine the seeking has become. */
@Composable
private fun PrecisionMeter(band: Int) {
    Row(Modifier.height(16.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
        repeat(PreciseSeek.bands) { i ->
            val on = i <= band
            val color by animateColorAsState(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, label = "meter")
            val height by animateDpAsState(if (on) (7 + i * 3).dp else (5 + i * 3).dp, Motion.responsive(), label = "meter height")
            Box(Modifier.width(4.dp).height(height).clip(RoundedCornerShape(2.dp)).background(color))
        }
    }
}

/**
 * A time ruler that slides under a fixed needle exactly as fast as the finger moves the place, so changing bands
 * visibly zooms it. Finer marks fade in as the ruler zooms rather than popping.
 */
@Composable
private fun SeekRuler(valueMs: Float, durationMs: Float, msPerDp: Float, modifier: Modifier) {
    // Zoom in log space so each band change feels like the same size of step.
    val zoom by animateFloatAsState(ln(msPerDp), spring(dampingRatio = .8f, stiffness = Spring.StiffnessMediumLow), label = "ruler zoom")
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant, fontFeatureSettings = "tnum")
    Canvas(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        drawRect(Brush.horizontalGradient(0f to Color.Transparent, .18f to Color.Black, .82f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
    }) {
        val rate = exp(zoom)
        val pxPerMs = density / rate
        val center = size.width / 2
        val step = PreciseSeek.step(rate, RULER_MIN_DP)
        val major = PreciseSeek.major(step, rate, 72f)
        val baseline = size.height - 2.dp.toPx()
        fun marks(every: Long, alpha: Float, length: Float) {
            if (alpha <= 0f) return
            val first = max(0f, valueMs - center / pxPerMs)
            val last = min(durationMs, valueMs + center / pxPerMs)
            var t = (floor(first / every) * every).toLong()
            while (t <= last) {
                if (t >= 0 && (major == null || t % major != 0L)) {
                    val x = center + (t - valueMs) * pxPerMs
                    drawLine(colors.onSurfaceVariant.copy(alpha = .55f * alpha), Offset(x, baseline), Offset(x, baseline - length), 1.5.dp.toPx(), StrokeCap.Round)
                }
                t += every
            }
        }
        // Finer marks fade in between 4 and 8 dp apart, so zooming in grows detail smoothly.
        PreciseSeek.finer(step)?.let { finer -> marks(finer, ((finer * pxPerMs / density - 4f) / 4f).coerceIn(0f, 1f), 6.dp.toPx()) }
        marks(step, 1f, 10.dp.toPx())
        major?.let { every ->
            var t = (floor(max(0f, valueMs - center / pxPerMs) / every) * every).toLong()
            while (t <= min(durationMs, valueMs + center / pxPerMs)) {
                val x = center + (t - valueMs) * pxPerMs
                drawLine(colors.onSurface.copy(alpha = .8f), Offset(x, baseline), Offset(x, baseline - 18.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
                val text = measurer.measure(formatTime(t), labelStyle)
                drawText(text, topLeft = Offset(x - text.size.width / 2f, 0f))
                t += every
            }
        }
        // The ends of the part are drawn as walls the ruler cannot pass.
        listOf(0f, durationMs).forEach { edge ->
            val x = center + (edge - valueMs) * pxPerMs
            if (x in 0f..size.width) drawLine(colors.outline, Offset(x, baseline + 2.dp.toPx()), Offset(x, 14.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
        }
        drawLine(colors.outlineVariant, Offset(0f, baseline), Offset(size.width, baseline), 1.dp.toPx())
        // The needle: where the place is now.
        drawRoundRect(colors.primary, Offset(center - 1.5.dp.toPx(), 12.dp.toPx()), Size(3.dp.toPx(), size.height - 10.dp.toPx()), CornerRadius(1.5.dp.toPx()))
        drawCircle(colors.primary, 4.dp.toPx(), Offset(center, 12.dp.toPx()))
    }
}
