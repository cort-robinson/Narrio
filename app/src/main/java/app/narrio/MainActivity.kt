package app.narrio

import android.os.Bundle
import android.content.Intent
import android.view.KeyEvent
import androidx.lifecycle.ViewModelProvider
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentFactory
import app.narrio.ui.NarrioApp
import org.readium.r2.navigator.epub.EpubNavigatorFragment

/** A FragmentActivity so the reader can host Readium's navigator fragment inside Compose. */
class MainActivity : FragmentActivity() {
    /** Consulted before the window handles a key; the reader uses it for volume-key page turns. */
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
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openUpdates(intent)
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = keyInterceptor?.invoke(event) == true || super.dispatchKeyEvent(event)
    private fun openUpdates(intent: Intent?) {
        if (intent?.getBooleanExtra("showUpdates", false) == true) {
            ViewModelProvider(this)[app.narrio.ui.NarrioViewModel::class.java].navigate(2)
            intent.removeExtra("showUpdates")
        }
    }
}

private class RestoredReaderFactory(private val fallback: FragmentFactory) : FragmentFactory() {
    private val placeholder = EpubNavigatorFragment.createDummyFactory()
    override fun instantiate(classLoader: ClassLoader, className: String) =
        if (className == EpubNavigatorFragment::class.java.name) placeholder.instantiate(classLoader, className)
        else fallback.instantiate(classLoader, className)
}
