package dev.shelfwatch.companion

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
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import dev.shelfwatch.Graph
import dev.shelfwatch.R
import dev.shelfwatch.download.DownloadService
import dev.shelfwatch.ui.MainActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket

@Serializable
private data class DiscoveryReply(val app: String, val device: String, val ip: String, val port: Int)

/**
 * „PC-Verbindung“: hält WLAN aktiv und stellt die Companion-API im Heimnetz bereit.
 * - TCP 8765: HTTP/JSON-API (geschützt mit 6-stelligem Code)
 * - UDP 8766: Antwort auf "SHELFWATCH_DISCOVER" für die automatische Suche am PC
 * Beendet sich nach 30 min ohne Anfrage, sofern kein Download mehr läuft.
 */
class CompanionService : Service() {

    companion object {
        const val TCP_PORT = 8765
        const val UDP_PORT = 8766
        private const val CHANNEL = "companion"
        private const val NOTIF_ID = 3001
        private const val IDLE_TIMEOUT_MS = 30 * 60_000L

        data class Info(
            val running: Boolean = false,
            val starting: Boolean = false,
            val address: String? = null,
            val error: String? = null,
            val lastClient: String? = null,
            val lastClientAt: Long = 0,
        )

        private val _info = MutableStateFlow(Info())
        val info = _info.asStateFlow()

        fun start(ctx: Context) {
            _info.value = Info(starting = true)
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, CompanionService::class.java))
            } catch (e: Exception) {
                _info.value = Info(error = "Start nicht möglich: ${e.message}")
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, CompanionService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var udpSocket: DatagramSocket? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var lastActivity = SystemClock.elapsedRealtime()
    private val cm by lazy { getSystemService(ConnectivityManager::class.java) }
    private val api by lazy {
        CompanionApi(this) { ip ->
            lastActivity = SystemClock.elapsedRealtime()
            _info.update { it.copy(lastClient = ip, lastClientAt = System.currentTimeMillis()) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "PC-Verbindung", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, notification("Wird gestartet…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (e: Exception) {
            _info.value = Info(error = "Start nicht möglich: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            scope.launch { runServer() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { serverSocket?.close() }
        runCatching { udpSocket?.close() }
        wifiCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        multicastLock?.let { if (it.isHeld) it.release() }
        _info.update { Info(error = it.error) }
        super.onDestroy()
    }

    private fun fail(msg: String) {
        _info.value = Info(error = msg)
        stopSelf()
    }

    private suspend fun runServer() {
        val wifi = requestWifi()
        val network = wifi ?: cm.activeNetwork
        val ip = network?.let { n ->
            cm.getLinkProperties(n)?.linkAddresses
                ?.map { it.address }
                ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress
        }
        if (ip == null) {
            fail("Kein WLAN – bitte auf der Uhr ein WLAN einrichten")
            return
        }
        val ss = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(TCP_PORT))
            }
        } catch (e: IOException) {
            fail("Port $TCP_PORT nicht verfügbar: ${e.message}")
            return
        }
        serverSocket = ss
        lastActivity = SystemClock.elapsedRealtime()
        _info.value = Info(running = true, address = "$ip:$TCP_PORT")
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification("$ip · Code ${Graph.prefs.companionPin}"))

        scope.launch { udpLoop(wifi, ip) }
        scope.launch { idleWatcher() }

        while (currentCoroutineContext().isActive) {
            val client = try {
                ss.accept()
            } catch (e: IOException) {
                break
            }
            scope.launch { runCatching { api.handle(client) } }
        }
    }

    /** WLAN explizit anfordern – Wear OS schaltet es sonst bei Display-aus ab. */
    private suspend fun requestWifi(): Network? {
        val deferred = CompletableDeferred<Network>()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                deferred.complete(network)
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        return try {
            cm.requestNetwork(request, cb)
            wifiCallback = cb
            withTimeoutOrNull(20_000) { deferred.await() }
        } catch (e: Exception) {
            null
        }
    }

    private fun udpLoop(network: Network?, ip: String) {
        val ds = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(UDP_PORT))
            }
        } catch (e: IOException) {
            return // Suche nicht verfügbar – manuelle IP-Eingabe funktioniert trotzdem
        }
        runCatching { network?.bindSocket(ds) }
        udpSocket = ds
        multicastLock = getSystemService(WifiManager::class.java)
            ?.createMulticastLock("ShelfWatch:discovery")
            ?.apply { setReferenceCounted(false); acquire() }
        val reply = Graph.json.encodeToString(DiscoveryReply("ShelfWatch", api.deviceName(), ip, TCP_PORT))
            .toByteArray(Charsets.UTF_8)
        val buf = ByteArray(512)
        while (true) {
            val p = DatagramPacket(buf, buf.size)
            try {
                ds.receive(p)
            } catch (e: IOException) {
                break
            }
            if (String(p.data, 0, p.length, Charsets.UTF_8).trim() == "SHELFWATCH_DISCOVER") {
                runCatching { ds.send(DatagramPacket(reply, reply.size, p.address, p.port)) }
            }
        }
    }

    private suspend fun idleWatcher() {
        while (currentCoroutineContext().isActive) {
            delay(60_000)
            val idle = SystemClock.elapsedRealtime() - lastActivity > IDLE_TIMEOUT_MS
            if (idle && DownloadService.state.value.isEmpty()) {
                _info.value = Info()
                stopSelf()
                return
            }
        }
    }

    private fun notification(text: String): android.app.Notification {
        val pi = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_computer)
            .setContentTitle("PC-Verbindung aktiv")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pi)
        OngoingActivity.Builder(this, NOTIF_ID, builder)
            .setStaticIcon(R.drawable.ic_computer)
            .setTouchIntent(pi)
            .setStatus(Status.Builder().addTemplate("#t#").addPart("t", Status.TextPart("PC")).build())
            .build()
            .apply(this)
        return builder.build()
    }
}
