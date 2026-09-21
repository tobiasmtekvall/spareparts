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
import kotlinx.serialization.json.Json

data class AppSettings(
    val baseUrl: String = "",
    val lastSync: Long = 0,
    val version: Long = 0,
    val publicUrl: String = "",
    val theme: String = THEME_DARK,
) {
    val configured: Boolean get() = baseUrl.isNotBlank()

    companion object {
        const val THEME_DARK = "dark"
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
    }
}

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * Server address, sync bookkeeping and the theme. The session (token + account) lives in
 * [SessionManager]; this class only stores it on disk through [SessionPersistence].
 */
class SettingsStore(context: Context, scope: CoroutineScope) : SessionPersistence {
    private val ds = context.applicationContext.dataStore
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private object K {
        val url = stringPreferencesKey("base_url")
        val session = stringPreferencesKey("session")
        val lastSync = longPreferencesKey("last_sync")
        val version = longPreferencesKey("version")
        val publicUrl = stringPreferencesKey("public_url")
        val theme = stringPreferencesKey("theme")
        // v1 keys, cleared on the first sign-in of v2
        val oldKey = stringPreferencesKey("api_key")
        val oldUser = stringPreferencesKey("user")
    }

    private val flow = ds.data.map { p ->
        AppSettings(
            baseUrl = p[K.url].orEmpty(),
            lastSync = p[K.lastSync] ?: 0,
            version = p[K.version] ?: 0,
            publicUrl = p[K.publicUrl].orEmpty(),
            theme = p[K.theme] ?: AppSettings.THEME_DARK,
        )
    }

    // Small file; reading it once at startup keeps the first frame consistent.
    val settings: StateFlow<AppSettings> = flow.stateIn(scope, SharingStarted.Eagerly, runBlocking { flow.first() })

    val current: AppSettings get() = settings.value

    /** Where the server is. Changing it makes our data version meaningless, so it is reset. */
    suspend fun setBaseUrl(baseUrl: String) {
        ds.edit {
            val old = it[K.url].orEmpty()
            it[K.url] = baseUrl.trim()
            if (old.trimEnd('/') != baseUrl.trim().trimEnd('/')) {
                it[K.version] = 0
                it.remove(K.publicUrl)
            }
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

    // ------------------------------------------------------------- session

    override suspend fun load(): Session? {
        val raw = ds.data.first()[K.session].orEmpty()
        if (raw.isBlank()) return null
        return runCatching { json.decodeFromString(Session.serializer(), raw) }.getOrNull()
    }

    override suspend fun save(session: Session?) {
        ds.edit {
            if (session == null) it.remove(K.session)
            else it[K.session] = json.encodeToString(Session.serializer(), session)
            // The free-text name and API key of v1 are gone.
            it.remove(K.oldKey)
            it.remove(K.oldUser)
        }
    }
}
