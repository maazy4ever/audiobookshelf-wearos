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
import dev.shelfwatch.companion.CompanionService

@Composable
fun CompanionScreen() {
    val ctx = LocalContext.current
    val info by CompanionService.info.collectAsState()
    var pin by remember { mutableStateOf(Graph.prefs.companionPin) }
    val active = info.running || info.starting

    ScreenList {
        item { ListHeader { Text("PC-Verbindung") } }
        item {
            ToggleChip(
                modifier = Modifier.fillMaxWidth(),
                checked = active,
                onCheckedChange = { on ->
                    if (on) CompanionService.start(ctx) else CompanionService.stop(ctx)
                },
                label = { Text(if (active) "Aktiv" else "Aus") },
                secondaryLabel = { Text(if (info.starting) "Verbinde WLAN…" else "für ShelfWatch Companion") },
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
                "Code: $pin",
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
                    "PC ${info.lastClient} · ${formatAgo(info.lastClientAt)}",
                    style = MaterialTheme.typography.caption3,
                    textAlign = TextAlign.Center,
                )
            }
        }
        item {
            Chip(
                modifier = Modifier.fillMaxWidth(),
                onClick = { pin = Graph.prefs.newCompanionPin() },
                label = { Text("Neuer Code") },
                secondaryLabel = { Text("trennt bekannte PCs") },
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
        item {
            Text(
                "Schaltet sich nach 30 min ohne Zugriff ab (außer während Downloads).",
                style = MaterialTheme.typography.caption3,
                textAlign = TextAlign.Center,
            )
        }
    }
}
