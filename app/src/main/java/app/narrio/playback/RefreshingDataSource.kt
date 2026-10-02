package app.narrio.playback

import android.net.Uri
import androidx.media3.datasource.*
import app.narrio.data.TorBoxDelivery
import app.narrio.domain.AudioPart
import java.util.concurrent.ConcurrentHashMap

/** The player sees stable local IDs; expiring CDN links stay in memory only. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class RefreshingDataSource(
    private val upstream: DataSource,
    private val torbox: TorBoxDelivery,
    private val parts: ConcurrentHashMap<String, AudioPart>,
    private val links: ConcurrentHashMap<String, String>,
) : DataSource {
    override fun open(dataSpec: DataSpec): Long {
        val part = parts[dataSpec.uri.toString()] ?: return upstream.open(dataSpec)
        fun resolve(force: Boolean): String {
            if (force) links.remove(part.id)
            return links.getOrPut(part.id) { torbox.resolve(part) }
        }
        val resolved = dataSpec.withUri(Uri.parse(resolve(false)))
        return try { upstream.open(resolved) } catch (error: HttpDataSource.InvalidResponseCodeException) {
            upstream.close()
            if (part.torrentId == null || error.responseCode !in listOf(401, 403, 404, 410)) throw error
            upstream.open(dataSpec.withUri(Uri.parse(resolve(true))))
        }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int) = upstream.read(buffer, offset, length)
    override fun getUri(): Uri? = upstream.uri
    override fun getResponseHeaders() = upstream.responseHeaders
    override fun addTransferListener(listener: TransferListener) = upstream.addTransferListener(listener)
    override fun close() = upstream.close()
}
