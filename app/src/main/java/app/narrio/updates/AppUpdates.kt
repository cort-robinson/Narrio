package app.narrio.updates

import android.app.Application
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.work.*
import app.narrio.BuildConfig
import app.narrio.data.NarrioJson
import app.narrio.playback.PlaybackHub
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class AppUpdates(private val application: Application, private val playback: PlaybackHub) {
    private val preferences = application.getSharedPreferences("appUpdates", Application.MODE_PRIVATE)
    private val channel = UpdateChannel.forApplication(application.packageName, BuildConfig.DEBUG)
    private val installer = AppUpdateInstaller(application)
    private val policy = channel?.let { UpdatePolicy(it, BuildConfig.VERSION_CODE.toLong(), installer.certificate()) }
    private val repository = policy?.let { UpdateRepository(OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS).callTimeout(10, TimeUnit.MINUTES).followSslRedirects(false)
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", "Narrio/${BuildConfig.VERSION_NAME} updates").build()) }.build(), it) }
    private val directory = File(application.filesDir, "app-updates")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private var confirmation: Intent? = null
    private var visibilityJob: Job? = null
    @Volatile private var foreground = false
    @Volatile private var hiddenAt = 0L
    private val mutableState = MutableStateFlow(restore())
    val state = mutableState.asStateFlow()

    init {
        schedule()
        scope.launch { playback.state.map { it.playing || it.buffering }.distinctUntilChanged().drop(1).collect { busy ->
            mutableState.update { it.copy(waitingForPlayback = busy && it.available != null) }
            if (!busy) installAutomatically()
        } }
    }

    private fun restore(): UpdateState {
        val available = runCatching { NarrioJson.decodeFromString<AppUpdate>(preferences.getString("candidate", "").orEmpty()) }.getOrNull()
            ?.takeIf { policy?.acceptsCached(it) == true }
        if (available == null) {
            preferences.edit().remove("candidate").remove("session").remove("blockedCode").apply()
            UpdateNotifications.clear(application)
            directory.listFiles()?.filter { it.name.matches(Regex("Narrio-[\\d.]+(?:-dev\\.\\d+)?\\.apk(?:\\.part)?")) }?.forEach(File::delete)
        }
        val phase = when {
            available == null -> UpdatePhase.IDLE
            preferences.getInt("session", -1) >= 0 -> UpdatePhase.CONFIRMATION
            File(directory, available.apk).isFile -> UpdatePhase.READY
            else -> UpdatePhase.IDLE
        }
        return UpdateState(channel, preferences.getBoolean("automatic", true), phase, available,
            lastChecked = preferences.getLong("checked", 0), permissionNeeded = channel != null && !installer.permissionGranted())
    }

    private fun saveCandidate(update: AppUpdate?) {
        preferences.edit().apply { if (update == null) remove("candidate") else putString("candidate", NarrioJson.encodeToString(update)) }.apply()
    }

    fun setAutomatic(value: Boolean) {
        preferences.edit().putBoolean("automatic", value).apply()
        mutableState.update { it.copy(automatic = value) }
        schedule()
        if (value) scope.launch { runAutomatic() }
    }

    private fun schedule() {
        if (channel == null) return
        val work = WorkManager.getInstance(application)
        if (!state.value.automatic) { work.cancelUniqueWork("narrio-app-updates"); return }
        val hours = if (channel == UpdateChannel.DEV) 1L else 12L
        work.enqueueUniquePeriodicWork("narrio-app-updates", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<AppUpdateWorker>(hours, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                .setInitialDelay(hours, TimeUnit.HOURS).build())
    }

    fun visibility(visible: Boolean) {
        foreground = visible
        hiddenAt = if (visible) 0 else android.os.SystemClock.elapsedRealtime()
        visibilityJob?.cancel()
        mutableState.update { it.copy(permissionNeeded = channel != null && !installer.permissionGranted(),
            waitingForPlayback = it.available != null && (playback.state.value.playing || playback.state.value.buffering)) }
        visibilityJob = scope.launch {
            if (visible) runAutomatic() else { delay(5_000); installAutomatically() }
        }
    }

    // Activity recreation and brief trips to Android Settings must not trigger package replacement.
    private fun backgroundSettled() = hasSettledBackground(foreground, hiddenAt, android.os.SystemClock.elapsedRealtime())

    fun checkNow() { scope.launch { check(true); downloadAutomatically() } }
    fun downloadNow() { scope.launch { download(true) } }

    suspend fun runAutomatic() {
        if (!state.value.automatic || channel == null) return
        check(false)
        downloadAutomatically()
        installAutomatically()
    }

    private suspend fun check(force: Boolean) = mutex.withLock {
        val repo = repository ?: return@withLock
        if (state.value.phase in listOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.INSTALLING, UpdatePhase.CONFIRMATION)) return@withLock
        val now = System.currentTimeMillis()
        val interval = if (channel == UpdateChannel.DEV) TimeUnit.MINUTES.toMillis(10) else TimeUnit.HOURS.toMillis(6)
        if (!force && (!state.value.automatic || now - preferences.getLong("attempt", 0) < interval)) return@withLock
        preferences.edit().putLong("attempt", now).apply()
        val previous = state.value
        mutableState.value = previous.copy(phase = UpdatePhase.CHECKING, error = null)
        try {
            val update = repo.latest() ?: previous.available
            if (update != previous.available) {
                directory.listFiles()?.filter { it.name != update?.apk && it.name.matches(Regex("Narrio-[\\d.]+(?:-dev\\.\\d+)?\\.apk(?:\\.part)?")) }?.forEach(File::delete)
                preferences.edit().remove("blockedCode").apply()
            }
            saveCandidate(update)
            preferences.edit().putLong("checked", now).apply()
            mutableState.value = state.value.copy(available = update, lastChecked = now,
                phase = if (update != null && File(directory, update.apk).isFile) UpdatePhase.READY else UpdatePhase.IDLE,
                waitingForPlayback = update != null && (playback.state.value.playing || playback.state.value.buffering),
                permissionNeeded = !installer.permissionGranted())
        } catch (cancelled: CancellationException) { mutableState.update { it.copy(phase = previous.phase, error = previous.error) }; throw cancelled }
        catch (error: Exception) { mutableState.value = state.value.copy(phase = previous.phase, error = message(error)) }
    }

    private fun unmetered(): Boolean {
        val network = application.getSystemService(ConnectivityManager::class.java)
        return network.getNetworkCapabilities(network.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    }

    private suspend fun downloadAutomatically() {
        if (state.value.automatic && unmetered() && state.value.phase == UpdatePhase.IDLE && state.value.available != null) download(false)
    }

    private suspend fun download(manual: Boolean) = mutex.withLock {
        val update = state.value.available ?: return@withLock
        val repo = repository ?: return@withLock
        if (state.value.phase !in listOf(UpdatePhase.IDLE, UpdatePhase.READY)) return@withLock
        if (!manual && (!state.value.automatic || !unmetered())) return@withLock
        mutableState.update { it.copy(phase = UpdatePhase.DOWNLOADING, progress = 0f, error = null) }
        try {
            val file = repo.download(update, directory) { progress ->
                if (!manual && !state.value.automatic) throw CancellationException("Automatic updates paused")
                mutableState.update { it.copy(progress = progress) }
            }
            withContext(Dispatchers.IO) { installer.verify(file, update) }
            mutableState.update { it.copy(phase = UpdatePhase.READY, progress = 1f, permissionNeeded = !installer.permissionGranted(),
                waitingForPlayback = playback.state.value.playing || playback.state.value.buffering) }
            if (!foreground) UpdateNotifications.ready(application, update)
        } catch (cancelled: CancellationException) { mutableState.update { it.copy(phase = UpdatePhase.IDLE) }; throw cancelled }
        catch (error: Exception) { File(directory, update.apk).delete(); mutableState.update { it.copy(phase = UpdatePhase.IDLE, error = message(error)) } }
    }

    private suspend fun installAutomatically() {
        val audio = playback.state.value
        if (state.value.phase == UpdatePhase.READY && preferences.getLong("blockedCode", -1) != state.value.available?.code &&
            automaticInstallAllowed(state.value.automatic, installer.permissionGranted(), audio.playing, audio.buffering, false, !backgroundSettled(), Build.VERSION.SDK_INT)) install(false)
    }

    fun installNow(context: android.content.Context) {
        if (playback.state.value.playing || playback.state.value.buffering) {
            mutableState.update { it.copy(waitingForPlayback = true) }; return
        }
        val intent = confirmation
        if (intent != null) {
            runCatching { context.startActivity(intent) }.onSuccess { confirmation = null }
                .onFailure { mutableState.update { it.copy(error = "Could not open the installer. Try confirming again.") } }
            return
        }
        scope.launch { install(true) }
    }

    private suspend fun install(manual: Boolean) = mutex.withLock {
        val update = state.value.available ?: return@withLock
        if (state.value.phase !in listOf(UpdatePhase.READY, UpdatePhase.CONFIRMATION)) return@withLock
        val idle = { !playback.state.value.playing && !playback.state.value.buffering && (manual || (backgroundSettled() && state.value.automatic)) }
        if (!idle()) { mutableState.update { it.copy(waitingForPlayback = true) }; return@withLock }
        if (!installer.permissionGranted()) { mutableState.update { it.copy(permissionNeeded = true) }; return@withLock }
        installer.abandon(activeSession())
        confirmation = null
        mutableState.update { it.copy(phase = UpdatePhase.INSTALLING, error = null) }
        try {
            playback.service?.saveBeforeAppUpdate()
            withContext(Dispatchers.IO) { installer.install(File(directory, update.apk), update, manual, idle) { id -> preferences.edit().putInt("session", id).commit() } }
        } catch (cancelled: CancellationException) {
            mutableState.update { if (it.phase == UpdatePhase.INSTALLING && activeSession() < 0) it.copy(phase = UpdatePhase.READY) else it }
            throw cancelled
        }
        catch (error: Exception) {
            preferences.edit().remove("session").apply { if (idle()) putLong("blockedCode", update.code) }.apply()
            mutableState.update { it.copy(phase = UpdatePhase.READY, error = message(error)) }
        }
    }

    fun activeSession() = preferences.getInt("session", -1)
    fun installResult(status: Int, intent: Intent?) {
        when (status) {
            android.content.pm.PackageInstaller.STATUS_SUCCESS -> {
                preferences.edit().remove("session").remove("candidate").remove("blockedCode").apply()
                state.value.available?.let { File(directory, it.apk).delete() }
                mutableState.update { it.copy(available = null, phase = UpdatePhase.IDLE, error = null) }
                UpdateNotifications.clear(application)
            }
            android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                confirmation = intent
                mutableState.update { it.copy(phase = UpdatePhase.CONFIRMATION) }
                if (foreground && !playback.state.value.playing && !playback.state.value.buffering && intent != null) {
                    runCatching { application.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onSuccess { confirmation = null }
                }
                else state.value.available?.let { UpdateNotifications.ready(application, it, confirmation = true) }
            }
            else -> {
                preferences.edit().remove("session").putLong("blockedCode", state.value.available?.code ?: -1).apply()
                mutableState.update { it.copy(phase = UpdatePhase.READY, error = "Update wasn't installed. You can try again.") }
            }
        }
    }

    private fun message(error: Exception) = error.message?.take(180)?.takeIf { !it.contains("http", true) }
        ?: "Could not complete the update. Check your connection and try again."
}
