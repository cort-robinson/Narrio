package app.narrio.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.metadata.id3.ChapterFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.narrio.*
import app.narrio.data.*
import app.narrio.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

@androidx.annotation.OptIn(UnstableApi::class)
class ListeningService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var graph: AppGraph
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private val parts = ConcurrentHashMap<String, AudioPart>()
    private val links = ConcurrentHashMap<String, String>()
    private var currentBook: Audiobook? = null
    private var currentSource: AudioSource? = null
    private var chapterJob: Job? = null
    private var chapters: List<Chapter> = emptyList()
    private var sleepUntil = 0L
    private var sleepAtEnd = false
    private var error: String? = null
    private var lastSave = 0L
    var initialized = false
        private set

    override fun onCreate() {
        super.onCreate()
        graph = (application as NarrioApplication).graph
        val streamHttp = graph.http.newBuilder().callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS).build()
        val upstream = DefaultDataSource.Factory(this, OkHttpDataSource.Factory(streamHttp))
        val network = DataSource.Factory { RefreshingDataSource(upstream.createDataSource(), graph.torbox, parts, links) }
        val factory = graph.offline.playbackFactory(network)
        player = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(factory))
            .setSeekBackIncrementMs(30_000).setSeekForwardIncrementMs(30_000)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(30_000, 90_000, 1_000, 3_000).build())
            .setAudioAttributes(AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).setUsage(C.USAGE_MEDIA).build(), true)
            .setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_NETWORK).build()
        player.setPlaybackSpeed(graph.preferences.getFloat("speed", 1f))
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player).setSessionActivity(activity).setCallback(object : MediaSession.Callback {
            override fun onConnect(mediaSession: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                if (controller.packageName != packageName && !controller.isTrusted) return MediaSession.ConnectionResult.reject()
                val commands = Player.Commands.Builder().addAllCommands()
                    .remove(Player.COMMAND_SET_MEDIA_ITEM).remove(Player.COMMAND_CHANGE_MEDIA_ITEMS).build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(mediaSession).setAvailablePlayerCommands(commands).build()
            }
        }).build()
        graph.playback.service = this
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { publish() }
            override fun onIsPlayingChanged(isPlaying: Boolean) { scope.launch { save() } }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                scope.launch { save() }
                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION && sleepAtEnd) { sleepAtEnd = false; player.pause(); publish() }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) error = null
                if (playbackState == Player.STATE_ENDED) { sleepAtEnd = false; scope.launch { save() } }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { chapters = emptyList(); readChapters(); scope.launch { save() } }
            override fun onPlayerError(playbackError: PlaybackException) {
                error = "Audio couldn't continue. Check your connection, then retry. TorBox sources also need a connected account."
                publish(); scope.launch { save() }
            }
            override fun onMetadata(metadata: Metadata) {
                val parsed = (0 until metadata.length()).mapNotNull { i ->
                    (metadata[i] as? ChapterFrame)?.let { frame ->
                        val title = (0 until frame.subFrameCount).mapNotNull { frame.getSubFrame(it) as? TextInformationFrame }
                            .firstOrNull()?.values?.firstOrNull() ?: frame.chapterId
                        Chapter(title, frame.startTimeMs.toLong())
                    }
                }
                if (parsed.isNotEmpty()) { chapters = parsed.sortedBy { it.startMs }; publish() }
            }
        })
        scope.launch {
            try { graph.library.lastPlayed()?.let { entry -> entry.source()?.let { load(entry.book(), it, false) } } }
            finally { initialized = true }
            while (isActive) {
                if (sleepUntil > 0 && System.currentTimeMillis() >= sleepUntil) { sleepUntil = 0; player.pause(); save() }
                if (player.isPlaying && System.currentTimeMillis() - lastSave >= 5000) save()
                if (graph.playback.visible || player.isPlaying || sleepUntil > 0) publish()
                delay(if (graph.playback.visible) 1000 else 5000)
            }
        }
    }

    suspend fun load(book: Audiobook, source: AudioSource, autoplay: Boolean = true, partId: String? = null, positionMs: Long? = null) {
        if (source.parts.isEmpty()) return
        save()
        graph.library.save(book)
        val previous = graph.library.find(book.id)
        val sameLayout = previous?.source()?.id == source.id
        val history = graph.library.position(book.id, source.id)
        val index = resumeIndex(source.parts, partId ?: history?.partId ?: if (sameLayout) previous?.partId.orEmpty() else "")
        val position = positionMs ?: history?.positionMs ?: if (sameLayout) previous?.positionMs ?: 0 else 0
        currentBook = book; currentSource = source; error = null; chapters = emptyList()
        parts.clear(); links.clear()
        val items = source.parts.map { part ->
            val stableUri = stableAudioUri(part)
            parts[stableUri] = part
            MediaItem.Builder().setMediaId(part.id).setUri(stableUri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(part.title).setAlbumTitle(book.title)
                    .setArtist(book.author).setIsPlayable(true).setArtworkUri(book.coverUrl.takeIf { it.isNotBlank() }?.let(Uri::parse)).build()).build()
        }
        player.setMediaItems(items, index, position.coerceAtLeast(0))
        // Leave a restored session idle until the listener actually resumes.
        if (autoplay) { player.prepare(); player.play() }
        publish(); save()
    }

    fun toggle() {
        error = null
        if (player.isPlaying) player.pause() else { if (player.playbackState == Player.STATE_IDLE) player.prepare(); player.play() }
        publish()
    }
    fun retry() { error = null; links.clear(); player.prepare(); player.play(); publish() }
    fun seek(position: Long) { player.seekTo(position.coerceAtLeast(0)); publish(); scope.launch { save() } }
    fun skip(delta: Long) { seek((player.currentPosition + delta).coerceAtLeast(0).let { if (player.duration > 0) it.coerceAtMost(player.duration) else it }) }
    fun part(index: Int, position: Long = 0) {
        if (index !in 0 until player.mediaItemCount) return
        error = null; player.seekTo(index, position); if (player.playbackState == Player.STATE_IDLE) player.prepare(); player.play(); scope.launch { save() }
    }
    fun speed(value: Float) { player.setPlaybackSpeed(value.coerceIn(0.5f, 3f)); graph.preferences.edit().putFloat("speed", value).apply(); publish() }
    fun sleep(minutes: Int, endOfPart: Boolean = false) {
        sleepUntil = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0
        sleepAtEnd = endOfPart; publish()
    }
    fun disconnect() {
        if (currentSource?.let { it.delivery == "torbox" && !graph.offline.complete(it) } == true) { player.pause(); player.stop(); error = "TorBox is disconnected. Reconnect in Settings to resume this source." }
        links.clear(); publish()
    }
    suspend fun forget() {
        save(); player.stop(); player.clearMediaItems(); currentBook = null; currentSource = null; parts.clear(); links.clear(); chapters = emptyList(); publish()
    }
    suspend fun bookmark(label: String = "") {
        val book = currentBook ?: return; val source = currentSource ?: return
        val part = source.parts.getOrNull(player.currentMediaItemIndex) ?: return
        graph.library.bookmark(BookmarkEntry(bookId = book.id, sourceId = source.id, partId = part.id,
            positionMs = player.currentPosition, label = label.ifBlank { part.title }))
    }

    private suspend fun save() {
        val book = currentBook ?: return; val source = currentSource ?: return
        val part = source.parts.getOrNull(player.currentMediaItemIndex) ?: return
        graph.library.progress(book.id, NarrioJson.encodeToString(source), part.id, player.currentPosition.coerceAtLeast(0), System.currentTimeMillis())
        lastSave = System.currentTimeMillis()
    }

    /** Flush the paused position before package replacement can stop this process. Called on main. */
    suspend fun saveBeforeAppUpdate() {
        check(!player.isPlaying && player.playbackState != Player.STATE_BUFFERING) { "Pause playback before installing the update" }
        save()
    }

    private fun publish() {
        graph.playback.state.value = ListeningState(currentBook, currentSource, player.currentMediaItemIndex.coerceAtLeast(0),
            player.currentPosition.coerceAtLeast(0), player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: currentSource?.parts?.getOrNull(player.currentMediaItemIndex)?.durationMs ?: 0,
            player.isPlaying, player.playbackState == Player.STATE_BUFFERING, player.playbackParameters.speed, sleepUntil, sleepAtEnd, chapters, error)
    }

    private fun readChapters() {
        chapterJob?.cancel()
        val part = currentSource?.parts?.getOrNull(player.currentMediaItemIndex) ?: return
        if (!part.name.endsWith(".m4b", true)) return
        chapterJob = scope.launch {
            val parsed = withContext(Dispatchers.IO) {
                runCatching {
                    if (graph.offline.complete(part)) return@runCatching graph.offline.cachedEdges(part).flatMap(ChapterReader::parseChpl).distinctBy { it.startMs }.sortedBy { it.startMs }
                    val url = graph.torbox.resolve(part)
                    graph.http.newCall(Request.Builder().url(url).header("Range", "bytes=0-262143").build()).execute().use { r ->
                        if (r.code != 206) return@use emptyList<Chapter>()
                        val head = r.body?.bytes() ?: return@use emptyList<Chapter>()
                        val front = ChapterReader.parseChpl(head)
                        if (front.isNotEmpty()) return@use front
                        val length = r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: return@use emptyList<Chapter>()
                        graph.http.newCall(Request.Builder().url(url).header("Range", "bytes=${(length - 524288).coerceAtLeast(0)}-${length - 1}").build()).execute().use { tail ->
                            if (tail.code == 206) ChapterReader.parseChpl(tail.body?.bytes() ?: byteArrayOf()) else emptyList()
                        }
                    }
                }.getOrDefault(emptyList())
            }
            if (currentSource?.parts?.getOrNull(player.currentMediaItemIndex)?.id == part.id && parsed.isNotEmpty()) { chapters = parsed; publish() }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onTaskRemoved(rootIntent: Intent?) { if (!player.playWhenReady) stopSelf() }
    override fun onDestroy() {
        runBlocking { save() }
        graph.playback.service = null
        chapterJob?.cancel(); scope.cancel(); session?.release(); player.release(); super.onDestroy()
    }
}
