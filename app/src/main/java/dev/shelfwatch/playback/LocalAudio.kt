package dev.shelfwatch.playback

import android.content.Context
import android.media.MediaMetadataRetriever
import java.io.File

/** Eigene Audiodateien (MP3/Opus), die von der Windows-App auf die Uhr kopiert wurden. */
object LocalAudio {
    const val PREFIX = "local:"
    private val EXTENSIONS = setOf("mp3", "opus")

    fun isLocalId(itemId: String) = itemId.startsWith(PREFIX)

    fun idFor(fileName: String) = PREFIX + fileName

    fun dir(context: Context): File = File(context.filesDir, "local-audio").apply { mkdirs() }

    /**
     * Liefert einen sicheren Dateinamen oder null. Nur Dateiname (keine Pfade),
     * nur .mp3 und .opus, Sonderzeichen werden ersetzt.
     */
    fun sanitize(raw: String): String? {
        val name = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return null
        val ext = name.substring(dot + 1).lowercase()
        if (ext !in EXTENSIONS) return null
        val base = name.substring(0, dot)
            .map { if (it.isLetterOrDigit() || it in " ._()-&,'") it else '_' }
            .joinToString("")
            .trim()
            .trimStart('.')
            .take(120)
        if (base.isBlank()) return null
        return "$base.$ext"
    }

    fun fileFor(context: Context, name: String): File? =
        sanitize(name)?.let { File(dir(context), it) }

    /** Dateiname und Größe aller vorhandenen Dateien, alphabetisch. */
    fun list(context: Context): List<Pair<String, Long>> =
        dir(context).listFiles { f -> f.isFile }
            .orEmpty()
            .filter { sanitize(it.name) == it.name }
            .sortedBy { it.name.lowercase() }
            .map { it.name to it.length() }

    /** Dauer in Sekunden, 0.0 wenn die Datei nicht gelesen werden kann. */
    fun durationSec(file: File): Double {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.path)
            (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0
        } catch (e: Exception) {
            0.0
        } finally {
            r.release()
        }
    }
}
