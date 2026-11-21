package dev.shelfwatch.playback

import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dev.shelfwatch.BuildConfig
import dev.shelfwatch.Graph
import dev.shelfwatch.R
import dev.shelfwatch.str
import dev.shelfwatch.data.ApiException
import dev.shelfwatch.data.Prefs
import dev.shelfwatch.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Wiedergabe als Foreground-Service (Typ mediaPlayback):
 * - Wakelock + WLAN-Lock während der Wiedergabe (C.WAKE_MODE_NETWORK)
 * - großer Puffer für Streaming über den Bluetooth-Proxy
 * - automatische Wiederholung bei Netzwerkfehlern
 * - läuft weiter, wenn die App geschlossen/weggewischt wird
 * - synchronisiert den Fortschritt alle 15 s mit Audiobookshelf (offline: lokal + später)
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    companion object {
        const val CMD_PLAY_ITEM = "dev.shelfwatch.PLAY_ITEM"
        const val CMD_STOP = "dev.shelfwatch.STOP"
        const val ARG_ITEM_ID = "itemId"
        private const val SYNC_INTERVAL_MS = 15_000L
        private const val MAX_RETRIES = 40
    }

    /** Laufendes Hörbuch inkl. Server-Session und gemessener Hörzeit. */
    private class ActiveBook(val item: PlayableItem) {
        var sessionId: String? = null
        private var listenedMs = 0L
        private var playingSince = 0L

        fun startClock() {
            if (playingSince == 0L) playingSince = SystemClock.elapsedRealtime()
        }

        fun stopClock() {
            if (playingSince != 0L) {
                listenedMs += SystemClock.elapsedRealtime() - playingSince
                playingSince = 0L
            }
        }

        fun takeListenedSec(): Double {
            var ms = listenedMs
            if (playingSince != 0L) {
                val now = SystemClock.elapsedRealtime()
                ms += now - playingSince
                playingSince = now
            }
            listenedMs = 0L
            return ms / 1000.0
        }
    }

    private lateinit var player: ExoPlayer
    private lateinit var mediaSourceFactory: MediaSource.Factory
    private var mediaSession: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var active: ActiveBook? = null
    private var syncJob: Job? = null
    private var loadJob: Job? = null
    private var retryCount = 0
    private val syncMutex = Mutex()
    private lateinit var retryWakeLock: PowerManager.WakeLock

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.K_PAUSE_ON_DISCONNECT) player.setHandleAudioBecomingNoisy(Graph.prefs.pauseOnDisconnect)
    }

    override fun onCreate() {
        super.onCreate()
        val prefs = Graph.prefs
        val httpFactory = OkHttpDataSource.Factory(Graph.http)
            .setUserAgent("ShelfWatch/${BuildConfig.VERSION_NAME}")
        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)
        mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(10))

        val loadControl = DefaultLoadControl.Builder()
            // min 60 s, max 10 min Puffer – überbrückt Aussetzer der Bluetooth-/WLAN-Verbindung
            .setBufferDurationsMs(60_000, 10 * 60_000, 2_500, 5_000)
            .setTargetBufferBytes(32 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(prefs.pauseOnDisconnect)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(prefs.skipBackSec * 1000L)
            .setSeekForwardIncrementMs(prefs.skipForwardSec * 1000L)
            .build()
        player.playbackParameters = PlaybackParameters(prefs.speed)
        player.addListener(playerListener)

        val sessionActivity = PendingIntent.getActivity(
            this, 0, MainActivity.playerIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val bitmapLoader = CacheBitmapLoader(
            DataSourceBitmapLoader(
                MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor()),
                dataSourceFactory,
            )
        )
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(sessionCallback)
            .setSessionActivity(sessionActivity)
            .setBitmapLoader(bitmapLoader)
            .build()
        setMediaNotificationProvider(WearMediaNotificationProvider(this))

        retryWakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ShelfWatch:playbackRetry")
            .apply { setReferenceCounted(false) }
        prefs.register(prefListener)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /** App weggewischt: Wiedergabe läuft weiter, solange etwas abgespielt wird. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        syncNow(closeSession = true)
        Graph.prefs.unregister(prefListener)
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        scope.cancel()
        Graph.nowPlaying.value = null
        super.onDestroy()
    }

    // --- Session-Callback (Befehle aus der UI) --------------------------------

    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_PLAY_ITEM, Bundle.EMPTY))
                .add(SessionCommand(CMD_STOP, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.accept(
                commands,
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS,
            )
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CMD_PLAY_ITEM -> args.getString(ARG_ITEM_ID)?.let { playItem(it) }
                CMD_STOP -> stopAndClear()
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    // --- Laden & Starten ------------------------------------------------------

    private fun playItem(itemId: String) {
        val current = active
        if (current != null && current.item.itemId == itemId && player.playbackState != Player.STATE_IDLE) {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
            return
        }
        loadJob?.cancel()
        if (current != null) {
            syncNow(closeSession = true)
            active = null
        }
        Graph.playbackError.value = null
        Graph.playbackLoading.value = true
        loadJob = scope.launch {
            try {
                val item = withContext(Dispatchers.IO) { resolvePlayable(itemId) }
                val startSec = withContext(Dispatchers.IO) { resolveStartPosition(item) }
                val book = ActiveBook(item)
                active = book
                retryCount = 0
                Graph.nowPlaying.value = item
                player.setHandleAudioBecomingNoisy(Graph.prefs.pauseOnDisconnect)
                player.setMediaSource(buildSource(item), (startSec * 1000).toLong())
                player.prepare()
                player.play()
                // Server-Session für Statistik & Live-Sync öffnen (auch bei lokalen Dateien)
                Graph.appScope.launch {
                    val sid = runCatching { Graph.api.startSession(itemId).id }.getOrNull()
                    withContext(Dispatchers.Main) { if (active === book) book.sessionId = sid }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Graph.playbackError.value = e.message ?: str(R.string.err_loading)
            } finally {
                Graph.playbackLoading.value = false
            }
        }
    }

    private suspend fun resolvePlayable(itemId: String): PlayableItem {
        Graph.downloads.get(itemId)?.let { return Graph.downloads.toPlayable(it) }
        val li = try {
            Graph.api.item(itemId)
        } catch (e: IOException) {
            throw IOException(str(R.string.err_offline_not_downloaded, e.message.orEmpty()))
        }
        val media = li.media ?: throw IOException(str(R.string.err_no_media))
        val files = media.audioFiles.filter { !it.exclude }.sortedBy { it.index }
        if (files.isEmpty()) throw IOException(str(R.string.err_no_audio))
        return PlayableItem(
            itemId = itemId,
            title = media.metadata.title ?: str(R.string.unknown),
            author = media.metadata.authorName.orEmpty(),
            duration = media.duration.takeIf { it > 0 } ?: files.sumOf { it.duration },
            tracks = files.map { PlayTrack(Graph.api.fileUrl(itemId, it.ino), it.duration) },
            chapters = media.chapters.map { Chapter(it.start, it.end, it.title) },
            coverUri = Graph.api.coverUrl(itemId, 400),
            isLocal = false,
        )
    }

    /** Neuerer Stand gewinnt: lokaler (offline gehörter) oder Server-Fortschritt. */
    private suspend fun resolveStartPosition(item: PlayableItem): Double {
        val local = Graph.progress.get(item.itemId)
        val server = withTimeoutOrNull(8_000) { runCatching { Graph.api.progress(item.itemId) }.getOrNull() }
        val useLocal = local != null && (server == null || local.lastUpdate >= server.lastUpdate)
        if (!useLocal && server != null) Graph.progress.adoptServer(server)
        val (pos, finished) = when {
            useLocal -> local!!.currentTime to local.isFinished
            server != null -> server.currentTime to server.isFinished
            else -> 0.0 to false
        }
        return if (finished || pos >= item.duration - 5) 0.0 else pos.coerceAtLeast(0.0)
    }

    /** Alle Audiodateien als eine durchgehende Zeitleiste → Position = ABS-currentTime. */
    private fun buildSource(item: PlayableItem): MediaSource {
        val metadata = MediaMetadata.Builder()
            .setTitle(item.title)
            .setArtist(item.author)
            .setAlbumTitle(item.title)
            .setArtworkUri(item.coverUri?.let(Uri::parse))
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
            .build()
        val main = MediaItem.Builder().setMediaId(item.itemId).setMediaMetadata(metadata).build()
        val builder = ConcatenatingMediaSource2.Builder()
            .setMediaSourceFactory(mediaSourceFactory)
            .setMediaItem(main)
        item.tracks.forEach { t ->
            builder.add(MediaItem.fromUri(t.uri), (t.duration * 1000).toLong().coerceAtLeast(1L))
        }
        return builder.build()
    }

    private fun stopAndClear() {
        loadJob?.cancel()
        syncNow(closeSession = true)
        active = null
        Graph.nowPlaying.value = null
        player.stop()
        player.clearMediaItems()
    }

    // --- Player-Events --------------------------------------------------------

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            Graph.isPlaying.value = isPlaying
            val a = active ?: return
            if (isPlaying) {
                a.startClock()
                retryCount = 0
                Graph.playbackError.value = null
                startSyncLoop()
            } else {
                a.stopClock()
                syncJob?.cancel()
                // nur bei echter Pause synchronisieren, nicht bei kurzem Puffern
                if (!player.playWhenReady) syncNow()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                val a = active ?: return
                syncNow(closeSession = true)
                a.sessionId = null
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) saveLocal()
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            Graph.prefs.speed = playbackParameters.speed
        }

        override fun onPlayerError(error: PlaybackException) {
            scheduleRetry(error)
        }
    }

    /** Netzwerkfehler o. Ä.: Position sichern und automatisch erneut vorbereiten. */
    private fun scheduleRetry(error: PlaybackException) {
        if (active == null) return
        saveLocal()
        if (retryCount >= MAX_RETRIES) {
            Graph.playbackError.value = str(R.string.err_playback_failed, error.errorCodeName)
            return
        }
        retryCount++
        Graph.playbackError.value = str(R.string.err_retrying, retryCount)
        retryWakeLock.acquire(90_000)
        scope.launch {
            delay((retryCount * 3_000L).coerceAtMost(30_000L))
            if (player.playerError != null && active != null) {
                player.prepare() // behält Position und playWhenReady
            }
            if (retryWakeLock.isHeld) retryWakeLock.release()
        }
    }

    // --- Synchronisation ------------------------------------------------------

    private fun startSyncLoop() {
        syncJob?.cancel()
        syncJob = scope.launch {
            while (isActive) {
                delay(SYNC_INTERVAL_MS)
                syncNow()
            }
        }
    }

    private fun currentPositionSec(a: ActiveBook): Double =
        (player.currentPosition / 1000.0).coerceIn(0.0, a.item.duration)

    private fun saveLocal() {
        val a = active ?: return
        Graph.progress.put(a.item.itemId, currentPositionSec(a), a.item.duration, player.playbackState == Player.STATE_ENDED)
    }

    /**
     * Speichert lokal und überträgt zum Server:
     * offene Session → /api/session/:id/sync, sonst PATCH /api/me/progress/:id.
     * Fehlschläge landen in der Offline-Warteschlange (WorkManager).
     */
    private fun syncNow(closeSession: Boolean = false) {
        val a = active ?: return
        val itemId = a.item.itemId
        val duration = a.item.duration
        val finished = player.playbackState == Player.STATE_ENDED
        val pos = if (finished) duration else currentPositionSec(a)
        val listened = a.takeListenedSec()
        val stamp = Graph.progress.put(itemId, pos, duration, finished)
        val sid = a.sessionId
        if (closeSession) a.sessionId = null

        Graph.appScope.launch {
            syncMutex.withLock {
                try {
                    var sessionOk = false
                    if (sid != null && !finished) {
                        try {
                            Graph.api.syncSession(sid, pos, listened, duration)
                            sessionOk = true
                        } catch (e: ApiException) {
                            if (e.code != 404) throw e
                            // Session auf dem Server unbekannt (z. B. Neustart) → direkter Fortschritt
                            withContext(Dispatchers.Main) { if (a.sessionId == sid) a.sessionId = null }
                        }
                    }
                    if (!sessionOk) Graph.api.updateProgress(itemId, pos, duration, finished)
                    if (closeSession && sid != null) {
                        runCatching { Graph.api.closeSession(sid, pos, 0.0, duration) }
                    }
                    Graph.progress.markClean(itemId, stamp)
                    Graph.sync.reportSuccess()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Graph.sync.reportFailure(e)
                    Graph.sync.schedule()
                }
            }
        }
    }
}
