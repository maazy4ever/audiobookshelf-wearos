package dev.shelfwatch.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.shelfwatch.Graph
import dev.shelfwatch.data.ApiException
import dev.shelfwatch.data.Auth
import dev.shelfwatch.data.Library
import dev.shelfwatch.data.LibraryItem
import dev.shelfwatch.data.MediaProgress
import dev.shelfwatch.download.DownloadService
import dev.shelfwatch.playback.PlayerConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeState(
    val loading: Boolean = false,
    val offline: Boolean = false,
    val error: String? = null,
    val libraries: List<Library> = emptyList(),
    val inProgress: List<LibraryItem> = emptyList(),
)

data class LibState(
    val libraryId: String? = null,
    val name: String = "",
    val items: List<LibraryItem> = emptyList(),
    val total: Int = -1,
    val page: Int = 0,
    val loading: Boolean = false,
    val error: String? = null,
)

data class ItemState(
    val id: String,
    val loading: Boolean = true,
    val item: LibraryItem? = null,
    val error: String? = null,
)

data class LoginState(val busy: Boolean = false, val error: String? = null)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val api = Graph.api
    private val prefs = Graph.prefs

    val ambient = MutableStateFlow(false)
    val openPlayerRequest = MutableStateFlow(false)
    val player = PlayerConnection(app) { if (ambient.value) 30_000L else 500L }

    val loggedIn = Graph.loggedIn
    val authExpired = Graph.authExpired
    val downloads = Graph.downloads.items
    val downloadStates = DownloadService.state
    val syncStatus = Graph.sync.status
    val pendingSync = Graph.progress.pending
    val nowPlaying = Graph.nowPlaying
    val playbackError = Graph.playbackError
    val playbackLoading = Graph.playbackLoading

    val home = MutableStateFlow(HomeState())
    val library = MutableStateFlow(LibState())
    val item = MutableStateFlow<ItemState?>(null)
    val login = MutableStateFlow(LoginState())
    val progressMap = MutableStateFlow<Map<String, MediaProgress>>(emptyMap())

    init {
        player.connect()
    }

    override fun onCleared() {
        player.release()
    }

    // --- Login ----------------------------------------------------------------

    fun login(serverInput: String, username: String, password: String) {
        if (login.value.busy) return
        login.value = LoginState(busy = true)
        viewModelScope.launch {
            try {
                Auth.login(serverInput, username, password)
                login.value = LoginState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                login.value = LoginState(error = e.message)
            }
        }
    }

    fun logout() {
        player.stop()
        Auth.logout()
        home.value = HomeState()
        library.value = LibState()
        progressMap.value = emptyMap()
    }

    // --- Daten ----------------------------------------------------------------

    fun refreshHome() {
        if (home.value.loading) return
        home.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val (libs, me, inProgress) = coroutineScope {
                    val l = async { api.libraries() }
                    val m = async { api.me() }
                    val p = async { api.itemsInProgress() }
                    Triple(l.await(), m.await(), p.await())
                }
                progressMap.value = me.mediaProgress.filter { it.episodeId == null }.associateBy { it.libraryItemId }
                home.value = HomeState(
                    libraries = libs.filter { it.mediaType == "book" },
                    inProgress = inProgress.filter { it.mediaType == "book" },
                )
                // Online → ausstehende Offline-Fortschritte hochladen
                if (Graph.progress.dirtyItems().isNotEmpty()) Graph.sync.schedule()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                home.update { it.copy(loading = false, offline = e !is ApiException, error = e.message) }
            }
        }
    }

    fun openLibrary(id: String) {
        if (library.value.libraryId == id && library.value.items.isNotEmpty()) return
        val name = home.value.libraries.firstOrNull { it.id == id }?.name.orEmpty()
        library.value = LibState(libraryId = id, name = name)
        loadMore()
    }

    fun loadMore() {
        val s = library.value
        val id = s.libraryId ?: return
        if (s.loading || (s.total >= 0 && s.items.size >= s.total)) return
        library.value = s.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val r = api.libraryItems(id, s.page)
                library.update {
                    if (it.libraryId != id) it
                    else it.copy(
                        items = it.items + r.results.filter { li -> li.mediaType == "book" },
                        total = r.total,
                        page = it.page + 1,
                        loading = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                library.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    fun loadItem(id: String) {
        item.value = ItemState(id)
        viewModelScope.launch {
            try {
                val li = api.item(id)
                item.value = ItemState(id, loading = false, item = li)
                api.progress(id)?.let { p -> progressMap.update { it + (id to p) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                item.update { if (it?.id == id) ItemState(id, loading = false, error = e.message) else it }
            }
        }
    }

    /** Fortschritt 0..1 – bevorzugt der neuere Stand aus lokal/Server. */
    fun progressOf(id: String, progress: Map<String, MediaProgress>): Pair<Double, Boolean>? {
        val local = Graph.progress.get(id)
        val server = progress[id]
        return when {
            local != null && (server == null || local.lastUpdate >= server.lastUpdate) ->
                (if (local.duration > 0) local.currentTime / local.duration else 0.0) to local.isFinished
            server != null -> server.progress to server.isFinished
            else -> null
        }
    }

    fun syncNow() {
        Graph.appScope.launch { Graph.sync.pushPending() }
    }

    fun download(id: String) = DownloadService.enqueue(getApplication(), id)
    fun cancelDownload(id: String) = DownloadService.cancel(id)
    fun deleteDownload(id: String) = Graph.downloads.delete(id)
}
