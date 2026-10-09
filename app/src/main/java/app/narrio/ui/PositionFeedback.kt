package app.narrio.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import app.narrio.domain.MappingConfidence
import app.narrio.domain.PositionOrigin
import kotlin.math.abs

/*
 * Shared feedback for moving between reading and listening. The reader, together mode, and the library use these
 * so a mapped place and a jump look and sound the same everywhere.
 */

/** "≈" marks a place mapped from the other mode until narration confirms it. */
fun approximate(place: String, confidence: MappingConfidence): String = if (confidence == MappingConfidence.ESTIMATED) "≈ $place" else place

/** What a screen reader hears for [approximate]: "about Chapter 12, estimated". */
fun spokenApproximate(place: String, confidence: MappingConfidence): String =
    if (confidence == MappingConfidence.ESTIMATED) "about ${spokenPlace(place)}, estimated" else spokenPlace(place)

/** A place with its "≈" when estimated. [prefix] ("Reading opens at ") is read together with the place. */
@Composable
fun EstimatedPlace(place: String, confidence: MappingConfidence, modifier: Modifier = Modifier, prefix: String = "",
                   style: TextStyle = MaterialTheme.typography.labelMedium, color: Color = MaterialTheme.colorScheme.secondary) {
    Text(prefix + approximate(place, confidence), modifier.semantics { contentDescription = prefix + spokenApproximate(place, confidence) }, style = style, color = color)
}

/**
 * A move to the shared place that was larger than [app.narrio.domain.SyncThresholds] allow silently. [from] is the
 * mode whose activity set that place ("where you read"); [undo] returns to where this mode was before the jump.
 */
data class PositionJump(val bookId: String, val from: PositionOrigin, val place: String, val confidence: MappingConfidence = MappingConfidence.EXACT, val undo: () -> Unit) {
    val message: String get() = "Jumped to where you ${if (from == PositionOrigin.READING) "read" else "listened"}"
}

class JumpSnackbarVisuals(val jump: PositionJump) : SnackbarVisuals {
    override val message: String get() = jump.message
    override val actionLabel: String get() = "Undo"
    override val withDismissAction: Boolean get() = false
    // Long, so Undo stays reachable; SnackbarHost extends it further for accessibility services.
    override val duration: SnackbarDuration get() = SnackbarDuration.Long
}

/** Shows [jump] and runs its Undo when chosen. Returns true when the listener undid the jump. */
suspend fun SnackbarHostState.showJump(jump: PositionJump): Boolean =
    (showSnackbar(JumpSnackbarVisuals(jump)) == SnackbarResult.ActionPerformed).also { if (it) jump.undo() }

/**
 * The app's snackbar: jumps get their own treatment; everything else stays a standard Material snackbar.
 * Any of them can be swiped sideways to dismiss, fading as it goes; TalkBack offers the same as a Dismiss action.
 */
@Composable
fun NarrioSnackbar(data: SnackbarData) {
    val jump = (data.visuals as? JumpSnackbarVisuals)?.jump
    key(data) {
        val swipe = rememberSwipeToDismissBoxState()
        SwipeToDismissBox(swipe, backgroundContent = {}, onDismiss = { data.dismiss() },
            modifier = Modifier.semantics { customActions = listOf(CustomAccessibilityAction("Dismiss") { data.dismiss(); true }) }) {
            val width = LocalWindowInfo.current.containerSize.width.coerceAtLeast(1)
            Box(Modifier.graphicsLayer { alpha = 1f - (abs(runCatching { swipe.requireOffset() }.getOrDefault(0f)) / (width * .6f)).coerceIn(0f, 1f) }) {
                if (jump == null) Snackbar(data) else JumpSnackbar(jump, data::performAction)
            }
        }
    }
}

@Composable
fun JumpSnackbar(jump: PositionJump, undo: () -> Unit, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.inversePrimary
    Snackbar(modifier.padding(12.dp), action = { TextButton(undo, colors = ButtonDefaults.textButtonColors(contentColor = accent)) { Text("Undo") } }) {
        Row(Modifier.semantics(mergeDescendants = true) { contentDescription = "${jump.message}, ${spokenApproximate(jump.place, jump.confidence)}" },
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (jump.from == PositionOrigin.READING) Icons.AutoMirrored.Rounded.MenuBook else Icons.Rounded.Headphones, null, Modifier.size(20.dp), tint = accent)
            Column {
                Text(jump.message, style = MaterialTheme.typography.bodyMedium)
                Text(approximate(jump.place, jump.confidence), style = MaterialTheme.typography.labelMedium, color = accent)
            }
        }
    }
}
