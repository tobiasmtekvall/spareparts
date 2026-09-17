package se.spareparts.inventory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import se.spareparts.inventory.data.ApiClient
import se.spareparts.inventory.data.ApiException
import se.spareparts.inventory.data.PendingAdjustment
import se.spareparts.inventory.data.ServerConfig
import se.spareparts.inventory.domain.PartMatcher
import java.util.UUID

/**
 * Talks to a real inventory server. Skipped unless run with
 *   ./gradlew testDebugUnitTest -Pserver=http://localhost:8765
 * Stock changes made here are reverted (net zero), but they do appear in the movement log.
 */
class ServerSmokeTest {
    private val server = System.getProperty("spareparts.server").orEmpty()
    private val api = ApiClient({ ServerConfig(server, System.getenv("INVENTORY_API_KEY").orEmpty(), "smoke-test") })

    @Test fun endToEnd() = runBlocking {
        assumeTrue("no -Pserver given", server.isNotBlank())

        val ping = api.ping()
        assertTrue(ping.ok)

        val all = api.parts(null)
        val parts = all.parts!!
        assertTrue("server has parts", parts.isNotEmpty())
        println("server v${all.version}: ${parts.size} parts, public_url=${all.publicUrl}")
        assertTrue(api.parts(all.version).unchanged || api.parts(all.version).version != all.version)

        val sample = parts.first()
        val detail = api.part(sample.pn)
        assertEquals(sample.pn, detail.part.pn)
        assertTrue(detail.qr!!.contains("/p/"))

        // Local matcher and server agree on QR payloads and raw part numbers
        assertEquals(sample.pn, api.lookup(detail.qr!!).first().pn)
        assertEquals(sample.pn, PartMatcher.lookup(detail.qr, parts).first().pn)
        assertEquals(sample.pn, api.lookup("SP:${sample.pn}").first().pn)

        // OCR-style text containing a model code: compare local vs server top hit
        parts.firstOrNull { PartMatcher.normalize(it.model).length >= 8 }?.let { p ->
            val label = "MANUFACTURER GmbH\nType ${p.model}\nSN 00000\n24V DC"
            val remote = api.lookup(label).map { it.pn }
            val local = PartMatcher.lookup(label, parts).map { it.pn }
            println("OCR lookup for ${p.model}: server=$remote local=$local")
            assertEquals(remote.firstOrNull(), local.firstOrNull())
        }

        // Parity: every part number / model / MPN resolves to the same top hit locally and on the server
        var checked = 0
        val mismatches = mutableListOf<String>()
        for (p in parts) {
            for (q in listOf(p.pn, "SP:${p.pn}", p.model.orEmpty(), p.mpn.orEmpty(), "Label: ${p.model.orEmpty()} 24V")) {
                if (PartMatcher.normalize(q).length < 5) continue
                val remote = api.lookup(q).map { it.pn }
                val local = PartMatcher.lookup(q, parts).map { it.pn }
                checked++
                if (remote.toSet() != local.toSet() && remote.size == 1) mismatches += "'$q': server=$remote local=$local"
                else if (remote.isNotEmpty() && local.isNotEmpty() && remote.first() != local.first() && remote.size == 1)
                    mismatches += "'$q': server=$remote local=$local"
            }
        }
        println("parity: $checked lookups compared, ${mismatches.size} mismatches")
        mismatches.take(10).forEach(::println)
        assertTrue(mismatches.isEmpty())

        // Single adjust, then revert
        val before = api.part(sample.pn).part.stock
        val up = api.adjust(sample.pn, 1.0, null, "smoke test +1", "smoke-test")
        assertEquals(before + 1, up.stock, 1e-9)
        val back = api.adjust(sample.pn, -1.0, null, "smoke test revert", "smoke-test")
        assertEquals(before, back.stock, 1e-9)

        // Negative stock is refused with a readable error
        try {
            api.adjust(sample.pn, -(before + 1000), null, "should fail", "smoke-test")
            fail("expected 400")
        } catch (e: ApiException) {
            assertEquals(400, e.code)
            println("negative adjust refused: ${e.message}")
        }

        // Batch endpoint used by the offline queue
        val ok1 = PendingAdjustment(UUID.randomUUID().toString(), sample.pn, delta = 2.0, reason = "smoke batch", user = "smoke-test")
        val ok2 = PendingAdjustment(UUID.randomUUID().toString(), sample.pn, delta = -2.0, reason = "smoke batch revert", user = "smoke-test")
        val bad = PendingAdjustment(UUID.randomUUID().toString(), "NO-SUCH-PART-XYZ", delta = 1.0)
        val batch = api.adjustBatch(listOf(ok1, bad, ok2))
        assertEquals(listOf(ok1.id, bad.id, ok2.id), batch.results.map { it.id })
        assertEquals(listOf(true, false, true), batch.results.map { it.ok })
        assertEquals(before, api.part(sample.pn).part.stock, 1e-9)

        val moves = api.movements(sample.pn)
        assertTrue(moves.any { it.reason == "smoke batch revert" })
        assertTrue(moves.first().ts > 1_000_000_000)

        val low = api.low()
        assertFalse(low.any { !it.isLow })

        // PATCH round trip on notes (restored afterwards)
        val oldNotes = detail.part.notes.orEmpty()
        val patched = api.patch(sample.pn, kotlinx.serialization.json.buildJsonObject {
            put("notes", kotlinx.serialization.json.JsonPrimitive("smoke test note"))
        })
        assertEquals("smoke test note", patched.notes)
        api.patch(sample.pn, kotlinx.serialization.json.buildJsonObject {
            put("notes", kotlinx.serialization.json.JsonPrimitive(oldNotes))
        })
        Unit
    }
}
