package dev.shelfwatch.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import dev.shelfwatch.Graph
import dev.shelfwatch.R
import dev.shelfwatch.data.ApiException
import dev.shelfwatch.data.await
import dev.shelfwatch.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class DlState(
    val status: Status,
    val progress: Float = 0f,
    val error: String? = null,
    /** true = Verbindung weg, Download wartet auf Netz und setzt dann fort */
    val waitingForNetwork: Boolean = false,
) {
    enum class Status { QUEUED, RUNNING, FAILED }
}

private class CancelledDownload : IOException("abgebrochen")

/**
 * Lädt Hörbücher als Foreground-Service herunter. Maßnahmen gegen Abbrüche:
 * - Foreground-Service (dataSync) + Ongoing Activity → System beendet den Prozess nicht
 * - Partial-Wakelock + WLAN-High-Perf-Lock → CPU und WLAN schlafen nicht ein
 * - explizit angefordertes WLAN-Netz (Wear OS schaltet WLAN sonst bei Display-aus ab)
 * - Verbindungsabbruch: wartet auf Netz und setzt per HTTP-Range fort (gibt erst nach
 *   30 min ohne jeden Fortschritt auf)
 * - persistente Warteschlange + START_STICKY → nach Prozess-Neustart geht es weiter
 */
class DownloadService : Service() {

    companion object {
        private const val ACTION_ENQUEUE = "dev.shelfwatch.download.ENQUEUE"
        private const val ACTION_RESUME = "dev.shelfwatch.download.RESUME"
        private const val EXTRA_ID = "itemId"
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 2001
        private const val NOTIF_DONE_ID = 2002
        private const val STALL_LIMIT_MS = 30 * 60_000L
        private const val WAKELOCK_TIMEOUT_MS = 12 * 60 * 60_000L

        private val _state = MutableStateFlow<Map<String, DlState>>(emptyMap())
        val state = _state.asStateFlow()
        private val cancelled: MutableSet<String> = ConcurrentHashMap.newKeySet()

        fun enqueue(ctx: Context, itemId: String) {
            cancelled.remove(itemId)
            Graph.prefs.addToDownloadQueue(itemId)
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, DownloadService::class.java).setAction(ACTION_ENQUEUE).putExtra(EXTRA_ID, itemId),
            )
        }

        /** Unterbrochene Downloads fortsetzen (z. B. nach Neustart der Uhr oder Prozess-Kill). */
        fun resumePending(ctx: Context) {
            if (Graph.prefs.downloadQueue.isEmpty()) return
            try {
                ContextCompat.startForegroundService(
                    ctx, Intent(ctx, DownloadService::class.java).setAction(ACTION_RESUME),
                )
            } catch (_: Exception) {
                // Start aus dem Hintergrund nicht erlaubt → beim nächsten Öffnen der App
            }
        }

        fun cancel(itemId: String) {
            cancelled.add(itemId)
            Graph.prefs.removeFromDownloadQueue(itemId)
            _state.update { it - itemId }
        }
    }

    private class WifiHandle(val client: OkHttpClient, val release: () -> Unit)

    private val queue = ArrayDeque<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val nm by lazy { getSystemService(NotificationManager::class.java) }
    private val cm by lazy { getSystemService(ConnectivityManager::class.java) }
    private var lastNotify = 0L

    @Volatile private var wifi: WifiHandle? = null
    @Volatile private var wifiFailures = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, notification("Download wird vorbereitet…", null),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } catch (e: Exception) {
            // z. B. System-Neustart des Service aus dem Hintergrund nicht erlaubt (Android 12+).
            // Warteschlange bleibt gespeichert und wird beim nächsten App-Start fortgesetzt.
            stopSelf()
            return START_NOT_STICKY
        }
        val id = intent?.getStringExtra(EXTRA_ID)
        if (intent?.action == ACTION_ENQUEUE && id != null) {
            add(id)
        } else {
            // RESUME oder Neustart durch das System (intent == null)
            Graph.prefs.downloadQueue.forEach { add(it) }
        }
        if (worker?.isActive != true) worker = scope.launch { runQueue() }
        return START_STICKY
    }

    private fun add(id: String) {
        synchronized(queue) {
            val running = _state.value[id]?.status == DlState.Status.RUNNING
            if (id !in queue && !running && id !in cancelled) {
                queue.addLast(id)
                _state.update { it + (id to DlState(DlState.Status.QUEUED)) }
            }
        }
    }

    /** Android 15+: dataSync-Dienste dürfen max. 6 h/Tag laufen. Sauber beenden, Queue bleibt erhalten. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        worker?.cancel()
        _state.update { m ->
            m.mapValues { (_, s) ->
                s.copy(status = DlState.Status.FAILED, error = "Zeitlimit erreicht – wird später fortgesetzt")
            }
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        dropWifi()
        releaseLocks()
        super.onDestroy()
    }

    private suspend fun runQueue() {
        acquireLocks()
        wifiFailures = 0
        try {
            while (true) {
                val id = synchronized(queue) { queue.removeFirstOrNull() } ?: break
                if (cancelled.remove(id)) continue
                acquireLocks() // Wakelock-Timeout pro Hörbuch erneuern
                downloadItem(id)
            }
        } finally {
            dropWifi()
            releaseLocks()
        }
        withContext(Dispatchers.Main) {
            if (synchronized(queue) { queue.isEmpty() }) {
                ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else {
                worker = scope.launch { runQueue() }
            }
        }
    }

    private suspend fun downloadItem(id: String) {
        val store = Graph.downloads
        val api = Graph.api
        setState(id, DlState(DlState.Status.RUNNING, 0f))
        try {
            val li = withNetworkRetry(id) { api.item(id) }
            val media = li.media ?: throw IOException("Kein Medium gefunden")
            val files = media.audioFiles.filter { !it.exclude }.sortedBy { it.index }
            if (files.isEmpty()) throw IOException("Keine Audiodateien (Podcasts werden nicht unterstützt)")
            val title = media.metadata.title ?: "Hörbuch"
            val tracks = files.map {
                LocalTrack(
                    index = it.index,
                    ino = it.ino,
                    fileName = it.metadata.filename,
                    ext = it.metadata.ext.trimStart('.').ifBlank { "audio" },
                    duration = it.duration,
                    size = it.metadata.size,
                )
            }
            val meta = DownloadedItem(
                itemId = id,
                libraryId = li.libraryId,
                title = title,
                author = media.metadata.authorName.orEmpty(),
                duration = media.duration,
                tracks = tracks,
                chapters = media.chapters.map { LocalChapter(it.start, it.end, it.title) },
            )
            store.saveMeta(meta)

            val total = tracks.sumOf { maxOf(it.size, 1L) }.toFloat()
            var done = 0L
            for (t in tracks) {
                checkCancelled(id)
                val target = store.trackFile(id, t)
                val complete = target.exists() && (t.size <= 0 || target.length() == t.size)
                if (!complete) {
                    val base = done
                    downloadWithRetry(
                        listOf(api.downloadUrl(id, t.ino), api.fileUrl(id, t.ino)), target, id,
                    ) { bytes -> reportProgress(id, title, (base + bytes) / total) }
                }
                done += maxOf(t.size, target.length())
                reportProgress(id, title, done / total, force = true)
            }
            val hasCover = runCatching {
                downloadFile(client(), listOf(api.coverUrl(id, 400)), store.coverFile(id), id) {}
                true
            }.getOrDefault(false)
            store.saveMeta(meta.copy(hasCover = hasCover, complete = true, downloadedAt = System.currentTimeMillis()))
            Graph.prefs.removeFromDownloadQueue(id)
            _state.update { it - id }
            nm.notify(
                NOTIF_DONE_ID,
                NotificationCompat.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_book)
                    .setContentTitle("Download fertig")
                    .setContentText(title)
                    .setAutoCancel(true)
                    .build(),
            )
        } catch (e: CancelledDownload) {
            store.delete(id)
            Graph.prefs.removeFromDownloadQueue(id)
            _state.update { it - id }
        } catch (e: CancellationException) {
            throw e // Service wird beendet → bleibt in der persistenten Queue
        } catch (e: Exception) {
            Graph.prefs.removeFromDownloadQueue(id)
            setState(id, DlState(DlState.Status.FAILED, error = e.message ?: e.javaClass.simpleName))
        }
    }

    private fun checkCancelled(id: String) {
        if (id in cancelled) throw CancelledDownload()
    }

    private fun isFatal(e: Exception) =
        e is ApiException && e.code in 400..499 && e.code != 408 && e.code != 429

    /** Kleine API-Aufrufe: bei Netzproblemen warten und wiederholen. */
    private suspend fun <T> withNetworkRetry(id: String, block: suspend () -> T): T {
        val start = SystemClock.elapsedRealtime()
        var attempt = 0
        while (true) {
            checkCancelled(id)
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is CancelledDownload || isFatal(e) || e !is IOException) throw e
                if (SystemClock.elapsedRealtime() - start > STALL_LIMIT_MS) throw e
                attempt++
                waitForNetwork(id, attempt)
            }
        }
    }

    /**
     * Datei-Download, der nicht aufgibt: Bei Abbruch wird auf Netz gewartet und per Range fortgesetzt.
     * Erst wenn 30 min lang überhaupt kein Byte mehr ankommt, gilt der Download als fehlgeschlagen.
     */
    private suspend fun downloadWithRetry(
        urls: List<String>,
        target: File,
        id: String,
        onBytes: (Long) -> Unit,
    ) {
        var lastProgress = SystemClock.elapsedRealtime()
        var attempt = 0
        while (true) {
            checkCancelled(id)
            val client = client()
            val viaWifi = client !== Graph.http
            try {
                downloadFile(client, urls, target, id) { bytes ->
                    lastProgress = SystemClock.elapsedRealtime()
                    attempt = 0
                    onBytes(bytes)
                }
                if (viaWifi) wifiFailures = 0
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is CancelledDownload || isFatal(e) || e !is IOException) throw e
                if (SystemClock.elapsedRealtime() - lastProgress > STALL_LIMIT_MS) throw e
                if (viaWifi) {
                    // WLAN-Netz verloren oder Server darüber nicht erreichbar → neu anfordern;
                    // nach 3 Fehlschlägen ohne Fortschritt dauerhaft das Standardnetz nutzen
                    wifiFailures++
                    dropWifi()
                }
                attempt++
                waitForNetwork(id, attempt)
            }
        }
    }

    private suspend fun waitForNetwork(id: String, attempt: Int) {
        _state.update { m -> m[id]?.let { m + (id to it.copy(waitingForNetwork = true)) } ?: m }
        nm.notify(NOTIF_ID, notification("Warte auf Verbindung…", null))
        awaitInternet(5 * 60_000L)
        delay((2_000L * attempt).coerceAtMost(30_000L))
        _state.update { m -> m[id]?.let { m + (id to it.copy(waitingForNetwork = false)) } ?: m }
    }

    private suspend fun awaitInternet(timeoutMs: Long): Boolean {
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) return true
        val available = CompletableDeferred<Unit>()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                available.complete(Unit)
            }
        }
        return try {
            cm.registerDefaultNetworkCallback(cb)
            withTimeoutOrNull(timeoutMs) { available.await() } != null
        } catch (e: Exception) {
            false
        } finally {
            runCatching { cm.unregisterNetworkCallback(cb) }
        }
    }

    /** Liefert einen HTTP-Client über WLAN (falls möglich), sonst über das Standardnetz. */
    private suspend fun client(): OkHttpClient {
        if (!Graph.prefs.preferWifiDownloads || wifiFailures >= 3) return Graph.http
        wifi?.let { return it.client }
        val handle = requestWifi() ?: return Graph.http
        wifi = handle
        return handle.client
    }

    /**
     * Wear OS leitet Traffic standardmäßig über Bluetooth zum Handy und schaltet WLAN bei
     * Display-aus ab. Ein explizit angefordertes Netz bleibt aktiv, solange die Anfrage besteht.
     */
    private suspend fun requestWifi(): WifiHandle? {
        val deferred = CompletableDeferred<Network>()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                deferred.complete(network)
            }

            override fun onLost(network: Network) {
                if (wifi != null) dropWifi()
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm.requestNetwork(request, cb)
        } catch (e: Exception) {
            return null
        }
        val release = { runCatching { cm.unregisterNetworkCallback(cb) }; Unit }
        val network = withTimeoutOrNull(15_000) { deferred.await() }
        if (network == null) {
            release()
            wifiFailures++
            return null
        }
        val client = Graph.http.newBuilder()
            .socketFactory(network.socketFactory)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = network.getAllByName(hostname).toList()
            })
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        return WifiHandle(client, release)
    }

    private fun dropWifi() {
        val w = wifi
        wifi = null
        w?.release?.invoke()
    }

    /** Lädt eine Datei mit Resume-Unterstützung (.part + Range-Header). */
    private suspend fun downloadFile(
        client: OkHttpClient,
        urls: List<String>,
        target: File,
        id: String,
        onBytes: (Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val part = File(target.path + ".part")
        var lastCode = 0
        for (url in urls) {
            val existing = if (part.exists()) part.length() else 0L
            val req = Request.Builder().url(url)
                .apply { if (existing > 0) header("Range", "bytes=$existing-") }
                .build()
            client.newCall(req).await().use { r ->
                if (r.code == 403 || r.code == 404) {
                    lastCode = r.code
                    return@use // nächste URL versuchen
                }
                if (r.code == 416 && existing > 0) {
                    if (target.exists()) target.delete()
                    part.renameTo(target)
                    return@withContext
                }
                if (!r.isSuccessful) throw ApiException(r.code, "Download")
                val append = r.code == 206 && existing > 0
                var written = if (append) existing else 0L
                FileOutputStream(part, append).use { out ->
                    r.body!!.byteStream().use { input ->
                        val buf = ByteArray(128 * 1024)
                        while (true) {
                            ensureActive()
                            checkCancelled(id)
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            written += n
                            onBytes(written)
                        }
                    }
                }
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) throw IOException("Datei konnte nicht gespeichert werden")
                return@withContext
            }
        }
        throw ApiException(lastCode, "Kein Zugriff auf Datei")
    }

    private fun reportProgress(id: String, title: String, fraction: Float, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotify < 2000) return
        lastNotify = now
        val f = fraction.coerceIn(0f, 1f)
        _state.update { if (it.containsKey(id)) it + (id to DlState(DlState.Status.RUNNING, f)) else it }
        nm.notify(NOTIF_ID, notification(title, (f * 100).toInt()))
    }

    private fun setState(id: String, s: DlState) {
        _state.update { it + (id to s) }
    }

    private fun notification(text: String, progress: Int?): Notification {
        val pi = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val label = if (progress != null) "$text · $progress %" else text
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Download")
            .setContentText(label)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(pi)
            .apply { if (progress != null) setProgress(100, progress, false) }
        // Wear OS: als laufende Aktivität kennzeichnen (Symbol auf dem Zifferblatt)
        OngoingActivity.Builder(this, NOTIF_ID, builder)
            .setStaticIcon(R.drawable.ic_download)
            .setTouchIntent(pi)
            .setStatus(Status.Builder().addTemplate("#t#").addPart("t", Status.TextPart(label)).build())
            .build()
            .apply(this)
        return builder.build()
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val wl = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ShelfWatch:download")
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        wl.acquire(WAKELOCK_TIMEOUT_MS) // erneuert den Timeout
        if (wifiLock == null) {
            wifiLock = getSystemService(WifiManager::class.java)
                ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ShelfWatch:download")
                ?.apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }
}
