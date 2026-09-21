package se.spareparts.inventory.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Server answered with an error status. [message] is the server's `error` text when present. */
class ApiException(val code: Int, message: String) : IOException(message)

/** Server URL missing or malformed – not a connectivity problem. */
class NotConfiguredException(message: String) : IOException(message)

/**
 * Where to talk to and with which token. The token is the session token from `POST /api/login`
 * or a shared device token; it goes in the `X-Api-Key` header. There is no `X-User` any more –
 * the server knows who the token belongs to.
 */
data class ServerConfig(val baseUrl: String, val token: String = "")

/**
 * Thin, dependency-light client for the inventory server's JSON API.
 * No Android classes here so it can be exercised from JVM tests.
 */
class ApiClient(
    private val config: () -> ServerConfig,
    client: OkHttpClient? = null,
) {
    val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }

    private val http: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun baseUrl(): HttpUrl {
        val raw = config().baseUrl.trim().trimEnd('/')
        if (raw.isEmpty()) throw NotConfiguredException("Server URL not set")
        val withScheme = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "http://$raw"
        return withScheme.toHttpUrlOrNull() ?: throw NotConfiguredException("Invalid server URL: $raw")
    }

    /** Resolves a server-relative URL (e.g. `/files/...`) against the configured base. */
    fun resolve(url: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        val base = baseUrl()
        return base.resolve(url)?.toString() ?: (base.toString().trimEnd('/') + "/" + url.trimStart('/'))
    }

    private fun url(path: String, query: Map<String, String> = emptyMap()): HttpUrl {
        val b = baseUrl().newBuilder()
        path.trim('/').split('/').forEach { b.addPathSegment(it) }
        query.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build()
    }

    private fun partUrl(pn: String, vararg tail: String): HttpUrl {
        val b = baseUrl().newBuilder().addPathSegment("api").addPathSegment("parts").addPathSegment(pn)
        tail.forEach { b.addPathSegment(it) }
        return b.build()
    }

    private suspend fun call(url: HttpUrl, method: String = "GET", body: JsonElement? = null,
                             auth: Boolean = true): String =
        withContext(Dispatchers.IO) {
            val cfg = config()
            val rb = Request.Builder().url(url).header("Accept", "application/json")
            if (auth && cfg.token.isNotBlank()) rb.header("X-Api-Key", sanitizeHeader(cfg.token))
            val reqBody = body?.let { json.encodeToString(JsonElement.serializer(), it).toRequestBody(jsonType) }
            rb.method(method, reqBody)
            http.newCall(rb.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = runCatching {
                        json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.contentOrNull
                    }.getOrNull() ?: "HTTP ${resp.code}"
                    throw ApiException(resp.code, msg)
                }
                text
            }
        }

    /** HTTP headers must be ISO-8859-1 printable; strip anything else rather than crash. */
    private fun sanitizeHeader(s: String) = s.trim().filter { it in ' '..'~' || it in ' '..'ÿ' }

    suspend fun ping(): PingResponse = json.decodeFromString(call(url("api/ping")))

    // ------------------------------------------------------------------ accounts

    /**
     * Signs in. Needs no token of its own; the token it returns is used for everything else.
     * A wrong password, a disabled account or too many attempts come back as a 401 with a
     * human-readable message in [ApiException.message].
     */
    suspend fun login(username: String, password: String): LoginResponse {
        val body = buildJsonObject {
            put("username", username.trim())
            put("password", password)
        }
        return json.decodeFromString(call(url("api/login"), "POST", body, auth = false))
    }

    /** Who the current token belongs to, with the permissions the server will enforce. */
    suspend fun me(): MeResponse = json.decodeFromString(call(url("api/me")))

    /** Changes the signed-in person's own password. 403 when [current] is wrong. */
    suspend fun changePassword(current: String, new: String) {
        val body = buildJsonObject {
            put("current", current)
            put("new", new)
        }
        call(url("api/me"), "POST", body)
    }

    suspend fun parts(since: Long?): PartsResponse {
        val q = if (since != null && since > 0) mapOf("since" to since.toString()) else emptyMap()
        return json.decodeFromString(call(url("api/parts", q)))
    }

    suspend fun part(pn: String): PartDetail {
        val el = json.parseToJsonElement(call(partUrl(pn))).jsonObject
        val part = json.decodeFromJsonElement<Part>(JsonObject(el - "files" - "qr"))
        val files = (el["files"] as? JsonArray)?.let { json.decodeFromJsonElement<List<PartFile>>(it) }.orEmpty()
        val qr = el["qr"]?.jsonPrimitive?.contentOrNull
        return PartDetail(part, files, qr)
    }

    suspend fun lookup(text: String): List<Part> =
        json.decodeFromString(call(url("api/lookup", mapOf("q" to text))))

    suspend fun low(): List<Part> = json.decodeFromString(call(url("api/low")))

    suspend fun movements(pn: String): List<Movement> = json.decodeFromString(call(partUrl(pn, "movements")))

    suspend fun adjust(pn: String, delta: Double?, set: Double?, reason: String, user: String): Part {
        val body = buildJsonObject {
            if (set != null) put("set", set) else put("delta", delta ?: 0.0)
            put("reason", reason)
            put("user", user)
            put("source", "android")
        }
        return json.decodeFromString(call(partUrl(pn, "adjust"), "POST", body))
    }

    suspend fun adjustBatch(items: List<PendingAdjustment>): BatchResponse {
        val body = buildJsonObject {
            put("items", buildJsonArray {
                items.forEach { a ->
                    add(buildJsonObject {
                        put("id", a.id)
                        put("pn", a.pn)
                        if (a.setTo != null) put("set", a.setTo) else put("delta", a.delta ?: 0.0)
                        put("reason", a.reason)
                        put("user", a.user)
                        put("source", a.source)
                    })
                }
            })
        }
        return json.decodeFromString(call(url("api/adjust"), "POST", body))
    }

    suspend fun patch(pn: String, changes: JsonObject): Part =
        json.decodeFromString(call(partUrl(pn), "PATCH", changes))
}
