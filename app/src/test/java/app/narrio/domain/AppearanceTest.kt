package app.narrio.domain

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class AppearanceTest {
    @Test fun existingDayNightAndSystemPreferencesKeepTheirMode() {
        assertEquals(ThemeMode.DAY, AppearanceCodec.decode(null, "Day").mode)
        assertEquals(ThemeMode.NIGHT, AppearanceCodec.decode(null, "Night").mode)
        assertEquals(ThemeMode.SYSTEM, AppearanceCodec.decode(null, "System").mode)
        assertEquals(AppearanceSettings(), AppearanceCodec.decode(null, "unknown"))
        assertEquals(ThemeMode.DAY, AppearanceCodec.decode("damaged json", "Day").mode)
        assertTrue(ThemeMode.SYSTEM.isDark(true)); assertFalse(ThemeMode.SYSTEM.isDark(false))
        assertTrue(ThemeMode.NIGHT.isDark(false)); assertFalse(ThemeMode.DAY.isDark(true))
    }

    @Test fun savedCustomColoursAndTypographyRoundTripIndependentlyOfTheActivePalette() {
        val custom = CustomTheme("Aurora", ThemeColours(0xAABBCC, 0x123456, 0x101010), ThemeColours(0x556677, 0xA12345, 0xFAF2E0))
        val settings = AppearanceSettings(ThemeMode.SYSTEM, ThemePalette.OCEAN, AppFont.SYSTEM, AppTextSize.LARGE, custom)
        assertEquals(settings, AppearanceCodec.decode(AppearanceCodec.encode(settings)))
        assertEquals(custom.night, settings.copy(palette = ThemePalette.CUSTOM).colours(true))
        assertEquals(custom.day, settings.copy(palette = ThemePalette.CUSTOM).colours(false))
        assertEquals(settings.font, settings.copy(palette = ThemePalette.ROSEWOOD).font)
    }

    @Test fun futureFieldsUnknownEnumsAndMissingCustomPaletteRecoverSafely() {
        val settings = AppearanceCodec.decode("""{"mode":"DAY","palette":"FUTURE","font":"FUTURE","textSize":"FUTURE","newSetting":true}""")
        assertEquals(ThemeMode.DAY, settings.mode)
        assertEquals(ThemePalette.LISTENING_ROOM, settings.palette)
        assertEquals(AppFont.NARRIO, settings.font)
        assertEquals(AppTextSize.STANDARD, settings.textSize)
        assertEquals(ThemePalette.LISTENING_ROOM, AppearanceCodec.decode("""{"palette":"CUSTOM"}""").palette)
        assertEquals("My theme", CustomTheme("  ").normalized().name)
        assertEquals(28, CustomTheme("a".repeat(100)).normalized().name.length)
    }

    @Test fun hexColoursAcceptOnlyCompleteOpaqueRgbValues() {
        assertEquals(0xAABBCC, ThemeContrast.parseHex(" #aAbBcC "))
        assertEquals(0x001122, ThemeContrast.parseHex("001122"))
        listOf("", "#ABC", "#AABBCCDD", "nothex", "-12345", "##AABBCC").forEach { assertNull(ThemeContrast.parseHex(it)) }
        assertEquals("#001122", ThemeContrast.hex(0x001122))
        assertEquals(21.0, ThemeContrast.ratio(0, 0xFFFFFF), .00001)
    }

    @Test fun arbitraryUserColoursStayReadableEvenAtTheBlackWhiteCrossover() {
        val random = Random(42)
        val backgrounds = (0..255).map { (it shl 16) or (it shl 8) or it } + List(500) { random.nextInt(0x1000000) }
        backgrounds.forEach { background ->
            val surfaces = listOf(background) + listOf(.035f, .065f, .1f, .13f).map { ThemeContrast.surface(background, it) }
            val ink = ThemeContrast.foreground(background)
            surfaces.forEach { assertTrue("Text on ${ThemeContrast.hex(it)}", ThemeContrast.ratio(ink, it) >= 4.5) }
            val accent = ThemeContrast.readable(random.nextInt(0x1000000), surfaces)
            surfaces.forEach { assertTrue("Accent on ${ThemeContrast.hex(it)}", ThemeContrast.ratio(accent, it) >= 4.5) }
            assertTrue(ThemeContrast.ratio(accent, ThemeContrast.foreground(accent)) >= 4.5)
        }
    }
}
