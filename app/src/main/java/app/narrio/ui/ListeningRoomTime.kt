package app.narrio.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.NavigateBefore
import androidx.compose.material.icons.automirrored.rounded.NavigateNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.narrio.domain.*
import app.narrio.playback.*

/** One sleep timer option. */
internal data class SleepChoice(val label: String, val mode: SleepMode, val minutes: Int = 0) {
    fun selected(timer: SleepTimer) = timer.mode == mode && (mode != SleepMode.MINUTES || timer.minutes == minutes)
}

/**
 * The options that fit this recording: minutes always; the end of the chapter once its chapters are known; the end of
 * the audio part only when there are several parts (a single file's part is the whole book). An active choice stays
 * listed so it can be seen selected.
 */
internal fun sleepChoices(state: ListeningState): List<SleepChoice> = buildList {
    listOf(15, 30, 45, 60, 90).forEach { add(SleepChoice("In $it minutes", SleepMode.MINUTES, it)) }
    if (state.chapters.isNotEmpty() || state.sleep.mode == SleepMode.END_OF_CHAPTER) add(SleepChoice("At the end of this chapter", SleepMode.END_OF_CHAPTER))
    if ((state.source?.parts?.size ?: 0) > 1 || state.sleep.mode == SleepMode.END_OF_PART) add(SleepChoice("At the end of this audio part", SleepMode.END_OF_PART))
    add(SleepChoice("Turn timer off", SleepMode.OFF))
}

/** Whole minutes left on the timer, rounded up so it never reads 0 while running; null when unknown. */
internal fun sleepMinutes(state: ListeningState, nowMs: Long = System.currentTimeMillis()): Long? =
    state.sleepRemainingMs(nowMs)?.let { ((it.coerceAtLeast(0) + 59_999) / 60_000).coerceAtLeast(1) }

/** What the running timer will do, in the dialog's words. */
internal fun sleepStatus(state: ListeningState, nowMs: Long = System.currentTimeMillis()): String {
    val minutes = sleepMinutes(state, nowMs)
    fun count(n: Long) = "$n minute${if (n == 1L) "" else "s"}"
    val about = minutes?.let { ", in about ${count(it)}" }.orEmpty()
    return when (state.sleep.mode) {
        SleepMode.OFF -> "Choose when playback pauses. The narration fades out over its last few seconds."
        SleepMode.MINUTES -> "Pauses in about ${count(minutes ?: 1)}."
        SleepMode.END_OF_CHAPTER -> "Pauses at the end of this chapter$about."
        SleepMode.END_OF_PART -> "Pauses at the end of this audio part$about."
    }
}

/** The sleep slot's short label: "Sleep", minutes left, or where it stops. */
internal fun sleepLabel(state: ListeningState) = when (state.sleep.mode) {
    SleepMode.OFF -> "Sleep"
    SleepMode.MINUTES -> "${sleepMinutes(state) ?: 1}m"
    SleepMode.END_OF_CHAPTER -> "End of chapter"
    SleepMode.END_OF_PART -> "End of part"
}

/** Sleep timer choices, shared by the Listening room and read along. */
@Composable
internal fun SleepDialog(vm: NarrioViewModel, state: ListeningState, dismiss: () -> Unit) {
    val service = vm.graph.playback.service
    var shake by remember { mutableStateOf(vm.graph.preferences.getBoolean(ListeningService.SHAKE_TO_EXTEND, true)) }
    SleepTimerDialog(state, { choice -> service?.sleep(choice.mode, choice.minutes); dismiss() }, { service?.extendSleep() },
        shake.takeIf { hasAccelerometer() }, { shake = it; vm.graph.preferences.edit().putBoolean(ListeningService.SHAKE_TO_EXTEND, it).apply() }, dismiss)
}

/** The dialog itself, separate from the service so its choices can be checked with fixed states. [shake] is null without a motion sensor. */
@Composable
internal fun SleepTimerDialog(state: ListeningState, choose: (SleepChoice) -> Unit, extend: () -> Unit, shake: Boolean?, setShake: (Boolean) -> Unit, dismiss: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    ChoiceDialog("Sleep timer", dismiss, extra = if (state.sleep.active) ({
        TextButton({ haptics.performHapticFeedback(HapticFeedbackType.Confirm); extend() }, Modifier.testTag("sleep-extend").semantics { contentDescription = "Add 15 minutes" }) { Text("+15 min") }
    }) else null) {
        Text(sleepStatus(state), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp).testTag("sleep-status").semantics { liveRegion = LiveRegionMode.Polite })
        Column(Modifier.selectableGroup()) {
            sleepChoices(state).forEach { choice -> OptionRow(choice.label, choice.selected(state.sleep)) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); choose(choice) } }
        }
        if (shake != null) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(shake, role = Role.Switch, onValueChange = setShake).padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Shake to keep listening", style = MaterialTheme.typography.bodyLarge)
                    Text("In the timer's last minute, shake the phone to add 15 minutes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(shake, null)
            }
        }
    }
}

@Composable
private fun hasAccelerometer(): Boolean {
    val context = LocalContext.current
    return remember { (context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null }
}

/** "Ch 12 · 9 h 41 m left in book (7 h 45 m at 1.25×)" and how a screen reader says it. */
internal data class TimeLeft(val text: String, val spoken: String)

/**
 * Time left in the whole book when every part's length is known, otherwise in this part; never a guess. The chapter
 * number appears for single-file books, whose chapters number the whole book. At other speeds it adds the listening
 * time at that speed.
 */
internal fun timeLeft(state: ListeningState): TimeLeft? {
    val book = state.bookTime
    val (remaining, where) = when {
        book != null -> book.remainingMs to "book"
        state.durationMs > 0 -> (state.durationMs - state.positionMs).coerceAtLeast(0) to "this part"
        else -> return null
    }
    val chapter = chapterIndexAt(state.chapters, state.positionMs).takeIf { it >= 0 && book != null && state.source?.parts?.size == 1 }?.let { it + 1 }
    val faster = state.speed != 1f && state.speed > 0f
    val text = listOfNotNull(chapter?.let { "Ch $it" }, "${timeLeftLabel(remaining)} left in $where").joinToString(" · ") +
        if (faster) " (${timeLeftLabel(atSpeed(remaining, state.speed))} at ${speedLabel(state.speed)})" else ""
    val spoken = listOfNotNull(chapter?.let { "Chapter $it" }, "${spokenTimeLeft(remaining)} left in $where",
        if (faster) "${spokenTimeLeft(atSpeed(remaining, state.speed))} at ${speedLabel(state.speed).removeSuffix("×")} times speed" else null).joinToString(", ")
    return TimeLeft(text, spoken)
}

/** [dense] (short windows) keeps it to one line. */
@Composable
internal fun TimeLeftLine(state: ListeningState, modifier: Modifier = Modifier, dense: Boolean = false) {
    val line = timeLeft(state) ?: return
    Text(line.text, modifier.testTag("book-time-left").semantics { contentDescription = line.spoken }, style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.secondary, maxLines = if (dense) 1 else 2, overflow = TextOverflow.Ellipsis)
}

/**
 * The title line: the chapter playing now (the part when there are no chapters), with previous/next beside it for
 * chapter-aware or multipart recordings. Moving keeps playing or paused as it was.
 */
@Composable
internal fun ChapterTitleRow(vm: NarrioViewModel, state: ListeningState, dense: Boolean, subtitle: String?) {
    val haptics = LocalHapticFeedback.current
    val parts = state.source?.parts?.size ?: 1
    val chapters = state.chapters.isNotEmpty()
    val noun = if (chapters) "chapter" else "part"
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(state.chapter?.title ?: state.part?.title ?: "Ready to listen", Modifier.testTag("listening-title"), style = MaterialTheme.typography.titleSmall,
                maxLines = if (dense) 1 else 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (chapters || parts > 1) {
            listOf(false, true).forEach { forward ->
                val target = state.step(forward)
                IconButton({ target?.let { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); vm.graph.playback.service?.go(it) } }, enabled = target != null,
                    modifier = Modifier.testTag(if (forward) "next-chapter" else "previous-chapter")) {
                    Icon(if (forward) Icons.AutoMirrored.Rounded.NavigateNext else Icons.AutoMirrored.Rounded.NavigateBefore, if (forward) "Next $noun" else "Previous $noun")
                }
            }
        }
    }
}
