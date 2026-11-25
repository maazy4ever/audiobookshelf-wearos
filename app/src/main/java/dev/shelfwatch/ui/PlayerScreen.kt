package dev.shelfwatch.ui

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.gestures.ScrollableState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.compose.ui.focus.FocusRequester
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import android.util.Log
import dev.shelfwatch.str
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import dev.shelfwatch.Graph
import dev.shelfwatch.R
import dev.shelfwatch.str
import java.util.Locale
import kotlin.math.abs

private val SPEEDS = listOf(0.75f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f)
/** Pixel-Summe, ab der die Krone einen Lautstärkeschritt auslöst (im Log abstimmbar). */
private const val ROTARY_PX_PER_STEP = 20f
private const val ROTARY_TAG = "ShelfWatchRotary"

@Composable
private fun VolumeButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(ButtonDefaults.ExtraSmallButtonSize),
        colors = ButtonDefaults.secondaryButtonColors(),
    ) { Text(label, style = MaterialTheme.typography.caption2) }
}

@Composable
fun PlayerScreen(vm: MainViewModel) {
    val ps by vm.player.state.collectAsState()
    val posMs by vm.player.position.collectAsState()
    val now by vm.nowPlaying.collectAsState()
    val loading by vm.playbackLoading.collectAsState()
    val error by vm.playbackError.collectAsState()
    val pending by vm.pendingSync.collectAsState()
    val sync by vm.syncStatus.collectAsState()
    val ctx = LocalContext.current
    val audio = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    /** Lautstärke in Prozent, kurz nach dem Drehen angezeigt. */
    var volumeShown by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(volumeShown) {
        if (volumeShown != null) {
            delay(1_500)
            volumeShown = null
        }
    }

    val item = now
    val durSec = item?.duration?.takeIf { it > 0 } ?: (ps.durationMs / 1000.0)
    val posSec = posMs / 1000.0
    val fraction = if (durSec > 0) (posSec / durSec).toFloat().coerceIn(0f, 1f) else 0f
    val chapter = item?.chapterAt(posSec)

    /** Lautstärke um [steps] Schritte ändern, eigene Anzeige statt System-Overlay. */
    fun changeVolume(steps: Int) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val new = (audio.getStreamVolume(AudioManager.STREAM_MUSIC) + steps).coerceIn(0, max)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, new, 0)
        volumeShown = new * 100 / max
    }

    // Krone: Drehereignisse laufen über den Scroll-Mechanismus von Wear Compose. Der Fokus wird dort
    // selbst verwaltet; die gelieferten Pixel-Werte summieren sich zu Lautstärkeschritten.
    var rotaryAcc by remember { mutableFloatStateOf(0f) }
    val rotaryState = remember {
        ScrollableState { delta ->
            Log.d(ROTARY_TAG, "rotary delta=$delta")
            rotaryAcc += delta
            val steps = (rotaryAcc / ROTARY_PX_PER_STEP).toInt()
            if (steps != 0) {
                rotaryAcc -= steps * ROTARY_PX_PER_STEP
                changeVolume(steps)
            }
            delta
        }
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(30) {
            if (runCatching { focusRequester.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(100)
        }
    }

    Scaffold(timeText = { TimeText() }) {
        Box(
            Modifier
                .fillMaxSize()
                .rotaryScrollable(
                    behavior = RotaryScrollableDefaults.behavior(scrollableState = rotaryState),
                    focusRequester = focusRequester,
                ),
            contentAlignment = Alignment.Center,
        ) {
            volumeShown?.let { pct ->
                Text(
                    str(R.string.volume_percent, pct),
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.primary,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                )
            }
            CircularProgressIndicator(
                progress = fraction,
                modifier = Modifier.fillMaxSize().padding(3.dp),
                startAngle = 295f,
                endAngle = 245f,
                strokeWidth = 4.dp,
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 22.dp),
            ) {
                Spacer(Modifier.height(4.dp))
                Text(
                    item?.title ?: str(if (loading) R.string.loading else R.string.nothing_selected),
                    style = MaterialTheme.typography.button,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Text(
                    error ?: chapter?.title ?: item?.author.orEmpty(),
                    style = MaterialTheme.typography.caption3,
                    color = if (error != null) MaterialTheme.colors.error else MaterialTheme.colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { vm.player.seekBy(-Graph.prefs.skipBackSec) },
                        modifier = Modifier.size(ButtonDefaults.SmallButtonSize),
                        colors = ButtonDefaults.secondaryButtonColors(),
                    ) { Icon(painterResource(R.drawable.ic_replay), contentDescription = str(R.string.rewind)) }
                    Spacer(Modifier.width(6.dp))
                    Button(
                        onClick = { vm.player.togglePlay() },
                        modifier = Modifier.size(ButtonDefaults.LargeButtonSize),
                        enabled = item != null,
                    ) {
                        if (loading || (ps.buffering && ps.playWhenReady)) {
                            CircularProgressIndicator(Modifier.size(28.dp))
                        } else {
                            Icon(
                                painterResource(if (ps.playWhenReady) R.drawable.ic_pause else R.drawable.ic_play),
                                contentDescription = str(if (ps.playWhenReady) R.string.pause else R.string.play),
                                modifier = Modifier.size(30.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    Button(
                        onClick = { vm.player.seekBy(Graph.prefs.skipForwardSec) },
                        modifier = Modifier.size(ButtonDefaults.SmallButtonSize),
                        colors = ButtonDefaults.secondaryButtonColors(),
                    ) { Icon(painterResource(R.drawable.ic_forward), contentDescription = str(R.string.fast_forward)) }
                }
                Spacer(Modifier.height(4.dp))
                // Sync-Punkt: grün = synchronisiert, orange = ausstehend
                Row(verticalAlignment = Alignment.CenterVertically) {
                    VolumeButton("−") { changeVolume(-1) }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "● ",
                        style = MaterialTheme.typography.caption3,
                        color = when {
                            pending > 0 -> MaterialTheme.colors.primary
                            sync.lastSuccessAt > 0 -> MaterialTheme.colors.secondary
                            else -> MaterialTheme.colors.onSurfaceVariant
                        },
                    )
                    Text(
                        "${formatClock(posSec)} / ${formatClock(durSec)}",
                        style = MaterialTheme.typography.caption3,
                    )
                    Spacer(Modifier.width(4.dp))
                    VolumeButton("+") { changeVolume(1) }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SmallAction(R.drawable.ic_skip_previous, str(R.string.prev_chapter), enabled = item?.chapters?.isNotEmpty() == true) {
                        val chapters = item?.chapters.orEmpty()
                        val cur = chapters.indexOfLast { posSec >= it.start - 0.5 }
                        if (cur >= 0) {
                            val target = if (posSec - chapters[cur].start > 5 || cur == 0) chapters[cur].start
                            else chapters[cur - 1].start
                            vm.player.seekToSec(target)
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    Button(
                        onClick = {
                            val i = SPEEDS.indexOfFirst { abs(it - ps.speed) < 0.01f }
                            vm.player.setSpeed(SPEEDS[(i + 1).mod(SPEEDS.size)])
                        },
                        modifier = Modifier.size(width = 52.dp, height = ButtonDefaults.ExtraSmallButtonSize),
                        colors = ButtonDefaults.secondaryButtonColors(),
                    ) {
                        val s = ps.speed
                        val label = if (s % 1f == 0f) s.toInt().toString() else String.format(Locale.ROOT, "%s", s)
                        Text("$label×", style = MaterialTheme.typography.caption2)
                    }
                    Spacer(Modifier.width(4.dp))
                    SmallAction(R.drawable.ic_skip_next, str(R.string.next_chapter), enabled = item?.chapters?.isNotEmpty() == true) {
                        item?.chapters?.firstOrNull { it.start > posSec + 0.5 }?.let { vm.player.seekToSec(it.start) }
                    }
                    Spacer(Modifier.width(4.dp))
                    SmallAction(R.drawable.ic_bluetooth, "Bluetooth") { openBluetooth(ctx) }
                }
            }
        }
    }
}

@Composable
private fun SmallAction(icon: Int, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(ButtonDefaults.ExtraSmallButtonSize),
        colors = ButtonDefaults.secondaryButtonColors(),
    ) { Icon(painterResource(icon), contentDescription = description, modifier = Modifier.size(18.dp)) }
}
