package dev.shelfwatch

import android.app.Application
import dev.shelfwatch.data.AbsApi
import dev.shelfwatch.data.Prefs
import dev.shelfwatch.data.ProgressStore
import dev.shelfwatch.data.SyncManager
import dev.shelfwatch.data.buildHttpClient
import dev.shelfwatch.download.DownloadStore
import dev.shelfwatch.playback.PlayableItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/** Lokalisierter Text aus res/values(-de)/strings.xml – nutzbar in UI und Diensten. */
fun str(@androidx.annotation.StringRes id: Int, vararg args: Any): String = Graph.app.getString(id, *args)

/** Einfacher Service-Locator – alles läuft in einem Prozess. */
object Graph {
    lateinit var app: Application
        private set
    lateinit var prefs: Prefs
        private set
    lateinit var http: OkHttpClient
        private set
    lateinit var api: AbsApi
        private set
    lateinit var downloads: DownloadStore
        private set
    lateinit var progress: ProgressStore
        private set
    lateinit var sync: SyncManager
        private set

    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = true
    }

    /** Scope für Arbeit, die einen Screen/Service überleben soll (z. B. finaler Sync). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val loggedIn = MutableStateFlow(false)
    val authExpired = MutableStateFlow(false)
    val nowPlaying = MutableStateFlow<PlayableItem?>(null)
    val playbackError = MutableStateFlow<String?>(null)
    val playbackLoading = MutableStateFlow(false)
    val isPlaying = MutableStateFlow(false)

    fun init(application: Application) {
        app = application
        prefs = Prefs(application)
        http = buildHttpClient(prefs, apiProvider = { api }, onAuthExpired = { authExpired.value = true })
        api = AbsApi(prefs, http, json)
        downloads = DownloadStore(application, json)
        progress = ProgressStore(application, json)
        sync = SyncManager(application, api, progress)
        loggedIn.value = prefs.isLoggedIn
    }
}
