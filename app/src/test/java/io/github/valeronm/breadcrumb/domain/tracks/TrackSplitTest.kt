package io.github.valeronm.breadcrumb.domain.tracks

import io.github.valeronm.breadcrumb.data.db.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The cut's rule, tested where it lives — the track screen grays its scissors on what [TrackSplit]
 * refuses and previews what it plans, and the repository writes the same boundaries, so a
 * disagreement here is a dialog that promises a track the write doesn't produce.
 */
class TrackSplitTest {

    private val start = 1_700_000_000_000L

    /** A fix every 10 s from [start]; [ignoredAt] indices are rejected fixes still on the track. */
    private fun points(count: Int, ignoredAt: Set<Int> = emptySet()) = (0 until count).map { i ->
        TrackPoint(
            trackId = 1,
            latitude = 1.0 + i * 0.0001,
            longitude = -2.0,
            altitude = null,
            accuracy = 5f,
            speed = null,
            bearing = null,
            timestamp = start + i * 10_000L,
            ignored = i in ignoredAt,
        )
    }

    private fun tsOf(index: Int) = start + index * 10_000L

    @Test fun `a cut in the middle names both halves' bounds`() {
        val plan = TrackSplit.plan(points(10), tsOf(4))!!

        assertEquals(tsOf(4), plan.cutTs)
        assertEquals("the last fix that stays behind", tsOf(3), plan.firstEndTs)
        assertEquals("the cut fix opens the second track", tsOf(4), plan.secondStartTs)
    }

    @Test fun `the boundary is the outermost fix of either kind, not the last good one`() {
        // A rejected fix sits against the cut. The repository hands the row this timestamp, so the
        // plan must report it too — reporting the last *good* fix instead is how a preview and a
        // stored track come to disagree by the seconds between them.
        assertEquals(tsOf(3), TrackSplit.plan(points(10, ignoredAt = setOf(3)), tsOf(4))!!.firstEndTs)
        assertEquals(tsOf(4), TrackSplit.plan(points(10, ignoredAt = setOf(4)), tsOf(4))!!.secondStartTs)
    }

    @Test fun `order does not matter, so a caller may concatenate its point lists unsorted`() {
        val all = points(10)
        val shuffled = all.filterIndexed { i, _ -> i % 2 == 0 } + all.filterIndexed { i, _ -> i % 2 == 1 }

        assertEquals(TrackSplit.plan(all, tsOf(4)), TrackSplit.plan(shuffled, tsOf(4)))
    }

    @Test fun `a cut is refused only where one side holds no fix at all`() {
        assertNull("nothing before it", TrackSplit.plan(points(10), tsOf(0)))
        assertNull("nothing after it", TrackSplit.plan(points(10), tsOf(10)))
        assertEquals("one fix before it", tsOf(0), TrackSplit.plan(points(10), tsOf(1))!!.firstEndTs)
        assertEquals("one fix after it", tsOf(9), TrackSplit.plan(points(10), tsOf(9))!!.secondStartTs)
        assertEquals("only rejected fixes before it", tsOf(2), TrackSplit.plan(points(10, ignoredAt = setOf(0, 1, 2)), tsOf(3))!!.firstEndTs)
    }
}
