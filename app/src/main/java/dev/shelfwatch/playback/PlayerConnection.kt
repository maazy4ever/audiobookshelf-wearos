package dev.shelfwatch.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** UI-seitige Verbindung zum PlaybackService über einen Media3-MediaController. */
class PlayerConnection(private val context: Context, private val tickMs: () -> Long) {

    data class State(
        val connected: Boolean = false,
        val isPlaying: Boolean = false,
        val playWhenReady: Boolean = false,
        val buffering: Boolean = false,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val speed: Float = 1f,
        val mediaId: String? = null,
    )

    private val scope = MainScope()
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = update()
    }

    fun connect() {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val f = MediaController.Builder(context, token).buildAsync()
        future = f
        scope.launch {
            val c = try {
                f.await()
            } catch (e: Exception) {
                future = null
                return@launch
            }
            controller = c
            c.addListener(listener)
            update()
            while (isActive) {
                delay(tickMs())
                update()
            }
        }
    }

    private suspend fun ctrl(): MediaController? {
        connect()
        return controller ?: future?.let { runCatching { it.await() }.getOrNull() }
    }

    private fun update() {
        val c = controller ?: return
        _state.value = State(
            connected = true,
            isPlaying = c.isPlaying,
            playWhenReady = c.playWhenReady,
            buffering = c.playbackState == Player.STATE_BUFFERING,
            positionMs = c.currentPosition,
            durationMs = c.duration.takeIf { it != C.TIME_UNSET } ?: 0L,
            speed = c.playbackParameters.speed,
            mediaId = c.currentMediaItem?.mediaId,
        )
    }

    fun playItem(itemId: String) {
        scope.launch {
            val args = Bundle().apply { putString(PlaybackService.ARG_ITEM_ID, itemId) }
            ctrl()?.sendCustomCommand(SessionCommand(PlaybackService.CMD_PLAY_ITEM, Bundle.EMPTY), args)
        }
    }

    fun stop() {
        scope.launch {
            ctrl()?.sendCustomCommand(SessionCommand(PlaybackService.CMD_STOP, Bundle.EMPTY), Bundle.EMPTY)
        }
    }

    fun togglePlay() {
        scope.launch {
            val c = ctrl() ?: return@launch
            if (c.playWhenReady && c.playbackState != Player.STATE_ENDED) {
                c.pause()
            } else {
                if (c.playbackState == Player.STATE_IDLE) c.prepare()
                if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
                c.play()
            }
            update()
        }
    }

    fun seekBy(seconds: Int) {
        scope.launch {
            val c = ctrl() ?: return@launch
            val dur = c.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
            c.seekTo((c.currentPosition + seconds * 1000L).coerceIn(0L, dur))
            update()
        }
    }

    fun seekToSec(sec: Double) {
        scope.launch {
            ctrl()?.seekTo((sec * 1000).toLong().coerceAtLeast(0L))
            update()
        }
    }

    fun setSpeed(speed: Float) {
        scope.launch {
            ctrl()?.setPlaybackSpeed(speed)
            update()
        }
    }

    fun release() {
        scope.cancel()
        controller?.removeListener(listener)
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller = null
    }
}
