package dev.shelfwatch.playback

data class PlayTrack(val uri: String, val duration: Double)

data class Chapter(val start: Double, val end: Double, val title: String)

/** Ein abspielbares Hörbuch – entweder gestreamt oder aus lokalen Dateien. */
data class PlayableItem(
    val itemId: String,
    val title: String,
    val author: String,
    val duration: Double,
    val tracks: List<PlayTrack>,
    val chapters: List<Chapter>,
    val coverUri: String?,
    val isLocal: Boolean,
) {
    fun chapterAt(sec: Double): Chapter? =
        chapters.lastOrNull { sec >= it.start - 0.5 }
}
