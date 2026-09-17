package se.spareparts.inventory.data

import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Crash-safe JSON file (write-to-temp + rename via [AtomicFile]). */
class JsonFile<T>(file: File, private val serializer: KSerializer<T>, private val json: Json) {
    private val atomic = AtomicFile(file)
    private val mutex = Mutex()

    suspend fun read(): T? = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching { json.decodeFromString(serializer, atomic.readFully().decodeToString()) }.getOrNull()
        }
    }

    suspend fun write(value: T) = withContext(Dispatchers.IO) {
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
