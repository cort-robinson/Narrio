package app.narrio.playback

import android.app.Notification
import androidx.media3.exoplayer.offline.*
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler
import app.narrio.NarrioApplication
import app.narrio.R

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class OfflineDownloadService : DownloadService(2001, DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL, "narrio_downloads", R.string.download_channel, 0) {
    private val graph get() = (application as NarrioApplication).graph
    override fun getDownloadManager() = graph.offline.manager
    override fun getScheduler(): Scheduler = PlatformScheduler(this, 2002)
    override fun onTimeout(startId: Int, fgsType: Int) { graph.offline.manager.setStopReason(null, 1); stopSelf() }
    override fun getForegroundNotification(downloads: List<Download>, notMetRequirements: Int): Notification =
        DownloadNotificationHelper(this, "narrio_downloads").buildProgressNotification(this, R.drawable.ic_download,
            android.app.PendingIntent.getActivity(this, 1, android.content.Intent(this, app.narrio.MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT),
            "Saving audiobooks for offline listening", downloads, notMetRequirements)
}
