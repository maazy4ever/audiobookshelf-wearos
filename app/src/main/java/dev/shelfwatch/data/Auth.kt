package dev.shelfwatch.data

import dev.shelfwatch.Graph
import dev.shelfwatch.R
import dev.shelfwatch.str
import kotlinx.coroutines.CancellationException

class LoginException(message: String) : Exception(message)

/** Login/Logout – gemeinsam genutzt von der Uhr-UI und der PC-Companion-Schnittstelle. */
object Auth {

    /** Wirft [LoginException] mit verständlicher Meldung. */
    suspend fun login(serverInput: String, username: String, password: String) {
        val api = Graph.api
        val prefs = Graph.prefs
        if (serverInput.isBlank() || username.isBlank()) throw LoginException(str(R.string.err_server_user_required))
        val user = try {
            val server = resolveServer(serverInput.trim())
            api.login(server, username.trim(), password).also { prefs.serverUrl = server }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            throw LoginException(if (e.code == 401) str(R.string.err_bad_credentials) else e.message ?: str(R.string.err_login_failed))
        } catch (e: Exception) {
            throw LoginException(str(R.string.err_server_unreachable, e.message.orEmpty()))
        }
        val token = user.accessToken ?: user.token ?: throw LoginException(str(R.string.err_no_token))
        prefs.accessToken = token
        prefs.refreshToken = user.refreshToken
        prefs.username = user.username
        Graph.authExpired.value = false
        Graph.loggedIn.value = true
        Graph.sync.schedule()
    }

    fun logout() {
        Graph.prefs.accessToken = null
        Graph.prefs.refreshToken = null
        Graph.authExpired.value = false
        Graph.loggedIn.value = false
    }

    /** Ohne Schema wird zuerst https, dann http probiert. */
    private suspend fun resolveServer(input: String): String {
        val s = input.trimEnd('/')
        if (s.startsWith("http://") || s.startsWith("https://")) return s
        val candidates = listOf("https://$s", "http://$s")
        return candidates.firstOrNull { Graph.api.ping(it) } ?: candidates.first()
    }
}
