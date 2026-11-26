package dev.havlicek.rag

/** F9: a piece of the user's note with its character range in the original text ([start] inclusive, [end] exclusive). */
data class NoteChunk(val id: String, val start: Int, val end: Int, val text: String)

/**
 * F9: splits extracted note text (OCR / PDF / DOCX output) into ~[maxChars] chunks on paragraph, then line, then
 * sentence boundaries, keeping exact offsets so quotes can be mapped back to the scan. Chunks are the citation unit
 * the model refers to ("C3"); windows group consecutive chunks into one LLM call.
 */
object NoteChunker {
    private val BREAKS = listOf(Regex("\\n\\s*\\n"), Regex("\\n"), Regex("(?<=[.!?])\\s+"), Regex("\\s+"))

    fun chunk(text: String, maxChars: Int = 1_200): List<NoteChunk> {
        require(maxChars >= 50) { "maxChars must be >= 50" }
        val pieces = mutableListOf<IntRange>()
        split(text, 0, text.length, maxChars, 0, pieces)
        return pieces
            .map { trim(text, it.first, it.last + 1) }
            .filter { (s, e) -> e > s }
            .mapIndexed { i, (s, e) -> NoteChunk("C${i + 1}", s, e, text.substring(s, e)) }
    }

    /** Consecutive chunks whose total length stays within [maxChars] (a single oversized chunk gets its own window). */
    fun windows(chunks: List<NoteChunk>, maxChars: Int): List<List<NoteChunk>> =
        chunks.fold(mutableListOf<MutableList<NoteChunk>>()) { acc, c ->
            val current = acc.lastOrNull()
            if (current != null && current.sumOf { it.text.length } + c.text.length <= maxChars) current += c else acc += mutableListOf(c)
            acc
        }

    /** Greedy packing of segments separated by the coarsest break that works; recurses into finer breaks. */
    private fun split(text: String, from: Int, to: Int, max: Int, level: Int, out: MutableList<IntRange>) {
        if (to - from <= max) { out += from until to; return }
        if (level >= BREAKS.size) { // no break at all: hard cut
            var s = from
            while (s < to) { out += s until minOf(s + max, to); s += max }
            return
        }
        val cuts = BREAKS[level].findAll(text.subSequence(from, to)).map { from + it.range.last + 1 }.toList() + to
        var segStart = from
        var packStart = from
        for (cut in cuts) {
            if (cut - packStart > max && segStart > packStart) {
                out += packStart until segStart
                packStart = segStart
            }
            if (cut - packStart > max) { // one segment alone is too long -> finer breaks
                split(text, packStart, cut, max, level + 1, out)
                packStart = cut
            }
            segStart = cut
        }
        if (packStart < to) out += packStart until to
    }

    private fun trim(text: String, start: Int, end: Int): Pair<Int, Int> {
        var s = start
        var e = end
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        return s to e
    }
}

/**
 * F9: finds a model-reported quote in the note, tolerant to whitespace, case, typographic quotes/dashes, markdown /
 * bullet characters (` * # > | •) and a trailing "...". Returns the range in the ORIGINAL text, so the app can highlight it. null = not in the note (= not grounded).
 */
object QuoteLocator {
    private const val MIN_QUOTE_CHARS = 8
    private val IGNORED = setOf('`', '*', '#', '>', '|', '\u2022', '\u25CF', '\u25AA')

    fun locate(note: String, quote: String): IntRange? {
        val q = normalize(quote.trim().trim('"', '\u201C', '\u201D', '\'').removeSuffix("...").removeSuffix("\u2026").trim())
        if (q.text.length < MIN_QUOTE_CHARS) return null
        val n = normalize(note)
        val at = n.text.indexOf(q.text)
        if (at < 0) return null
        return n.origin[at]..n.origin[at + q.text.length - 1]
    }

    private class Normalized(val text: String, val origin: IntArray)

    /** lowercase, unified quotes/dashes, whitespace runs -> one space; [Normalized.origin] maps back to the input. */
    private fun normalize(s: String): Normalized {
        val sb = StringBuilder(s.length)
        val origin = IntArray(s.length)
        var lastSpace = true
        s.forEachIndexed { i, raw ->
            if (raw in IGNORED) return@forEachIndexed // markdown / bullet noise the model usually drops when quoting
            val c = when (raw) {
                '\u2018', '\u2019', '\u201A' -> '\''
                '\u201C', '\u201D', '\u201E' -> '"'
                '\u2013', '\u2014', '\u2212' -> '-'
                else -> raw.lowercaseChar()
            }
            if (c.isWhitespace()) {
                if (!lastSpace) { origin[sb.length] = i; sb.append(' '); lastSpace = true }
            } else {
                origin[sb.length] = i; sb.append(c); lastSpace = false
            }
        }
        return Normalized(sb.toString(), origin)
    }
}
