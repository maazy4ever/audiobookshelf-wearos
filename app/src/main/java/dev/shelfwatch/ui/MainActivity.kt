package dev.shelfwatch.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.wear.ambient.AmbientLifecycleObserver
import dev.shelfwatch.Graph
import dev.shelfwatch.download.DownloadService

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OPEN_PLAYER = "open_player"

        fun playerIntent(ctx: Context): Intent =
            Intent(ctx, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_PLAYER, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    private val vm: MainViewModel by viewModels()

    /**
     * Always-on: Die App bleibt beim Senken des Handgelenks im Vordergrund (gedimmte Ansicht),
     * statt zum Zifferblatt zurückzukehren. Die Wiedergabe selbst läuft ohnehin im Service weiter.
     */
    private val ambientObserver by lazy {
        AmbientLifecycleObserver(this, object : AmbientLifecycleObserver.AmbientLifecycleCallback {
            override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
                vm.ambient.value = true
            }

            override fun onExitAmbient() {
                vm.ambient.value = false
            }
        })
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Graph.prefs.alwaysOn) lifecycle.addObserver(ambientObserver)
        handleIntent(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            ShelfWatchUi(vm, onAlwaysOnChanged = { recreate() })
        }
    }

    override fun onResume() {
        super.onResume()
        // Unterbrochene Downloads (Prozess-Kill, Neustart, Zeitlimit) automatisch fortsetzen
        DownloadService.resumePending(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true) {
            vm.openPlayerRequest.value = true
        }
    }
}
