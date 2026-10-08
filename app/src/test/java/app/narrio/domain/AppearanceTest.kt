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

    @Test fun pureBlackAppliesAtNightOnlyAndReaderOnlyWhileReading() {
        val reader = AppearanceSettings(palette = ThemePalette.HALLOWEEN, pureBlack = PureBlack.READER)
        assertEquals(0x000000, reader.forBook(null, BookThemes(), reading = true).colours(true).background)
        assertEquals(presetColours(ThemePalette.HALLOWEEN, true), reader.forBook(null, BookThemes(), reading = false).colours(true))
        assertEquals(presetColours(ThemePalette.HALLOWEEN, false), reader.forBook(null, BookThemes(), reading = true).colours(false))
        val everywhere = reader.copy(pureBlack = PureBlack.EVERYWHERE)
        assertEquals(0x000000, everywhere.forBook(null, BookThemes(), reading = false).colours(true).background)
        assertEquals(everywhere, AppearanceCodec.decode(AppearanceCodec.encode(everywhere)))
    }

    @Test fun bookColoursOverrideTheAppPaletteAndCoverMatchingIsTheOptionalDefault() {
        val cover = CustomTheme("Cover", ThemeColours(0xFF0000, 0x00FF00, 0x110000), ThemeColours(0x880000, 0x008800, 0xFFF0F0))
        val themes = BookThemes(covers = mapOf("b" to cover)).with("a", BookTheme(BookColours.PALETTE, ThemePalette.AMETHYST))
        val app = AppearanceSettings(palette = ThemePalette.OCEAN)
        assertEquals(ThemePalette.AMETHYST, app.forBook("a", themes, reading = false).palette)
        assertEquals(ThemePalette.OCEAN, app.forBook("b", themes, reading = false).palette)
        assertEquals(cover.night, app.copy(coverThemes = true).forBook("b", themes, reading = false).colours(true))
        // Without a derived cover yet, the book follows the app until its colours arrive.
        assertEquals(ThemePalette.OCEAN, app.copy(coverThemes = true).forBook("c", themes, reading = false).palette)
        // An explicit app choice outranks the cover default.
        assertEquals(ThemePalette.OCEAN, app.copy(coverThemes = true).forBook("b", themes.with("b", BookTheme(BookColours.APP)), reading = false).palette)
        assertEquals(themes, BookThemesCodec.decode(BookThemesCodec.encode(themes)))
        assertEquals(BookThemes(), BookThemesCodec.decode("damaged"))
        assertEquals(BookTheme(BookColours.COVER), BookTheme(BookColours.COVER, ThemePalette.EMBER).normalized())
    }

    @Test fun coverCacheKeepsOnlyTheMostRecentCovers() {
        val cover = CustomTheme.from(ThemePalette.FOREST)
        val themes = (0..BookThemes.MAX_COVERS + 5).fold(BookThemes()) { all, index -> all.withCover("book-$index", cover) }
        assertEquals(BookThemes.MAX_COVERS, themes.covers.size)
        assertFalse("book-0" in themes.covers)
        assertTrue("book-${BookThemes.MAX_COVERS + 5}" in themes.covers)
    }

    @Test fun coverColoursFollowTheCoversDominantHueAndNeutralCoversGetGraphite() {
        fun hue(colour: Int) = CoverColours.hsv(colour).first
        // A mostly black cover with a pumpkin-orange title and a little purple.
        val pixels = IntArray(1000) { index -> when { index < 600 -> 0xFF0A0A0A.toInt(); index < 900 -> 0xFFF07818.toInt(); else -> 0xFF7A3FB0.toInt() } }
        val theme = CoverColours.theme(pixels)
        assertEquals(hue(0xF07818), hue(theme.night.accent), 3f)
        assertEquals(hue(0xF07818), hue(theme.day.accent), 3f)
        assertEquals(hue(0x7A3FB0), hue(theme.night.secondary), 3f)
        assertTrue(ThemeContrast.luminance(theme.night.background) < .02)
        assertTrue(ThemeContrast.luminance(theme.day.background) > .8)
        val grey = IntArray(500) { 0xFF000000.toInt() or (it % 256 * 0x010101) }
        assertEquals(CustomTheme.from(ThemePalette.GRAPHITE).copy(name = "Cover"), CoverColours.theme(grey))
        assertEquals(CustomTheme.from(ThemePalette.GRAPHITE).copy(name = "Cover"), CoverColours.theme(IntArray(10)))
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
