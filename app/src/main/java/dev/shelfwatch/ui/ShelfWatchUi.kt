package dev.shelfwatch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import dev.shelfwatch.R
import dev.shelfwatch.str
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ShelfWatchUi(vm: MainViewModel, onAlwaysOnChanged: () -> Unit) {
    val loggedIn by vm.loggedIn.collectAsState()
    val ambient by vm.ambient.collectAsState()

    MaterialTheme(colors = ShelfColors) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (!loggedIn) {
                LoginScreen(vm)
            } else {
                val nav = rememberSwipeDismissableNavController()
                val openPlayer by vm.openPlayerRequest.collectAsState()
                LaunchedEffect(openPlayer) {
                    if (openPlayer) {
                        vm.openPlayerRequest.value = false
                        if (nav.currentDestination?.route != "player") nav.navigate("player")
                    }
                }
                SwipeDismissableNavHost(navController = nav, startDestination = "home") {
                    composable("home") { HomeScreen(vm, nav) }
                    composable("library/{id}") { entry ->
                        LibraryScreen(vm, nav, entry.arguments?.getString("id").orEmpty())
                    }
                    composable("item/{id}") { entry ->
                        ItemScreen(vm, nav, entry.arguments?.getString("id").orEmpty())
                    }
                    composable("player") { PlayerScreen(vm) }
                    composable("downloads") { DownloadsScreen(vm, nav) }
                    composable("settings") { SettingsScreen(vm, onAlwaysOnChanged) }
                    composable("companion") { CompanionScreen() }
                    composable("local") { LocalFilesScreen(vm, nav) }
                }
            }
            if (ambient) AmbientOverlay(vm)
        }
    }
}

/** Stromsparende Always-on-Ansicht: schwarz, nur Text, Aktualisierung ~minütlich. */
@Composable
fun AmbientOverlay(vm: MainViewModel) {
    val now by vm.nowPlaying.collectAsState()
    val ps by vm.player.state.collectAsState()
    val posMs by vm.player.position.collectAsState()
    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date()),
            style = MaterialTheme.typography.display3,
            color = Color.White,
        )
        Spacer(Modifier.height(8.dp))
        val item = now
        if (item != null) {
            Text(
                item.title,
                color = Color.LightGray,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val posSec = posMs / 1000.0
            val remainingMin = ((item.duration - posSec) / 60).toLong().coerceAtLeast(0)
            Text(
                (if (ps.playWhenReady) "▶ " else "❚❚ ") + str(R.string.time_left, (remainingMin / 60).toInt(), (remainingMin % 60).toInt()),
                color = Color.Gray,
                style = MaterialTheme.typography.caption2,
            )
        }
    }
}
