package app.filemanagerx

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import app.filemanagerx.ui.AppRoot
import app.filemanagerx.ui.IncomingFiles
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Archive handed to us by another app; consumed once by the UI. */
    private var incomingArchive by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askForNotifications()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            AppRoot(
                container = appContainer,
                incomingArchive = incomingArchive,
                onIncomingConsumed = { incomingArchive = null },
            )
        }
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* the service works either way */ }

    /** Needed only to show the progress notification; denying it never blocks any feature. */
    private fun askForNotifications() {
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return
        lifecycleScope.launch { incomingArchive = IncomingFiles.resolve(applicationContext, uri) }
    }
}
