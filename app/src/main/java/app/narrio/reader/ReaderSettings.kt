package app.narrio.reader

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Book text faces: the publisher's, the four Appearance fonts, and OpenDyslexic. */
@Serializable
enum class ReaderFont(val label: String, val description: String) {
    PUBLISHER("Publisher", "The book's own typeface"),
    NARRIO("Narrio", "Manrope text, Newsreader headings"),
    NEWSREADER("Newsreader", "A literary serif"),
    MANROPE("Manrope", "A clean sans serif"),
    ANDROID("Android", "Your device's typeface"),
    OPEN_DYSLEXIC("OpenDyslexic", "Weighted letters for dyslexia"),
}

@Serializable
enum class ReaderSpacing(val label: String, val lineHeight: Double) { TIGHT("Tight", 1.3), NORMAL("Normal", 1.55), LOOSE("Loose", 1.85) }

@Serializable
enum class ReaderMargins(val label: String, val pageMargins: Double) { NARROW("Narrow", 0.5), NORMAL("Normal", 1.0), WIDE("Wide", 1.7) }

/**
 * Reader preferences kept on this phone. Spacing, justification and hyphenation apply only when [publisherStyles]
 * is off; adjusting any of them turns it off (see [withAdvanced]).
 */
@Serializable
data class ReaderSettings(
    val font: ReaderFont = ReaderFont.PUBLISHER,
    val fontScale: Double = 1.0,
    val spacing: ReaderSpacing = ReaderSpacing.NORMAL,
    val margins: ReaderMargins = ReaderMargins.NORMAL,
    val justify: Boolean = true,
    val hyphenate: Boolean = true,
    val publisherStyles: Boolean = true,
    val scroll: Boolean = false,
    val volumeKeys: Boolean = true,
) {
    fun normalized() = copy(fontScale = (Math.round(fontScale.coerceIn(MIN_SCALE, MAX_SCALE) * 20) / 20.0))
    fun larger() = copy(fontScale = fontScale + SCALE_STEP).normalized()
    fun smaller() = copy(fontScale = fontScale - SCALE_STEP).normalized()
    fun withAdvanced(change: ReaderSettings.() -> ReaderSettings) = change(this).copy(publisherStyles = false)

    companion object {
        const val MIN_SCALE = 0.7
        const val MAX_SCALE = 2.5
        const val SCALE_STEP = 0.1
    }
}

object ReaderSettingsCodec {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }
    fun encode(value: ReaderSettings): String = json.encodeToString(ReaderSettings.serializer(), value.normalized())
    fun decode(value: String?): ReaderSettings =
        value?.let { runCatching { json.decodeFromString(ReaderSettings.serializer(), it).normalized() }.getOrNull() } ?: ReaderSettings()
}
