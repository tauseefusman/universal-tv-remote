package com.example.tvremote.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.tvremote.core.KnownApp
import com.example.tvremote.core.TvDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray

/**
 * Local-only storage. Pairing credentials (client certs, tokens, client keys) are kept in
 * EncryptedSharedPreferences backed by the Android Keystore. No account, no cloud.
 */
class TvStore(context: Context) {
    private val prefs: SharedPreferences = run {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "tv_store", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val _tvs = MutableStateFlow(loadTvs())
    val tvs: StateFlow<List<TvDevice>> = _tvs

    private fun loadTvs(): List<TvDevice> {
        val raw = prefs.getString("tvs", null) ?: return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { TvDevice.fromJson(arr.getJSONObject(it)) }
    }

    private fun persist(list: List<TvDevice>) {
        prefs.edit().putString("tvs", JSONArray(list.map { it.toJson() }).toString()).apply()
        _tvs.value = list
    }

    fun upsert(tv: TvDevice) = persist(_tvs.value.filterNot { it.id == tv.id } + tv)
    fun rename(id: String, name: String) =
        persist(_tvs.value.map { if (it.id == id) it.copy(name = name) else it })
    fun updateHost(id: String, host: String) =
        persist(_tvs.value.map { if (it.id == id) it.copy(host = host) else it })
    fun forget(id: String) {
        prefs.edit().remove("cred_$id").remove("fav_$id").apply()
        persist(_tvs.value.filterNot { it.id == id })
    }

    var lastTvId: String?
        get() = prefs.getString("last_tv", null)
        set(v) { prefs.edit().putString("last_tv", v).apply() }

    var haptics: Boolean
        get() = prefs.getBoolean("haptics", true)
        set(v) { prefs.edit().putBoolean("haptics", v).apply() }

    fun credentials(id: String): String? = prefs.getString("cred_$id", null)
    fun saveCredentials(id: String, blob: String) = prefs.edit().putString("cred_$id", blob).apply()

    /** Per-TV ordered favourites. Entries are app ids (platform-specific) or "known:YOUTUBE". */
    fun favorites(id: String): List<String> {
        val raw = prefs.getString("fav_$id", null)
            ?: return KnownApp.entries.map { "known:${it.name}" }
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { arr.getString(it) }
    }

    fun setFavorites(id: String, list: List<String>) =
        prefs.edit().putString("fav_$id", JSONArray(list).toString()).apply()
}
