package se.spareparts.inventory.domain

import se.spareparts.inventory.data.Part
import java.net.URLDecoder

/**
 * Offline twin of the server's `/api/lookup` and search logic.
 *
 * Matches QR payloads (`http://host/p/<pn>`, `SP:<pn>`), raw barcodes and whole OCR'd label
 * text against the cached part list. Pure Kotlin so it can be unit tested on the JVM.
 */
object PartMatcher {

    const val MIN_ID_LENGTH = 5
    private const val LIMIT = 10
    private val QR_MARKERS = listOf("/p/", "SP:")

    fun normalize(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        val sb = StringBuilder(s.length)
        for (ch in s) if (ch.isLetterOrDigit()) sb.append(ch.uppercaseChar())
        return sb.toString()
    }

    fun lookup(text: String?, parts: List<Part>, limit: Int = LIMIT): List<Part> {
        val t = text?.trim().orEmpty()
        if (t.isEmpty() || parts.isEmpty()) return emptyList()

        // 1. QR codes printed by the server: ".../p/<pn>" or "SP:<pn>"
        for (marker in QR_MARKERS) {
            val idx = t.indexOf(marker, ignoreCase = marker == "SP:")
            if (idx >= 0) {
                val cand = t.substring(idx + marker.length)
                    .substringBefore('?').substringBefore('#').trim('/', ' ')
                val decoded = runCatching { URLDecoder.decode(cand.replace("+", "%2B"), "UTF-8") }.getOrDefault(cand)
                findByPn(decoded, parts)?.let { return listOf(it) }
            }
        }

        // 2. Exact identifier: part number first, then model / manufacturer part number
        findByPn(t, parts)?.let { return listOf(it) }
        val nt = normalize(t)
        if (nt.isEmpty()) return emptyList()
        val byPn = parts.filter { normalize(it.pn) == nt }
        if (byPn.isNotEmpty()) return byPn.take(limit)
        val byModel = parts.filter { normalize(it.model) == nt || normalize(it.mpn) == nt }
        if (byModel.isNotEmpty()) return byModel.take(limit)

        // 3. Known identifiers contained in free text (OCR labels contain lots of other words)
        val scored = ArrayList<Pair<Int, Part>>()
        for (p in parts) {
            var best = 0
            best = maxOf(best, containedScore(p.pn, 3, nt))
            best = maxOf(best, containedScore(p.model, 2, nt))
            best = maxOf(best, containedScore(p.mpn, 2, nt))
            if (best > 0) scored += best to p
        }
        if (scored.isNotEmpty()) {
            return scored.sortedWith(compareByDescending<Pair<Int, Part>> { it.first }.thenBy { it.second.pn })
                .take(limit).map { it.second }
        }

        // 4. Fallback: code-looking tokens (>= 5 chars with a digit) as prefix/substring of identifiers
        val tokens = t.split(Regex("\\s+"))
            .filter { normalize(it).length >= MIN_ID_LENGTH && it.any(Char::isDigit) }
            .distinct().sortedByDescending { it.length }.take(8)
        val out = LinkedHashMap<String, Part>()
        for (tok in tokens) {
            for (p in search(tok, parts, limit = 5)) out.putIfAbsent(p.pn, p)
        }
        return out.values.take(limit)
    }

    private fun containedScore(key: String?, weight: Int, blob: String): Int {
        val k = normalize(key)
        return if (k.length >= MIN_ID_LENGTH && blob.contains(k)) weight * k.length else 0
    }

    private fun findByPn(pn: String, parts: List<Part>): Part? {
        val q = pn.trim()
        if (q.isEmpty()) return null
        return parts.firstOrNull { it.pn.equals(q, ignoreCase = true) }
    }

    /** Ranked search, same scoring idea as the server's `/api/search`. */
    fun search(query: String, parts: List<Part>, limit: Int = 25): List<Part> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val nq = normalize(q)
        val terms = q.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val scored = ArrayList<Pair<Int, Part>>()
        for (p in parts) {
            val ids = listOf(normalize(p.pn), normalize(p.model), normalize(p.mpn))
            var score = when {
                nq.isNotEmpty() && nq in ids -> 1000
                nq.length >= 4 && ids.any { it.startsWith(nq) } -> 400
                nq.length >= 4 && ids.any { it.contains(nq) } -> 250
                else -> 0
            }
            val hay = haystack(p)
            val hits = terms.count { hay.contains(it) }
            if (terms.isNotEmpty() && hits == terms.size) score += 50 + 10 * hits
            if (score > 0) scored += score to p
        }
        return scored.sortedWith(compareByDescending<Pair<Int, Part>> { it.first }.thenBy { it.second.pn })
            .take(limit).map { it.second }
    }

    /** Instant list filter used by the Search screen: every term must appear somewhere. */
    fun filter(query: String, parts: List<Part>): List<Part> {
        val q = query.trim()
        if (q.isEmpty()) return parts
        val terms = q.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val nq = normalize(q)
        val matched = parts.filter { p ->
            val hay = haystack(p)
            terms.all { hay.contains(it) } ||
                (nq.length >= 3 && (normalize(p.pn).contains(nq) || normalize(p.model).contains(nq) || normalize(p.mpn).contains(nq)))
        }
        // Identifier hits first, then keep the incoming (category, pn) order.
        return matched.sortedByDescending { p ->
            when {
                nq.isEmpty() -> 0
                normalize(p.pn) == nq || normalize(p.model) == nq || normalize(p.mpn) == nq -> 3
                normalize(p.pn).startsWith(nq) -> 2
                else -> 0
            }
        }
    }

    fun haystack(p: Part): String = buildString {
        for (s in listOf(p.pn, p.name, p.manufacturer, p.model, p.mpn, p.location, p.category, p.positions, p.notes)) {
            if (!s.isNullOrEmpty()) append(s).append(' ')
        }
        for (s in p.specs) append(s.key).append(' ').append(s.value).append(' ')
    }.lowercase()
}
