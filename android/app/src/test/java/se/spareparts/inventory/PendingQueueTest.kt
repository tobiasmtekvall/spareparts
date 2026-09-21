package se.spareparts.inventory

import org.junit.Assert.assertEquals
import org.junit.Test
import se.spareparts.inventory.data.ApiClient
import se.spareparts.inventory.data.Part
import se.spareparts.inventory.data.PendingAdjustment
import se.spareparts.inventory.data.Repository
import se.spareparts.inventory.data.ServerConfig
import se.spareparts.inventory.data.qty

class PendingQueueTest {
    private val parts = listOf(Part("A", onHand = 5.0), Part("B", onHand = null), Part("C", onHand = 1.0))

    @Test fun deltasAndSetsApplyInOrder() {
        val q = listOf(
            PendingAdjustment("1", "A", delta = -2.0),
            PendingAdjustment("2", "B", delta = 3.0),
            PendingAdjustment("3", "A", setTo = 10.0),
            PendingAdjustment("4", "A", delta = 1.0),
        )
        val out = Repository.applyPending(parts, q).associate { it.pn to it.onHand }
        assertEquals(11.0, out["A"])
        assertEquals(3.0, out["B"])
        assertEquals(1.0, out["C"])
    }

    @Test fun partsParseWithNullsAndOddSpecs() {
        val api = ApiClient({ ServerConfig("") })
        val json = """{"version":7,"public_url":"http://x","parts":[
            {"pn":"P1","name":"n","installed":null,"price":null,"on_hand":2.0,"min_qty":null,
             "specs":[["Width","800"],["Voltage",24],["Solo"]],"links":[{"title":"t","url":"u","custom":true}],
             "extra_field":"ignored"}]}"""
        val resp = api.json.decodeFromString(se.spareparts.inventory.data.PartsResponse.serializer(), json)
        val p = resp.parts!!.single()
        assertEquals(7L, resp.version)
        assertEquals(listOf("Width" to "800", "Voltage" to "24", "Solo" to ""), p.specs.map { it.key to it.value })
        assertEquals(0.0, p.min, 0.0)
        assertEquals(false, p.isLow)
        // round-trip through the cache format
        val again = api.json.decodeFromString(Part.serializer(), api.json.encodeToString(Part.serializer(), p))
        assertEquals(p, again)
    }

    @Test fun unchangedResponse() {
        val api = ApiClient({ ServerConfig("") })
        val r = api.json.decodeFromString(se.spareparts.inventory.data.PartsResponse.serializer(), """{"version":3,"unchanged":true}""")
        assertEquals(true, r.unchanged)
        assertEquals(null, r.parts)
    }

    @Test fun quantityFormatting() {
        assertEquals("3", 3.0.qty())
        assertEquals("2.5", 2.5.qty())
        assertEquals("–", (null as Double?).qty())
    }

    @Test fun resolvesRelativeFileUrls() {
        val api = ApiClient({ ServerConfig("192.168.1.20:8765/") })
        assertEquals("http://192.168.1.20:8765/files/Belts/A1%20-%20x/manual.pdf",
            api.resolve("/files/Belts/A1%20-%20x/manual.pdf"))
        assertEquals("https://example.com/a", api.resolve("https://example.com/a"))
    }
}
