package io.github.valeronm.breadcrumb.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every case of [stayBounds], on a plain JVM.
 *
 * The bounds are plain instants and which of them the slicer cut is passed in, because that is how
 * the row learns it — the rule reads a stamp rather than asking whether a timestamp lands on local
 * midnight. Instants here are therefore arbitrary: no zone, no calendar, and no fixture that has to
 * agree with a slicer about where a day begins.
 */
class StayBoundsTest {

    @Test fun `a stay between two clock times states both`() {
        assertEquals(StayBounds.Between(START, END), uncut(START, END))
    }

    @Test fun `an ongoing stay is stated from its start`() {
        assertEquals(StayBounds.Since(START), uncut(START, null))
    }

    @Test fun `a stay cut at its start states only its end`() {
        assertEquals(StayBounds.Until(END), stayBounds(START, END, holdsStart = false, holdsEnd = true))
    }

    @Test fun `a stay cut at its end states only its start`() {
        assertEquals(StayBounds.From(START), stayBounds(START, END, holdsStart = true, holdsEnd = false))
    }

    @Test fun `a stay cut at both ends states no clock time`() {
        assertEquals(
            StayBounds.AllDay,
            stayBounds(START, END, holdsStart = false, holdsEnd = false),
        )
    }

    @Test fun `an ongoing stay cut at its start states no clock time`() {
        assertEquals(StayBounds.AllDay, stayBounds(START, null, holdsStart = false, holdsEnd = true))
    }

    @Test fun `bounds landing on one clock minute are stated once`() {
        assertEquals(StayBounds.At(START), uncut(START, START + 20_000))
    }

    @Test fun `bounds a few seconds apart across a minute are stated as two`() {
        val start = START + 55_000
        assertEquals(StayBounds.Between(start, start + 20_000), uncut(start, start + 20_000))
    }

    /**
     * A duration beside a cut bound would restate the clock time and understate the stay. Asked of the
     * cases themselves rather than of inputs that produce them — which the tests above already pin —
     * so the list is one row per case and a case added later has nowhere to hide.
     */
    @Test fun `only bounds the day did not cut carry a duration`() {
        val cases = listOf<Pair<StayBounds, Boolean>>(
            StayBounds.AllDay to false,
            StayBounds.Until(END) to false,
            StayBounds.From(START) to false,
            StayBounds.Since(START) to true,
            StayBounds.At(START) to true,
            StayBounds.Between(START, END) to true,
        )
        assertEquals(cases.map { it.second }, cases.map { it.first.withDuration })
    }

    /** A stay the day slicing left alone — both bounds its own. */
    private fun uncut(start: Long, end: Long?) =
        stayBounds(start, end, holdsStart = true, holdsEnd = true)

    private companion object {
        /** Two instants far enough apart to sit on different clock minutes. The rule reads no
         *  calendar off them — which bounds were cut arrives as a flag, not as a timestamp. */
        const val START = 1_614_848_400_000L
        const val END = START + 3 * 3_600_000L
    }
}
