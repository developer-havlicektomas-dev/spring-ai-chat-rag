package dev.havlicek.rag

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F9: chunk offsets, windows, quote matching and quiz distribution (pure functions, no Spring). */
class NoteTextTest {
    private val note = buildString {
        repeat(6) { p ->
            append("Paragraph $p. ")
            repeat(8) { s -> append("Photosynthesis fact $p.$s turns light into chemical energy. ") }
            append("\n\n")
        }
    }

    @Test
    fun `chunks keep exact offsets, stay under the limit and cover the text`() {
        val chunks = NoteChunker.chunk(note, 300)
        assertTrue(chunks.size > 6)
        chunks.forEach {
            assertEquals(note.substring(it.start, it.end), it.text)
            assertTrue(it.text.length <= 300, "${it.id} has ${it.text.length}")
        }
        assertEquals((1..chunks.size).map { "C$it" }, chunks.map { it.id })
        assertEquals(note.filterNot(Char::isWhitespace), chunks.joinToString("") { it.text }.filterNot(Char::isWhitespace))
    }

    @Test
    fun `short note is one chunk, long word is hard cut, windows respect the budget`() {
        assertEquals(listOf("Hello world"), NoteChunker.chunk("  Hello world \n", 100).map { it.text })
        assertEquals(3, NoteChunker.chunk("x".repeat(250), 100).size)
        val windows = NoteChunker.windows(NoteChunker.chunk(note, 300), 1_000)
        assertTrue(windows.size > 1)
        windows.forEach { w -> assertTrue(w.sumOf { it.text.length } <= 1_000) }
        assertEquals(NoteChunker.chunk(note, 300), windows.flatten())
    }

    @Test
    fun `quotes match despite case, whitespace, typographic quotes and ellipsis`() {
        val text = "Mitochondria are the \u201Cpowerhouse\u201D of the cell.\nThey make   ATP \u2013 the energy currency."
        val r = assertNotNull(QuoteLocator.locate(text, "they make ATP - the energy currency..."))
        assertEquals("They make   ATP \u2013 the energy currency", text.substring(r.first, r.last + 1))
        assertNotNull(QuoteLocator.locate(text, "the \"powerhouse\" of the cell"))
        assertNull(QuoteLocator.locate(text, "Chloroplasts make glucose"))
        assertNull(QuoteLocator.locate(text, "ATP")) // too short to prove anything
        val md = "## Tuning\n- `RAG_TOP_K` (default **4**): more chunks give more recall"
        val m = assertNotNull(QuoteLocator.locate(md, "RAG_TOP_K (default 4): more chunks"))
        assertEquals("RAG_TOP_K` (default **4**): more chunks", md.substring(m.first, m.last + 1))
    }

    @Test
    fun `options are shuffled deterministically and the correct index follows`() {
        val options = listOf("right", "a", "b", "c")
        val (shuffled, correct) = StudyGuideService.shuffled(options, 0, "Where?")
        assertEquals("right", shuffled[correct])
        assertEquals(options.toSet(), shuffled.toSet())
        assertEquals(shuffled to correct, StudyGuideService.shuffled(options, 0, "Where?"))
        val firsts = (1..40).map { StudyGuideService.shuffled(options, 0, "q$it").second }.toSet()
        assertTrue(firsts.size > 1) // not always at index 0
    }

    @Test
    fun `quiz questions are split by window length`() {
        assertEquals(listOf(5), StudyGuideService.distribute(5, listOf(100)))
        assertEquals(listOf(3, 2), StudyGuideService.distribute(5, listOf(600, 400)))
        assertEquals(10, StudyGuideService.distribute(10, listOf(1, 2, 3)).sum())
        assertEquals(listOf(1, 0, 0), StudyGuideService.distribute(1, listOf(500, 300, 200)))
    }
}
