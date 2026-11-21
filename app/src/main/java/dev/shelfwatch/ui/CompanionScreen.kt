package dev.shelfwatch.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import dev.shelfwatch.Graph
import dev.shelfwatch.R
import dev.shelfwatch.str
import dev.shelfwatch.companion.CompanionService

@Composable
fun CompanionScreen() {
    val ctx = LocalContext.current
    val info by CompanionService.info.collectAsState()
    var pin by remember { mutableStateOf(Graph.prefs.companionPin) }
    val active = info.running || info.starting

    ScreenList {
        item { ListHeader { Text(str(R.string.pc_connection)) } }
        item {
            ToggleChip(
                modifier = Modifier.fillMaxWidth(),
                checked = active,
                onCheckedChange = { on ->
                    if (on) CompanionService.start(ctx) else CompanionService.stop(ctx)
                },
                label = { Text(str(if (active) R.string.state_on else R.string.state_off)) },
                secondaryLabel = { Text(str(if (info.starting) R.string.companion_connecting else R.string.companion_sub)) },
                toggleControl = { Switch(checked = active) },
            )
        }
        if (info.running) {
            item {
                Text(
                    info.address.orEmpty(),
                    style = MaterialTheme.typography.title3,
                    textAlign = TextAlign.Center,
                )
            }
        }
        item {
            Text(
                str(R.string.companion_code, pin),
                style = MaterialTheme.typography.title2,
                color = MaterialTheme.colors.primary,
                textAlign = TextAlign.Center,
            )
        }
        info.error?.let {
            item { Text(it, color = MaterialTheme.colors.error, textAlign = TextAlign.Center) }
        }
        if (info.lastClient != null) {
            item {
                Text(
                    str(R.string.companion_last_pc, info.lastClient.orEmpty(), formatAgo(info.lastClientAt)),
                    style = MaterialTheme.typography.caption3,
                    textAlign = TextAlign.Center,
                )
            }
        }
        item {
            Chip(
                modifier = Modifier.fillMaxWidth(),
                onClick = { pin = Graph.prefs.newCompanionPin() },
                label = { Text(str(R.string.new_code)) },
                secondaryLabel = { Text(str(R.string.new_code_sub)) },
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
        item {
            Text(
                str(R.string.companion_idle_hint),
                style = MaterialTheme.typography.caption3,
                textAlign = TextAlign.Center,
            )
        }
    }
}
