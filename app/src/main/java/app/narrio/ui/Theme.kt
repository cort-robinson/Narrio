package app.narrio.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.sp
import app.narrio.R
import app.narrio.domain.*

@OptIn(ExperimentalTextApi::class)
private fun brandFont(resource: Int, weight: FontWeight) = Font(resource, weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))
val Editorial = FontFamily(brandFont(R.font.newsreader, FontWeight.Normal), brandFont(R.font.newsreader, FontWeight.Medium), brandFont(R.font.newsreader, FontWeight.SemiBold))
val Humanist = FontFamily(brandFont(R.font.manrope, FontWeight.Normal), brandFont(R.font.manrope, FontWeight.Medium), brandFont(R.font.manrope, FontWeight.SemiBold), brandFont(R.font.manrope, FontWeight.Bold))

private val Night = darkColorScheme(
    primary = Color(0xFFE8AF79), onPrimary = Color(0xFF342312), primaryContainer = Color(0xFF59402C), onPrimaryContainer = Color(0xFFFFDDBB),
    secondary = Color(0xFFB1C9AC), onSecondary = Color(0xFF1F3328), secondaryContainer = Color(0xFF304739), onSecondaryContainer = Color(0xFFD3E7CA),
    background = Color(0xFF101B1A), onBackground = Color(0xFFF4EDDE), surface = Color(0xFF101B1A), onSurface = Color(0xFFF4EDDE),
    surfaceVariant = Color(0xFF263832), onSurfaceVariant = Color(0xFFB9C8BD), outline = Color(0xFF809087), outlineVariant = Color(0xFF36483F),
    surfaceContainer = Color(0xFF1B2A27), surfaceContainerHigh = Color(0xFF263832), surfaceContainerLow = Color(0xFF15221F),
    surfaceContainerLowest = Color(0xFF101B1A), surfaceContainerHighest = Color(0xFF263832), surfaceDim = Color(0xFF101B1A), surfaceBright = Color(0xFF263832), surfaceTint = Color(0xFFE8AF79),
    tertiary = Color(0xFFB1C9AC), onTertiary = Color(0xFF1F3328), tertiaryContainer = Color(0xFF304739), onTertiaryContainer = Color(0xFFD3E7CA),
    inverseSurface = Color(0xFFF4EDDE), inverseOnSurface = Color(0xFF20332C), inversePrimary = Color(0xFF805031),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
)
private val Day = lightColorScheme(
    primary = Color(0xFF805031), onPrimary = Color(0xFFFFFFFF), primaryContainer = Color(0xFFFFDBC0), onPrimaryContainer = Color(0xFF3A2110),
    secondary = Color(0xFF476447), onSecondary = Color.White, secondaryContainer = Color(0xFFD4E5C9), onSecondaryContainer = Color(0xFF223B24),
    background = Color(0xFFF6F0E5), onBackground = Color(0xFF20332C), surface = Color(0xFFF6F0E5), onSurface = Color(0xFF20332C),
    surfaceVariant = Color(0xFFEEE5D5), onSurfaceVariant = Color(0xFF536359), outline = Color(0xFF727C71), outlineVariant = Color(0xFFD0D1C3),
    surfaceContainer = Color(0xFFEEE5D5), surfaceContainerHigh = Color(0xFFE7DCCA), surfaceContainerLow = Color(0xFFF3EBDC),
    surfaceContainerLowest = Color(0xFFF6F0E5), surfaceContainerHighest = Color(0xFFE7DCCA), surfaceDim = Color(0xFFE7DCCA), surfaceBright = Color(0xFFF6F0E5), surfaceTint = Color(0xFF805031),
    tertiary = Color(0xFF476447), onTertiary = Color.White, tertiaryContainer = Color(0xFFD4E5C9), onTertiaryContainer = Color(0xFF223B24),
    inverseSurface = Color(0xFF20332C), inverseOnSurface = Color(0xFFF4EDDE), inversePrimary = Color(0xFFE8AF79),
)

fun appFontFamily(font: AppFont, heading: Boolean = false): FontFamily = when (font) {
    AppFont.NARRIO -> if (heading) Editorial else Humanist
    AppFont.MANROPE -> Humanist
    AppFont.NEWSREADER -> Editorial
    AppFont.SYSTEM -> FontFamily.Default
}

fun typographyFor(font: AppFont, size: AppTextSize): Typography {
    fun type(points: Int, line: Int, heading: Boolean = false, weight: FontWeight = FontWeight.Normal): TextStyle {
        val family = appFontFamily(font, heading)
        return TextStyle(fontFamily = family, fontSize = (points * size.scale).sp, lineHeight = (line * size.scale).sp,
            fontWeight = weight, letterSpacing = if (family == Editorial) (-.4 * size.scale).sp else 0.sp)
    }
    return Typography(
        displayLarge = type(56, 58, true), displayMedium = type(44, 47, true), displaySmall = type(36, 40, true),
        headlineLarge = type(32, 36, true), headlineMedium = type(28, 32, true), headlineSmall = type(24, 28, true),
        titleLarge = type(22, 28, true, FontWeight.Medium), titleMedium = type(16, 23, weight = FontWeight.SemiBold), titleSmall = type(14, 20, weight = FontWeight.SemiBold),
        bodyLarge = type(16, 26), bodyMedium = type(14, 22), bodySmall = type(12, 19),
        labelLarge = type(14, 20, weight = FontWeight.SemiBold), labelMedium = type(12, 18, weight = FontWeight.SemiBold), labelSmall = type(11, 16, weight = FontWeight.Medium),
    )
}

fun colourSchemeFor(settings: AppearanceSettings, dark: Boolean): ColorScheme {
    if (settings.palette == ThemePalette.LISTENING_ROOM || settings.palette == ThemePalette.CUSTOM && settings.custom == null) return if (dark) Night else Day
    val colours = settings.colours(dark)
    val background = colours.background
    val low = ThemeContrast.surface(background, .035f)
    val container = ThemeContrast.surface(background, .065f)
    val high = ThemeContrast.surface(background, .10f)
    val highest = ThemeContrast.surface(background, .13f)
    val surfaces = listOf(background, low, container, high, highest)
    val ink = ThemeContrast.foreground(background)
    val primary = ThemeContrast.readable(colours.accent, surfaces)
    val secondary = ThemeContrast.readable(colours.secondary, surfaces)
    val primaryContainer = ThemeContrast.mix(background, primary, .18f)
    val secondaryContainer = ThemeContrast.mix(background, secondary, .18f)
    val error = ThemeContrast.readable(if (dark) 0xFFB4AB else 0xBA1A1A, surfaces)
    val errorContainer = ThemeContrast.mix(background, error, .18f)
    fun color(rgb: Int) = Color(0xFF000000.toInt() or rgb)
    val scheme = if (dark) darkColorScheme() else lightColorScheme()
    return scheme.copy(
        primary = color(primary), onPrimary = color(ThemeContrast.foreground(primary)),
        primaryContainer = color(primaryContainer), onPrimaryContainer = color(ThemeContrast.foreground(primaryContainer)),
        secondary = color(secondary), onSecondary = color(ThemeContrast.foreground(secondary)),
        secondaryContainer = color(secondaryContainer), onSecondaryContainer = color(ThemeContrast.foreground(secondaryContainer)),
        tertiary = color(secondary), onTertiary = color(ThemeContrast.foreground(secondary)),
        tertiaryContainer = color(secondaryContainer), onTertiaryContainer = color(ThemeContrast.foreground(secondaryContainer)),
        background = color(background), onBackground = color(ink), surface = color(background), onSurface = color(ink),
        surfaceDim = color(background), surfaceBright = color(highest), surfaceContainerLowest = color(background),
        surfaceContainerLow = color(low), surfaceContainer = color(container), surfaceContainerHigh = color(high), surfaceContainerHighest = color(highest),
        surfaceVariant = color(high), onSurfaceVariant = color(ThemeContrast.readable(ThemeContrast.mix(ink, background, .28f), surfaces)),
        outline = color(ThemeContrast.readable(ThemeContrast.mix(ink, background, .45f), surfaces, 3.0)),
        outlineVariant = color(ThemeContrast.mix(ink, background, .82f)), surfaceTint = color(primary),
        error = color(error), onError = color(ThemeContrast.foreground(error)),
        errorContainer = color(errorContainer), onErrorContainer = color(ThemeContrast.foreground(errorContainer)),
        inverseSurface = color(ink), inverseOnSurface = color(background), inversePrimary = color(ThemeContrast.readable(colours.accent, listOf(ink))),
    )
}

@Composable
fun NarrioTheme(settings: AppearanceSettings, dark: Boolean = settings.mode.isDark(isSystemInDarkTheme()), content: @Composable () -> Unit) {
    val colours = remember(settings.palette, settings.custom, dark) { colourSchemeFor(settings, dark) }
    val typography = remember(settings.font, settings.textSize) { typographyFor(settings.font, settings.textSize) }
    MaterialTheme(colorScheme = animatedColorScheme(colours), typography = typography, content = content)
}
