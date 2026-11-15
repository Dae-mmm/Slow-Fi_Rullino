package com.rullino.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.rullino.app.ui.AppRoot
import com.rullino.app.ui.RullinoTheme

class MainActivity : ComponentActivity() {
    /** File .emulsion aperto da fuori (file manager, chat…). */
    private val pendingImport = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Schermo pieno: niente barre di sistema, come il dorso di una macchina.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (savedInstanceState == null) pendingImport.value = importUri(intent)
        setContent {
            RullinoTheme {
                AppRoot(
                    importUri = pendingImport.value,
                    onImportHandled = { pendingImport.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        importUri(intent)?.let { pendingImport.value = it }
    }

    private fun importUri(intent: Intent?): Uri? =
        if (intent?.action == Intent.ACTION_VIEW) intent.data else null
}
