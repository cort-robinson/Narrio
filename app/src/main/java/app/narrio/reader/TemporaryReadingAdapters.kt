package app.narrio.reader

import android.content.Context
import app.narrio.data.BookTextParser
import app.narrio.data.LibraryDao
import app.narrio.domain.EbookEdition
import app.narrio.domain.EditionFiles
import app.narrio.domain.PositionUpdate
import app.narrio.domain.SharedPosition
import app.narrio.domain.SharedPositionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/*
 * TEMPORARY until workstream A (feature/ereader-data) lands Room-backed editions and shared positions.
 * Remove both adapters when rebasing onto it; nothing else depends on their internals.
 */

/**
 * Temporary [EditionFiles] over follow-along attachments: the one text attached to a shelf book, stored by
 * `FollowAlongStore` as `files/follow-along/<hash of book id>/<documentId>.<epub|txt>`. Timing tracks aren't editions.
 */
class TemporaryFollowAlongEditionFiles(private val context: Context, private val library: LibraryDao) : EditionFiles {
    private fun directory(bookId: String) = File(context.filesDir, "follow-along/" + BookTextParser.fingerprint(bookId.toByteArray(Charsets.UTF_8)))

    override suspend fun editions(bookId: String): List<EbookEdition> = withContext(Dispatchers.IO) {
        val entry = library.bookText(bookId) ?: return@withContext emptyList()
        val file = original(bookId, entry.documentId) ?: return@withContext emptyList()
        val book = library.find(bookId)?.book()
        listOf(EbookEdition(entry.documentId, bookId, book?.title.orEmpty(), book?.author.orEmpty(), file.extension.uppercase(), active = true))
    }

    override suspend fun original(bookId: String, editionId: String): File? = withContext(Dispatchers.IO) {
        if (!editionId.matches(Regex("[a-f0-9]{64}"))) return@withContext null
        listOf("epub", "txt").map { File(directory(bookId), "$editionId.$it") }.firstOrNull(File::isFile)
    }
}

/**
 * Temporary in-memory [SharedPositionStore]: positions last while the app process runs. It enforces the
 * contract's sequence rule so callers behave as they will against the persistent store.
 */
class TemporaryInMemoryPositionStore : SharedPositionStore {
    private val mutex = Mutex()
    private val positions = HashMap<String, MutableStateFlow<SharedPosition?>>()
    private fun flow(bookId: String) = synchronized(positions) { positions.getOrPut(bookId) { MutableStateFlow(null) } }

    override fun observe(bookId: String): Flow<SharedPosition?> = flow(bookId).asStateFlow()
    override suspend fun current(bookId: String): SharedPosition? = flow(bookId).value

    override suspend fun commit(update: PositionUpdate): SharedPosition? = mutex.withLock {
        val state = flow(update.bookId)
        val current = state.value
        if ((current?.sequence ?: 0L) != update.basedOnSequence) return@withLock null
        // An update replaces both sides: a counterpart it doesn't carry is no longer known to match.
        SharedPosition(update.bookId, update.origin, update.text, update.audio, update.textConfidence, update.audioConfidence,
            (current?.sequence ?: 0L) + 1, System.currentTimeMillis()).also { state.value = it }
    }
}
