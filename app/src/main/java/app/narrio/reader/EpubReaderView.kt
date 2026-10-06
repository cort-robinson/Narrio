package app.narrio.reader

import android.view.ActionMode
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import app.narrio.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.epub.css.ColCount
import org.readium.r2.navigator.epub.css.Length
import org.readium.r2.navigator.epub.css.RsProperties
import org.readium.r2.navigator.preferences.Color
import org.readium.r2.navigator.preferences.ColumnCount
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.ImageFilter
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.shared.ExperimentalReadiumApi

/** Page colours for the book: the Appearance palette's ground and ink. */
data class ReaderColors(val background: Int, val text: Int, val dark: Boolean)

/** The font families the reader declares to Readium; [ReaderFont.NARRIO] pairs Manrope text with Newsreader headings. */
internal val ReaderFont.family: FontFamily? get() = when (this) {
    ReaderFont.PUBLISHER -> null
    ReaderFont.NARRIO -> FontFamily(ReaderDocuments.NARRIO_PAIRING)
    ReaderFont.NEWSREADER -> FontFamily("Newsreader")
    ReaderFont.MANROPE -> FontFamily("Manrope")
    ReaderFont.ANDROID -> FontFamily.SANS_SERIF
    ReaderFont.OPEN_DYSLEXIC -> FontFamily.OPEN_DYSLEXIC
}

@OptIn(ExperimentalReadiumApi::class)
fun ReaderSettings.toEpubPreferences(colors: ReaderColors, spread: Boolean) = EpubPreferences(
    fontFamily = font.family,
    fontSize = fontScale,
    lineHeight = spacing.lineHeight,
    pageMargins = margins.pageMargins,
    textAlign = if (justify) TextAlign.JUSTIFY else TextAlign.START,
    hyphens = hyphenate,
    publisherStyles = publisherStyles,
    scroll = scroll,
    columnCount = if (spread && !scroll) ColumnCount.TWO else ColumnCount.ONE,
    backgroundColor = Color(colors.background),
    textColor = Color(colors.text),
    imageFilter = if (colors.dark) ImageFilter.DARKEN else null,
)

@OptIn(ExperimentalReadiumApi::class)
private fun navigatorConfiguration(selection: ActionMode.Callback?, spread: Boolean) = EpubNavigatorFragment.Configuration {
    servedAssets += "fonts/.*"
    // ReadiumCSS only lays out two columns above 60em of width; an unfolded phone in landscape is narrower, so a
    // spread sets the reading-system column count directly. These properties are fixed when the navigator is created.
    if (spread) readiumCssRsProperties = RsProperties(colCount = ColCount.TWO, colWidth = Length.Rem(12.0))
    selectionActionModeCallback = selection
    decorationTemplates = decorationTemplates.copy().also(NarrationMark::register)
    for ((family, file) in listOf("Newsreader" to "newsreader.ttf", "Manrope" to "manrope.ttf", ReaderDocuments.NARRIO_PAIRING to "manrope.ttf")) {
        addFontFamilyDeclaration(FontFamily(family)) {
            addFontFace { addSource("fonts/$file"); setFontWeight(200..800) }
        }
    }
}

/**
 * Hosts Readium's EPUB navigator for [controller]. The navigator opens at the controller's cursor, follows
 * [preferences] as they change, is recreated at the same cursor when [spread] (two pages side by side) changes, and restores the reader's place whenever its size changes (rotation, folding,
 * window resizing). [selectionActionMode] can extend the text-selection menu; by default Android's own
 * actions (copy, share, translate, dictionary apps) appear.
 */
@OptIn(ExperimentalReadiumApi::class)
@Composable
fun EpubReaderView(controller: ReaderController, preferences: EpubPreferences, spread: Boolean, modifier: Modifier = Modifier, selectionActionMode: ActionMode.Callback? = null) {
    val activity = LocalActivity.current as FragmentActivity
    val scope = rememberCoroutineScope()
    val currentPreferences = rememberUpdatedState(preferences)
    AndroidView(
        // The outer frame notes the reader's touches, which tell their page turns from the navigator's own settling.
        factory = { context -> TouchNotingFrame(context).apply { touched = controller::touched; addView(FragmentContainerView(context).apply { id = R.id.narrio_reader_navigator }) } },
        modifier = modifier.onSizeChanged { controller.relayout() },
    )
    DisposableEffect(controller, spread) {
        val manager = activity.supportFragmentManager
        var fragment: EpubNavigatorFragment? = null
        // Fragment transactions must run on the main thread, whatever dispatcher resumes this effect.
        val job = scope.launch(Dispatchers.Main.immediate) {
            val initial = controller.anchor()?.let { controller.book.locator(it) }
            val factory = EpubNavigatorFactory(controller.book.publication).createFragmentFactory(
                initialLocator = initial, initialPreferences = currentPreferences.value, listener = controller,
                configuration = navigatorConfiguration(selectionActionMode, spread),
            )
            val created = factory.instantiate(activity.classLoader, EpubNavigatorFragment::class.java.name) as EpubNavigatorFragment
            if (manager.isDestroyed) return@launch
            manager.beginTransaction().setReorderingAllowed(true).replace(R.id.narrio_reader_navigator, created, TAG).commitNowAllowingStateLoss()
            fragment = created
            controller.attach(created)
        }
        onDispose {
            job.cancel()
            controller.detach()
            fragment?.let { if (!manager.isDestroyed) manager.beginTransaction().remove(it).commitAllowingStateLoss() }
        }
    }
    LaunchedEffect(controller, preferences) {
        val navigator = controller.navigator ?: return@LaunchedEffect
        navigator.submitPreferences(preferences)
        controller.relayout()
    }
}

private class TouchNotingFrame(context: android.content.Context) : android.widget.FrameLayout(context) {
    var touched: () -> Unit = {}
    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN || event.actionMasked == android.view.MotionEvent.ACTION_MOVE ||
            event.actionMasked == android.view.MotionEvent.ACTION_UP) touched()
        return super.dispatchTouchEvent(event)
    }
}

private const val TAG = "narrio-reader"
