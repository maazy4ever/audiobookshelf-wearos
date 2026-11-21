package dev.shelfwatch.data

import android.os.Build
import dev.shelfwatch.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class ApiException(val code: Int, message: String) :
    IOException("HTTP $code" + if (message.isNotBlank()) ": $message" else "")

/** Minimaler Audiobookshelf-REST-Client. */
class AbsApi(
    private val prefs: Prefs,
    private val client: OkHttpClient,
    private val json: Json,
) {
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    val baseUrl: String get() = prefs.serverUrl.trimEnd('/')

    private fun String.asBody() = toRequestBody(jsonType)

    private suspend fun exec(request: Request): String = withContext(Dispatchers.IO) {
        client.newCall(request).await().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiException(r.code, body.take(160))
            body
        }
    }

    private fun req(path: String) = Request.Builder().url(baseUrl + path)

    // --- Verbindung & Login ---------------------------------------------------

    suspend fun ping(server: String): Boolean = runCatching {
        exec(Request.Builder().url(server.trimEnd('/') + "/ping").header(NO_AUTH, "1").build())
            .contains("success")
    }.getOrDefault(false)

    suspend fun login(server: String, username: String, password: String): AbsUser {
        val body = json.encodeToString(LoginRequest(username, password)).asBody()
        val text = exec(
            Request.Builder().url(server.trimEnd('/') + "/login")
                .header(NO_AUTH, "1")
                .header("x-return-tokens", "true")
                .post(body)
                .build()
        )
        return json.decodeFromString<LoginResponse>(text).user
    }

    fun refreshTokensBlocking(plain: OkHttpClient, refreshToken: String): RefreshResult = try {
        plain.newCall(
            Request.Builder().url("$baseUrl/auth/refresh")
                .header("x-refresh-token", refreshToken)
                .post("{}".asBody())
                .build()
        ).execute().use { r ->
            when {
                r.isSuccessful -> {
                    val user = json.decodeFromString<LoginResponse>(r.body!!.string()).user
                    val access = user.accessToken ?: user.token
                    if (access == null) RefreshResult.Rejected else RefreshResult.Ok(access, user.refreshToken)
                }
                r.code == 401 || r.code == 403 -> RefreshResult.Rejected
                else -> RefreshResult.NetworkError
            }
        }
    } catch (e: Exception) {
        RefreshResult.NetworkError
    }

    // --- Bibliothek -----------------------------------------------------------

    suspend fun me(): AbsUser = json.decodeFromString(exec(req("/api/me").build()))

    suspend fun libraries(): List<Library> =
        json.decodeFromString<LibrariesResponse>(exec(req("/api/libraries").build())).libraries

    suspend fun libraryItems(libraryId: String, page: Int, limit: Int = 30): LibraryItemsResponse =
        json.decodeFromString(
            exec(req("/api/libraries/$libraryId/items?limit=$limit&page=$page&sort=media.metadata.title&minified=1").build())
        )

    suspend fun itemsInProgress(): List<LibraryItem> =
        json.decodeFromString<ItemsInProgressResponse>(exec(req("/api/me/items-in-progress?limit=25").build())).libraryItems

    suspend fun item(id: String): LibraryItem =
        json.decodeFromString(exec(req("/api/items/$id?expanded=1").build()))

    // --- Fortschritt & Sessions ----------------------------------------------

    /** null, wenn auf dem Server noch kein Fortschritt existiert. */
    suspend fun progress(itemId: String): MediaProgress? = try {
        json.decodeFromString<MediaProgress>(exec(req("/api/me/progress/$itemId").build()))
    } catch (e: ApiException) {
        if (e.code == 404) null else throw e
    }

    suspend fun updateProgress(itemId: String, currentTime: Double, duration: Double, isFinished: Boolean) {
        val p = when {
            isFinished -> 1.0
            duration > 0 -> (currentTime / duration).coerceIn(0.0, 1.0)
            else -> 0.0
        }
        val body = json.encodeToString(ProgressUpdate(currentTime, duration, p, isFinished)).asBody()
        exec(req("/api/me/progress/$itemId").patch(body).build())
    }

    suspend fun startSession(itemId: String): PlaybackSessionResponse {
        val body = PlayRequest(
            deviceInfo = DeviceInfo(
                deviceId = prefs.deviceId,
                clientName = "ShelfWatch (Wear OS)",
                clientVersion = BuildConfig.VERSION_NAME,
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                sdkVersion = Build.VERSION.SDK_INT,
            ),
            supportedMimeTypes = listOf(
                "audio/flac", "audio/mpeg", "audio/mp4", "audio/ogg", "audio/aac", "audio/webm", "audio/x-m4b",
            ),
        )
        val text = exec(req("/api/items/$itemId/play").post(json.encodeToString(body).asBody()).build())
        return json.decodeFromString(text)
    }

    suspend fun syncSession(sessionId: String, currentTime: Double, timeListened: Double, duration: Double) {
        val body = json.encodeToString(SyncRequest(currentTime, timeListened, duration)).asBody()
        exec(req("/api/session/$sessionId/sync").post(body).build())
    }

    suspend fun closeSession(sessionId: String, currentTime: Double, timeListened: Double, duration: Double) {
        val body = json.encodeToString(SyncRequest(currentTime, timeListened, duration)).asBody()
        exec(req("/api/session/$sessionId/close").post(body).build())
    }

    // --- URLs -----------------------------------------------------------------

    fun coverUrl(itemId: String, width: Int = 200) = "$baseUrl/api/items/$itemId/cover?width=$width&format=jpeg"
    fun fileUrl(itemId: String, ino: String) = "$baseUrl/api/items/$itemId/file/$ino"
    fun downloadUrl(itemId: String, ino: String) = "$baseUrl/api/items/$itemId/file/$ino/download"
}
