package dev.shelfwatch.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class LocalProgress(
    val currentTime: Double,
    val duration: Double,
    val isFinished: Boolean = false,
    /** ms-Zeitstempel; dient auch als Versionsmarke */
    val lastUpdate: Long,
    /** true = noch nicht zum Server übertragen */
    val dirty: Boolean,
)

/** Lokaler Hörfortschritt inkl. Offline-Warteschlange. */
class ProgressStore(context: Context, private val json: Json) {
    private val sp = context.getSharedPreferences("progress", Context.MODE_PRIVATE)
    private val cache = HashMap<String, LocalProgress>()
    private var lastStamp = 0L
    private val _pending = MutableStateFlow(0)
    val pending = _pending.asStateFlow()

    init {
        sp.all.forEach { (k, v) ->
            (v as? String)?.let { s -> runCatching { cache[k] = json.decodeFromString<LocalProgress>(s) } }
        }
        refreshPending()
    }

    @Synchronized
    fun get(itemId: String): LocalProgress? = cache[itemId]

    /** Speichert Fortschritt als „dirty“ und liefert die Versionsmarke zurück. */
    @Synchronized
    fun put(itemId: String, currentTime: Double, duration: Double, isFinished: Boolean): Long {
        val stamp = maxOf(System.currentTimeMillis(), lastStamp + 1).also { lastStamp = it }
        save(itemId, LocalProgress(currentTime, duration, isFinished, stamp, dirty = true))
        return stamp
    }

    @Synchronized
    fun markClean(itemId: String, stamp: Long) {
        val p = cache[itemId] ?: return
        if (p.dirty && p.lastUpdate == stamp) save(itemId, p.copy(dirty = false))
    }

    @Synchronized
    fun adoptServer(mp: MediaProgress) {
        save(mp.libraryItemId, LocalProgress(mp.currentTime, mp.duration, mp.isFinished, mp.lastUpdate, dirty = false))
    }

    @Synchronized
    fun dirtyItems(): Map<String, LocalProgress> = cache.filterValues { it.dirty }

    private fun save(itemId: String, p: LocalProgress) {
        cache[itemId] = p
        sp.edit().putString(itemId, json.encodeToString(p)).apply()
        refreshPending()
    }

    private fun refreshPending() {
        _pending.value = cache.values.count { it.dirty }
    }
}
