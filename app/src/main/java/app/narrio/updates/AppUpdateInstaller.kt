package app.narrio.updates

import android.app.PendingIntent
import android.content.*
import android.content.pm.*
import android.os.Build
import app.narrio.NarrioApplication
import java.io.File
import java.security.MessageDigest

class AppUpdateInstaller(private val context: Context) {
    private val manager get() = context.packageManager
    fun permissionGranted() = manager.canRequestPackageInstalls()

    @Suppress("DEPRECATION")
    private fun info(path: String? = null): PackageInfo {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        return if (path == null) manager.getPackageInfo(context.packageName, flags)
        else manager.getPackageArchiveInfo(path, flags) ?: error("Downloaded update is not a valid APK")
    }

    @Suppress("DEPRECATION")
    private fun certificates(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { byte -> "%02x".format(byte) } }.toSet()
    }

    fun certificate(): String = certificates(info()).singleOrNull() ?: error("App signing identity is unavailable")

    @Suppress("DEPRECATION")
    fun verify(file: File, update: AppUpdate) {
        require(UpdateRepository.verifiesBytes(file, update)) { "Update verification failed. Download it again." }
        val installed = info()
        val archive = info(file.absolutePath)
        val archiveCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        val installedCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        require(archive.packageName == context.packageName && archiveCode == update.code && archiveCode > installedCode && archive.versionName == update.version) { "Update is not compatible with this app version" }
        require(archive.applicationInfo?.flags?.and(ApplicationInfo.FLAG_DEBUGGABLE) == 0) { "Update must be a signed release build" }
        require(certificates(archive) == setOf(update.certificate) && certificates(installed) == setOf(update.certificate)) { "Update signing identity does not match this app" }
    }

    /** persistSession runs before commit so even an immediate system callback is attributable. */
    fun install(file: File, update: AppUpdate, manual: Boolean, idle: () -> Boolean, persistSession: (Int) -> Unit) {
        verify(file, update)
        require(permissionGranted()) { "Allow Narrio to install app updates first" }
        require(idle()) { "Pause playback before installing the update" }
        val installer = manager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(update.bytes)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(if (manual) PackageInstaller.SessionParams.USER_ACTION_REQUIRED else PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                file.inputStream().use { input -> session.openWrite("base.apk", 0, update.bytes).use { output -> input.copyTo(output); session.fsync(output) } }
                require(idle()) { "Playback started. The update will wait." }
                val intent = Intent(context, UpdateInstallReceiver::class.java).putExtra("narrioSession", id)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val callback = PendingIntent.getBroadcast(context, id, intent, flags)
                persistSession(id)
                session.commit(callback.intentSender)
            }
        } catch (error: Exception) { installer.abandonSession(id); throw error }
    }

    fun abandon(id: Int) { if (id >= 0) runCatching { manager.packageInstaller.abandonSession(id) } }
}

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val updates = (context.applicationContext as NarrioApplication).graph.updates
        val id = intent.getIntExtra("narrioSession", -1)
        if (id < 0 || id != updates.activeSession()) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        @Suppress("DEPRECATION")
        val confirmation = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
        updates.installResult(status, confirmation)
    }
}
