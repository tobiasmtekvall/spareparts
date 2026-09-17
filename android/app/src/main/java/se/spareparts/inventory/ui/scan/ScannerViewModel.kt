package se.spareparts.inventory.ui.scan

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import se.spareparts.inventory.data.Part
import se.spareparts.inventory.data.Repository
import kotlin.coroutines.cancellation.CancellationException

enum class ScanSource { BARCODE, LABEL, MANUAL }

sealed interface ScanState {
    data object Scanning : ScanState
    data class Looking(val source: ScanSource) : ScanState
    data class Choose(val text: String, val parts: List<Part>, val source: ScanSource) : ScanState
    data class NoMatch(val text: String, val source: ScanSource, val note: String? = null) : ScanState
}

sealed interface ScanEvent {
    data class Open(val pn: String) : ScanEvent
    data object Hit : ScanEvent
}

class ScannerViewModel(private val repo: Repository) : ViewModel() {
    private val _state = MutableStateFlow<ScanState>(ScanState.Scanning)
    val state: StateFlow<ScanState> = _state.asStateFlow()

    private val _events = Channel<ScanEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var lastValue: String? = null
    private var lastSeen = 0L

    /** True while the analyzer should skip barcode frames. */
    val paused: Boolean get() = _state.value != ScanState.Scanning

    /** Called for every barcode the analyzer sees (on a background thread). */
    fun onBarcode(raw: String) {
        val value = raw.trim()
        if (value.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            val repeat = value == lastValue && now - lastSeen < DEBOUNCE_MS
            lastValue = value
            lastSeen = now          // a code that stays in view keeps being ignored
            if (repeat || paused) return
            _state.value = ScanState.Looking(ScanSource.BARCODE)
        }
        resolve(value, ScanSource.BARCODE)
    }

    fun onLabelText(text: String) {
        if (text.isBlank()) {
            _state.value = ScanState.NoMatch("", ScanSource.LABEL, "No text found – move closer and hold steady.")
            return
        }
        resolve(text, ScanSource.LABEL)
    }

    fun startLabelRead() {
        _state.value = ScanState.Looking(ScanSource.LABEL)
    }

    fun onLabelFailed(message: String) {
        _state.value = ScanState.NoMatch("", ScanSource.LABEL, message)
    }

    fun manual(text: String) {
        if (text.isBlank()) return
        _state.value = ScanState.Looking(ScanSource.MANUAL)
        resolve(text, ScanSource.MANUAL)
    }

    private fun resolve(text: String, source: ScanSource) {
        viewModelScope.launch {
            var parts = repo.lookupLocal(text)
            var note: String? = null
            if (parts.isEmpty() && repo.canReachServer()) {
                try {
                    parts = repo.lookupRemote(text)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    note = "Server lookup failed: ${Repository.describe(e)}"
                }
            } else if (parts.isEmpty()) {
                note = "Offline – searched the cached parts only."
            }
            when (parts.size) {
                0 -> _state.value = ScanState.NoMatch(text, source, note)
                1 -> {
                    _events.send(ScanEvent.Hit)
                    _events.send(ScanEvent.Open(parts[0].pn))
                    // Stay paused until the screen comes back (see resume()).
                }
                else -> {
                    _events.send(ScanEvent.Hit)
                    _state.value = ScanState.Choose(text, parts, source)
                }
            }
        }
    }

    /** Scanner visible again: ignore whatever code is still in front of the lens for a moment. */
    fun resume() {
        synchronized(this) {
            lastSeen = SystemClock.elapsedRealtime()
            _state.value = ScanState.Scanning
        }
    }

    fun dismiss() = resume()

    companion object {
        const val DEBOUNCE_MS = 2500L
    }
}
