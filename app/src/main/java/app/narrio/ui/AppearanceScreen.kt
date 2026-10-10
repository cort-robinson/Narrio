package app.narrio.ui

import android.graphics.Color as AndroidColor
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.rounded.Image
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import app.narrio.domain.*
import kotlin.math.roundToInt

@Composable
fun AppearanceEntry(settings: AppearanceSettings, open: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(14.dp))
        .clickable(role = Role.Button, onClick = open).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(Icons.Rounded.Palette, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text("Appearance", style = MaterialTheme.typography.titleMedium)
            Text("${settings.paletteName} · ${settings.mode.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Rounded.ChevronRight, null)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppearanceScreen(settings: AppearanceSettings, change: (AppearanceSettings) -> Unit, back: () -> Unit, modifier: Modifier = Modifier) {
    var editing by rememberSaveable { mutableStateOf(false) }
    val dark = settings.mode.isDark(isSystemInDarkTheme())
    BackHandler { if (editing) editing = false else back() }
    AnimatedContent(editing, modifier, transitionSpec = { Motion.sharedAxisX(targetState) }, label = "appearance page") { edit ->
        if (edit) CustomThemeEditor(settings, { custom -> change(settings.copy(palette = ThemePalette.CUSTOM, custom = custom)); editing = false }, { editing = false }, Modifier)
        else AppearanceOptions(settings, dark, change, back) { editing = true }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AppearanceOptions(settings: AppearanceSettings, dark: Boolean, change: (AppearanceSettings) -> Unit, back: () -> Unit, edit: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Appearance") }, navigationIcon = {
            IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to settings") }
        })
        LazyColumn(Modifier.fillMaxSize().testTag("appearance-options"), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item {
                Text("Changes apply throughout Narrio and stay on this device.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(20.dp))
                ThemePreview(settings, dark)
            }
            item {
                SectionTitle("Light & dark", "Every palette has a Day and Night version.")
                FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(settings.mode == mode, { change(settings.copy(mode = mode)) }, { Text(mode.label) }, modifier = Modifier.testTag("mode-${mode.name}"))
                    }
                }
                if (settings.mode == ThemeMode.SYSTEM) Text("Follows your device's light and dark setting.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val (seasonal, everyday) = ThemePalette.entries.partition { it.seasonal }
            item { SectionTitle("Palette", "${everyday.size - 1} presets, or one of your own.") }
            paletteRows(everyday, settings, dark, change, edit)
            item { SectionTitle("Seasonal", "Palettes for the time of year. Use one as long as you like.") }
            paletteRows(seasonal, settings, dark, change, edit)
            item {
                OutlinedButton({ edit() }, Modifier.fillMaxWidth().testTag("edit-custom-theme")) {
                    Icon(Icons.Rounded.Palette, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(if (settings.palette == ThemePalette.CUSTOM) "Edit custom theme" else "Create custom theme")
                }
                if (settings.custom != null) {
                    Spacer(Modifier.height(8.dp))
                    Text("Your custom palette stays saved when you switch themes.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                SectionTitle("Pure black", "True black Night backgrounds for OLED screens. Saves battery in the dark.")
                FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PureBlack.entries.forEach { option ->
                        FilterChip(settings.pureBlack == option, { change(settings.copy(pureBlack = option)) }, { Text(option.label) }, modifier = Modifier.testTag("pure-black-${option.name}"))
                    }
                }
                Text(settings.pureBlack.description + if (settings.pureBlack != PureBlack.OFF && !dark) " Switch to Night to see it." else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                SectionTitle("Book colours", "Any book can have colours of its own: choose them from its reader or Listening room.")
                Row(Modifier.fillMaxWidth().toggleable(settings.coverThemes, role = Role.Switch) { change(settings.copy(coverThemes = it)) }
                    .testTag("cover-themes").padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 16.dp)) {
                        Text("Match each book's cover", style = MaterialTheme.typography.titleMedium)
                        Text("Books without their own colours take a palette from their cover while you read or listen.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(settings.coverThemes, null)
                }
            }
            item {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(24.dp))
                SectionTitle("App font", "Bundled or built in. No downloads needed.")
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppFont.entries.forEach { font ->
                        Row(Modifier.fillMaxWidth().selectable(settings.font == font, role = Role.RadioButton, onClick = { change(settings.copy(font = font)) })
                            .testTag("font-${font.name}").padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(settings.font == font, null)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(font.label, style = MaterialTheme.typography.titleMedium, fontFamily = appFontFamily(font, true))
                                Text(font.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            item {
                SectionTitle("Text size", "Adds a little breathing room to your device's text size.")
                FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppTextSize.entries.forEach { size ->
                        FilterChip(settings.textSize == size, { change(settings.copy(textSize = size)) }, { Text(size.label) }, modifier = Modifier.testTag("text-size-${size.name}"))
                    }
                }
            }
            item {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(12.dp))
                TextButton({ change(AppearanceSettings(custom = settings.custom)) }, Modifier.testTag("restore-appearance")) { Text("Restore default appearance") }
                Text("Returns to Listening room, Night and Narrio type. Keeps your saved custom palette.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.paletteRows(palettes: List<ThemePalette>, settings: AppearanceSettings, dark: Boolean,
                                                                    change: (AppearanceSettings) -> Unit, edit: () -> Unit) {
    palettes.chunked(2).forEach { pair -> item {
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            pair.forEach { palette ->
                PaletteOption(settings, palette, dark, Modifier.weight(1f)) {
                    if (palette == ThemePalette.CUSTOM && settings.custom == null) edit()
                    else change(settings.copy(palette = palette))
                }
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    } }
}

/**
 * Colours for one book: follow Appearance, match the cover, or any palette. [themes] supplies the cover's palette once
 * derived; until then its swatch shows a cover icon.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BookThemePicker(book: Audiobook, appearance: AppearanceSettings, themes: BookThemes, dark: Boolean, choose: (BookTheme) -> Unit) {
    val current = themes.choice(book.id, appearance)
    val cover = themes.covers[book.id]
    val options = buildList {
        add(BookTheme(BookColours.APP))
        if (book.coverUrl.isNotBlank()) add(BookTheme(BookColours.COVER))
        ThemePalette.entries.filter { it != ThemePalette.CUSTOM || appearance.custom != null }.forEach { add(BookTheme(BookColours.PALETTE, it)) }
    }
    fun name(option: BookTheme) = when (option.colours) {
        BookColours.APP -> "App theme"
        BookColours.COVER -> "Match the cover"
        BookColours.PALETTE -> appearance.copy(palette = option.palette).paletteName
    }
    FlowRow(Modifier.selectableGroup().testTag("book-theme-picker"), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        options.forEach { option ->
            val colours = when (option.colours) {
                BookColours.APP -> appearance.colours(dark)
                BookColours.COVER -> cover?.let { if (dark) it.night else it.day }
                BookColours.PALETTE -> appearance.copy(palette = option.palette).colours(dark)
            }
            val selected = current == option
            Box(Modifier.size(44.dp).clip(CircleShape)
                .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                .selectable(selected, role = Role.RadioButton) { choose(option) }
                .semantics { contentDescription = name(option) }
                .testTag("book-theme-${if (option.colours == BookColours.PALETTE) option.palette.name else option.colours.name}"), contentAlignment = Alignment.Center) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize().padding(if (selected) 5.dp else 1.dp).clip(CircleShape)) {
                    drawRect(colours?.let { Color(0xFF000000.toInt() or it.background) } ?: Color.Transparent)
                    colours?.takeIf { option.colours == BookColours.PALETTE }?.let { drawCircle(Color(0xFF000000.toInt() or it.accent), radius = size.minDimension * .28f, center = androidx.compose.ui.geometry.Offset(size.width * .64f, size.height * .64f)) }
                }
                // The icon takes the accent's place, in the accent colour.
                val ink = colours?.let { Color(0xFF000000.toInt() or ThemeContrast.readable(it.accent, listOf(it.background), 3.0)) } ?: MaterialTheme.colorScheme.onSurfaceVariant
                when (option.colours) {
                    BookColours.APP -> Icon(Icons.Rounded.Palette, null, Modifier.size(18.dp), tint = ink)
                    BookColours.COVER -> Icon(Icons.Rounded.Image, null, Modifier.size(18.dp), tint = ink)
                    BookColours.PALETTE -> Unit
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    val detail = when (current.colours) {
        BookColours.APP -> "App theme · ${appearance.paletteName}. This book follows your Appearance settings."
        BookColours.COVER -> if (cover == null) "Match the cover · Reading the cover's colours…" else "Match the cover · Colours drawn from this book's cover."
        BookColours.PALETTE -> "${name(current)} · Only this book. Your Appearance settings are unchanged."
    }
    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
}

@Composable
private fun SectionTitle(title: String, description: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(8.dp))
    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun PaletteOption(settings: AppearanceSettings, palette: ThemePalette, dark: Boolean, modifier: Modifier, choose: () -> Unit) {
    val previewSettings = settings.copy(palette = palette)
    val scheme = remember(palette, settings.custom, dark) { colourSchemeFor(previewSettings, dark) }
    val selected = settings.palette == palette
    val name = if (palette == ThemePalette.CUSTOM) settings.custom?.name ?: "Custom" else palette.label
    val description = if (palette == ThemePalette.CUSTOM && settings.custom == null) "Create your own" else palette.description
    val shape = RoundedCornerShape(14.dp)
    val interaction = remember { MutableInteractionSource() }
    val ring by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, tween(Motion.MEDIUM), label = "palette ring")
    Column(modifier.pressScale(interaction).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerLow, shape)
        .border(2.dp, ring, shape)
        .selectable(selected, interaction, LocalIndication.current, role = Role.RadioButton, onClick = choose).testTag("palette-${palette.name}").padding(12.dp)) {
        Row(Modifier.fillMaxWidth().background(scheme.background, RoundedCornerShape(8.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Aa", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontFamily = appFontFamily(settings.font, true), color = scheme.onBackground)
            Box(Modifier.size(14.dp).background(scheme.secondary, CircleShape))
            Box(Modifier.size(24.dp).background(scheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                androidx.compose.animation.AnimatedVisibility(selected, enter = scaleIn(Motion.responsive()) + fadeIn(), exit = scaleOut() + fadeOut()) {
                    Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = scheme.onPrimary)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(name, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemePreview(settings: AppearanceSettings, dark: Boolean) {
    NarrioTheme(settings, dark) {
        Surface(Modifier.fillMaxWidth().testTag("theme-preview"), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Preview · ${if (dark) "Night" else "Day"}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("The Secret Garden", style = MaterialTheme.typography.headlineMedium)
                Text("Frances Hodgson Burnett", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.PlayArrow, null, Modifier.size(20.dp)); Text("Listen", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                        Text("On your shelf", Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

private enum class ColourRole(val label: String) { ACCENT("Accent"), SECONDARY("Supporting"), BACKGROUND("Background") }
private val customThemeSaver = Saver<CustomTheme, String>(
    save = { AppearanceCodec.json.encodeToString(CustomTheme.serializer(), it) },
    restore = { AppearanceCodec.json.decodeFromString(CustomTheme.serializer(), it) },
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CustomThemeEditor(settings: AppearanceSettings, save: (CustomTheme) -> Unit, cancel: () -> Unit, modifier: Modifier) {
    var draft by rememberSaveable(stateSaver = customThemeSaver) {
        mutableStateOf(if (settings.palette == ThemePalette.CUSTOM) settings.custom ?: CustomTheme() else CustomTheme.from(settings.palette))
    }
    val systemDark = isSystemInDarkTheme()
    var dark by rememberSaveable { mutableStateOf(settings.mode.isDark(systemDark)) }
    var role by rememberSaveable { mutableStateOf(ColourRole.ACCENT) }
    var validHex by rememberSaveable { mutableStateOf(true) }
    var startMenu by remember { mutableStateOf(false) }
    val colours = if (dark) draft.night else draft.day
    val colour = when (role) { ColourRole.ACCENT -> colours.accent; ColourRole.SECONDARY -> colours.secondary; ColourRole.BACKGROUND -> colours.background }
    fun setColour(value: Int) {
        val current = if (dark) draft.night else draft.day
        val updated = when (role) { ColourRole.ACCENT -> current.copy(accent = value); ColourRole.SECONDARY -> current.copy(secondary = value); ColourRole.BACKGROUND -> current.copy(background = value) }
        draft = if (dark) draft.copy(night = updated) else draft.copy(day = updated)
        validHex = true
    }
    LaunchedEffect(dark, role) { validHex = true }
    BackHandler(onBack = cancel)
    Column(modifier.fillMaxSize().imePadding()) {
        TopAppBar(title = { Text("Custom theme") }, navigationIcon = { IconButton(cancel) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Cancel custom theme") } })
        LazyColumn(Modifier.weight(1f).testTag("custom-options"), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                Text("Edit Day and Night separately. Text and controls automatically keep their contrast. Your app changes only when you save.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                OutlinedTextField(draft.name, { draft = draft.copy(name = it.take(28)) }, Modifier.fillMaxWidth().testTag("custom-theme-name"),
                    label = { Text("Theme name") }, singleLine = true, isError = draft.name.isBlank(),
                    supportingText = { Text(if (draft.name.isBlank()) "Give your theme a name." else "${draft.name.length}/28") })
                Box {
                    TextButton({ startMenu = true }) { Text("Start from a preset") }
                    DropdownMenu(startMenu, { startMenu = false }) {
                        ThemePalette.entries.filter { it != ThemePalette.CUSTOM }.forEach { palette ->
                            DropdownMenuItem(text = { Text(palette.label) }, onClick = {
                                draft = CustomTheme.from(palette).copy(name = draft.name); validHex = true; startMenu = false
                            })
                        }
                    }
                }
            }
            item {
                FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(dark, { dark = true }, { Text("Night") }, modifier = Modifier.testTag("custom-night"))
                    FilterChip(!dark, { dark = false }, { Text("Day") }, modifier = Modifier.testTag("custom-day"))
                }
                Spacer(Modifier.height(12.dp))
                ThemePreview(settings.copy(palette = ThemePalette.CUSTOM, custom = draft), dark)
            }
            item {
                Text("${if (dark) "Night" else "Day"} colors", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ColourRole.entries.forEach { target -> FilterChip(role == target, { role = target }, { Text(target.label) }, modifier = Modifier.testTag("colour-role-${target.name}")) }
                }
                Spacer(Modifier.height(12.dp))
                key(dark, role) { ColourControls(colour, role.label, ::setColour) { validHex = it } }
            }
        }
        Button({ save(draft.normalized()) }, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp).testTag("save-custom-theme"), enabled = validHex && draft.name.isNotBlank()) {
            Text("Save & use theme")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColourControls(colour: Int, label: String, change: (Int) -> Unit, valid: (Boolean) -> Unit) {
    var hex by rememberSaveable { mutableStateOf(ThemeContrast.hex(colour)) }
    var hsv by rememberSaveable { mutableStateOf(FloatArray(3).also { AndroidColor.colorToHSV(0xFF000000.toInt() or colour, it) }) }
    LaunchedEffect(colour) {
        hex = ThemeContrast.hex(colour); valid(true)
        if ((AndroidColor.HSVToColor(hsv) and 0xFFFFFF) != colour) {
            hsv = FloatArray(3).also { AndroidColor.colorToHSV(0xFF000000.toInt() or colour, it) }
        }
    }
    // A swatch or slider replaces whatever is typed, even when the colour itself doesn't change.
    fun pick(value: Int) { hex = ThemeContrast.hex(value); valid(true); change(value) }
    val swatches = listOf(0xE8AF79, 0xE3C28A, 0xB5D49C, 0x7CC5AE, 0x8ACED8, 0x94B1E5, 0xCCBAE0, 0xE9B1BE, 0xF4EDDE, 0xFFFFFF, 0x263832, 0x191C20)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        swatches.forEach { swatch ->
            Box(Modifier.size(48.dp).background(Color(0xFF000000.toInt() or swatch), CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                .clickable(role = Role.Button) { pick(swatch) }.semantics { contentDescription = "Choose ${ThemeContrast.hex(swatch)}"; selected = colour == swatch }, contentAlignment = Alignment.Center) {
                if (colour == swatch) Icon(Icons.Rounded.Check, null, tint = Color(0xFF000000.toInt() or ThemeContrast.foreground(swatch)))
            }
        }
    }
    Spacer(Modifier.height(20.dp))
    listOf("Hue", "Color strength", "Brightness").forEachIndexed { index, title ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(if (index == 0) "${hsv[index].roundToInt()}°" else "${(hsv[index] * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(hsv[index], { value ->
            val updated = hsv.copyOf().apply { this[index] = value }
            hsv = updated
            pick(AndroidColor.HSVToColor(updated) and 0xFFFFFF)
        }, valueRange = if (index == 0) 0f..360f else 0f..1f, modifier = Modifier.semantics { contentDescription = "$label $title" })
    }
    val invalid = ThemeContrast.parseHex(hex) == null
    OutlinedTextField(hex, { value ->
        hex = value.take(7)
        val parsed = ThemeContrast.parseHex(hex)
        valid(parsed != null)
        if (parsed != null) change(parsed)
    }, Modifier.fillMaxWidth().testTag("colour-hex"), label = { Text("$label hex color") }, singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters), isError = invalid,
        supportingText = { Text(if (invalid) "Enter six hex digits, such as #8ACED8." else "The preview adjusts contrast where needed.") })
}
