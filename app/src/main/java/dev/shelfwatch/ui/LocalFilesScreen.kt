package dev.shelfwatch.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import dev.shelfwatch.R
import dev.shelfwatch.playback.LocalAudio
import dev.shelfwatch.str

/** Eigene MP3/Opus-Dateien. Die Wiedergabe läuft über denselben Player wie Hörbücher. */
@Composable
fun LocalFilesScreen(vm: MainViewModel, nav: NavHostController) {
    val ctx = LocalContext.current
    val files by produceState<List<Pair<String, Long>>>(emptyList()) {
        value = withContext(Dispatchers.IO) { LocalAudio.list(ctx) }
    }

    ScreenList {
        item { ListHeader { Text(str(R.string.local_files)) } }
        if (files.isEmpty()) {
            item {
                Text(
                    str(R.string.local_files_empty),
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center,
                )
            }
        }
        items(files) { (name, size) ->
            Chip(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    vm.player.playItem(LocalAudio.idFor(name))
                    nav.navigate("player")
                },
                icon = { Icon(painterResource(R.drawable.ic_music), contentDescription = null) },
                label = { Text(name.substringBeforeLast('.'), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                secondaryLabel = { Text("${name.substringAfterLast('.').uppercase()} · ${formatBytes(size)}") },
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
    }
}
