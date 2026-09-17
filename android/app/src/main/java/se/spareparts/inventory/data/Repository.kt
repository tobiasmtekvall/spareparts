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
    private val connectivity: ConnectivityMonitor,
    private val scope: CoroutineScope,
    private val scheduleFlush: () -> Unit,
) {
    val api = ApiClient({ settings.current.server() })

    private val cacheFile = JsonFile(File(filesDir, "parts-cache.json"), CachedParts.serializer(), api.json)
    private val queueFile = JsonFile(File(filesDir, "pending-adjustments.json"),
        ListSerializer(PendingAdjustment.serializer()), api.json)

    private val base = MutableStateFlow(CachedParts())
    private val _pending = MutableStateFlow<List<PendingAdjustment>>(emptyList())
    val pending: StateFlow<List<PendingAdjustment>> = _pending.asStateFlow()

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val online: StateFlow<Boolean> get() = connectivity.online

    val parts: StateFlow<List<Part>> = combine(base, _pending) { b, q -> applyPending(b.parts, q) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val categories: StateFlow<List<String>> = combine(base, _pending) { b, _ ->
        b.parts.mapNotNull { it.category?.takeIf(String::isNotBlank) }.distinct().sorted()
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val syncMutex = Mutex()

    init {
        scope.launch {
            cacheFile.read()?.let { base.value = it }
            queueFile.read()?.let { _pending.value = it }
            _loaded.value = true
        }
        connectivity.onBecameOnline = { scope.launch { sync() } }
    }

    private suspend fun awaitLoaded() {
        if (!_loaded.value) _loaded.first { it }
    }

    fun part(pn: String): Part? = parts.value.firstOrNull { it.pn.equals(pn, ignoreCase = true) }

    // ------------------------------------------------------------------ sync

    /** Sends queued changes, then pulls whatever changed since our version. Safe to call often. */
    suspend fun sync(full: Boolean = false): Boolean {
        awaitLoaded()
        if (!settings.current.configured) {
            _status.update { it.copy(error = "Set the server URL in Settings") }
            return false
        }
        return syncMutex.withLock {
            _status.update { it.copy(syncing = true) }
            try {
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
                _status.value = SyncStatus(reachable = e is ApiException, error = describe(e))
                false
            }
        }
    }

    /** Flush the offline queue. Returns true when nothing is left that could be retried. */
    suspend fun flush(): Boolean {
        awaitLoaded()
        if (_pending.value.isEmpty()) return true
        if (!settings.current.configured) return false
        return syncMutex.withLock {
            try {
                flushLocked(); true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.update { it.copy(reachable = e is ApiException, error = describe(e)) }
                false
            }
        }
    }

    private suspend fun flushLocked() {
        val batch = _pending.value
        if (batch.isEmpty()) return
        val resp = api.adjustBatch(batch)
        val done = resp.results.mapNotNull { it.id }.toSet()
        val okIds = resp.results.filter { it.ok }.mapNotNull { it.id }.toSet()
        // Fold the accepted changes into the snapshot so the UI does not flicker before the pull.
        val accepted = batch.filter { it.id in okIds }
        if (accepted.isNotEmpty()) {
            base.update { it.copy(parts = applyPending(it.parts, accepted)) }
        }
        setPending(_pending.value.filterNot { it.id in done })
        resp.results.filter { !it.ok }.forEach { r ->
            val item = batch.firstOrNull { it.id == r.id }
            _messages.tryEmit("${item?.pn ?: "?"}: change rejected – ${r.error ?: "unknown error"}")
        }
    }

    private suspend fun setPending(list: List<PendingAdjustment>) {
        _pending.value = list
        queueFile.write(list)
    }

    // --------------------------------------------------------------- stock

    suspend fun adjust(pn: String, delta: Double? = null, set: Double? = null, reason: String = ""): AdjustOutcome {
        awaitLoaded()
        val current = part(pn) ?: return AdjustOutcome.Rejected("Unknown part $pn")
        val after = set ?: (current.stock + (delta ?: 0.0))
        if (after < 0) return AdjustOutcome.Rejected("Only ${current.stock.qty()} in stock")
        if (set == null && (delta ?: 0.0) == 0.0) return AdjustOutcome.Rejected("Nothing to change")
        val user = settings.current.user

        if (settings.current.configured && connectivity.online.value && _pending.value.isEmpty()) {
            try {
                val updated = api.adjust(current.pn, delta, set, reason, user)
                replaceInBase(updated)
                _status.update { it.copy(reachable = true, error = null) }
                return AdjustOutcome.Applied(updated)
            } catch (e: ApiException) {
                return AdjustOutcome.Rejected(e.message ?: "Rejected by server")
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                _status.update { it.copy(reachable = false, error = describe(e)) }
                // fall through to the offline queue
            }
        }
        val item = PendingAdjustment(
            id = UUID.randomUUID().toString(), pn = current.pn,
            delta = if (set == null) delta else null, setTo = set, reason = reason, user = user,
        )
        setPending(_pending.value + item)
        scheduleFlush()
        if (connectivity.online.value) scope.launch { flush() }
        return AdjustOutcome.Queued
    }

    suspend fun discardPending() = setPending(emptyList())

    // ------------------------------------------------------------- details

    suspend fun detail(pn: String): PartDetail {
        val d = api.part(pn)
        replaceInBase(d.part)
        _status.update { it.copy(reachable = true) }
        return d
    }

    suspend fun movements(pn: String): List<Movement> = api.movements(pn)

    suspend fun update(pn: String, changes: JsonObject): Part {
        val p = api.patch(pn, changes)
        replaceInBase(p)
        return p
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
        api.lookup(text).map { remote -> part(remote.pn) ?: remote }

    fun canReachServer(): Boolean = settings.current.configured && connectivity.online.value

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
            is ApiException -> if (e.code == 401) "API key rejected" else e.message ?: "Server error ${e.code}"
            is NotConfiguredException -> e.message ?: "Server not configured"
            is java.net.UnknownHostException -> "Server not found"
            is java.net.SocketTimeoutException -> "Server did not respond"
            is java.net.ConnectException -> "Cannot connect to server"
            is IOException -> e.message ?: "Network error"
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}
