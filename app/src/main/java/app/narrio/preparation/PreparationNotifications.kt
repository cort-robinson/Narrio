package app.narrio.preparation

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.narrio.MainActivity
import app.narrio.NarrioApplication
import app.narrio.R
import app.narrio.data.PreparationChange

/** A notification's request: open [bookId]'s page, or [listen] to the preparation [generation] it announced. */
data class PreparationRequest(val bookId: String, val generation: String, val listen: Boolean)

/**
 * Says when a book TorBox was getting ready can be listened to, or couldn't be prepared. Nothing is shown while it
 * prepares. Without notification permission these are skipped; the shelf still shows the change.
 */
object PreparationNotifications {
    const val CHANNEL = "torbox-preparation"
    internal const val ACTION_OPEN = "app.narrio.action.OPEN_PREPARED_BOOK"
    internal const val ACTION_LISTEN = "app.narrio.action.LISTEN_TO_PREPARED_BOOK"
    internal const val EXTRA_BOOK = "book"
    internal const val EXTRA_GENERATION = "generation"
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
            .setContentIntent(pending(context, ACTION_OPEN, book.id, change.generation)).setAutoCancel(true).setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
        if (change.ready) notification.addAction(R.drawable.ic_play, "Listen", pending(context, ACTION_LISTEN, book.id, change.generation))
        manager.notify(book.id, ID, notification.build())
    }

    fun clear(context: Context, bookId: String) { context.getSystemService(NotificationManager::class.java).cancel(bookId, ID) }

    /** Only this app can send these: they go to a non-exported activity, never to the exported launcher. */
    private fun pending(context: Context, action: String, bookId: String, generation: String) = PendingIntent.getActivity(context, 0,
        Intent(context, PreparationActionActivity::class.java).setAction(action)
            // Distinct data keeps each book's pending intents apart; the ids travel as extras.
            .setData(Uri.Builder().scheme("narrio").authority("preparation").appendPath(bookId).appendPath(generation).build())
            .putExtra(EXTRA_BOOK, bookId).putExtra(EXTRA_GENERATION, generation),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

/**
 * Receives a preparation notification's tap or Listen, hands the request to Narrio in memory, and brings Narrio
 * forward with an ordinary launch. Narrio still checks the request's generation before playing anything.
 */
class PreparationActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bookId = intent.getStringExtra(PreparationNotifications.EXTRA_BOOK)
        // Reopening from Recents replays the original intent; it mustn't act again.
        val replayed = savedInstanceState != null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (bookId != null && !replayed) {
            val listen = intent.action == PreparationNotifications.ACTION_LISTEN
            if (listen) PreparationNotifications.clear(this, bookId)
            (application as NarrioApplication).graph.preparationRequests.value =
                PreparationRequest(bookId, intent.getStringExtra(PreparationNotifications.EXTRA_GENERATION).orEmpty(), listen)
        }
        // Narrio's own task, reusing its activity when it's already open.
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
