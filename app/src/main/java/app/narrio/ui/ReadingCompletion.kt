package app.narrio.ui

import app.narrio.domain.SyncThresholds
import app.narrio.reader.EditionLayout
import app.narrio.reader.VisibleRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Finishes or reopens a book from reading. A committed page that shows the end of the edition finishes it; any other
 * committed page means the book is being read again. A book short enough to fit on one page never turns a page to
 * commit, so dwelling on that page finishes it. Each verdict waits for the edition's layout, and counts only if no
 * newer page has come along meanwhile.
 */
class ReadingCompletion(
    private val layout: StateFlow<EditionLayout?>,
    private val scope: CoroutineScope,
    private val dwellMs: Long = SyncThresholds.READING_DWELL_MS,
    private val apply: suspend (finished: Boolean) -> Unit,
) {
    private var latest = 0L
    private var dwell: Job? = null

    /** The page a reading commit qualified on: dwelt on, or turned past. Null when that page isn't known. */
    fun committed(page: VisibleRange?) {
        page ?: return
        judge { edition -> readingFinished(edition, page.first, page.end) }
    }

    /** The page on screen now. */
    fun showing(page: VisibleRange?) {
        dwell = judge(dwellMs) { edition -> true.takeIf { page != null && showsWholeEdition(edition, page) } }
    }

    /** [verdict] is finished, not finished, or null for no change. */
    private fun judge(waitMs: Long = 0, verdict: (EditionLayout) -> Boolean?): Job {
        val turn = ++latest
        dwell?.cancel()
        return scope.launch {
            if (waitMs > 0) delay(waitMs)
            val edition = layout.filterNotNull().first()
            if (turn == latest) verdict(edition)?.let { apply(it) }
        }
    }
}

/** The page runs from the edition's first character to past its last: the whole book is on screen. */
fun showsWholeEdition(layout: EditionLayout, page: VisibleRange): Boolean =
    readingFinished(layout, page.first, page.end) && layout.position(page.first.resource, page.first.offset) == 0L
