package dev.shelfwatch.companion

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import dev.shelfwatch.BuildConfig
import dev.shelfwatch.Graph
import dev.shelfwatch.R
import dev.shelfwatch.str
import dev.shelfwatch.data.Auth
import dev.shelfwatch.data.LoginException
import dev.shelfwatch.download.DlState
import dev.shelfwatch.download.DownloadService
import dev.shelfwatch.playback.LocalAudio
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class CHello(val app: String, val device: String, val version: String)

@Serializable
data class CNowPlaying(
    val itemId: String,
    val title: String,
    val positionSec: Double,
    val durationSec: Double,
    val playing: Boolean,
)

@Serializable
data class CStatus(
    val device: String,
    val appVersion: String,
    val loggedIn: Boolean,
    val authExpired: Boolean,
    val server: String,
    val username: String,
    val freeBytes: Long,
    val usedBytes: Long,
    val pendingSync: Int,
    val lastSyncAt: Long,
    val nowPlaying: CNowPlaying? = null,
)

@Serializable
data class CDownload(
    val itemId: String,
    val title: String,
    val author: String,
    val durationSec: Double,
    val sizeBytes: Long,
    /** complete | queued | running | waiting | failed | incomplete */
    val status: String,
    val progress: Double,
    val error: String? = null,
)

@Serializable
data class CLocalFile(val name: String, val sizeBytes: Long)

@Serializable
data class CEnqueue(val itemId: String)

@Serializable
data class CLogin(val server: String, val username: String, val password: String)

@Serializable
data class CResult(val ok: Boolean, val error: String? = null)

private const val MAX_BODY = 64 * 1024
private const val MAX_UPLOAD = 2L * 1024 * 1024 * 1024
/** Mindestens freier Speicher, der nach einem Upload übrig bleiben muss. */
private const val RESERVE_BYTES = 50L * 1024 * 1024

/**
 * Minimaler HTTP/1.1-Server (JSON, Connection: close) für die Windows-Companion-App.
 * Alle Endpunkte außer /api/hello erfordern den Header `X-ShelfWatch-Pin`.
 * Dateiuploads (POST /api/local) werden als Binärstream direkt auf die Platte geschrieben.
 */
class CompanionApi(private val context: Context, private val onRequest: (String) -> Unit) {
    private val json = Graph.json
    private val failures = ConcurrentHashMap<String, Pair<Int, Long>>()
    private val idPattern = Regex("^[A-Za-z0-9_-]{1,64}$")

    fun deviceName(): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL

    fun handle(socket: Socket) {
        socket.soTimeout = 30_000
        socket.use { s ->
            val input = BufferedInputStream(s.getInputStream())
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val target = parts[1]
            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "")
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            val ip = s.inetAddress?.hostAddress.orEmpty()
            val out = s.getOutputStream()

            // Datei-Upload: Body sind Binärdaten, daher nicht als Text lesen
            if (method == "POST" && path == "/api/local") {
                val (code, text) = try {
                    uploadLocal(headers, query, input, ip)
                } catch (e: Exception) {
                    400 to err(e.message ?: e.javaClass.simpleName)
                }
                writeResponse(out, code, text)
                return
            }

            val bodyText = if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
                readChunked(input)
            } else {
                val len = headers["content-length"]?.toIntOrNull()?.coerceIn(0, MAX_BODY) ?: 0
                String(readExactly(input, len), Charsets.UTF_8)
            }
            val (code, text) = try {
                route(method, path, headers, bodyText, ip)
            } catch (e: Exception) {
                400 to err(e.message ?: e.javaClass.simpleName)
            }
            writeResponse(out, code, text)
        }
    }

    private fun writeResponse(out: OutputStream, code: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        out.write(
            ("HTTP/1.1 $code ${reason(code)}\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
        )
        out.write(bytes)
        out.flush()
    }

    private fun route(method: String, path: String, headers: Map<String, String>, body: String, ip: String): Pair<Int, String> {
        if (method == "GET" && path == "/api/hello") {
            return 200 to json.encodeToString(CHello("ShelfWatch", deviceName(), BuildConfig.VERSION_NAME))
        }
        checkAuth(headers, ip)?.let { return it }

        return when {
            method == "GET" && path == "/api/status" -> 200 to json.encodeToString(status())
            method == "GET" && path == "/api/downloads" -> 200 to json.encodeToString(downloads())
            method == "POST" && path == "/api/downloads" -> enqueue(json.decodeFromString<CEnqueue>(body).itemId)
            method == "DELETE" && path.startsWith("/api/downloads/") -> remove(path.removePrefix("/api/downloads/"))
            method == "GET" && path == "/api/local" -> 200 to json.encodeToString(localFiles())
            method == "DELETE" && path.startsWith("/api/local/") ->
                deleteLocal(URLDecoder.decode(path.removePrefix("/api/local/"), "UTF-8"))
            method == "POST" && path == "/api/login" -> login(json.decodeFromString(body))
            method == "POST" && path == "/api/logout" -> {
                Auth.logout()
                200 to ok()
            }
            else -> 404 to err("Unknown path: $method $path")
        }
    }

    /** Prüft den Code mit Sperre nach 5 Fehlversuchen. Liefert null bei Erfolg, sonst die Fehlerantwort. */
    private fun checkAuth(headers: Map<String, String>, ip: String): Pair<Int, String>? {
        val now = SystemClock.elapsedRealtime()
        val f = failures[ip]
        if (f != null && f.second > now) return 429 to err(str(R.string.err_too_many_attempts))
        if (headers["x-shelfwatch-pin"] != Graph.prefs.companionPin) {
            val count = (f?.first ?: 0) + 1
            failures[ip] = if (count >= 5) 0 to now + 60_000 else count to 0L
            return 401 to err(str(R.string.err_wrong_code))
        }
        failures.remove(ip)
        onRequest(ip)
        return null
    }

    private fun status(): CStatus {
        val prefs = Graph.prefs
        val now = Graph.nowPlaying.value
        val sync = Graph.sync.status.value
        return CStatus(
            device = deviceName(),
            appVersion = BuildConfig.VERSION_NAME,
            loggedIn = Graph.loggedIn.value,
            authExpired = Graph.authExpired.value,
            server = prefs.serverUrl,
            username = prefs.username.orEmpty(),
            freeBytes = Graph.downloads.freeBytes(),
            usedBytes = Graph.downloads.usedBytes(),
            pendingSync = Graph.progress.pending.value,
            lastSyncAt = sync.lastSuccessAt,
            nowPlaying = now?.let {
                CNowPlaying(
                    itemId = it.itemId,
                    title = it.title,
                    positionSec = Graph.progress.get(it.itemId)?.currentTime ?: 0.0,
                    durationSec = it.duration,
                    playing = Graph.isPlaying.value,
                )
            },
        )
    }

    private fun downloads(): List<CDownload> {
        val states = DownloadService.state.value
        val items = Graph.downloads.items.value
        val ids = LinkedHashSet<String>().apply { addAll(states.keys); addAll(items.keys) }
        return ids.map { id ->
            val d = items[id]
            val s = states[id]
            val status = when {
                s?.status == DlState.Status.QUEUED -> "queued"
                s?.status == DlState.Status.RUNNING && s.waitingForNetwork -> "waiting"
                s?.status == DlState.Status.RUNNING -> "running"
                s?.status == DlState.Status.FAILED -> "failed"
                d?.complete == true -> "complete"
                else -> "incomplete"
            }
            CDownload(
                itemId = id,
                title = d?.title.orEmpty(),
                author = d?.author.orEmpty(),
                durationSec = d?.duration ?: 0.0,
                sizeBytes = d?.totalSize ?: 0L,
                status = status,
                progress = if (status == "complete") 1.0 else (s?.progress?.toDouble() ?: 0.0),
                error = s?.error,
            )
        }
    }

    private fun localFiles(): List<CLocalFile> =
        LocalAudio.list(context).map { CLocalFile(it.first, it.second) }

    /**
     * Lädt eine MP3/Opus-Datei hoch: POST /api/local?name=<Dateiname>, Body = Rohdaten.
     * Erfordert Content-Length. Geschrieben wird zuerst als .part, dann umbenannt.
     */
    private fun uploadLocal(headers: Map<String, String>, query: String, input: InputStream, ip: String): Pair<Int, String> {
        // Code vor dem Lesen des Bodys prüfen
        checkAuth(headers, ip)?.let { return it }

        val name = queryParam(query, "name")?.let { LocalAudio.sanitize(it) }
            ?: return 400 to err(str(R.string.err_local_type))
        val length = headers["content-length"]?.toLongOrNull()
            ?: return 411 to err(str(R.string.err_length_required))
        if (length <= 0 || length > MAX_UPLOAD) return 413 to err(str(R.string.err_local_too_large))
        if (length + RESERVE_BYTES > Graph.downloads.freeBytes()) return 507 to err(str(R.string.err_local_no_space))

        val dir = LocalAudio.dir(context)
        val part = java.io.File(dir, "$name.part")
        var received = 0L
        part.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            while (received < length) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), length - received).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                received += n
            }
        }
        if (received != length) {
            part.delete()
            return 400 to err(str(R.string.err_local_incomplete))
        }
        val target = java.io.File(dir, name)
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            part.delete()
            return 500 to err(str(R.string.err_local_save))
        }
        onRequest(ip)
        return 200 to ok()
    }

    private fun deleteLocal(raw: String): Pair<Int, String> {
        val file = LocalAudio.fileFor(context, raw) ?: return 400 to err(str(R.string.err_invalid_id))
        if (file.exists()) file.delete()
        return 200 to ok()
    }

    private fun queryParam(query: String, key: String): String? =
        query.split('&')
            .firstOrNull { it.substringBefore('=') == key }
            ?.substringAfter('=', "")
            ?.let { URLDecoder.decode(it, "UTF-8") }

    private fun enqueue(itemId: String): Pair<Int, String> {
        if (!idPattern.matches(itemId)) return 400 to err(str(R.string.err_invalid_id))
        if (!Graph.loggedIn.value) return 409 to err(str(R.string.err_watch_not_signed_in))
        if (Graph.downloads.isDownloaded(itemId)) return 200 to ok()
        return try {
            DownloadService.enqueue(context, itemId)
            200 to ok()
        } catch (e: Exception) {
            // Download bleibt in der Warteschlange und startet beim nächsten Öffnen der App
            409 to err(str(R.string.err_download_deferred, e.javaClass.simpleName))
        }
    }

    private fun remove(itemId: String): Pair<Int, String> {
        if (!idPattern.matches(itemId)) return 400 to err(str(R.string.err_invalid_id))
        if (DownloadService.state.value.containsKey(itemId)) DownloadService.cancel(itemId)
        Graph.downloads.delete(itemId)
        return 200 to ok()
    }

    private fun login(req: CLogin): Pair<Int, String> = try {
        runBlocking { Auth.login(req.server, req.username, req.password) }
        200 to ok()
    } catch (e: LoginException) {
        400 to err(e.message ?: str(R.string.err_login_failed))
    }

    private fun ok() = json.encodeToString(CResult(true))
    private fun err(msg: String) = json.encodeToString(CResult(false, msg))

    private fun reason(code: Int) = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        409 -> "Conflict"
        411 -> "Length Required"
        413 -> "Payload Too Large"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        507 -> "Insufficient Storage"
        else -> "Error"
    }

    private fun readExactly(input: InputStream, len: Int): ByteArray {
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) break
            off += n
        }
        return if (off == len) buf else buf.copyOf(off)
    }

    /** HTTP/1.1 chunked transfer encoding (für JSON-Anfragen). */
    private fun readChunked(input: InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        while (out.size() <= MAX_BODY) {
            val sizeLine = readLine(input) ?: break
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) {
                // Trailer bis zur Leerzeile überspringen
                while (!readLine(input).isNullOrEmpty()) { /* ignorieren */ }
                break
            }
            out.write(readExactly(input, size.coerceAtMost(MAX_BODY)))
            readLine(input) // CRLF nach dem Chunk
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) return null
            sb.append(c.toChar())
        }
    }
}
