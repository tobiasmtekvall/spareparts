package se.spareparts.inventory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.spareparts.inventory.data.Part
import se.spareparts.inventory.data.Spec
import se.spareparts.inventory.domain.PartMatcher

class PartMatcherTest {

    private val belt = Part(pn = "A005415", name = "Belt", category = "Belts", manufacturer = "Forbo Siegling",
        model = "EL 0/V10 LG-SE black", specs = listOf(Spec("Width (mm)", "800")), location = "Shelf B2")
    private val motor = Part(pn = "A001200", name = "Gear motor", category = "Gear motors", manufacturer = "SEW",
        model = "KA47DRN80M4", mpn = "01.7654321.0001")
    private val sensor = Part(pn = "A001201", name = "Photo sensor", category = "Electrical", manufacturer = "Sick",
        model = "WL12-3P2431", mpn = "1041391")
    private val shortModel = Part(pn = "A009999", name = "Bolt", model = "M8")
    private val lowerPn = Part(pn = "b-77/x", name = "Odd part number")
    private val parts = listOf(belt, motor, sensor, shortModel, lowerPn)

    private fun lookup(text: String) = PartMatcher.lookup(text, parts).map { it.pn }

    @Test fun normalizeKeepsUppercaseAlphanumerics() {
        assertEquals("EL0V10LGSEBLACK", PartMatcher.normalize("EL 0/V10 LG-SE black"))
        assertEquals("", PartMatcher.normalize(null))
        assertEquals("", PartMatcher.normalize(" -/. "))
    }

    @Test fun emptyInputGivesNothing() {
        assertTrue(lookup("").isEmpty())
        assertTrue(lookup("   ").isEmpty())
        assertTrue(PartMatcher.lookup("A005415", emptyList()).isEmpty())
    }

    @Test fun qrUrlPayload() {
        assertEquals(listOf("A005415"), lookup("http://192.168.1.20:8765/p/A005415"))
        assertEquals(listOf("A005415"), lookup("https://parts.example.com/p/a005415/?utm=x#top"))
    }

    @Test fun qrUrlWithEncodedPartNumber() {
        assertEquals(listOf("b-77/x"), lookup("http://host:8765/p/b-77%2Fx"))
    }

    @Test fun spPrefixPayload() {
        assertEquals(listOf("A001200"), lookup("SP:A001200"))
        assertEquals(listOf("A001200"), lookup("  SP:A001200  "))
    }

    @Test fun rawPartNumberCaseInsensitive() {
        assertEquals(listOf("A001201"), lookup("a001201"))
    }

    @Test fun exactModelAndMpnIgnoringPunctuation() {
        assertEquals(listOf("A001201"), lookup("WL12-3P2431"))
        assertEquals(listOf("A001201"), lookup("wl12 3p2431"))
        assertEquals(listOf("A001200"), lookup("0176543210001"))   // barcode of the MPN without dots
        assertEquals(listOf("A005415"), lookup("el0/v10 lg-se BLACK"))
    }

    @Test fun ocrLabelTextFindsModelInside() {
        val label = """
            SICK
            Photoelectric sensor
            Type: WL12-3P2431  Part no.: 1041391
            10...30 V DC  IP67
            Made in Germany
        """.trimIndent()
        assertEquals("A001201", lookup(label).first())
    }

    @Test fun ocrLabelWithPartNumberBeatsModelOfOtherPart() {
        // Contains the motor model (11 chars, weight 2 = 22) and the sensor PN (7 chars, weight 3 = 21)
        // The motor wins because its model code is longer.
        val text = "KA47DRN80M4 service kit for A001201"
        assertEquals(listOf("A001200", "A001201"), lookup(text))
    }

    @Test fun partNumberWeightedAboveEqualLengthModel() {
        val a = Part(pn = "PN12345", model = "")
        val b = Part(pn = "Z1", model = "MD12345")
        val res = PartMatcher.lookup("label PN12345 / MD12345", listOf(b, a)).map { it.pn }
        assertEquals(listOf("PN12345", "Z1"), res)
    }

    @Test fun shortIdentifiersAreIgnoredInFreeText() {
        // "M8" is shorter than 5 characters and must not match arbitrary text.
        assertTrue(lookup("Use M8 screws for mounting").isEmpty())
    }

    @Test fun ocrWithSpacesAndHyphensInsideCode() {
        assertEquals("A005415", lookup("Forbo Siegling\nEL 0/V10 LG-SE black 800x1910").first())
    }

    @Test fun tokenFallbackFindsPartialCodes() {
        // Not a complete identifier, but a code-looking token that prefixes a model.
        assertEquals(listOf("A001200"), lookup("Motor KA47DRN ordered"))
    }

    @Test fun noMatchReturnsEmpty() {
        assertTrue(lookup("Hello world 42").isEmpty())
        assertTrue(lookup("http://example.com/p/UNKNOWN").isEmpty())
    }

    @Test fun limitIsRespected() {
        val many = (1..30).map { Part(pn = "X%05d".format(it)) }
        val text = many.joinToString(" ") { it.pn }
        assertEquals(10, PartMatcher.lookup(text, many).size)
    }

    @Test fun filterMatchesAllTermsAcrossFields() {
        assertEquals(listOf("A005415"), PartMatcher.filter("forbo 800", parts).map { it.pn })
        assertEquals(listOf("A005415"), PartMatcher.filter("shelf b2", parts).map { it.pn })
        assertEquals(parts, PartMatcher.filter("  ", parts))
        assertEquals(listOf("A001201"), PartMatcher.filter("wl123p", parts).map { it.pn })
    }

    @Test fun searchRanksExactIdentifierFirst() {
        val res = PartMatcher.search("A00120", parts).map { it.pn }
        assertEquals(setOf("A001200", "A001201"), res.toSet())
        assertEquals("A001201", PartMatcher.search("A001201", parts).first().pn)
    }
}
