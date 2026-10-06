package app.narrio.reader

import android.graphics.Color
import android.os.Parcel
import android.os.Parcelable
import androidx.annotation.ColorInt
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.html.HtmlDecorationTemplate
import org.readium.r2.navigator.html.HtmlDecorationTemplates

/**
 * How narration is drawn on the page while reading along: a soft wash behind the narrated sentence, a dotted
 * underline when that place is only estimated, and a stronger mark on the narrated word. The wash blends with the
 * page (multiply on light pages, screen on dark ones) so the ink keeps its contrast. Colours are `!important`
 * because ReadiumCSS clears element backgrounds when the reader's own page colours apply. New marks fade in with the
 * motion system's emphasized-decelerate curve unless [animated] is off (Android's animations are removed).
 */
data class NarrationMark(val kind: Int, @ColorInt val tint: Int, val animated: Boolean) : Decoration.Style {
    override fun describeContents() = 0
    override fun writeToParcel(dest: Parcel, flags: Int) { dest.writeInt(kind); dest.writeInt(tint); dest.writeInt(if (animated) 1 else 0) }

    internal fun html(): String {
        val r = Color.red(tint); val g = Color.green(tint); val b = Color.blue(tint)
        val light = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255 > 0.5
        val wash = "rgba($r,$g,$b,${if (light) .26 else .17})"
        val line = "rgb($r,$g,$b)"
        val blend = if (light) "screen" else "multiply"
        val motion = if (animated) " narrio-in" else ""
        return when (kind) {
            ESTIMATED -> """<div class="narrio-estimated$motion" style="border-bottom-color:$line !important">$PASS_THROUGH</div>"""
            WORD -> """<div class="narrio-word$motion" style="background-color:rgba($r,$g,$b,${if (light) .40 else .26}) !important;box-shadow:inset 0 -2px 0 $line !important;mix-blend-mode:$blend">$PASS_THROUGH</div>"""
            else -> """<div class="narrio-said$motion" style="background-color:$wash !important;mix-blend-mode:$blend">$PASS_THROUGH</div>"""
        }
    }

    companion object CREATOR : Parcelable.Creator<NarrationMark> {
        const val SENTENCE = 0
        const val ESTIMATED = 1
        const val WORD = 2

        /**
         * Readium hit-tests every decoration before a tap reaches the page, and the first hit wins. A mark's only
         * "activable" element sits far off the page, so narration never takes a tap: taps on text still seek, and
         * taps on a user's highlight under the narration still open it.
         */
        private const val PASS_THROUGH = """<span data-activable="1" style="position:absolute;left:-100000px;top:-100000px;width:1px;height:1px"></span>"""
        override fun createFromParcel(source: Parcel) = NarrationMark(source.readInt(), source.readInt(), source.readInt() == 1)
        override fun newArray(size: Int) = arrayOfNulls<NarrationMark>(size)

        private const val STYLESHEET = """
.narrio-said { border-radius: 4px; margin: -1px -2px; padding: 1px 2px; }
.narrio-word { border-radius: 3px; margin: -1px -1px; padding: 1px 1px; }
.narrio-estimated { box-sizing: border-box; border-bottom: 2px dotted; }
@keyframes narrio-in { from { opacity: 0; } to { opacity: 1; } }
.narrio-in { animation: narrio-in 300ms cubic-bezier(0.05, 0.7, 0.1, 1) both; }
.narrio-word.narrio-in { animation-duration: 150ms; }
"""

        /** Registers the narration marks with the navigator; they are fixed when the navigator is created. */
        fun register(templates: HtmlDecorationTemplates) {
            templates[NarrationMark::class] = HtmlDecorationTemplate(
                layout = HtmlDecorationTemplate.Layout.BOXES,
                width = HtmlDecorationTemplate.Width.WRAP,
                element = { decoration -> (decoration.style as? NarrationMark)?.html() ?: "<div></div>" },
                stylesheet = STYLESHEET,
            )
        }
    }
}
