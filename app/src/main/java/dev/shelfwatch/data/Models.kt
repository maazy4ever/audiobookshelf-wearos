package dev.shelfwatch.data

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class LoginResponse(val user: AbsUser)

@Serializable
data class AbsUser(
    val id: String = "",
    val username: String = "",
    /** Legacy-Token (ABS < 2.26) */
    val token: String? = null,
    /** JWT-Tokens (ABS >= 2.26) */
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val mediaProgress: List<MediaProgress> = emptyList(),
)

@Serializable
data class MediaProgress(
    val libraryItemId: String = "",
    val episodeId: String? = null,
    val duration: Double = 0.0,
    val progress: Double = 0.0,
    val currentTime: Double = 0.0,
    val isFinished: Boolean = false,
    val lastUpdate: Long = 0,
)

@Serializable
data class LibrariesResponse(val libraries: List<Library> = emptyList())

@Serializable
data class Library(val id: String, val name: String = "", val mediaType: String = "book")

@Serializable
data class LibraryItemsResponse(val results: List<LibraryItem> = emptyList(), val total: Int = 0)

@Serializable
data class ItemsInProgressResponse(val libraryItems: List<LibraryItem> = emptyList())

@Serializable
data class LibraryItem(
    val id: String,
    val libraryId: String? = null,
    val mediaType: String = "book",
    val media: BookMedia? = null,
)

@Serializable
data class BookMedia(
    val metadata: BookMetadata = BookMetadata(),
    val duration: Double = 0.0,
    val size: Long = 0,
    val audioFiles: List<AudioFile> = emptyList(),
    val chapters: List<AbsChapter> = emptyList(),
)

@Serializable
data class BookMetadata(
    val title: String? = null,
    val subtitle: String? = null,
    val authorName: String? = null,
    val narratorName: String? = null,
    val seriesName: String? = null,
)

@Serializable
data class AudioFile(
    val index: Int = 0,
    val ino: String,
    val duration: Double = 0.0,
    val mimeType: String? = null,
    val exclude: Boolean = false,
    val metadata: FileMetadata = FileMetadata(),
)

@Serializable
data class FileMetadata(val filename: String = "", val ext: String = "", val size: Long = 0)

@Serializable
data class AbsChapter(val id: Int = 0, val start: Double = 0.0, val end: Double = 0.0, val title: String = "")

@Serializable
data class DeviceInfo(
    val deviceId: String,
    val clientName: String,
    val clientVersion: String,
    val manufacturer: String,
    val model: String,
    val sdkVersion: Int,
)

@Serializable
data class PlayRequest(
    val deviceInfo: DeviceInfo,
    val forceDirectPlay: Boolean = true,
    val forceTranscode: Boolean = false,
    val supportedMimeTypes: List<String>,
    val mediaPlayer: String = "exoplayer",
)

@Serializable
data class PlaybackSessionResponse(val id: String, val currentTime: Double = 0.0)

@Serializable
data class SyncRequest(val currentTime: Double, val timeListened: Double, val duration: Double)

@Serializable
data class ProgressUpdate(
    val currentTime: Double,
    val duration: Double,
    val progress: Double,
    val isFinished: Boolean,
)
