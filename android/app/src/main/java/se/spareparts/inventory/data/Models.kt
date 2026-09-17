package se.spareparts.inventory.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray

/** One spare part as served by the inventory server. Numbers may be null on the wire. */
@Serializable
data class Part(
    val pn: String,
    val name: String? = "",
    val category: String? = "",
    val manufacturer: String? = "",
    val model: String? = "",
    val mpn: String? = "",
    val positions: String? = "",
    val installed: Double? = null,
    val unit: String? = "",
    val price: Double? = null,
    @SerialName("qty_ordered") val qtyOrdered: Double? = null,
    @SerialName("qty_on_slip") val qtyOnSlip: Double? = null,
    @SerialName("on_hand") val onHand: Double? = 0.0,
    @SerialName("min_qty") val minQty: Double? = 0.0,
    @SerialName("packing_slip") val packingSlip: String? = "",
    @SerialName("order_no") val orderNo: String? = "",
    val location: String? = "",
    val notes: String? = "",
    @Serializable(with = SpecListSerializer::class)
    val specs: List<Spec> = emptyList(),
    val links: List<Link> = emptyList(),
) {
    val stock: Double get() = onHand ?: 0.0
    val min: Double get() = minQty ?: 0.0
    val isLow: Boolean get() = min > 0 && stock < min
    val isOut: Boolean get() = stock <= 0.0
    val positionList: List<String>
        get() = positions.orEmpty().split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
    val stockValue: Double? get() = price?.let { it * stock }
    val manufacturerLine: String
        get() = listOfNotNull(manufacturer?.takeIf { it.isNotBlank() }, model?.takeIf { it.isNotBlank() })
            .joinToString(" · ")
}

data class Spec(val key: String, val value: String)

@Serializable
data class Link(val title: String? = null, val url: String = "", val custom: Boolean? = null)

@Serializable
data class PartFile(val title: String = "", val url: String = "", val size: Long? = null)

/** Response of GET /api/parts/{pn}: a part plus server-side extras. */
data class PartDetail(val part: Part, val files: List<PartFile>, val qr: String?)

@Serializable
data class PartsResponse(
    val version: Long = 0,
    @SerialName("public_url") val publicUrl: String? = null,
    val parts: List<Part>? = null,
    val unchanged: Boolean = false,
)

@Serializable
data class PingResponse(val ok: Boolean = false, val auth: Boolean = false, val version: Long = 0)

@Serializable
data class Movement(
    val ts: Double = 0.0,
    val delta: Double? = 0.0,
    val after: Double? = null,
    val reason: String? = "",
    val user: String? = "",
    val source: String? = "",
)

/** A stock change waiting to be sent with POST /api/adjust. */
@Serializable
data class PendingAdjustment(
    val id: String,
    val pn: String,
    val delta: Double? = null,
    @SerialName("set") val setTo: Double? = null,
    val reason: String = "",
    val user: String = "",
    val source: String = "android",
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
data class BatchResult(val id: String? = null, val ok: Boolean = false, val error: String? = null)

@Serializable
data class BatchResponse(val results: List<BatchResult> = emptyList(), val version: Long = 0)

/** specs are `[[key, value], ...]` on the wire; tolerate odd shapes and non-string values. */
object SpecListSerializer : KSerializer<List<Spec>> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): List<Spec> {
        val el = (decoder as JsonDecoder).decodeJsonElement()
        if (el !is JsonArray) return emptyList()
        return el.mapNotNull { item ->
            when (item) {
                is JsonArray -> {
                    val k = item.getOrNull(0)?.asText().orEmpty()
                    val v = item.drop(1).joinToString(" ") { it.asText() }
                    if (k.isBlank() && v.isBlank()) null else Spec(k, v)
                }
                is JsonPrimitive -> item.contentOrNull?.let { Spec(it, "") }
                else -> null
            }
        }
    }

    override fun serialize(encoder: Encoder, value: List<Spec>) {
        (encoder as JsonEncoder).encodeJsonElement(buildJsonArray {
            value.forEach { add(JsonArray(listOf(JsonPrimitive(it.key), JsonPrimitive(it.value)))) }
        })
    }

    private fun JsonElement.asText(): String = when (this) {
        is JsonNull -> ""
        is JsonPrimitive -> contentOrNull.orEmpty()
        is JsonArray -> jsonArray.joinToString(" ") { it.asText() }
        else -> toString()
    }
}

/** Formats quantities without a trailing ".0". */
fun Double?.qty(): String {
    if (this == null) return "–"
    return if (this == Math.floor(this) && !this.isInfinite()) this.toLong().toString()
    else "%.2f".format(this).trimEnd('0').trimEnd('.', ',')
}
