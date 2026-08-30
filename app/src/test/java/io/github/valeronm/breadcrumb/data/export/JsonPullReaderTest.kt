package io.github.valeronm.breadcrumb.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.StringReader

/**
 * Malformed input surfaces only as [IllegalStateException], the type the online search catches to
 * read a bad body as no results; a conversion throwing its own type would take the caller down.
 */
class JsonPullReaderTest {

    private fun reader(json: String) = JsonPullReader(StringReader(json))

    @Test fun `numbers come back as Long when integral and Double otherwise`() {
        assertEquals(42L, reader("42").nextNumber())
        assertEquals(-1.5, reader("-1.5").nextNumber())
        assertEquals(2.0e3, reader("2e3").nextNumber())
    }

    @Test fun `a number made of number characters that is not a number is a parse error`() {
        assertThrows(IllegalStateException::class.java) { reader("1e").nextNumber() }
        assertThrows(IllegalStateException::class.java) { reader("--5").nextNumber() }
        assertThrows(IllegalStateException::class.java) { reader("1.2.3").nextNumber() }
    }

    @Test fun `a unicode escape that is not hex is a parse error`() {
        assertEquals("\u00e9", reader("\"\\u00e9\"").nextString())
        assertThrows(IllegalStateException::class.java) { reader("\"\\u12G4\"").nextString() }
    }
}
