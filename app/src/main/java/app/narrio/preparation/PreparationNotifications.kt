package app.narrio.preparation

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.narrio.MainActivity
import app.narrio.R
import app.narrio.data.PreparationChange

/**
 * Says when a book TorBox was getting ready can be listened to, or couldn't be prepared. Nothing is shown while it
 * prepares. Without notification permission these are skipped; the shelf still shows the change.
 */
object PreparationNotifications {
    const val CHANNEL = "torbox-preparation"
    const val ACTION_OPEN = "app.narrio.action.OPEN_BOOK"
    const val ACTION_LISTEN = "app.narrio.action.LISTEN"
    const val EXTRA_BOOK = "app.narrio.extra.BOOK_ID"
    private const val ID = 807

    fun show(context: Context, change: PreparationChange) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "TorBox preparation", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "When a book TorBox was getting ready is ready to listen, or couldn't be prepared"
        })
        val book = change.book
        val text = if (change.ready) "TorBox finished getting it ready. Listening streams it; nothing downloads to your phone."
            else "${change.problem} Open the book to try another recording."
        val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_headphones)
            .setContentTitle(if (change.ready) "${book.title} is ready to listen" else "${book.title} couldn't be prepared")
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending(context, ACTION_OPEN, book.id)).setAutoCancel(true).setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
        if (change.ready) notification.addAction(R.drawable.ic_play, "Listen", pending(context, ACTION_LISTEN, book.id))
        manager.notify(book.id, ID, notification.build())
    }

    fun clear(context: Context, bookId: String) { context.getSystemService(NotificationManager::class.java).cancel(bookId, ID) }

    /** Opens Narrio on the book's page, or with [ACTION_LISTEN] starts its prepared recording. */
    fun intent(context: Context, action: String, bookId: String): Intent = Intent(context, MainActivity::class.java).setAction(action)
        // Distinct data keeps each book's pending intents apart; the id itself travels as an extra.
        .setData(Uri.Builder().scheme("narrio").authority("book").appendPath(bookId).build())
        .putExtra(EXTRA_BOOK, bookId).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private fun pending(context: Context, action: String, bookId: String) =
        PendingIntent.getActivity(context, 0, intent(context, action, bookId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}
