package dev.shelfwatch.download

import android.content.Context
import android.net.Uri
import android.os.StatFs
import dev.shelfwatch.playback.Chapter
import dev.shelfwatch.playback.PlayTrack
import dev.shelfwatch.playback.PlayableItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class LocalTrack(
    val index: Int,
    val ino: String,
    val fileName: String,
    val ext: String,
    val duration: Double,
    val size: Long = 0,
)

@Serializable
data class LocalChapter(val start: Double, val end: Double, val title: String)

@Serializable
data class DownloadedItem(
    val itemId: String,
    val libraryId: String? = null,
    val title: String,
    val author: String = "",
    val duration: Double,
    val tracks: List<LocalTrack>,
    val chapters: List<LocalChapter> = emptyList(),
    val hasCover: Boolean = false,
    val complete: Boolean = false,
    val downloadedAt: Long = 0,
) {
    val totalSize: Long get() = tracks.sumOf { it.size }
}

/** Lokale Ablage: filesDir/downloads/<itemId>/{meta.json, 000.m4b, …, cover.jpg} */
class DownloadStore(context: Context, private val json: Json) {
    private val root = File(context.filesDir, "downloads").apply { mkdirs() }
    private val _items = MutableStateFlow<Map<String, DownloadedItem>>(emptyMap())

    /** Alle Einträge inkl. unvollständiger (complete=false). */
    val items = _items.asStateFlow()

    init {
        reload()
    }

    fun dir(itemId: String) = File(root, itemId.replace(Regex("[^A-Za-z0-9_-]"), "_"))
    fun trackFile(itemId: String, t: LocalTrack) = File(dir(itemId), "%03d.%s".format(t.index, t.ext))
    fun coverFile(itemId: String) = File(dir(itemId), "cover.jpg")
    private fun metaFile(itemId: String) = File(dir(itemId), "meta.json")

    /** Nur vollständig heruntergeladene Hörbücher, deren Dateien auch existieren. */
    fun get(itemId: String): DownloadedItem? =
        _items.value[itemId]?.takeIf { d -> d.complete && d.tracks.all { trackFile(itemId, it).exists() } }

    fun isDownloaded(itemId: String) = _items.value[itemId]?.complete == true

    @Synchronized
    fun saveMeta(item: DownloadedItem) {
        val dir = dir(item.itemId).apply { mkdirs() }
        val tmp = File(dir, "meta.json.tmp")
        tmp.writeText(json.encodeToString(item))
        val target = metaFile(item.itemId)
        if (target.exists()) target.delete()
        tmp.renameTo(target)
        reload()
    }

    @Synchronized
    fun delete(itemId: String) {
        dir(itemId).deleteRecursively()
        reload()
    }

    @Synchronized
    fun reload() {
        val map = HashMap<String, DownloadedItem>()
        root.listFiles()?.forEach { d ->
            val meta = File(d, "meta.json")
            if (meta.exists()) {
                runCatching { json.decodeFromString<DownloadedItem>(meta.readText()) }
                    .getOrNull()?.let { map[it.itemId] = it }
            }
        }
        _items.value = map
    }

    fun usedBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    fun freeBytes(): Long = runCatching { StatFs(root.path).availableBytes }.getOrDefault(0L)

    fun toPlayable(d: DownloadedItem) = PlayableItem(
        itemId = d.itemId,
        title = d.title,
        author = d.author,
        duration = d.duration.takeIf { it > 0 } ?: d.tracks.sumOf { it.duration },
        tracks = d.tracks.sortedBy { it.index }
            .map { PlayTrack(Uri.fromFile(trackFile(d.itemId, it)).toString(), it.duration) },
        chapters = d.chapters.map { Chapter(it.start, it.end, it.title) },
        coverUri = if (d.hasCover && coverFile(d.itemId).exists()) Uri.fromFile(coverFile(d.itemId)).toString() else null,
        isLocal = true,
    )
}
