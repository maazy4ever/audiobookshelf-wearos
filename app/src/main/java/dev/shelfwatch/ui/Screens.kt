package dev.shelfwatch.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import dev.shelfwatch.Graph
import dev.shelfwatch.R
import dev.shelfwatch.str
import dev.shelfwatch.companion.CompanionService
import dev.shelfwatch.data.LibraryItem
import dev.shelfwatch.download.DlState
import dev.shelfwatch.playback.LocalAudio
import androidx.compose.runtime.produceState
import androidx.compose.ui.res.pluralStringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// --- Login -------------------------------------------------------------------

@Composable
fun LoginScreen(vm: MainViewModel) {
    val state by vm.login.collectAsState()
    var server by remember { mutableStateOf(Graph.prefs.serverUrl) }
    var user by remember { mutableStateOf(Graph.prefs.username.orEmpty()) }
    var password by remember { mutableStateOf("") }

    ScreenList {
        item { ListHeader { Text("Audiobookshelf") } }
        item { InputField(str(R.string.server_url), server, { server = it }, KeyboardType.Uri) }
        item { InputField(str(R.string.username), user, { user = it }, KeyboardType.Text) }
        item { InputField(str(R.string.password), password, { password = it }, KeyboardType.Password, password = true) }
        item {
            Chip(
                modifier = Modifier.fillMaxWidth(),
                onClick = { vm.login(server, user, password) },
                enabled = !state.busy && server.isNotBlank() && user.isNotBlank(),
                label = { Text(str(if (state.busy) R.string.connecting else R.string.sign_in)) },
                colors = ChipDefaults.primaryChipColors(),
            )
        }
        state.error?.let { err ->
            item { Text(err, color = MaterialTheme.colors.error, textAlign = TextAlign.Center) }
        }
    }
}

// --- Start -------------------------------------------------------------------

@Composable
fun HomeScreen(vm: MainViewModel, nav: NavHostController) {
    val home by vm.home.collectAsState()
    val now by vm.nowPlaying.collectAsState()
    val ps by vm.player.state.collectAsState()
    val downloads by vm.downloads.collectAsState()
    val progress by vm.progressMap.collectAsState()
    val authExpired by vm.authExpired.collectAsState()

    LaunchedEffect(Unit) { vm.refreshHome() }

    ScreenList {
        item { ListHeader { Text("ShelfWatch") } }

        now?.let { np ->
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { nav.navigate("player") },
                    colors = ChipDefaults.primaryChipColors(),
                    icon = {
                        Icon(
                            painterResource(if (ps.playWhenReady) R.drawable.ic_pause else R.drawable.ic_play),
                            contentDescription = null,
                        )
                    },
                    label = { Text(np.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = { Text(str(if (ps.playWhenReady) R.string.state_playing else R.string.state_paused)) },
                )
            }
        }

        item { SyncLine(vm) }

        if (authExpired) {
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { vm.logout() },
                    label = { Text(str(R.string.auth_expired)) },
                    secondaryLabel = { Text(str(R.string.tap_to_sign_in)) },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
        }

        if (home.loading && home.inProgress.isEmpty() && home.libraries.isEmpty()) {
            item { CircularProgressIndicator(Modifier.size(28.dp)) }
        }
        if (home.offline) {
            item { Text(str(R.string.offline_downloads), style = MaterialTheme.typography.caption2) }
        }

        if (home.inProgress.isNotEmpty()) {
            item { ListHeader { Text(str(R.string.continue_listening)) } }
            items(home.inProgress) { li ->
                BookChip(rowOf(vm, li, progress, downloads.keys)) { nav.navigate("item/${li.id}") }
            }
        }

        if (home.libraries.isNotEmpty()) {
            item { ListHeader { Text(str(R.string.libraries)) } }
            items(home.libraries) { lib ->
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { nav.navigate("library/${lib.id}") },
                    icon = { Icon(painterResource(R.drawable.ic_library), contentDescription = null) },
                    label = { Text(lib.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
        }

        item { ListHeader { Text(str(R.string.more)) } }
        item {
            Chip(
                modifier = Modifier.fillMaxWidth(),
                onClick = { nav.navigate("downloads") },
                icon = { Icon(painterResource(R.drawable.ic_download), contentDescription = null) },
                label = { Text(str(R.string.downloads)) },
                secondaryLabel = {
                    val n = downloads.values.count { it.complete }
                    Text(pluralStringResource(R.plurals.n_audiobooks, n, n))
                },
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
        item {
            val localCount by produceState(0) {
                value = withContext(Dispatchers.IO) { LocalAudio.list(Graph.app).size }
            }
            Chip(
                modifier = Modifier.fillMaxWidth(),
                onClick = { nav.navigate("local") },
                icon = { Icon(painterResource(R.drawable.ic_music), contentDescription = null) },
                label = { Text(str(R.string.local_files)) },
                secondaryLabel = { Text(pluralStringResource(R.plurals.n_local_files, localCount, localCount)) },
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
        item {
            val companion by CompanionService.info.collectAsState()
            Chip(
                modifier = Modifier.fillMaxWidth(),
                onClick = { nav.navigate("companion") },
                icon = { Icon(painterResource(R.drawable.ic_computer), contentDescription = null) },
                label = { Text(str(R.string.pc_connection)) },
                secondaryLabel = { Text(str(if (companion.running) R.string.state_on else R.string.state_off)) },
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
        item {
            Chip(
                modifier = Modifier.fillMaxWidth(),
                onClick = { nav.navigate("settings") },
                icon = { Icon(painterResource(R.drawable.ic_settings), contentDescription = null) },
                label = { Text(str(R.string.settings)) },
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
        if (now != null && !ps.playWhenReady) {
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { vm.player.stop() },
                    label = { Text(str(R.string.stop_playback)) },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
        }
        item {
            Chip(
                modifier = Modifier.fillMaxWidth(),
                onClick = { vm.refreshHome() },
                label = { Text(str(if (home.loading) R.string.loading else R.string.refresh)) },
                colors = ChipDefaults.childChipColors(),
            )
        }
    }
}

@Composable
fun SyncLine(vm: MainViewModel) {
    val status by vm.syncStatus.collectAsState()
    val pending by vm.pendingSync.collectAsState()
    // alle 5 s neu berechnen, damit "vor x s" aktuell bleibt (ohne Abhängigkeit vom Player)
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(5_000)
            value = System.currentTimeMillis()
        }
    }
    val text = when {
        pending > 0 -> str(R.string.sync_pending, pending, formatAgo(status.lastSuccessAt, now))
        status.lastSuccessAt > 0 -> str(R.string.sync_ok, formatAgo(status.lastSuccessAt, now))
        else -> str(R.string.sync_never)
    }
    Text(
        text,
        style = MaterialTheme.typography.caption3,
        color = if (pending > 0) MaterialTheme.colors.primary else MaterialTheme.colors.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

private fun rowOf(
    vm: MainViewModel,
    li: LibraryItem,
    progress: Map<String, dev.shelfwatch.data.MediaProgress>,
    downloaded: Set<String>,
): BookRow {
    val p = vm.progressOf(li.id, progress)
    return BookRow(
        id = li.id,
        title = li.media?.metadata?.title ?: str(R.string.unknown),
        author = li.media?.metadata?.authorName.orEmpty(),
        progress = p?.first,
        finished = p?.second == true,
        downloaded = li.id in downloaded && Graph.downloads.isDownloaded(li.id),
    )
}

// --- Bibliothek --------------------------------------------------------------

@Composable
fun LibraryScreen(vm: MainViewModel, nav: NavHostController, libraryId: String) {
    val lib by vm.library.collectAsState()
    val progress by vm.progressMap.collectAsState()
    val downloads by vm.downloads.collectAsState()
    LaunchedEffect(libraryId) { vm.openLibrary(libraryId) }

    ScreenList {
        item { ListHeader { Text(lib.name.ifBlank { str(R.string.library) }, maxLines = 1) } }
        items(lib.items) { li ->
            BookChip(rowOf(vm, li, progress, downloads.keys)) { nav.navigate("item/${li.id}") }
        }
        when {
            lib.loading -> item { CircularProgressIndicator(Modifier.size(28.dp)) }
            lib.error != null -> item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { vm.loadMore() },
                    label = { Text(str(R.string.error_retry)) },
                    secondaryLabel = { Text(lib.error.orEmpty(), maxLines = 1) },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
            lib.total < 0 || lib.items.size < lib.total -> item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { vm.loadMore() },
                    label = { Text(str(R.string.load_more)) },
                    secondaryLabel = { Text(str(R.string.n_of_total, lib.items.size, lib.total)) },
                    colors = ChipDefaults.childChipColors(),
                )
            }
        }
    }
}

// --- Details -----------------------------------------------------------------

@Composable
fun ItemScreen(vm: MainViewModel, nav: NavHostController, itemId: String) {
    val st by vm.item.collectAsState()
    val downloads by vm.downloads.collectAsState()
    val dlStates by vm.downloadStates.collectAsState()
    val progress by vm.progressMap.collectAsState()
    val error by vm.playbackError.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    LaunchedEffect(itemId) { vm.loadItem(itemId) }

    val li = st?.takeIf { it.id == itemId }?.item
    val local = downloads[itemId]
    val title = li?.media?.metadata?.title ?: local?.title ?: "…"
    val author = li?.media?.metadata?.authorName ?: local?.author ?: ""
    val duration = li?.media?.duration ?: local?.duration ?: 0.0
    val sizeBytes = li?.media?.audioFiles?.filter { !it.exclude }?.sumOf { it.metadata.size }?.takeIf { it > 0 }
        ?: local?.totalSize ?: 0L
    val p = vm.progressOf(itemId, progress)
    val downloaded = local?.complete == true
    val dl = dlStates[itemId]

    ScreenList {
        item { Cover(itemId, Modifier.size(72.dp).clip(RoundedCornerShape(8.dp))) }
        item {
            Text(
                title,
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (author.isNotBlank()) item { Text(author, style = MaterialTheme.typography.caption2, maxLines = 1) }
        item {
            val pText = when {
                p?.second == true -> " · " + str(R.string.finished)
                p != null -> " · ${(p.first * 100).toInt()} %"
                else -> ""
            }
            Text(formatDuration(duration) + pText, style = MaterialTheme.typography.caption2)
        }
        item {
            Chip(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    vm.player.playItem(itemId)
                    nav.navigate("player")
                },
                enabled = li != null || downloaded,
                icon = { Icon(painterResource(R.drawable.ic_play), contentDescription = null) },
                label = { Text(str(if (p != null && p.first > 0 && !p.second) R.string.resume else R.string.play)) },
                secondaryLabel = { Text(str(if (downloaded) R.string.offline else R.string.stream)) },
                colors = ChipDefaults.primaryChipColors(),
            )
        }
        item {
            when {
                dl?.status == DlState.Status.RUNNING || dl?.status == DlState.Status.QUEUED -> Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { vm.cancelDownload(itemId) },
                    label = {
                        Text(
                            when {
                                dl.status == DlState.Status.QUEUED -> str(R.string.dl_queued)
                                dl.waitingForNetwork -> str(R.string.dl_waiting, (dl.progress * 100).toInt())
                                else -> str(R.string.dl_running, (dl.progress * 100).toInt())
                            }
                        )
                    },
                    secondaryLabel = { Text(str(R.string.tap_to_cancel)) },
                    colors = ChipDefaults.secondaryChipColors(),
                )
                downloaded && confirmDelete -> Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { vm.deleteDownload(itemId); confirmDelete = false },
                    icon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
                    label = { Text(str(R.string.confirm_delete)) },
                    colors = ChipDefaults.primaryChipColors(backgroundColor = MaterialTheme.colors.error),
                )
                downloaded -> Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { confirmDelete = true },
                    icon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
                    label = { Text(str(R.string.delete_download)) },
                    secondaryLabel = { Text(formatBytes(local!!.totalSize)) },
                    colors = ChipDefaults.secondaryChipColors(),
                )
                else -> Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        vm.download(itemId)
                        // Einmalig fragen: Akku-Optimierung aus, damit Doze den Download nicht drosselt
                        if (!isBatteryExempt(ctx) && !Graph.prefs.askedBatteryExemption) {
                            Graph.prefs.askedBatteryExemption = true
                            requestBatteryExemption(ctx)
                        }
                    },
                    enabled = li != null,
                    icon = { Icon(painterResource(R.drawable.ic_download), contentDescription = null) },
                    label = { Text(str(if (dl?.status == DlState.Status.FAILED) R.string.retry_download else R.string.download)) },
                    secondaryLabel = {
                        Text(
                            dl?.error ?: if (sizeBytes > 0) formatBytes(sizeBytes) else "",
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
        }
        st?.error?.takeIf { li == null && !downloaded }?.let {
            item { Text(str(R.string.unreachable, it), style = MaterialTheme.typography.caption3, textAlign = TextAlign.Center) }
        }
        error?.let {
            item { Text(it, style = MaterialTheme.typography.caption3, color = MaterialTheme.colors.error) }
        }
    }
}

// --- Downloads ---------------------------------------------------------------

@Composable
fun DownloadsScreen(vm: MainViewModel, nav: NavHostController) {
    val downloads by vm.downloads.collectAsState()
    val dlStates by vm.downloadStates.collectAsState()

    ScreenList {
        item { ListHeader { Text(str(R.string.downloads)) } }
        val active = dlStates.entries.toList()
        items(active) { (id, s) ->
            Chip(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (s.status == DlState.Status.FAILED) vm.download(id) else nav.navigate("item/$id")
                },
                label = { Text(downloads[id]?.title ?: str(R.string.audiobook), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                secondaryLabel = {
                    Text(
                        when (s.status) {
                            DlState.Status.QUEUED -> str(R.string.dl_queued)
                            DlState.Status.RUNNING ->
                                if (s.waitingForNetwork) str(R.string.dl_waiting, (s.progress * 100).toInt())
                                else str(R.string.dl_running, (s.progress * 100).toInt())
                            DlState.Status.FAILED -> str(R.string.dl_failed_tap)
                        },
                        maxLines = 1,
                    )
                },
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
        val done = downloads.values.filter { it.complete }.sortedByDescending { it.downloadedAt }
        if (done.isEmpty() && active.isEmpty()) {
            item { Text(str(R.string.nothing_downloaded), textAlign = TextAlign.Center) }
        }
        items(done) { d ->
            val p = vm.progressOf(d.itemId, emptyMap())
            BookChip(
                BookRow(d.itemId, d.title, d.author, p?.first, p?.second == true, downloaded = true)
            ) { nav.navigate("item/${d.itemId}") }
        }
        item {
            // Speicher-Scan im Hintergrund statt im Bildaufbau
            var storage by remember { mutableStateOf(0L to 0L) }
            LaunchedEffect(downloads) {
                storage = withContext(Dispatchers.IO) { Graph.downloads.usedBytes() to Graph.downloads.freeBytes() }
            }
            Text(
                str(R.string.storage, formatBytes(storage.first), formatBytes(storage.second)),
                style = MaterialTheme.typography.caption3,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// --- Einstellungen -----------------------------------------------------------

@Composable
fun SettingsScreen(vm: MainViewModel, onAlwaysOnChanged: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = Graph.prefs
    val pending by vm.pendingSync.collectAsState()
    var pauseOnDisconnect by remember { mutableStateOf(prefs.pauseOnDisconnect) }
    var alwaysOn by remember { mutableStateOf(prefs.alwaysOn) }
    var preferWifi by remember { mutableStateOf(prefs.preferWifiDownloads) }
    var skipBack by remember { mutableStateOf(prefs.skipBackSec) }
    var skipFwd by remember { mutableStateOf(prefs.skipForwardSec) }
    var confirmLogout by remember { mutableStateOf(false) }
    val batteryExempt = remember { isBatteryExempt(ctx) }

    ScreenList {
        item { ListHeader { Text(str(R.string.settings)) } }
        item {
            Chip(
                modifier = Modifier.fillMaxWidth(),
                onClick = { openBluetooth(ctx) },
                icon = { Icon(painterResource(R.drawable.ic_bluetooth), contentDescription = null) },
                label = { Text(str(R.string.pair_headphones)) },
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
        item {
            Chip(
                modifier = Modifier.fillMaxWidth(),
                onClick = { requestBatteryExemption(ctx) },
                label = { Text(str(R.string.battery_optimization)) },
                secondaryLabel = { Text(str(if (batteryExempt) R.string.battery_disabled else R.string.battery_enabled)) },
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
        item {
            ToggleChip(
                modifier = Modifier.fillMaxWidth(),
                checked = alwaysOn,
                onCheckedChange = {
                    alwaysOn = it
                    prefs.alwaysOn = it
                    onAlwaysOnChanged()
                },
                label = { Text(str(R.string.always_on)) },
                secondaryLabel = { Text(str(R.string.always_on_sub)) },
                toggleControl = { Switch(checked = alwaysOn) },
            )
        }
        item {
            ToggleChip(
                modifier = Modifier.fillMaxWidth(),
                checked = pauseOnDisconnect,
                onCheckedChange = { pauseOnDisconnect = it; prefs.pauseOnDisconnect = it },
                label = { Text(str(R.string.pause_on_disconnect)) },
                toggleControl = { Switch(checked = pauseOnDisconnect) },
            )
        }
        item {
            ToggleChip(
                modifier = Modifier.fillMaxWidth(),
                checked = preferWifi,
                onCheckedChange = { preferWifi = it; prefs.preferWifiDownloads = it },
                label = { Text(str(R.string.wifi_downloads)) },
                secondaryLabel = { Text(str(R.string.wifi_downloads_sub)) },
                toggleControl = { Switch(checked = preferWifi) },
            )
        }
        item {
            Chip(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val opts = listOf(5, 10, 15, 30, 60)
                    skipBack = opts[(opts.indexOf(skipBack) + 1) % opts.size]
                    prefs.skipBackSec = skipBack
                },
                label = { Text(str(R.string.skip_back, skipBack)) },
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
        item {
            Chip(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val opts = listOf(10, 15, 30, 60, 120)
                    skipFwd = opts[(opts.indexOf(skipFwd) + 1) % opts.size]
                    prefs.skipForwardSec = skipFwd
                },
                label = { Text(str(R.string.skip_forward, skipFwd)) },
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
        item {
            Chip(
                modifier = Modifier.fillMaxWidth(),
                onClick = { vm.syncNow() },
                label = { Text(str(R.string.sync_now)) },
                secondaryLabel = { Text(if (pending > 0) str(R.string.n_pending, pending) else str(R.string.all_synced)) },
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
        item { SyncLine(vm) }
        item {
            Text(
                "${prefs.username.orEmpty()} @ ${prefs.serverUrl}",
                style = MaterialTheme.typography.caption3,
                textAlign = TextAlign.Center,
            )
        }
        item {
            Chip(
                modifier = Modifier.fillMaxWidth(),
                onClick = { if (confirmLogout) vm.logout() else confirmLogout = true },
                label = { Text(str(if (confirmLogout) R.string.confirm_logout else R.string.logout)) },
                secondaryLabel = { Text(str(R.string.downloads_kept)) },
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
    }
}

fun openBluetooth(ctx: Context) {
    val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        .putExtra("EXTRA_CONNECTION_ONLY", true)
        .putExtra("EXTRA_CLOSE_ON_CONNECT", true)
        .putExtra("android.bluetooth.devicepicker.extra.FILTER_TYPE", 1)
    try {
        ctx.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
    }
}

fun isBatteryExempt(ctx: Context): Boolean =
    ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

fun requestBatteryExemption(ctx: Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
    try {
        ctx.startActivity(direct)
    } catch (_: Exception) {
        try {
            ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: Exception) {
        }
    }
}
