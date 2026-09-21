package se.spareparts.inventory.data

import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Somewhere a small piece of state is kept: a file on the phone, memory in tests. */
interface Snapshot<T> {
    suspend fun read(): T?
    suspend fun write(value: T)
}

/** Crash-safe JSON file (write-to-temp + rename via [AtomicFile]). */
class JsonFile<T>(file: File, private val serializer: KSerializer<T>, private val json: Json) : Snapshot<T> {
    private val atomic = AtomicFile(file)
    private val mutex = Mutex()

    override suspend fun read(): T? = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching { json.decodeFromString(serializer, atomic.readFully().decodeToString()) }.getOrNull()
        }
    }

    override suspend fun write(value: T) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val bytes = json.encodeToString(serializer, value).encodeToByteArray()
            val out = atomic.startWrite()
            try {
                out.write(bytes)
                atomic.finishWrite(out)
            } catch (t: Throwable) {
                atomic.failWrite(out)
                throw t
            }
        }
    }
}

/**
 * The offline queue of stock changes that have not reached the server yet.
 *
 * Kept apart from the session on purpose: signing out, or a token the server rejects, must never
 * discard work somebody already did on the shop floor. Pure Kotlin – unit tested.
 */
class PendingQueue(private val store: Snapshot<List<PendingAdjustment>>) {
    private val _items = MutableStateFlow<List<PendingAdjustment>>(emptyList())
    val items: StateFlow<List<PendingAdjustment>> =
        _items.asStateFlow()

    val value: List<PendingAdjustment> get() = _items.value
    val size: Int get() = _items.value.size
    val isEmpty: Boolean get() = _items.value.isEmpty()

    suspend fun load() {
        _items.value = store.read().orEmpty()
    }

    suspend fun add(item: PendingAdjustment) = set(_items.value + item)

    suspend fun remove(ids: Set<String>) = set(_items.value.filterNot { it.id in ids })

    suspend fun clear() = set(emptyList())

    private suspend fun set(list: List<PendingAdjustment>) {
        _items.value = list
        store.write(list)
    }
}

/** Keeps a value in memory only – used by the unit tests. */
class MemorySnapshot<T>(private var value: T? = null) : Snapshot<T> {
    override suspend fun read(): T? = value
    override suspend fun write(value: T) {
        this.value = value
    }
}
