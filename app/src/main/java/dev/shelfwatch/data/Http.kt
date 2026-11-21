package dev.shelfwatch.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** Header-Marker: Anfrage ohne Bearer-Token senden (Login, Ping). Wird vor dem Senden entfernt. */
const val NO_AUTH = "X-ShelfWatch-NoAuth"

fun buildHttpClient(
    prefs: Prefs,
    apiProvider: () -> AbsApi,
    onAuthExpired: () -> Unit,
): OkHttpClient {
    val plain = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    return plain.newBuilder()
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(AuthInterceptor(prefs))
        .authenticator(TokenAuthenticator(prefs, plain, apiProvider, onAuthExpired))
        .build()
}

private class AuthInterceptor(private val prefs: Prefs) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (req.header(NO_AUTH) != null) {
            return chain.proceed(req.newBuilder().removeHeader(NO_AUTH).build())
        }
        val token = prefs.accessToken
        val serverHost = prefs.serverUrl.toHttpUrlOrNull()?.host
        if (token.isNullOrEmpty() || req.header("Authorization") != null || serverHost != req.url.host) {
            return chain.proceed(req)
        }
        return chain.proceed(req.newBuilder().header("Authorization", "Bearer $token").build())
    }
}

/** Erneuert abgelaufene JWT-Access-Tokens (ABS >= 2.26) über den Refresh-Token. */
private class TokenAuthenticator(
    private val prefs: Prefs,
    private val plain: OkHttpClient,
    private val apiProvider: () -> AbsApi,
    private val onAuthExpired: () -> Unit,
) : Authenticator {
    private val lock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.priorResponse != null) return null // nur ein Versuch
        val used = response.request.header("Authorization")?.removePrefix("Bearer ") ?: return null
        synchronized(lock) {
            val current = prefs.accessToken
            if (!current.isNullOrEmpty() && current != used) {
                // anderer Thread hat bereits erneuert
                return response.request.newBuilder().header("Authorization", "Bearer $current").build()
            }
            val refresh = prefs.refreshToken
            if (refresh.isNullOrEmpty()) {
                onAuthExpired()
                return null
            }
            return when (val r = apiProvider().refreshTokensBlocking(plain, refresh)) {
                is RefreshResult.Ok -> {
                    prefs.accessToken = r.accessToken
                    r.refreshToken?.let { prefs.refreshToken = it }
                    response.request.newBuilder().header("Authorization", "Bearer ${r.accessToken}").build()
                }
                RefreshResult.Rejected -> {
                    onAuthExpired()
                    null
                }
                RefreshResult.NetworkError -> null
            }
        }
    }
}

sealed interface RefreshResult {
    data class Ok(val accessToken: String, val refreshToken: String?) : RefreshResult
    data object Rejected : RefreshResult
    data object NetworkError : RefreshResult
}

/** Abbrechbarer OkHttp-Aufruf für Coroutines. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, value, _ -> value.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
