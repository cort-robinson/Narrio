package app.narrio.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

@Serializable
enum class ThemeMode(val label: String) {
    NIGHT("Night"), DAY("Day"), SYSTEM("System");

    fun isDark(systemDark: Boolean) = when (this) { NIGHT -> true; DAY -> false; SYSTEM -> systemDark }
}

@Serializable
enum class ThemePalette(val label: String, val description: String, val seasonal: Boolean = false) {
    LISTENING_ROOM("Listening room", "Copper & sage"),
    OCEAN("Ocean", "Sea glass & blue ink"),
    FOREST("Forest", "Fern & golden light"),
    ROSEWOOD("Rosewood", "Rose & plum"),
    GRAPHITE("Graphite", "Quiet, neutral tones"),
    LAVENDER("Lavender", "Lilac & silver"),
    AMETHYST("Amethyst", "Violet & candlelight gold"),
    MIDNIGHT("Midnight", "Indigo & starlight"),
    EMBER("Ember", "Terracotta & ochre"),
    HALLOWEEN("Halloween", "Pumpkin & black", seasonal = true),
    CUSTOM("Custom", "Your own colours");
}

/** Night backgrounds in true black for OLED screens: only the reader's pages, or all of Narrio. */
@Serializable
enum class PureBlack(val label: String, val description: String) {
    OFF("Off", "Night uses each palette's own dark colour."),
    READER("Reader", "Book pages turn true black at night."),
    EVERYWHERE("Everywhere", "All of Narrio turns true black at night."),
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
    ThemePalette.LAVENDER -> if (dark) ThemeColours(0xCDB8F0, 0xB9C3D6, 0x1A1724) else ThemeColours(0x6A4FA0, 0x56607A, 0xF6F3FB)
    ThemePalette.AMETHYST -> if (dark) ThemeColours(0xD29CF5, 0xE6C77A, 0x170F22) else ThemeColours(0x7B3FA6, 0x7A5C1A, 0xF8F1FA)
    ThemePalette.MIDNIGHT -> if (dark) ThemeColours(0xA9BBFF, 0xD7D2B8, 0x0E1224) else ThemeColours(0x3A4E9A, 0x5F5C47, 0xF1F3FA)
    ThemePalette.EMBER -> if (dark) ThemeColours(0xF0A58A, 0xE2C27A, 0x221512) else ThemeColours(0x9A4A2E, 0x7A5B1E, 0xFAF1EC)
    ThemePalette.HALLOWEEN -> if (dark) ThemeColours(0xFF8A1F, 0xF2C14E, 0x0C0A09) else ThemeColours(0xB34A00, 0x2A2522, 0xFFF1E3)
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
    val pureBlack: PureBlack = PureBlack.OFF,
    /** Books without a colour choice of their own take their colours from their cover. */
    val coverThemes: Boolean = false,
) {
    val paletteName get() = if (palette == ThemePalette.CUSTOM) custom?.name ?: "Custom" else palette.label
    /** Night backgrounds are true black here; [forBook] resolves [PureBlack.READER] for the screen shown. */
    fun blackAt(dark: Boolean) = dark && pureBlack == PureBlack.EVERYWHERE
    fun colours(dark: Boolean): ThemeColours {
        val colours = if (palette == ThemePalette.CUSTOM && custom != null) {
            if (dark) custom.night else custom.day
        } else presetColours(palette, dark)
        return if (blackAt(dark)) colours.copy(background = 0x000000) else colours
    }
    fun normalized() = copy(
        custom = custom?.normalized(),
        palette = if (palette == ThemePalette.CUSTOM && custom == null) ThemePalette.LISTENING_ROOM else palette,
    )
}

/** How one book is coloured while it is read or listened to. */
@Serializable
enum class BookColours { APP, COVER, PALETTE }

@Serializable
data class BookTheme(val colours: BookColours = BookColours.APP, val palette: ThemePalette = ThemePalette.LISTENING_ROOM) {
    fun normalized() = if (colours == BookColours.PALETTE) this else copy(palette = ThemePalette.LISTENING_ROOM)
}

/** Per-book colour choices and the palettes drawn from book covers, kept on this device. */
@Serializable
data class BookThemes(val choices: Map<String, BookTheme> = emptyMap(), val covers: Map<String, CustomTheme> = emptyMap()) {
    /** The book's own choice, or the Appearance default: its cover when [AppearanceSettings.coverThemes] is on. */
    fun choice(bookId: String, appearance: AppearanceSettings) =
        choices[bookId] ?: BookTheme(if (appearance.coverThemes) BookColours.COVER else BookColours.APP)

    fun with(bookId: String, theme: BookTheme) = copy(choices = choices + (bookId to theme.normalized()))

    /** Keeps the most recent covers only; a forgotten cover is derived again when its book next opens. */
    fun withCover(bookId: String, cover: CustomTheme) =
        copy(covers = (covers - bookId + (bookId to cover)).entries.toList().takeLast(MAX_COVERS).associate { it.key to it.value })

    companion object { const val MAX_COVERS = 200 }
}

/**
 * The appearance while [bookId] is on screen: the book's own colours, with pure black resolved for whether the reader
 * is showing. Never saved; Appearance settings remain the app-wide choice.
 */
fun AppearanceSettings.forBook(bookId: String?, themes: BookThemes, reading: Boolean): AppearanceSettings {
    val black = pureBlack == PureBlack.EVERYWHERE || pureBlack == PureBlack.READER && reading
    val base = copy(pureBlack = if (black) PureBlack.EVERYWHERE else PureBlack.OFF)
    if (bookId == null) return base
    val choice = themes.choice(bookId, this)
    return when (choice.colours) {
        BookColours.APP -> base
        BookColours.PALETTE -> base.copy(palette = choice.palette).normalized()
        BookColours.COVER -> themes.covers[bookId]?.let { base.copy(palette = ThemePalette.CUSTOM, custom = it) } ?: base
    }
}

object BookThemesCodec {
    fun encode(value: BookThemes): String = AppearanceCodec.json.encodeToString(BookThemes.serializer(), value)
    fun decode(value: String?): BookThemes =
        value?.let { runCatching { AppearanceCodec.json.decodeFromString(BookThemes.serializer(), it) }.getOrNull() } ?: BookThemes()
}

/**
 * Derives a Day and Night palette from a cover's ARGB pixels: the most prominent hue becomes the accent, a distinct
 * second hue (or a neighbour of the first) supports it, and backgrounds are tinted toward the accent. Covers with
 * almost no colour get Graphite. Contrast is enforced afterwards, as for any custom palette.
 */
object CoverColours {
    private const val BUCKETS = 24
    private const val NAME = "Cover"

    fun theme(pixels: IntArray): CustomTheme {
        val weights = DoubleArray(BUCKETS)
        val hueX = DoubleArray(BUCKETS); val hueY = DoubleArray(BUCKETS); val saturation = DoubleArray(BUCKETS)
        var opaque = 0; var colourful = 0
        for (pixel in pixels) {
            if ((pixel ushr 24) < 128) continue
            opaque++
            val (h, s, v) = hsv(pixel)
            if (s < .18f || v < .18f) continue
            colourful++
            val weight = (s * s * v).toDouble()
            val bucket = (h / (360f / BUCKETS)).toInt().coerceIn(0, BUCKETS - 1)
            val radians = Math.toRadians(h.toDouble())
            weights[bucket] += weight
            hueX[bucket] += cos(radians) * weight; hueY[bucket] += sin(radians) * weight
            saturation[bucket] += s * weight
        }
        if (opaque == 0 || colourful < opaque * .03) return CustomTheme.from(ThemePalette.GRAPHITE).copy(name = NAME)
        fun smoothed(i: Int) = weights[(i + BUCKETS - 1) % BUCKETS] + 2 * weights[i] + weights[(i + 1) % BUCKETS]
        fun hue(i: Int) = ((Math.toDegrees(atan2(hueY[i], hueX[i])) + 360) % 360).toFloat()
        fun distance(a: Float, b: Float) = abs(a - b).let { minOf(it, 360 - it) }
        val first = (0 until BUCKETS).filter { weights[it] > 0 }.maxBy(::smoothed)
        val accentHue = hue(first)
        val accentSaturation = (saturation[first] / weights[first]).toFloat()
        val second = (0 until BUCKETS).filter { weights[it] > 0 && distance(hue(it), accentHue) >= 45 && smoothed(it) >= smoothed(first) * .08 }
            .maxByOrNull(::smoothed)
        val supportHue = second?.let(::hue) ?: ((accentHue + 35) % 360)
        val supportSaturation = second?.let { (saturation[it] / weights[it]).toFloat() } ?: (accentSaturation * .7f)
        return CustomTheme(NAME,
            night = ThemeColours(rgb(accentHue, accentSaturation.coerceIn(.35f, .65f), .93f), rgb(supportHue, (supportSaturation * .8f).coerceIn(.25f, .55f), .86f),
                rgb(accentHue, (accentSaturation * .5f).coerceIn(.18f, .45f), .11f)),
            day = ThemeColours(rgb(accentHue, accentSaturation.coerceIn(.55f, .85f), .52f), rgb(supportHue, supportSaturation.coerceIn(.4f, .7f), .44f),
                rgb(accentHue, (accentSaturation * .15f).coerceIn(.04f, .10f), .97f)),
        )
    }

    fun hsv(colour: Int): Triple<Float, Float, Float> {
        val r = ((colour shr 16) and 255) / 255f; val g = ((colour shr 8) and 255) / 255f; val b = (colour and 255) / 255f
        val max = maxOf(r, g, b); val delta = max - minOf(r, g, b)
        val hue = when {
            delta == 0f -> 0f
            max == r -> 60f * (((g - b) / delta) % 6f)
            max == g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }.let { if (it < 0f) it + 360f else it }
        return Triple(hue, if (max == 0f) 0f else delta / max, max)
    }

    fun rgb(hue: Float, saturation: Float, value: Float): Int {
        val c = value * saturation
        val x = c * (1 - abs((hue / 60f) % 2f - 1))
        val m = value - c
        val (r, g, b) = when ((hue / 60f).toInt().coerceIn(0, 5)) {
            0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        fun channel(v: Float) = ((v + m) * 255).roundToInt().coerceIn(0, 255)
        return (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }
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
