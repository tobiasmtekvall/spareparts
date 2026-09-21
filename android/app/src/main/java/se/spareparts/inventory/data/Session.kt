package se.spareparts.inventory.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import se.spareparts.inventory.domain.Permissions

/** The account behind the token: what `POST /api/login` and `GET /api/me` return under `user`. */
@Serializable
data class AccountUser(
    val id: Int = 0,
    val username: String = "",
    val name: String = "",
    val role: String = "",
    /** "person" or "device" (a shared scanner phone using a device token). */
    val kind: String = "person",
    val perms: List<String> = emptyList(),
    @SerialName("must_change") @Serializable(with = LooseBoolean::class) val mustChange: Boolean = false,
) {
    val displayName: String get() = name.ifBlank { username }.ifBlank { "Signed in" }
    val isDevice: Boolean get() = kind.equals("device", ignoreCase = true)
    val permissions: Permissions get() = Permissions.of(perms)

    /** Short label for the role chip. */
    val roleLabel: String get() = when (role.lowercase()) {
        "admin" -> "Admin"
        "manager" -> "Manager"
        "staff" -> "Staff"
        "viewer" -> "Viewer"
        else -> role.ifBlank { if (isDevice) "Device" else "Account" }
    }

    /** What the role means, matching the wording on the server. */
    val roleText: String get() = when (role.lowercase()) {
        "admin" -> "Everything, including accounts"
        "manager" -> "Edit parts, import, delete"
        "staff" -> "Book stock, set location and notes"
        "viewer" -> "Read only"
        else -> permissions.toString()
    }
}

/** A signed-in session: the token plus who it belongs to. Persisted between app starts. */
@Serializable
data class Session(
    val token: String = "",
    /** Unix seconds; 0 when unknown (device tokens do not expire). */
    val expires: Long = 0,
    val user: AccountUser = AccountUser(),
    /** true when the token was typed in as a shared device token instead of a personal sign-in. */
    val device: Boolean = false,
) {
    val valid: Boolean get() = token.isNotBlank()
    val permissions: Permissions get() = user.permissions

    /** Locally visible expiry. The server decides for real; this only avoids a pointless round trip. */
    fun expired(nowSeconds: Long = System.currentTimeMillis() / 1000): Boolean = expires in 1 until nowSeconds
}

@Serializable
data class LoginResponse(
    val token: String = "",
    val expires: Long = 0,
    val user: AccountUser = AccountUser(),
) {
    fun toSession(device: Boolean = false) = Session(token, expires, user, device)
}

@Serializable
data class RoleOption(val id: String = "", val text: String = "")

@Serializable
data class MeResponse(
    val user: AccountUser = AccountUser(),
    val roles: List<RoleOption> = emptyList(),
    val mode: String = "",
    @SerialName("can_manage_users") val canManageUsers: Boolean = false,
)

/** Persistence for the session – a file on the phone in the app, memory in tests. */
interface SessionPersistence {
    suspend fun load(): Session?
    suspend fun save(session: Session?)
}

/**
 * Holds the signed-in session.
 *
 * The offline queue is deliberately NOT touched here: a token that the server no longer accepts
 * must never throw away stock changes that were booked but not yet sent.
 */
class SessionManager(private val store: SessionPersistence) {

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    /** Message to show on the sign-in screen, e.g. after the token was rejected. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    val current: Session? get() = _session.value
    val token: String get() = _session.value?.token.orEmpty()
    val signedIn: Boolean get() = token.isNotBlank()
    val user: AccountUser? get() = _session.value?.user
    val perms: Permissions get() = _session.value?.permissions ?: Permissions.NONE

    /** Reads the stored session. Call once at start-up. */
    suspend fun load() {
        _session.value = store.load()?.takeIf { it.valid && !it.expired() }
    }

    suspend fun signIn(session: Session) {
        _notice.value = null
        _session.value = session
        store.save(session)
    }

    /** Fresh details from `GET /api/me`, so a role change takes effect without reinstalling. */
    suspend fun updateUser(user: AccountUser) {
        val s = _session.value ?: return
        if (s.user == user) return
        val next = s.copy(user = user)
        _session.value = next
        store.save(next)
    }

    /** Drops the token. The pending queue is left alone on purpose. */
    suspend fun signOut(notice: String? = null) {
        _session.value = null
        _notice.value = notice
        store.save(null)
    }

    /**
     * Called for every failed API call. A 401 means the token expired or the account was disabled
     * or had its password reset: end the session and ask for a new sign-in.
     * Returns true when this error ended the session.
     */
    suspend fun onApiError(e: Throwable): Boolean {
        if (e !is ApiException || e.code != 401) return false
        if (_session.value == null) return true
        signOut(SESSION_ENDED)
        return true
    }

    fun clearNotice() {
        _notice.value = null
    }

    companion object {
        const val SESSION_ENDED = "Your session ended - please sign in again"
    }
}

/** `must_change` comes back as 0/1 from SQLite and as true/false elsewhere. Accept both. */
object LooseBoolean : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LooseBoolean", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean {
        val el = (decoder as? JsonDecoder)?.decodeJsonElement() as? JsonPrimitive ?: return decoder.decodeBoolean()
        el.booleanOrNull?.let { return it }
        el.intOrNull?.let { return it != 0 }
        return el.contentOrNull?.lowercase() in setOf("true", "1", "yes")
    }

    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)
}
