package se.spareparts.inventory.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import se.spareparts.inventory.data.AdjustOutcome
import se.spareparts.inventory.data.Movement
import se.spareparts.inventory.data.Part
import se.spareparts.inventory.data.PartFile
import se.spareparts.inventory.data.Repository
import se.spareparts.inventory.data.qty
import kotlin.coroutines.cancellation.CancellationException

data class DetailExtras(
    val loading: Boolean = true,
    val refreshed: Boolean = false,
    val error: String? = null,
    val files: List<PartFile> = emptyList(),
    val qr: String? = null,
    val movements: List<Movement>? = null,
    val busy: Boolean = false,
)

class PartDetailViewModel(private val repo: Repository, val pn: String) : ViewModel() {

    val part: StateFlow<Part?> = repo.parts.map { list -> list.firstOrNull { it.pn.equals(pn, true) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, repo.part(pn))

    val pending: StateFlow<Int> = repo.pending.map { q -> q.count { it.pn.equals(pn, true) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private val _extras = MutableStateFlow(DetailExtras())
    val extras: StateFlow<DetailExtras> = _extras.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _extras.update { it.copy(loading = true) }
            if (!repo.canReachServer()) {
                _extras.update { it.copy(loading = false, error = "Offline – showing cached data") }
                return@launch
            }
            try {
                val d = repo.detail(pn)
                _extras.update { it.copy(files = d.files, qr = d.qr, refreshed = true, error = null) }
                val mv = runCatching { repo.movements(d.part.pn) }.getOrNull()
                _extras.update { it.copy(loading = false, movements = mv ?: it.movements) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _extras.update { it.copy(loading = false, error = Repository.describe(e)) }
            }
        }
    }

    fun adjust(delta: Double? = null, set: Double? = null, reason: String = "", label: String = "") {
        viewModelScope.launch {
            val before = part.value?.stock
            when (val r = repo.adjust(pn, delta, set, reason)) {
                is AdjustOutcome.Applied -> {
                    val what = label.ifBlank { changeText(before, r.part.stock) }
                    _messages.send("$what · now ${r.part.stock.qty()}")
                    reloadMovements()
                }
                AdjustOutcome.Queued -> _messages.send("Saved offline – will sync when connected")
                is AdjustOutcome.Rejected -> _messages.send(r.message)
            }
        }
    }

    private fun changeText(before: Double?, after: Double): String {
        val d = after - (before ?: after)
        return when {
            d > 0 -> "+${d.qty()}"
            d < 0 -> d.qty()
            else -> "Count confirmed"
        }
    }

    private fun reloadMovements() {
        viewModelScope.launch {
            runCatching { repo.movements(pn) }.onSuccess { mv -> _extras.update { it.copy(movements = mv) } }
        }
    }

    fun saveLocation(value: String) = patch("Location saved", "location" to JsonPrimitive(value.trim()))
    fun saveNotes(value: String) = patch("Notes saved", "notes" to JsonPrimitive(value.trim()))
    fun saveMin(value: Double?) = patch("Minimum saved", "min_qty" to (value?.let { JsonPrimitive(it) } ?: JsonNull))

    private fun patch(done: String, vararg fields: Pair<String, kotlinx.serialization.json.JsonElement>) {
        viewModelScope.launch {
            if (!repo.canReachServer()) {
                _messages.send("Editing needs a connection to the server")
                return@launch
            }
            _extras.update { it.copy(busy = true) }
            try {
                repo.update(pn, JsonObject(fields.toMap()))
                _messages.send(done)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send("Not saved: ${Repository.describe(e)}")
            } finally {
                _extras.update { it.copy(busy = false) }
            }
        }
    }

    fun fileUrl(url: String): String? = runCatching { repo.api.resolve(url) }.getOrNull()
    fun shareUrl(): String? = extras.value.qr ?: repo.shareUrl(pn)
}
