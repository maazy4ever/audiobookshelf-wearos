package dev.shelfwatch.data

import android.content.Context
import android.content.SharedPreferences
import dev.shelfwatch.BuildConfig
import java.util.UUID

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString(K_SERVER, null) ?: BuildConfig.DEFAULT_SERVER
        set(v) { sp.edit().putString(K_SERVER, v).commit() }

    var accessToken: String?
        get() = sp.getString(K_ACCESS, null)
        set(v) { sp.edit().putString(K_ACCESS, v).commit() }

    var refreshToken: String?
        get() = sp.getString(K_REFRESH, null)
        set(v) { sp.edit().putString(K_REFRESH, v).commit() }

    var username: String?
        get() = sp.getString("username", null)
        set(v) { sp.edit().putString("username", v).apply() }

    val deviceId: String
        get() = sp.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
            sp.edit().putString("deviceId", it).apply()
        }

    var speed: Float
        get() = sp.getFloat("speed", 1f)
        set(v) { sp.edit().putFloat("speed", v).apply() }

    var skipBackSec: Int
        get() = sp.getInt("skipBack", 15)
        set(v) { sp.edit().putInt("skipBack", v).apply() }

    var skipForwardSec: Int
        get() = sp.getInt("skipForward", 30)
        set(v) { sp.edit().putInt("skipForward", v).apply() }

    var pauseOnDisconnect: Boolean
        get() = sp.getBoolean(K_PAUSE_ON_DISCONNECT, true)
        set(v) { sp.edit().putBoolean(K_PAUSE_ON_DISCONNECT, v).apply() }

    var alwaysOn: Boolean
        get() = sp.getBoolean("alwaysOn", true)
        set(v) { sp.edit().putBoolean("alwaysOn", v).apply() }

    var preferWifiDownloads: Boolean
        get() = sp.getBoolean("preferWifi", true)
        set(v) { sp.edit().putBoolean("preferWifi", v).apply() }

    /** 6-stelliger Code für die PC-Companion-App. */
    val companionPin: String
        get() = sp.getString("companionPin", null) ?: newCompanionPin()

    fun newCompanionPin(): String {
        val pin = (100_000 + java.security.SecureRandom().nextInt(900_000)).toString()
        sp.edit().putString("companionPin", pin).commit()
        return pin
    }

    var askedBatteryExemption: Boolean
        get() = sp.getBoolean("askedBattery", false)
        set(v) { sp.edit().putBoolean("askedBattery", v).apply() }

    /** Persistente Download-Warteschlange – überlebt Prozess-Neustarts. */
    var downloadQueue: List<String>
        get() = sp.getString("dlQueue", "").orEmpty().split(',').filter { it.isNotBlank() }
        set(v) { sp.edit().putString("dlQueue", v.joinToString(",")).commit() }

    @Synchronized
    fun addToDownloadQueue(id: String) {
        val q = downloadQueue
        if (id !in q) downloadQueue = q + id
    }

    @Synchronized
    fun removeFromDownloadQueue(id: String) {
        downloadQueue = downloadQueue - id
    }

    val isLoggedIn: Boolean
        get() = !accessToken.isNullOrEmpty() && serverUrl.isNotEmpty()

    fun register(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(l)

    fun unregister(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(l)

    companion object {
        const val K_SERVER = "server"
        const val K_ACCESS = "accessToken"
        const val K_REFRESH = "refreshToken"
        const val K_PAUSE_ON_DISCONNECT = "pauseOnDisconnect"
    }
}
