package se.spareparts.inventory.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import se.spareparts.inventory.domain.PartMatcher
import se.spareparts.inventory.domain.Permissions
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

@Serializable
data class CachedParts(val version: Long = 0, val publicUrl: String? = null, val parts: List<Part> = emptyList())

data class SyncStatus(
    val syncing: Boolean = false,
    /** null = unknown yet, true = last request reached the server. */
    val reachable: Boolean? = null,
    val error: String? = null,
)

sealed interface AdjustOutcome {
    data class Applied(val part: Part) : AdjustOutcome
    data object Queued : AdjustOutcome
    data class Rejected(val message: String) : AdjustOutcome
}

/**
 * Single source of truth for parts. The on-disk cache holds the last server snapshot; the list the
 * UI sees is that snapshot with not-yet-sent stock changes applied on top (optimistic updates).
 */
class Repository(
    filesDir: File,
    private val settings: SettingsStore,
    private val session: SessionManager,
    private val connectivity: ConnectivityMonitor,
    private val scope: CoroutineScope,
    private val scheduleFlush: () -> Unit,
) {
    val api = ApiClient({ ServerConfig(settings.current.baseUrl, session.token) })

    private val cacheFile = JsonFile(File(filesDir, "parts-cache.json"), CachedParts.serializer(), api.json)
    private val queue = PendingQueue(JsonFile(File(filesDir, "pending-adjustments.json"),
        ListSerializer(PendingAdjustment.serializer()), api.json))

    private val base = MutableStateFlow(CachedParts())
    val pending: StateFlow<List<PendingAdjustment>> = queue.items

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val online: StateFlow<Boolean> get() = connectivity.online

    val parts: StateFlow<List<Part>> = combine(base, pending) { b, q -> applyPending(b.parts, q) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val categories: StateFlow<List<String>> = combine(base, pending) { b, _ ->
        b.parts.mapNotNull { it.category?.takeIf(String::isNotBlank) }.distinct().sorted()
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val syncMutex = Mutex()

    init {
        scope.launch {
            cacheFile.read()?.let { base.value = it }
            queue.load()
            _loaded.value = true
        }
        connectivity.onBecameOnline = { scope.launch { sync() } }
    }

    private suspend fun awaitLoaded() {
        if (!_loaded.value) _loaded.first { it }
    }

    fun part(pn: String): Part? = parts.value.firstOrNull { it.pn.equals(pn, ignoreCase = true) }

    /** What the signed-in account may do. The server enforces it; the UI only follows. */
    val perms: Permissions get() = session.perms

    // ------------------------------------------------------------------ sync

    /** Sends queued changes, then pulls whatever changed since our version. Safe to call often. */
    suspend fun sync(full: Boolean = false): Boolean {
        awaitLoaded()
        if (!settings.current.configured) {
            _status.update { it.copy(error = "Set the server URL in Settings") }
            return false
        }
        if (!session.signedIn) {
            _status.update { it.copy(syncing = false, error = "Not signed in") }
            return false
        }
        return syncMutex.withLock {
            _status.update { it.copy(syncing = true) }
            try {
                // Re-read the account every sync so a changed role takes effect straight away.
                session.updateUser(api.me().user)
                flushLocked()
                val since = if (full || base.value.parts.isEmpty()) null else settings.current.version
                val resp = api.parts(since)
                if (!resp.unchanged && resp.parts != null) {
                    val snapshot = CachedParts(resp.version, resp.publicUrl, resp.parts)
                    base.value = snapshot
                    cacheFile.write(snapshot)
                }
                settings.markSynced(resp.version, resp.publicUrl)
                _status.value = SyncStatus(reachable = true)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A 401 ends the session (the queue is kept) and sends us back to the sign-in screen.
                val signedOut = session.onApiError(e)
                _status.value = SyncStatus(
                    reachable = e is ApiException,
                    error = if (signedOut) SessionManager.SESSION_ENDED else describe(e),
                )
                false
            }
        }
    }

    /** Flush the offline queue. Returns true when nothing is left that could be retried. */
    suspend fun flush(): Boolean {
        awaitLoaded()
        if (queue.isEmpty) return true
        if (!settings.current.configured || !session.signedIn) return false
        return syncMutex.withLock {
            try {
                flushLocked(); true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val signedOut = session.onApiError(e)
                _status.update {
                    it.copy(reachable = e is ApiException,
                        error = if (signedOut) SessionManager.SESSION_ENDED else describe(e))
                }
                false
            }
        }
    }

    private suspend fun flushLocked() {
        val batch = queue.value
        if (batch.isEmpty()) return
        val resp = api.adjustBatch(batch)
        val done = resp.results.mapNotNull { it.id }.toSet()
        val okIds = resp.results.filter { it.ok }.mapNotNull { it.id }.toSet()
        // Fold the accepted changes into the snapshot so the UI does not flicker before the pull.
        val accepted = batch.filter { it.id in okIds }
        if (accepted.isNotEmpty()) {
            base.update { it.copy(parts = applyPending(it.parts, accepted)) }
        }
        queue.remove(done)
        resp.results.filter { !it.ok }.forEach { r ->
            val item = batch.firstOrNull { it.id == r.id }
            _messages.tryEmit("${item?.pn ?: "?"}: change rejected – ${r.error ?: "unknown error"}")
        }
    }

    // --------------------------------------------------------------- stock

    suspend fun adjust(pn: String, delta: Double? = null, set: Double? = null, reason: String = ""): AdjustOutcome {
        awaitLoaded()
        if (!perms.canAdjust) return AdjustOutcome.Rejected("Your account may not change stock")
        val current = part(pn) ?: return AdjustOutcome.Rejected("Unknown part $pn")
        val after = set ?: (current.stock + (delta ?: 0.0))
        if (after < 0) return AdjustOutcome.Rejected("Only ${current.stock.qty()} in stock")
        if (set == null && (delta ?: 0.0) == 0.0) return AdjustOutcome.Rejected("Nothing to change")
        val user = session.user?.username.orEmpty()

        if (settings.current.configured && session.signedIn && connectivity.online.value && queue.isEmpty) {
            try {
                val updated = api.adjust(current.pn, delta, set, reason, user)
                replaceInBase(updated)
                _status.update { it.copy(reachable = true, error = null) }
                return AdjustOutcome.Applied(updated)
            } catch (e: ApiException) {
                if (session.onApiError(e)) return AdjustOutcome.Rejected(SessionManager.SESSION_ENDED)
                return AdjustOutcome.Rejected(e.message ?: "Rejected by server")
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                _status.update { it.copy(reachable = false, error = describe(e)) }
                // fall through to the offline queue
            }
        }
        queue.add(PendingAdjustment(
            id = UUID.randomUUID().toString(), pn = current.pn,
            delta = if (set == null) delta else null, setTo = set, reason = reason, user = user,
        ))
        scheduleFlush()
        if (connectivity.online.value && session.signedIn) scope.launch { flush() }
        return AdjustOutcome.Queued
    }

    suspend fun discardPending() = queue.clear()

    // ------------------------------------------------------------- details

    suspend fun detail(pn: String): PartDetail {
        val d = guard { api.part(pn) }
        replaceInBase(d.part)
        _status.update { it.copy(reachable = true) }
        return d
    }

    suspend fun movements(pn: String): List<Movement> = guard { api.movements(pn) }

    suspend fun update(pn: String, changes: JsonObject): Part {
        val p = guard { api.patch(pn, changes) }
        replaceInBase(p)
        return p
    }

    /** Runs a call and ends the session if the server says the token is no longer good. */
    private suspend fun <T> guard(block: suspend () -> T): T {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            session.onApiError(e)
            throw e
        }
    }

    private suspend fun replaceInBase(p: Part) {
        val snap = base.value
        val idx = snap.parts.indexOfFirst { it.pn == p.pn }
        val list = if (idx >= 0) snap.parts.toMutableList().also { it[idx] = p } else snap.parts + p
        val next = snap.copy(parts = list)
        base.value = next
        cacheFile.write(next)
    }

    // -------------------------------------------------------------- lookup

    fun lookupLocal(text: String): List<Part> = PartMatcher.lookup(text, parts.value)

    /** Server lookup, mapped onto the local (optimistic) versions of the parts where we have them. */
    suspend fun lookupRemote(text: String): List<Part> =
        guard { api.lookup(text) }.map { remote -> part(remote.pn) ?: remote }

    fun canReachServer(): Boolean =
        settings.current.configured && session.signedIn && connectivity.online.value

    fun shareUrl(pn: String): String? {
        val s = settings.current
        val root = s.publicUrl.ifBlank { base.value.publicUrl.orEmpty() }.ifBlank { s.baseUrl }
        if (root.isBlank()) return null
        return root.trimEnd('/') + "/p/" + java.net.URLEncoder.encode(pn, "UTF-8").replace("+", "%20")
    }

    companion object {
        fun applyPending(parts: List<Part>, queue: List<PendingAdjustment>): List<Part> {
            if (queue.isEmpty()) return parts
            val byPn = queue.groupBy { it.pn }
            return parts.map { p ->
                val ops = byPn[p.pn] ?: return@map p
                var v = p.stock
                ops.forEach { op -> v = op.setTo ?: (v + (op.delta ?: 0.0)) }
                p.copy(onHand = v)
            }
        }

        fun describe(e: Throwable): String = when (e) {
            is ApiException -> when (e.code) {
                401 -> SessionManager.SESSION_ENDED
                403 -> e.message ?: "Your account is not allowed to do this"
                else -> e.message ?: "Server error ${e.code}"
            }
            is NotConfiguredException -> e.message ?: "Server not configured"
            is java.net.UnknownHostException -> "Server not found"
            is java.net.SocketTimeoutException -> "Server did not respond"
            is java.net.ConnectException -> "Cannot connect to server"
            is IOException -> e.message ?: "Network error"
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}
