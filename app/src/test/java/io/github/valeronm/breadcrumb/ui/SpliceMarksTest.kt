package io.github.valeronm.breadcrumb.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The half of `annotatedStringResource` that carries the logic: the platform has already placed the
 * marks where the language wants them, and what remains is putting each argument back at its own
 * mark. The reordering cases are the reason this exists — a line whose styled value moves is exactly
 * what the helper was built for, so a suite that only checked the English order would pass through
 * the bug it is meant to catch.
 *
 * The marks are spelled here rather than imported, so a change to their shape has to be a deliberate
 * one made in two places.
 */
class SpliceMarksTest {

    private fun mark(index: Int) = "\u0000$index\u0000"

    private fun splice(template: String, vararg args: CharSequence) =
        spliceMarks(template, args)

    @Test
    fun `an argument is spliced where its mark sits`() {
        assertEquals(
            AnnotatedString("Until 09:41"),
            splice("Until ${mark(0)}", "09:41"),
        )
    }

    @Test
    fun `a translation that leads with the value reads in its own order`() {
        // The case a prefix-and-suffix pair cannot express: same argument, opposite side.
        assertEquals(
            AnnotatedString("09:41 まで"),
            splice("${mark(0)} まで", "09:41"),
        )
    }

    @Test
    fun `two arguments swapped by the translation keep their own identities`() {
        assertEquals(
            AnnotatedString("arrival 10:15, departure 09:41"),
            splice("arrival ${mark(1)}, departure ${mark(0)}", "09:41", "10:15"),
        )
    }

    @Test
    fun `an argument repeated by the translation is spliced at each mark`() {
        assertEquals(
            AnnotatedString("09:41 – 09:41"),
            splice("${mark(0)} – ${mark(0)}", "09:41"),
        )
    }

    @Test
    fun `a template with no marks is carried through unchanged`() {
        assertEquals(AnnotatedString("All day"), splice("All day"))
    }

    @Test
    fun `a styled argument keeps its spans, and they land at the spliced offset`() {
        val red = SpanStyle(color = Color.Red)
        val styled = buildAnnotatedString {
            append("09:41")
            withStyle(red) { append("+1h") }
        }

        val spliced = splice("Until ${mark(0)}, then", styled)

        assertEquals("Until 09:41+1h, then", spliced.text)
        assertEquals(
            listOf(AnnotatedString.Range(red, "Until 09:41".length, "Until 09:41+1h".length)),
            spliced.spanStyles,
        )
    }
}
