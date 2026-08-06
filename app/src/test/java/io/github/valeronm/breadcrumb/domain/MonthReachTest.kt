package io.github.valeronm.breadcrumb.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

/**
 * Which month the figures may report on, and where a step from it lands.
 *
 * Every case here was previously reachable only by composing the Statistics page against a moved
 * device clock, which is why the rule was lifted out of it: the three that matter — a history
 * running past the present, one that stops short of it, and a selection that stops being reachable
 * while the reader sits on it — are each two lines once `nowMonth` is a parameter rather than a
 * reading of the device.
 */
class MonthReachTest {

    private fun month(year: Int, month: Int) = YearMonth.of(year, month)

    private fun months(vararg of: YearMonth) = of.map { MonthTotals(it, TimelineTotals.EMPTY) }

    private fun reach(
        history: List<MonthTotals>,
        now: YearMonth = month(2026, 8),
        selected: YearMonth = month(2026, 8),
    ) = MonthReach.of(history, now, selected)

    @Test fun `the history's first month is the near end`() {
        val reach = reach(months(month(2024, 3), month(2026, 8)))
        assertEquals(month(2024, 3), reach.first)
    }

    // A page opens on the present even where nothing has been recorded for months.
    @Test fun `the present is the far end when the history stops short of it`() {
        val reach = reach(months(month(2024, 3), month(2025, 1)), now = month(2026, 8))
        assertEquals(month(2026, 8), reach.last)
        assertEquals(month(2026, 8), reach.shown)
    }

    // An imported plan, or a phone whose date was wrong: the rows exist and must stay reachable.
    @Test fun `a history running past the present is not stranded beyond the last arrow`() {
        val reach = reach(months(month(2026, 8), month(2027, 4)), now = month(2026, 8))
        assertEquals(month(2027, 4), reach.last)
        assertTrue(reach.canStepForward)
    }

    @Test fun `a selection is held inside the bounds`() {
        val history = months(month(2024, 3), month(2026, 8))
        assertEquals(month(2024, 3), reach(history, selected = month(2019, 1)).shown)
        assertEquals(month(2026, 8), reach(history, selected = month(2030, 1)).shown)
    }

    // The history grows under an open screen, so the month someone is sitting on can stop being
    // reachable — the clamp is what keeps the page reporting on a month that exists.
    @Test fun `an unreachable selection lands on the nearest bound rather than nowhere`() {
        val reach = reach(months(month(2026, 6), month(2026, 8)), selected = month(2020, 5))
        assertEquals(month(2026, 6), reach.shown)
        assertFalse(reach.canStepBack)
    }

    @Test fun `each arrow is live only while there is somewhere to go`() {
        val history = months(month(2026, 6), month(2026, 8))
        reach(history, selected = month(2026, 6)).let {
            assertFalse(it.canStepBack)
            assertTrue(it.canStepForward)
        }
        reach(history, selected = month(2026, 8)).let {
            assertTrue(it.canStepBack)
            assertFalse(it.canStepForward)
        }
    }

    @Test fun `a step lands on the month it was aimed at`() {
        val reach = reach(months(month(2024, 3), month(2026, 8)))
        assertEquals(month(2026, 7), reach.stepped(-1))
        assertEquals(month(2025, 8), reach.stepped(-12))
    }

    // The tap's case: a bar can stand for a month older than anything recorded, and a tap on it must
    // do nothing rather than land on the nearest month it could reach.
    @Test fun `a step past either end is refused rather than clamped`() {
        val reach = reach(months(month(2026, 6), month(2026, 8)), selected = month(2026, 8))
        assertNull(reach.stepped(-11))
        assertNull(reach.stepped(1))
        assertEquals(month(2026, 6), reach.stepped(-2))
    }

    // A history of one month: both arrows dead, and the page still has a month to report on.
    @Test fun `a single recorded month is its own two bounds`() {
        val reach = reach(months(month(2026, 8)), now = month(2026, 8))
        assertEquals(month(2026, 8), reach.shown)
        assertFalse(reach.canStepBack)
        assertFalse(reach.canStepForward)
        assertNull(reach.stepped(-1))
    }
}
