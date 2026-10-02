package app.narrio.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.sp
import app.narrio.R

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
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
)
private val Day = lightColorScheme(
    primary = Color(0xFF805031), onPrimary = Color(0xFFFFFFFF), primaryContainer = Color(0xFFFFDBC0), onPrimaryContainer = Color(0xFF3A2110),
    secondary = Color(0xFF476447), onSecondary = Color.White, secondaryContainer = Color(0xFFD4E5C9), onSecondaryContainer = Color(0xFF223B24),
    background = Color(0xFFF6F0E5), onBackground = Color(0xFF20332C), surface = Color(0xFFF6F0E5), onSurface = Color(0xFF20332C),
    surfaceVariant = Color(0xFFEEE5D5), onSurfaceVariant = Color(0xFF56665C), outline = Color(0xFF727C71), outlineVariant = Color(0xFFD0D1C3),
    surfaceContainer = Color(0xFFEEE5D5), surfaceContainerHigh = Color(0xFFE7DCCA), surfaceContainerLow = Color(0xFFF3EBDC),
)

private fun type(size: Int, line: Int, serif: Boolean = false, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = if (serif) Editorial else Humanist, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight,
    letterSpacing = if (serif) (-0.4).sp else 0.sp,
)
val NarrioTypography = Typography(
    displayLarge = type(56, 58, true), displayMedium = type(44, 47, true), displaySmall = type(36, 40, true),
    headlineLarge = type(32, 36, true), headlineMedium = type(28, 32, true), headlineSmall = type(24, 28, true),
    titleLarge = type(22, 28, true, FontWeight.Medium), titleMedium = type(16, 23, weight = FontWeight.SemiBold), titleSmall = type(14, 20, weight = FontWeight.SemiBold),
    bodyLarge = type(16, 26), bodyMedium = type(14, 22), bodySmall = type(12, 19),
    labelLarge = type(14, 20, weight = FontWeight.SemiBold), labelMedium = type(12, 18, weight = FontWeight.SemiBold), labelSmall = type(11, 16, weight = FontWeight.Medium),
)

@Composable
fun NarrioTheme(theme: String, content: @Composable () -> Unit) {
    val dark = when (theme) { "Day" -> false; "System" -> isSystemInDarkTheme(); else -> true }
    MaterialTheme(colorScheme = if (dark) Night else Day, typography = NarrioTypography, content = content)
}
