package app.narrio.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

@Serializable
enum class ThemeMode(val label: String) {
    NIGHT("Night"), DAY("Day"), SYSTEM("System");

    fun isDark(systemDark: Boolean) = when (this) { NIGHT -> true; DAY -> false; SYSTEM -> systemDark }
}

@Serializable
enum class ThemePalette(val label: String, val description: String) {
    LISTENING_ROOM("Listening room", "Copper & sage"),
    OCEAN("Ocean", "Sea glass & blue ink"),
    FOREST("Forest", "Fern & golden light"),
    ROSEWOOD("Rosewood", "Rose & plum"),
    GRAPHITE("Graphite", "Quiet, neutral tones"),
    CUSTOM("Custom", "Your own colours");
}

@Serializable
enum class AppFont(val label: String, val description: String) {
    NARRIO("Narrio", "Newsreader titles, Manrope controls"),
    MANROPE("Manrope", "A clean sans serif throughout"),
    NEWSREADER("Newsreader", "A literary serif throughout"),
    SYSTEM("Android", "Your device's familiar typeface");
}

@Serializable
enum class AppTextSize(val label: String, val scale: Float) {
    STANDARD("Default", 1f), COMFORTABLE("Comfort", 1.1f), LARGE("Large", 1.2f);
}

/** Opaque sRGB colours; all text and Material surface roles are derived from these. */
@Serializable
data class ThemeColours(val accent: Int, val secondary: Int, val background: Int) {
    fun normalized() = ThemeColours(accent and 0xFFFFFF, secondary and 0xFFFFFF, background and 0xFFFFFF)
}

fun presetColours(palette: ThemePalette, dark: Boolean): ThemeColours = when (palette) {
    ThemePalette.OCEAN -> if (dark) ThemeColours(0x8ACED8, 0xB0BEDF, 0x101D26) else ThemeColours(0x226875, 0x525F84, 0xF0F5F7)
    ThemePalette.FOREST -> if (dark) ThemeColours(0xB5D49C, 0xE3C28A, 0x161E16) else ThemeColours(0x4C6838, 0x7C5D2C, 0xF2F4EA)
    ThemePalette.ROSEWOOD -> if (dark) ThemeColours(0xE9B1BE, 0xCCBAE0, 0x261A22) else ThemeColours(0x914D64, 0x69567D, 0xFAF1F3)
    ThemePalette.GRAPHITE -> if (dark) ThemeColours(0xD3D8DF, 0xB8C1CA, 0x191C20) else ThemeColours(0x454C56, 0x58626F, 0xF3F4F6)
    else -> if (dark) ThemeColours(0xE8AF79, 0xB1C9AC, 0x101B1A) else ThemeColours(0x805031, 0x476447, 0xF6F0E5)
}

@Serializable
data class CustomTheme(
    val name: String = "My theme",
    val night: ThemeColours = presetColours(ThemePalette.LISTENING_ROOM, true),
    val day: ThemeColours = presetColours(ThemePalette.LISTENING_ROOM, false),
) {
    fun normalized() = copy(name = name.trim().take(28).ifBlank { "My theme" }, night = night.normalized(), day = day.normalized())

    companion object {
        fun from(palette: ThemePalette) = CustomTheme(night = presetColours(palette, true), day = presetColours(palette, false))
    }
}

@Serializable
data class AppearanceSettings(
    val mode: ThemeMode = ThemeMode.NIGHT,
    val palette: ThemePalette = ThemePalette.LISTENING_ROOM,
    val font: AppFont = AppFont.NARRIO,
    val textSize: AppTextSize = AppTextSize.STANDARD,
    val custom: CustomTheme? = null,
) {
    val paletteName get() = if (palette == ThemePalette.CUSTOM) custom?.name ?: "Custom" else palette.label
    fun colours(dark: Boolean) = if (palette == ThemePalette.CUSTOM && custom != null) {
        if (dark) custom.night else custom.day
    } else presetColours(palette, dark)
    fun normalized() = copy(
        custom = custom?.normalized(),
        palette = if (palette == ThemePalette.CUSTOM && custom == null) ThemePalette.LISTENING_ROOM else palette,
    )
}

/** Tolerates newer optional fields, unknown enum values, and damaged local settings. */
object AppearanceCodec {
    val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }
    fun encode(value: AppearanceSettings): String = json.encodeToString(AppearanceSettings.serializer(), value.normalized())
    fun decode(value: String?, legacyMode: String? = null): AppearanceSettings {
        val fallback = AppearanceSettings(mode = when (legacyMode) { "Day" -> ThemeMode.DAY; "System" -> ThemeMode.SYSTEM; else -> ThemeMode.NIGHT })
        return value?.let { runCatching { json.decodeFromString(AppearanceSettings.serializer(), it).normalized() }.getOrNull() } ?: fallback
    }
}

/** WCAG sRGB contrast, shared by custom-theme generation and validation. */
object ThemeContrast {
    fun parseHex(value: String): Int? = value.trim().removePrefix("#").takeIf { it.matches(Regex("[0-9a-fA-F]{6}")) }?.toIntOrNull(16)
    fun hex(colour: Int) = String.format(Locale.ROOT, "#%06X", colour and 0xFFFFFF)

    fun luminance(colour: Int): Double {
        fun channel(shift: Int): Double {
            val value = ((colour shr shift) and 255) / 255.0
            return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
        }
        return .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
    }

    fun ratio(first: Int, second: Int): Double {
        val a = luminance(first); val b = luminance(second)
        return (maxOf(a, b) + .05) / (minOf(a, b) + .05)
    }

    fun mix(first: Int, second: Int, amount: Float): Int {
        fun channel(shift: Int) = (((first shr shift) and 255) * (1 - amount) + ((second shr shift) and 255) * amount).roundToInt().coerceIn(0, 255)
        return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    fun foreground(background: Int) = if (ratio(0xFFFFFF, background) >= ratio(0x000000, background)) 0xFFFFFF else 0x000000

    /** Retain the chosen hue where possible; move toward readable ink only as needed. */
    fun readable(colour: Int, backgrounds: List<Int>, minimum: Double = 4.5): Int {
        fun contrast(candidate: Int) = backgrounds.minOf { ratio(candidate, it) }
        if (contrast(colour) >= minimum) return colour
        val ink = listOf(0x000000, 0xFFFFFF).maxBy { contrast(it) }
        for (step in 1..255) {
            val candidate = mix(colour, ink, step / 255f)
            if (contrast(candidate) >= minimum) return candidate
        }
        return ink
    }

    fun surface(background: Int, amount: Float): Int {
        val ink = foreground(background)
        val tonal = mix(background, ink, amount)
        return if (ratio(ink, tonal) >= 4.5) tonal else mix(background, ink xor 0xFFFFFF, amount)
    }
}
