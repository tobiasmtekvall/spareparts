package se.spareparts.inventory.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking

data class AppSettings(
    val baseUrl: String = "",
    val apiKey: String = "",
    val user: String = "",
    val lastSync: Long = 0,
    val version: Long = 0,
    val publicUrl: String = "",
    val theme: String = THEME_DARK,
) {
    val configured: Boolean get() = baseUrl.isNotBlank()
    fun server() = ServerConfig(baseUrl, apiKey, user)

    companion object {
        const val THEME_DARK = "dark"
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
    }
}

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsStore(context: Context, scope: CoroutineScope) {
    private val ds = context.applicationContext.dataStore

    private object K {
        val url = stringPreferencesKey("base_url")
        val key = stringPreferencesKey("api_key")
        val user = stringPreferencesKey("user")
        val lastSync = longPreferencesKey("last_sync")
        val version = longPreferencesKey("version")
        val publicUrl = stringPreferencesKey("public_url")
        val theme = stringPreferencesKey("theme")
    }

    private val flow = ds.data.map { p ->
        AppSettings(
            baseUrl = p[K.url].orEmpty(),
            apiKey = p[K.key].orEmpty(),
            user = p[K.user].orEmpty(),
            lastSync = p[K.lastSync] ?: 0,
            version = p[K.version] ?: 0,
            publicUrl = p[K.publicUrl].orEmpty(),
            theme = p[K.theme] ?: AppSettings.THEME_DARK,
        )
    }

    // Small file; reading it once at startup keeps the first frame consistent.
    val settings: StateFlow<AppSettings> = flow.stateIn(scope, SharingStarted.Eagerly, runBlocking { flow.first() })

    val current: AppSettings get() = settings.value

    suspend fun saveServer(baseUrl: String, apiKey: String, user: String) {
        ds.edit {
            val oldUrl = it[K.url].orEmpty()
            it[K.url] = baseUrl.trim()
            it[K.key] = apiKey.trim()
            it[K.user] = user.trim()
            // Another server means our version number is meaningless there.
            if (oldUrl.trimEnd('/') != baseUrl.trim().trimEnd('/')) it[K.version] = 0
        }
    }

    suspend fun markSynced(version: Long, publicUrl: String?) {
        ds.edit {
            it[K.version] = version
            it[K.lastSync] = System.currentTimeMillis()
            if (!publicUrl.isNullOrBlank()) it[K.publicUrl] = publicUrl
        }
    }

    suspend fun setTheme(theme: String) {
        ds.edit { it[K.theme] = theme }
    }

    suspend fun resetVersion() {
        ds.edit { it[K.version] = 0 }
    }
}
