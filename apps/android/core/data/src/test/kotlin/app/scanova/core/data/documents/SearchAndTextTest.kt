package app.scanova.core.data.documents

import app.scanova.core.data.util.normalizeTags
import app.scanova.core.data.util.parseInstantOrNull
import app.scanova.core.data.util.truncateUtf8
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchAndTextTest {

    @Test
    fun `words become lower-case prefix terms that must all match`() {
        assertEquals("rental* agreem*", SearchQuery.toFtsMatch("Rental agreem"))
        assertEquals("invoice* 2026*", SearchQuery.toFtsMatch("  invoice   2026 "))
    }

    @Test
    fun `nothing searchable means no filter`() {
        assertNull(SearchQuery.toFtsMatch(""))
        assertNull(SearchQuery.toFtsMatch("   "))
        assertNull(SearchQuery.toFtsMatch("\"'()*-:"))
    }

    @Test
    fun `query syntax typed by the user is neutralised`() {
        // Quotes, parentheses, column filters and operators must not reach SQLite as syntax.
        assertEquals("tax* or* title* x* near* 2* y*", SearchQuery.toFtsMatch("\"tax\" OR title:x NEAR/2 (y)"))
        assertEquals("a* b*", SearchQuery.toFtsMatch("a* -b"))
        assertEquals("drop* table* documents*", SearchQuery.toFtsMatch("'; DROP TABLE documents; --"))
    }

    @Test
    fun `punctuation splits words the way the index tokenizer does`() {
        assertEquals("e* mail* o* brien*", SearchQuery.toFtsMatch("e-mail O'Brien"))
    }

    @Test
    fun `non-latin scripts are kept`() {
        assertEquals("کرایہ* نامہ*", SearchQuery.toFtsMatch("کرایہ نامہ"))
        assertEquals("straße* 12*", SearchQuery.toFtsMatch("Straße 12"))
    }

    @Test
    fun `very long input is capped`() {
        val match = SearchQuery.toFtsMatch((1..40).joinToString(" ") { "w$it" })!!

        assertEquals(12, match.split(" ").size)
    }

    @Test
    fun `truncation counts UTF-8 bytes and never splits a character`() {
        assertEquals("abc", truncateUtf8("abc", 10))
        assertEquals("ab", truncateUtf8("abc", 2))
        assertEquals("", truncateUtf8("abc", 0))
        // "é" is two bytes: three of them do not fit in five bytes.
        assertEquals("éé", truncateUtf8("ééé", 5))
        // "اردو" is eight bytes; seven bytes hold three letters.
        assertEquals("ارد", truncateUtf8("اردو", 7))
        // An emoji is one code point, two UTF-16 units, four UTF-8 bytes.
        assertEquals("a", truncateUtf8("a😀", 4))
        assertEquals("a😀", truncateUtf8("a😀", 5))
    }

    @Test
    fun `tags are trimmed, de-duplicated and keep their order`() {
        assertEquals(listOf("Tax 2026", "home"), normalizeTags(listOf("  Tax   2026 ", "", "home", "HOME", "tax 2026")))
    }

    @Test
    fun `export file names are safe for any file system`() {
        assertEquals("Rental agreement 2026.pdf", exportFileName("Rental agreement 2026"))
        assertEquals("a b c d.pdf", exportFileName("a/b\\c:d"))
        assertEquals("Scanova document.pdf", exportFileName("///"))
        assertEquals("Scanova document.pdf", exportFileName("..."))
        assertEquals("کرایہ نامہ.pdf", exportFileName("کرایہ نامہ"))
        assertEquals(84, exportFileName("x".repeat(300)).length)
    }

    @Test
    fun `api timestamps parse with and without fractions and offsets`() {
        assertEquals(Instant.parse("2026-11-10T09:00:00Z"), parseInstantOrNull("2026-11-10T09:00:00.000Z"))
        assertEquals(Instant.parse("2026-11-10T09:00:00Z"), parseInstantOrNull("2026-11-10T14:00:00+05:00"))
        assertNull(parseInstantOrNull("yesterday"))
        assertNull(parseInstantOrNull(null))
        assertNull(parseInstantOrNull(""))
    }
}
