package app.narrio.data

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.*
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.*
import androidx.media3.exoplayer.scheduler.Requirements
import app.narrio.domain.*
import app.narrio.playback.RefreshingDataSource
import app.narrio.playback.OfflineDownloadService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Serializable
data class OfflinePayload(val book: Audiobook, val source: AudioSource, val partId: String)

data class OfflineBook(
    val book: Audiobook, val source: AudioSource, val completedFiles: Int, val totalFiles: Int,
    val bytesDownloaded: Long, val totalBytes: Long, val failed: Boolean, val paused: Boolean, val waiting: Boolean,
) {
    val complete get() = completedFiles == totalFiles && totalFiles == source.parts.size
    val progress get() = if (totalBytes > 0) (bytesDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    val label get() = when {
        complete -> "Available offline"
        failed -> "Download interrupted"
        paused -> "Download paused"
        waiting -> "Waiting for Wi-Fi"
        else -> "Downloading to phone"
    }
}

/** Only explicit downloads write audio. The playback factory reads this cache without writing. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class OfflineStore(private val context: Context, http: OkHttpClient, private val torbox: TorBoxDelivery) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val database = StandaloneDatabaseProvider(context)
    val cache = SimpleCache(File(context.filesDir, "offline-audio"), NoOpCacheEvictor(), database)
    private val parts = ConcurrentHashMap<String, AudioPart>()
    private val links = ConcurrentHashMap<String, String>()
    private val upstream = DefaultDataSource.Factory(context, OkHttpDataSource.Factory(http.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).build()))
    private val resolvingFactory = DataSource.Factory { RefreshingDataSource(upstream.createDataSource(), torbox, parts, links) }
    val manager = DownloadManager(context, database, cache, resolvingFactory, Executors.newFixedThreadPool(2)).apply {
        maxParallelDownloads = 2
        minRetryCount = 3
        requirements = Requirements(if (context.getSharedPreferences("preferences", Context.MODE_PRIVATE).getBoolean("downloadWifi", true)) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)
    }
    val books = MutableStateFlow<List<OfflineBook>>(emptyList())
    var visible = false

    init {
        readDownloads().forEach { register(it.request) }
        manager.addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(manager: DownloadManager, download: Download, finalException: Exception?) { refresh() }
            override fun onDownloadRemoved(manager: DownloadManager, download: Download) { refresh() }
            override fun onRequirementsStateChanged(manager: DownloadManager, requirements: Requirements, notMetRequirements: Int) { refresh() }
            override fun onInitialized(manager: DownloadManager) { refresh() }
        })
        scope.launch { while (isActive) { if (visible) refreshNow(); delay(if (visible) 1000 else 5000) } }
        refresh()
    }

    fun playbackFactory(network: DataSource.Factory): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache).setUpstreamDataSourceFactory(network).setCacheWriteDataSinkFactory(null)

    fun queue(book: Audiobook, source: AudioSource) {
        source.parts.forEach { part ->
            val uri = stableAudioUri(part)
            val request = DownloadRequest.Builder(uri, Uri.parse(uri)).setCustomCacheKey(uri)
                .setData(NarrioJson.encodeToString(OfflinePayload(book, source, part.id)).toByteArray(Charsets.UTF_8)).build()
            parts[uri] = part
            DownloadService.sendAddDownload(context, OfflineDownloadService::class.java, request, true)
        }
    }

    fun pause(source: AudioSource) = source.parts.forEach { DownloadService.sendSetStopReason(context, OfflineDownloadService::class.java, stableAudioUri(it), 1, false) }
    fun resume(book: Audiobook, source: AudioSource) { queue(book, source); source.parts.forEach { DownloadService.sendSetStopReason(context, OfflineDownloadService::class.java, stableAudioUri(it), 0, true) } }
    fun remove(source: AudioSource) = source.parts.forEach { DownloadService.sendRemoveDownload(context, OfflineDownloadService::class.java, stableAudioUri(it), false) }
    fun setWifiOnly(value: Boolean) {
        DownloadService.sendSetRequirements(context, OfflineDownloadService::class.java, Requirements(if (value) Requirements.NETWORK_UNMETERED else Requirements.NETWORK), false)
    }
    fun disconnect() {
        books.value.filter { it.source.delivery == "torbox" && !it.complete }.forEach { pause(it.source) }
        links.clear()
    }
    fun complete(source: AudioSource): Boolean = source.parts.isNotEmpty() && source.parts.all { complete(it) }
    fun complete(part: AudioPart): Boolean = manager.downloadIndex.getDownload(stableAudioUri(part))?.state == Download.STATE_COMPLETED

    fun cachedEdges(part: AudioPart): List<ByteArray> {
        if (!complete(part)) return emptyList()
        val uri = stableAudioUri(part)
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(uri))
        if (length <= 0) return emptyList()
        val factory = CacheDataSource.Factory().setCache(cache).setCacheWriteDataSinkFactory(null)
        return listOf(0L to minOf(length, 262144), (length - 524288).coerceAtLeast(0) to minOf(length, 524288)).map { (position, count) ->
            val data = factory.createDataSource()
            try {
                data.open(DataSpec.Builder().setUri(uri).setPosition(position).setLength(count).setKey(uri).build())
                val bytes = ByteArray(count.toInt()); var read = 0
                while (read < bytes.size) { val next = data.read(bytes, read, bytes.size - read); if (next == C.RESULT_END_OF_INPUT) break; read += next }
                bytes.copyOf(read)
            } finally { data.close() }
        }
    }

    private fun register(request: DownloadRequest): OfflinePayload? = runCatching {
        NarrioJson.decodeFromString<OfflinePayload>(request.data.toString(Charsets.UTF_8)).also { payload ->
            payload.source.parts.forEach { parts[stableAudioUri(it)] = it }
        }
    }.getOrNull()
    private fun readDownloads(): List<Download> = manager.downloadIndex.getDownloads().use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.download) } }
    private fun refresh() { scope.launch { refreshNow() } }
    private suspend fun refreshNow() {
        val current = manager.currentDownloads.associateBy { it.request.id }
        val all = withContext(Dispatchers.IO) { readDownloads().map { current[it.request.id] ?: it } }
        val requests = all.mapNotNull { download -> register(download.request)?.let { it to download } }
        books.value = requests.groupBy { (payload, _) -> payload.book.id to payload.source.id }.values.map { group ->
            val payload = group.first().first; val downloads = group.map { it.second }
            fun length(download: Download) = download.contentLength.takeIf { it > 0 } ?: payload.source.parts.firstOrNull { stableAudioUri(it) == download.request.id }?.sizeBytes ?: 0
            OfflineBook(payload.book, payload.source, downloads.count { it.state == Download.STATE_COMPLETED }, downloads.size, downloads.sumOf { it.bytesDownloaded },
                if (payload.source.parts.all { it.sizeBytes > 0 }) payload.source.parts.sumOf { it.sizeBytes }
                else if (downloads.size == payload.source.parts.size && downloads.all { length(it) > 0 }) downloads.sumOf { length(it) } else 0,
                downloads.any { it.state == Download.STATE_FAILED }, downloads.any { it.stopReason != 0 }, manager.notMetRequirements != 0)
        }
    }
}

fun stableAudioUri(part: AudioPart) = "narrio://audio/${Uri.encode(part.id)}"
