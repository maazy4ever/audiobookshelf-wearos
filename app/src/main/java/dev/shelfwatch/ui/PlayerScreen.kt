package dev.shelfwatch.ui

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.focusable
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
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

@Composable
fun PlayerScreen(vm: MainViewModel) {
    val ps by vm.player.state.collectAsState()
    val now by vm.nowPlaying.collectAsState()
    val loading by vm.playbackLoading.collectAsState()
    val error by vm.playbackError.collectAsState()
    val pending by vm.pendingSync.collectAsState()
    val sync by vm.syncStatus.collectAsState()
    val ctx = LocalContext.current
    val audio = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val focus = remember { FocusRequester() }
    var rotaryAcc by remember { mutableFloatStateOf(0f) }

    val item = now
    val durSec = item?.duration ?: (ps.durationMs / 1000.0)
    val posSec = ps.positionMs / 1000.0
    val fraction = if (durSec > 0) (posSec / durSec).toFloat().coerceIn(0f, 1f) else 0f
    val chapter = item?.chapterAt(posSec)

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Scaffold(timeText = { TimeText() }) {
        Box(
            Modifier
                .fillMaxSize()
                // Krone = Lautstärke
                .onRotaryScrollEvent { e ->
                    rotaryAcc += e.verticalScrollPixels
                    if (abs(rotaryAcc) > 48f) {
                        audio.adjustStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            if (rotaryAcc > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                            AudioManager.FLAG_SHOW_UI,
                        )
                        rotaryAcc = 0f
                    }
                    true
                }
                .focusRequester(focus)
                .focusable(),
            contentAlignment = Alignment.Center,
        ) {
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
                Spacer(Modifier.height(14.dp))
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
