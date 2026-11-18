package com.rullino.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
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
        // Barre di sistema sempre scure con icone chiare: con lo stile automatico, sui telefoni in tema
        // chiaro (es. RAZR) la barra di navigazione diventava una striscia chiara con i tasti invisibili.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.argb(0xF2, 0x0D, 0x0C, 0x0C)),
        )
        // Si nasconde solo la barra di stato; la barra di navigazione resta, così da ogni telefono
        // (anche i pieghevoli come il RAZR, o con i tre tasti) si può sempre tornare indietro o uscire.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            show(WindowInsetsCompat.Type.navigationBars())
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
