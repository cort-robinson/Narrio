package app.narrio

import android.os.Bundle
import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.narrio.ui.NarrioApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { NarrioApp(this) }
        openUpdates(intent)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openUpdates(intent)
    }
    private fun openUpdates(intent: Intent?) {
        if (intent?.getBooleanExtra("showUpdates", false) == true) {
            ViewModelProvider(this)[app.narrio.ui.NarrioViewModel::class.java].navigate(2)
            intent.removeExtra("showUpdates")
        }
    }
}
