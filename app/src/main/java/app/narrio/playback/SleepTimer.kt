package app.narrio.playback

import app.narrio.domain.*
import kotlin.math.sqrt

enum class SleepMode { OFF, MINUTES, END_OF_CHAPTER, END_OF_PART }

/**
 * The sleep timer as the listener chose it. [minutes] remembers the preset so the dialog can show it selected (0 after
 * an extension, which no preset describes); [untilMs] is the wall-clock deadline of a minute timer. Chapter and part
 * timers stop at [stop], which follows the listener's seeks but not playback carrying on past it.
 */
data class SleepTimer(val mode: SleepMode = SleepMode.OFF, val minutes: Int = 0, val untilMs: Long = 0, val stop: PartPlace? = null) {
    val active: Boolean get() = mode != SleepMode.OFF
}

/** Where a chapter or part timer stops, from the current place; [PART_END] when it runs to the end of the part. */
fun sleepStop(mode: SleepMode, partIndex: Int, positionMs: Long, chapters: List<Chapter>): PartPlace? = when (mode) {
    SleepMode.END_OF_CHAPTER -> PartPlace(partIndex, chapterEndMs(chapters, positionMs) ?: PART_END)
    SleepMode.END_OF_PART -> PartPlace(partIndex, PART_END)
    else -> null
}

/**
 * Real time until the timer pauses playback, accounting for [speed]: zero or less once it should pause, null while
 * that can't be known (a part whose length hasn't loaded, or a place before the stop's part after a seek). Minute
 * timers count wall-clock time, paused or not.
 */
fun sleepRemainingMs(timer: SleepTimer, partIndex: Int, positionMs: Long, durationMs: Long, speed: Float, nowMs: Long): Long? = when (timer.mode) {
    SleepMode.OFF -> null
    SleepMode.MINUTES -> timer.untilMs - nowMs
    SleepMode.END_OF_CHAPTER, SleepMode.END_OF_PART -> timer.stop?.let { stop ->
        when {
            partIndex > stop.partIndex -> 0L
            partIndex < stop.partIndex -> null
            else -> (if (stop.positionMs == PART_END) durationMs.takeIf { it > 0 } else stop.positionMs)
                ?.let { atSpeed(it - positionMs, speed) }
        }
    }
}

/** The timer after "+15 min": a minute timer ending 15 minutes after the moment it would have paused. */
fun extendSleep(timer: SleepTimer, remainingMs: Long?, nowMs: Long, extraMs: Long = SLEEP_EXTENSION_MS): SleepTimer =
    SleepTimer(SleepMode.MINUTES, 0, nowMs + (if (timer.active) remainingMs ?: 0 else 0).coerceAtLeast(0) + extraMs)

/**
 * Whether the timer pauses now. A stop at a part's end waits for playback to cross into the next part, so the place is
 * kept at that part's start (the part change itself pauses); the last part's end stops playback anyway.
 */
fun sleepPausesNow(timer: SleepTimer, partIndex: Int, remainingMs: Long?): Boolean =
    remainingMs != null && remainingMs <= 0 && !(timer.stop?.positionMs == PART_END && timer.stop?.partIndex == partIndex)

/**
 * Narration volume while a timer runs out: full until the last [fadeMs], then down to silence. Squaring the ramp keeps
 * the perceived fade even, rather than staying loud and dropping away at the end.
 */
fun sleepFadeVolume(remainingMs: Long?, fadeMs: Long = SLEEP_FADE_MS): Float {
    if (remainingMs == null || remainingMs >= fadeMs) return 1f
    val ramp = (remainingMs.toFloat() / fadeMs).coerceIn(0f, 1f)
    return ramp * ramp
}

/** How long the service waits before checking the timer again: rarely while far off, finely while fading. */
fun sleepCheckDelayMs(remainingMs: Long?, fadeMs: Long = SLEEP_FADE_MS): Long = when {
    remainingMs == null -> 1_000
    remainingMs <= 0 -> 100
    remainingMs > fadeMs -> (remainingMs - fadeMs).coerceIn(50, 1_000)
    else -> remainingMs.coerceIn(10, 100)
}

/**
 * A deliberate shake: [hits] separate peaks over [thresholdG] within [windowMs], then a [cooldownMs] pause so one
 * shake adds time once. Turning over in bed rarely passes 2 g even once; a shake passes it on every stroke.
 */
class ShakeDetector(private val thresholdG: Float = 2.2f, private val hits: Int = 3, private val windowMs: Long = 1_200, private val cooldownMs: Long = 2_500) {
    private val peaks = ArrayDeque<Long>()
    private var above = false
    private var quietUntil = 0L

    /** Feeds one accelerometer sample in m/s²; true when it completes a shake. */
    fun sample(x: Float, y: Float, z: Float, timeMs: Long): Boolean {
        val g = sqrt(x * x + y * y + z * z) / 9.80665f
        val rising = g > thresholdG && !above
        above = g > thresholdG * .8f && (above || g > thresholdG)
        if (!rising || timeMs < quietUntil) return false
        peaks.addLast(timeMs)
        while (peaks.isNotEmpty() && timeMs - peaks.first() > windowMs) peaks.removeFirst()
        if (peaks.size < hits) return false
        peaks.clear(); quietUntil = timeMs + cooldownMs
        return true
    }

    fun reset() { peaks.clear(); above = false }
}

/** Stops at the part's end rather than a chapter boundary; resolved against the part's length when known. */
const val PART_END = Long.MAX_VALUE
const val SLEEP_FADE_MS = 12_000L
const val SLEEP_EXTENSION_MS = 15 * 60_000L
/** Shake-to-extend listens only in the timer's last minute, while playing, so the sensor stays off almost always. */
const val SHAKE_WINDOW_MS = 60_000L
