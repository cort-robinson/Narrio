package app.narrio.updates

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.narrio.*

class AppUpdateWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        (applicationContext as NarrioApplication).graph.updates.runAutomatic()
        return Result.success()
    }
}

internal object UpdateNotifications {
    private const val ID = 806
    fun ready(context: Context, update: AppUpdate, confirmation: Boolean = false) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val name = context.getString(R.string.app_name)
        manager.createNotificationChannel(NotificationChannel("app-updates", "App updates", NotificationManager.IMPORTANCE_LOW))
        val intent = Intent(context, MainActivity::class.java).putExtra("showUpdates", true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val action = PendingIntent.getActivity(context, ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(ID, NotificationCompat.Builder(context, "app-updates").setSmallIcon(R.drawable.ic_download)
            .setContentTitle(if (confirmation) "Confirm the $name update" else "$name update ready")
            .setContentText("${update.version} is ready to install.").setContentIntent(action).setAutoCancel(true).build())
    }
    fun clear(context: Context) { context.getSystemService(NotificationManager::class.java).cancel(ID) }
}
