package app.narrio

import android.os.Bundle
import android.content.Intent
import android.view.KeyEvent
import androidx.lifecycle.ViewModelProvider
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentFactory
import app.narrio.preparation.PreparationNotifications
import app.narrio.ui.NarrioApp
import org.readium.r2.navigator.epub.EpubNavigatorFragment

/** A FragmentActivity so the reader can host Readium's navigator fragment inside Compose. */
class MainActivity : FragmentActivity() {
    /** Consulted for keys no view handled; the reader uses it for volume-key page turns. */
    var keyInterceptor: ((KeyEvent) -> Boolean)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // After recreation Android restores fragments before Compose runs. A restored navigator has no book, so
        // restore a placeholder and remove it at once; the reader adds a fresh navigator at its saved place.
        supportFragmentManager.fragmentFactory = RestoredReaderFactory(supportFragmentManager.fragmentFactory)
        super.onCreate(savedInstanceState)
        supportFragmentManager.fragments.filterIsInstance<EpubNavigatorFragment>().takeIf { it.isNotEmpty() }?.let { restored ->
            supportFragmentManager.beginTransaction().apply { restored.forEach(::remove) }.commitNowAllowingStateLoss()
        }
        enableEdgeToEdge()
        setContent { NarrioApp(this) }
        openUpdates(intent)
        // A recreated activity keeps its launch intent; only a fresh launch acts on it.
        if (savedInstanceState == null) openPreparedBook(intent)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openUpdates(intent)
        openPreparedBook(intent)
    }
    // Volume keys reach the activity because no view consumes them; the reader can claim them before the system does.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = keyInterceptor?.invoke(event) == true || super.onKeyDown(keyCode, event)
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = keyInterceptor?.invoke(event) == true || super.onKeyUp(keyCode, event)
    private fun openUpdates(intent: Intent?) {
        if (intent?.getBooleanExtra("showUpdates", false) == true) {
            ViewModelProvider(this)[app.narrio.ui.NarrioViewModel::class.java].navigate(2)
            intent.removeExtra("showUpdates")
        }
    }
    /** A TorBox preparation notification: its tap opens the book, its Listen plays the prepared recording. */
    private fun openPreparedBook(intent: Intent?) {
        val bookId = intent?.getStringExtra(PreparationNotifications.EXTRA_BOOK) ?: return
        // Reopening from Recents replays the task's original intent; it mustn't start the book again.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val vm = ViewModelProvider(this)[app.narrio.ui.NarrioViewModel::class.java]
        when (intent.action) {
            PreparationNotifications.ACTION_LISTEN -> { PreparationNotifications.clear(this, bookId); vm.listenPrepared(bookId) }
            PreparationNotifications.ACTION_OPEN -> vm.openBook(bookId)
            else -> return
        }
        intent.removeExtra(PreparationNotifications.EXTRA_BOOK)
    }
}

private class RestoredReaderFactory(private val fallback: FragmentFactory) : FragmentFactory() {
    private val placeholder = EpubNavigatorFragment.createDummyFactory()
    override fun instantiate(classLoader: ClassLoader, className: String) =
        if (className == EpubNavigatorFragment::class.java.name) placeholder.instantiate(classLoader, className)
        else fallback.instantiate(classLoader, className)
}
