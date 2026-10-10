package app.narrio.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import app.narrio.*
import app.narrio.data.*
import app.narrio.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.encodeToString
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/** Skip distances shared by the app's controls, the notification, and headset/car controls. */
const val SKIP_BACK_MS = 10_000L
const val SKIP_FORWARD_MS = 30_000L
/** Adds 15 minutes to a running sleep timer from the media notification. */
private val EXTEND_SLEEP = SessionCommand("app.narrio.sleep.EXTEND", Bundle.EMPTY)

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
    /** Chapters read for this recording's parts, by part id. A part's chapters never stand in for another's. */
    private val partChapters = mutableMapOf<String, List<Chapter>>()
    private fun chaptersOf(index: Int): List<Chapter> = currentSource?.parts?.getOrNull(index)?.let { partChapters[it.id] }.orEmpty()
    private val chapters: List<Chapter> get() = chaptersOf(player.currentMediaItemIndex)
    private var sleep = SleepTimer()
    private var sleepJob: Job? = null
    /** Wakes the sleep loop when playback changes (play/pause, seeks, speed, length), so it needn't poll while paused. */
    private val sleepWake = Channel<Unit>(Channel.CONFLATED)
    private var extendShown = false
    private val shake = ShakeDetector()
    private var shakeListening = false
    private val shakeListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (shake.sample(event.values[0], event.values[1], event.values[2], android.os.SystemClock.elapsedRealtime())) { extendSleep(); nudge() }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    private val completion = ListeningCompletion()
    private var error: String? = null
    private var lastSave = 0L
    private val activity = ListeningActivityGate()
    private var activitySequence: Long? = null
    private var activityJob: Job? = null
    private var navigationEpoch = 0L
    private var correctionJob: Job? = null
    private var correctionWanted: ContentCursor? = null
    private var undoEpoch = -1L
    private var syncSeekPosition: Long? = null
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
            .setSeekBackIncrementMs(SKIP_BACK_MS).setSeekForwardIncrementMs(SKIP_FORWARD_MS)
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
                val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(EXTEND_SLEEP).build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(mediaSession).setAvailablePlayerCommands(commands)
                    .setAvailableSessionCommands(sessionCommands).build()
            }
            override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                if (customCommand.customAction != EXTEND_SLEEP.customAction) return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                extendSleep()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
        }).build()
        graph.playback.service = this
        // Playing through the last part finishes the book on the shelf; playing it again, from any control, reopens it.
        player.addListener(CompletionListener(player, completion, { currentBook?.id?.let { id -> currentSource?.let { id to it } } },
            finished = { id -> scope.launch { graph.library.finished(id) } }, resumed = { id -> scope.launch { graph.library.unfinished(id) } }))
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { publish(); if (sleep.active) sleepWake.trySend(Unit) }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Resuming mid-fade continues the fade at once rather than at full volume until the loop catches up.
                player.volume = sleepVolume(sleep, isPlaying, remaining())
                publish()
                if (isPlaying) startCorrection() else correctionJob?.cancel()
                scope.launch { save() }
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                // Includes media-session/controller seeks, not just Narrio's controls.
                if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                    val syncSeek = reason == Player.DISCONTINUITY_REASON_SEEK && syncSeekPosition?.let { kotlin.math.abs(it - newPosition.positionMs) < 1000 } == true
                    syncSeekPosition = null
                    if (!syncSeek) invalidateNavigation()
                }
                scope.launch { save() }
                // A seek moves a chapter/part timer to wherever the listener went; playing on past its stop pauses.
                if (reason == Player.DISCONTINUITY_REASON_SEEK) retargetSleep()
                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION && sleep.stop?.let { player.currentMediaItemIndex > it.partIndex } == true) finishSleep()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) error = null
                if (playbackState == Player.STATE_ENDED) { setSleep(SleepTimer()); scope.launch { save() } }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { readChapters(); scope.launch { save() } }
            override fun onPlayerError(playbackError: PlaybackException) {
                error = "Audio couldn't continue. Check your connection, then retry. TorBox sources also need a connected account."
                publish(); scope.launch { save() }
            }
            override fun onMetadata(metadata: Metadata) { id3Chapters(listOf(metadata)).takeIf { it.isNotEmpty() }?.let { storeChapters(player.currentMediaItemIndex, it) } }
            // MP3 files carry ID3 chapter frames in the file's tag, which Media3 reports on the track format, not as timed metadata.
            override fun onTracksChanged(tracks: Tracks) {
                val parsed = id3Chapters(tracks.groups.flatMap { group -> (0 until group.length).mapNotNull { group.getTrackFormat(it).metadata } })
                if (parsed.isNotEmpty()) storeChapters(player.currentMediaItemIndex, parsed)
            }
        })
        scope.launch {
            // The row's book may be a recording being got ready since; the played audio's own recording is restored.
            try { restorable()?.let { entry -> entry.source()?.let { load(graph.recordingFor(entry) ?: entry.book(), it, false) } } }
            finally { initialized = true }
            while (isActive) {
                if (player.isPlaying && System.currentTimeMillis() - lastSave >= 5000) save()
                if (graph.playback.visible || player.isPlaying || sleep.active) publish()
                commitListeningActivity()
                delay(if (graph.playback.visible || player.isPlaying) 1000 else 5000)
            }
        }
    }

    suspend fun load(book: Audiobook, source: AudioSource, autoplay: Boolean = true, partId: String? = null, positionMs: Long? = null) {
        if (source.parts.isEmpty()) return
        save()
        invalidateNavigation()
        graph.library.save(book)
        graph.mappingRepository.register(book.id, source)
        val previous = graph.library.find(book.id)
        val sameLayout = previous?.source()?.id == source.id
        val history = graph.library.position(book.id, source.id)
        val previousCursor = AudioCursor(source.id, history?.partId ?: if (sameLayout) previous?.partId.orEmpty() else source.parts.first().id,
            history?.positionMs ?: if (sameLayout) previous?.positionMs ?: 0 else 0)
        val jump = if (partId == null && positionMs == null) graph.readingSync.listeningStart(book.id, source, previousCursor) else null
        val index = resumeIndex(source.parts, partId ?: jump?.destination?.partId ?: history?.partId ?: if (sameLayout) previous?.partId.orEmpty() else "")
        val position = positionMs ?: jump?.destination?.positionMs ?: history?.positionMs ?: if (sameLayout) previous?.positionMs ?: 0 else 0
        // A chapter or part timer belongs to the recording it was set in; the same recording reloaded keeps it.
        val sameRecording = currentBook?.id == book.id && currentSource?.id == source.id
        if (!sameRecording) { partChapters.clear(); if (sleep.stop != null) setSleep(SleepTimer()) }
        currentBook = book; currentSource = source; error = null
        parts.clear(); links.clear()
        val items = source.parts.map { part ->
            val stableUri = stableAudioUri(part)
            parts[stableUri] = part
            MediaItem.Builder().setMediaId(part.id).setUri(stableUri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(part.title).setAlbumTitle(book.title)
                    .setArtist(book.author).setIsPlayable(true).setArtworkUri(book.coverUrl.takeIf { it.isNotBlank() }?.let(Uri::parse)).build()).build()
        }
        player.setMediaItems(items, index, position.coerceAtLeast(0))
        completion.loaded(book.id, source.id, index, position)
        if (sameRecording) retargetSleep()
        readChapters()
        if (jump?.confidence == MappingConfidence.ESTIMATED) correctionWanted = graph.sharedPositions.current(book.id)?.text
        undoEpoch = navigationEpoch
        // Leave a restored session idle until the listener actually resumes.
        if (autoplay) { player.prepare(); player.play() }
        publish(); save()
    }

    fun toggle() {
        error = null
        if (player.isPlaying) player.pause() else { if (player.playbackState == Player.STATE_IDLE) { recovering(); player.prepare() }; player.play() }
        publish()
    }
    fun retry() { error = null; links.clear(); recovering(); player.prepare(); player.play(); publish() }
    /** Listening restarts here after an error or a stop, so it can count toward finishing again. */
    private fun recovering() = completion.anchor(player.currentMediaItemIndex, player.currentPosition)
    fun seek(position: Long) { invalidateNavigation(); player.seekTo(position.coerceAtLeast(0)); publish(); scope.launch { save() } }
    fun skip(delta: Long) { seek((player.currentPosition + delta).coerceAtLeast(0).let { if (player.duration > 0) it.coerceAtMost(player.duration) else it }) }
    fun part(index: Int, position: Long = 0) {
        if (index !in 0 until player.mediaItemCount) return
        invalidateNavigation()
        error = null; player.seekTo(index, position); if (player.playbackState == Player.STATE_IDLE) player.prepare(); player.play(); scope.launch { save() }
    }
    /** Previous/next chapter or part: moves the place without starting or stopping playback. */
    fun go(place: PartPlace) {
        if (place.partIndex !in 0 until player.mediaItemCount) return
        if (place.partIndex == player.currentMediaItemIndex) return seek(place.positionMs)
        invalidateNavigation(); error = null
        player.seekTo(place.partIndex, place.positionMs.coerceAtLeast(0)); publish(); scope.launch { save() }
    }
    fun speed(value: Float) { player.setPlaybackSpeed(value.coerceIn(0.5f, 3f)); graph.preferences.edit().putFloat("speed", value).apply(); publish() }
    /** Starts, replaces, or (with [SleepMode.OFF]) cancels the sleep timer. */
    fun sleep(mode: SleepMode, minutes: Int = 0) {
        setSleep(when (mode) {
            SleepMode.OFF -> SleepTimer()
            SleepMode.MINUTES -> if (minutes > 0) SleepTimer(mode, minutes, System.currentTimeMillis() + minutes * 60_000L) else SleepTimer()
            else -> boundaryTimer(mode, PartPlace(player.currentMediaItemIndex, player.currentPosition), chapters)
        })
    }
    /** "+15 min": from the timer dialog, the notification, or a shake in its last minute. */
    fun extendSleep() {
        if (!sleep.active) return
        setSleep(app.narrio.playback.extendSleep(sleep, remaining(), System.currentTimeMillis()))
    }
    /** The narration's volume, which a running timer fades out; exposed for playback tests. */
    val volume: Float get() = player.volume

    private fun remaining(): Long? = sleepRemainingMs(sleep, player.currentMediaItemIndex, player.currentPosition,
        player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0, player.playbackParameters.speed, System.currentTimeMillis())

    private fun setSleep(timer: SleepTimer) {
        sleep = timer
        sleepJob?.cancel(); sleepJob = null
        if (timer.active) sleepJob = scope.launch { runSleep() } else { player.volume = 1f; listenForShake(false) }
        showExtendButton(timer.active)
        publish()
    }

    /**
     * Fades the narration over the last seconds, then pauses. Checks finely only while fading; while paused it waits for
     * playback to change (a minute timer also wakes when its clock runs out) with the volume back at full.
     */
    private suspend fun runSleep() {
        while (sleep.active) {
            val left = remaining()
            if (sleepPausesNow(sleep, player.currentMediaItemIndex, left)) { finishSleep(); return }
            val playing = player.isPlaying
            player.volume = sleepVolume(sleep, playing, left)
            listenForShake(playing && left != null && left <= SHAKE_WINDOW_MS && graph.preferences.getBoolean(SHAKE_TO_EXTEND, true))
            val wait = sleepWaitMs(sleep, playing, left)
            if (wait == null) sleepWake.receive() else withTimeoutOrNull(wait) { sleepWake.receive() }
        }
    }

    private fun finishSleep() {
        sleep = SleepTimer(); sleepJob?.cancel(); sleepJob = null
        player.pause(); player.volume = 1f
        listenForShake(false); showExtendButton(false)
        publish(); scope.launch { save() }
    }

    /** Aims a chapter/part timer from the current place. During a seek into another part this already names that part. */
    private fun retargetSleep() {
        if (sleep.stop == null) return
        sleep = boundaryTimer(sleep.mode, PartPlace(player.currentMediaItemIndex, player.currentPosition), chapters)
        player.volume = sleepVolume(sleep, player.isPlaying, remaining())
        sleepWake.trySend(Unit); publish()
    }

    /** Keeps a part's chapters and narrows a chapter timer that was aimed at that part before they were known. */
    private fun storeChapters(index: Int, parsed: List<Chapter>) {
        val part = currentSource?.parts?.getOrNull(index) ?: return
        val sorted = parsed.distinctBy { it.startMs }.sortedBy { it.startMs }
        if (partChapters[part.id] == sorted) return
        partChapters[part.id] = sorted
        val narrowed = withChapters(sleep, index, sorted)
        if (narrowed != sleep) { sleep = narrowed; sleepWake.trySend(Unit) }
        publish()
    }

    private fun listenForShake(on: Boolean) {
        if (on == shakeListening) return
        val sensors = getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        if (on) {
            val accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
            shake.reset()
            shakeListening = sensors.registerListener(shakeListener, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        } else { sensors.unregisterListener(shakeListener); shakeListening = false }
    }

    /** A short buzz confirms a shake was heard in the dark; the narration also returns to full volume. */
    private fun nudge() {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            else @Suppress("DEPRECATION") (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
        if (vibrator?.hasVibrator() != true) return
        vibrator.vibrate(if (Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK) else VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    /** The notification offers "+15 min" only while a timer runs. */
    private fun showExtendButton(show: Boolean) {
        if (show == extendShown) return
        extendShown = show
        session?.setMediaButtonPreferences(if (show) listOf(CommandButton.Builder(CommandButton.ICON_PLUS).setDisplayName("Add 15 minutes to the sleep timer")
            .setSessionCommand(EXTEND_SLEEP).setSlots(CommandButton.SLOT_OVERFLOW).build()) else emptyList())
    }
    fun disconnect() {
        if (currentSource?.let { it.delivery == "torbox" && !graph.offline.complete(it) } == true) { player.pause(); player.stop(); error = "TorBox is disconnected. Reconnect in Settings to resume this source." }
        links.clear(); publish()
    }
    suspend fun forget() {
        // Detach first: clearing the player fires listeners that would otherwise save position 0 over the listener's place.
        save(); invalidateNavigation(); completion.cleared(); currentBook = null; currentSource = null; player.stop(); player.clearMediaItems(); parts.clear(); links.clear(); partChapters.clear()
        error = null; setSleep(SleepTimer())
    }
    /** Clear Now playing but keep the shelf entry and position; the next launch stays empty until something plays again. */
    suspend fun dismiss() {
        forget()
        graph.preferences.edit().putLong(DISMISSED_AT, System.currentTimeMillis()).apply()
    }
    /** The last played book, unless the listener dismissed it after that. */
    suspend fun restorable(): ShelfEntry? = graph.library.lastPlayed()?.takeIf { it.playedAt > graph.preferences.getLong(DISMISSED_AT, 0) }
    suspend fun bookmark(label: String = "") {
        val book = currentBook ?: return; val source = currentSource ?: return
        val part = source.parts.getOrNull(player.currentMediaItemIndex) ?: return
        val audio = AudioCursor(source.id, part.id, player.currentPosition.coerceAtLeast(0))
        // The reading place is kept only when narration confirms it; otherwise the bookmark list maps it each time.
        val text = runCatching { BookmarkMapping(graph, book.id).textFor(audio) }.getOrNull()
        graph.library.bookmark(newBookmark(book.id, label.ifBlank { part.title }, audio = audio, mappedText = text))
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
        graph.sharedPositions.playingBookId = if (player.isPlaying) currentBook?.id else null
        val source = currentSource
        val bookId = currentBook?.id
        source?.parts?.getOrNull(player.currentMediaItemIndex)?.let { part ->
            val duration = player.duration
            if (bookId != null && graph.mappingRepository.duration(bookId, source.id, part.id, duration)) scope.launch {
                graph.mappingRepository.persistDuration(bookId, source.id, part.id, duration)
            }
            // Keep measured lengths, so whole-book time appears once every part has been heard or described.
            if (duration != C.TIME_UNSET && duration > 0 && part.durationMs != duration)
                currentSource = source.copy(parts = source.parts.map { if (it.id == part.id) it.copy(durationMs = duration) else it })
        }
        graph.playback.state.value = ListeningState(currentBook, currentSource, player.currentMediaItemIndex.coerceAtLeast(0),
            player.currentPosition.coerceAtLeast(0), player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: currentSource?.parts?.getOrNull(player.currentMediaItemIndex)?.durationMs ?: 0,
            player.isPlaying, player.playbackState == Player.STATE_BUFFERING, player.playbackParameters.speed, sleep, chapters, error, partChapters.toMap())
    }

    private fun invalidateNavigation() {
        navigationEpoch++; correctionJob?.cancel(); correctionJob = null; correctionWanted = null
        syncSeekPosition = null
        activityJob?.cancel(); activityJob = null
        activity.reset(); activitySequence = null
    }

    private suspend fun commitListeningActivity() {
        val book = currentBook ?: return
        val source = currentSource ?: return
        val part = source.parts.getOrNull(player.currentMediaItemIndex) ?: return
        if (activitySequence == null) activitySequence = graph.sharedPositions.current(book.id)?.sequence ?: 0
        if (!activity.sample(book.id, source.id, player.isPlaying, android.os.SystemClock.elapsedRealtime())) return
        val epoch = navigationEpoch
        val cursor = AudioCursor(source.id, part.id, player.currentPosition.coerceAtLeast(0))
        val observed = activitySequence ?: return
        // Mapping/storage suspends. Cancellation on navigation prevents an obsolete callback writing.
        val commitJob = scope.launch {
            if (epoch != navigationEpoch) return@launch
            graph.readingSync.listeningCommit(book.id, cursor, observed)
            if (epoch == navigationEpoch) activitySequence = graph.sharedPositions.current(book.id)?.sequence ?: 0
        }
        activityJob = commitJob
        commitJob.join()
    }

    private fun startCorrection() {
        val wanted = correctionWanted ?: return
        if (correctionJob?.isActive == true) return
        val epoch = navigationEpoch
        correctionJob = scope.launch {
            val book = currentBook ?: return@launch
            val source = currentSource ?: return@launch
            val part = source.parts.getOrNull(player.currentMediaItemIndex) ?: return@launch
            try {
                val snapshot = graph.mappingRepository.snapshot(book.id, source.id) ?: return@launch
                if (snapshot.document.id != wanted.editionId) return@launch
                val duration = player.duration.takeIf { it > 0 } ?: part.durationMs
                val target = SyncTarget(book, source, part, player.currentPosition, duration, snapshot.document,
                    snapshot.bindings.firstOrNull { it.sourceId == source.id && it.partId == part.id })
                val startedAt = android.os.SystemClock.elapsedRealtime()
                val corrected = graph.readingSync.correct(target, wanted, graph.narrationSync, {
                    if (epoch != navigationEpoch || !player.isPlaying) null else {
                        val latest = graph.mappingRepository.snapshot(book.id, source.id)
                        latest?.let { target.copy(document = it.document, binding = it.bindings.firstOrNull { b -> b.sourceId == source.id && b.partId == part.id }) }
                    }
                }) { id, binding -> graph.followAlong.mergeNarration(id, binding, binding.anchors.filter { it.auto }, duration) }
                if (corrected != null && epoch == navigationEpoch && player.isPlaying) {
                    // Playback continued during recognition; correct to where the reader start has advanced.
                    val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
                    correctionWanted = null
                    val destination = (corrected.audio.positionMs + (elapsed * player.playbackParameters.speed).toLong()).coerceAtMost(duration - 1).coerceAtLeast(0)
                    syncSeekPosition = destination
                    player.seekTo(destination)
                    publish()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Keep the estimated position when model/audio/text cannot be read. */ }
            finally { if (epoch == navigationEpoch) correctionWanted = null }
        }
    }

    /** B/E use this for an explicit sentence tap; browsing alone must never call it. */
    suspend fun seekFromText(cursor: ContentCursor): Boolean {
        val book = currentBook ?: return false
        val source = currentSource ?: return false
        val snapshot = graph.mappingRepository.snapshot(book.id, source.id) ?: return false
        val pairing = graph.readingSync.pairing(book.id, snapshot)
        if (pairing == PairingStatus.MISMATCH) return false
        val mapped = graph.positionMapper.audioFor(book.id, cursor, source.id) ?: return false
        invalidateNavigation()
        player.seekTo(resumeIndex(source.parts, mapped.audio.partId), mapped.audio.positionMs)
        graph.readingSync.sentenceSeek(book.id, cursor, mapped, pairing)
        if (mapped.confidence == MappingConfidence.ESTIMATED) correctionWanted = cursor
        if (player.isPlaying) startCorrection()
        publish()
        return true
    }

    /**
     * A switch back to listening (leaving read along, or starting it from a page): start at the shared place, as
     * [load] does, when reading moved it. Large moves offer Undo through [ReadingSync]'s audio jump.
     */
    suspend fun alignToSharedPosition(): Boolean {
        val book = currentBook ?: return false
        val source = currentSource ?: return false
        val part = source.parts.getOrNull(player.currentMediaItemIndex) ?: return false
        if (graph.sharedPositions.current(book.id)?.origin != PositionOrigin.READING || player.isPlaying) return false
        val here = AudioCursor(source.id, part.id, player.currentPosition.coerceAtLeast(0))
        val jump = graph.readingSync.listeningStart(book.id, source, here)
        val destination = jump.destination?.takeIf { it != here } ?: return false
        invalidateNavigation()
        player.seekTo(resumeIndex(source.parts, destination.partId), destination.positionMs)
        if (jump.confidence == MappingConfidence.ESTIMATED) correctionWanted = graph.sharedPositions.current(book.id)?.text
        undoEpoch = navigationEpoch
        publish(); save()
        return true
    }

    fun undoSyncJump() {
        val jump = graph.readingSync.state.value.audioJump ?: return
        if (!jump.offerUndo || undoEpoch != navigationEpoch) return
        val previous = jump.previous ?: return
        val source = currentSource?.takeIf { it.id == previous.sourceId } ?: return
        invalidateNavigation()
        player.seekTo(resumeIndex(source.parts, previous.partId), previous.positionMs)
        graph.readingSync.clearJump(); publish()
    }

    private fun readChapters() {
        chapterJob?.cancel()
        val index = player.currentMediaItemIndex
        val part = currentSource?.parts?.getOrNull(index) ?: return
        if (!part.name.endsWith(".m4b", true) || part.id in partChapters) return
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
            if (currentSource?.parts?.getOrNull(index)?.id == part.id && parsed.isNotEmpty()) storeChapters(index, parsed)
        }
    }

    /** ID3 chapter frames (CHAP) with their title subframe, from timed metadata or an MP3's tag. */
    private fun id3Chapters(entries: List<Metadata>): List<Chapter> = entries.flatMap { metadata ->
        (0 until metadata.length()).mapNotNull { i ->
            (metadata[i] as? ChapterFrame)?.let { frame ->
                val title = (0 until frame.subFrameCount).mapNotNull { frame.getSubFrame(it) as? TextInformationFrame }
                    .firstOrNull()?.values?.firstOrNull() ?: frame.chapterId
                Chapter(title, frame.startTimeMs.toLong())
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onTaskRemoved(rootIntent: Intent?) { if (!player.playWhenReady) stopSelf() }
    override fun onDestroy() {
        runBlocking { save() }
        graph.playback.service = null
        graph.sharedPositions.playingBookId = null
        listenForShake(false)
        chapterJob?.cancel(); scope.cancel(); session?.release(); player.release(); super.onDestroy()
    }

    companion object {
        private const val DISMISSED_AT = "playbackDismissedAt"
        /** Preference: shaking the phone in a sleep timer's last minute adds 15 minutes. On unless turned off. */
        const val SHAKE_TO_EXTEND = "sleepShakeToExtend"
    }
}
