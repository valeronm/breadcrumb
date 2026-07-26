package io.github.valeronm.breadcrumb.domain

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

    @Test fun `a cut in the middle names both halves' bounds and counts`() {
        val plan = TrackSplit.plan(points(10), tsOf(4))!!

        assertEquals(tsOf(4), plan.cutTs)
        assertEquals("the last fix that stays behind", tsOf(3), plan.firstEndTs)
        assertEquals("the cut fix opens the second track", tsOf(4), plan.secondStartTs)
        assertEquals(4, plan.firstGoodPoints)
        assertEquals(6, plan.secondGoodPoints)
    }

    @Test fun `the boundary is the outermost fix of either kind, not the last good one`() {
        // A rejected fix sits against the cut. The repository hands the row this timestamp, so the
        // plan must report it too — reporting the last *good* fix instead is how a preview and a
        // stored track come to disagree by the seconds between them.
        val plan = TrackSplit.plan(points(10, ignoredAt = setOf(3)), tsOf(4))!!

        assertEquals(tsOf(3), plan.firstEndTs)
        assertEquals("but it is not part of the path either half draws", 3, plan.firstGoodPoints)
    }

    @Test fun `an ignored fix at the cut opens the second half's clock without counting`() {
        val plan = TrackSplit.plan(points(10, ignoredAt = setOf(4)), tsOf(4))!!

        assertEquals(tsOf(4), plan.secondStartTs)
        assertEquals(5, plan.secondGoodPoints)
    }

    @Test fun `order does not matter, so a caller may concatenate its point lists unsorted`() {
        val all = points(10)
        val shuffled = all.filterIndexed { i, _ -> i % 2 == 0 } + all.filterIndexed { i, _ -> i % 2 == 1 }

        assertEquals(TrackSplit.plan(all, tsOf(4)), TrackSplit.plan(shuffled, tsOf(4)))
    }

    @Test fun `a cut leaving too few good fixes on either side is refused`() {
        assertNull("nothing before it", TrackSplit.plan(points(10), tsOf(0)))
        assertNull("one fix before it", TrackSplit.plan(points(10), tsOf(1)))
        assertNull("one fix after it", TrackSplit.plan(points(10), tsOf(9)))
        assertNull("nothing after it", TrackSplit.plan(points(10), tsOf(10)))
        // Two either side is the floor, and it holds.
        assertEquals(tsOf(2), TrackSplit.plan(points(4), tsOf(2))!!.cutTs)
    }

    @Test fun `only good fixes count toward the floor`() {
        // Four fixes before the cut, but two of them rejected: not a line, so not a track.
        assertNull(TrackSplit.plan(points(10, ignoredAt = setOf(0, 1, 2)), tsOf(4)))
    }

    @Test fun `the floor is the same test the screen asks with counts alone`() {
        assertEquals(false, TrackSplit.isLegalCut(1, 8))
        assertEquals(false, TrackSplit.isLegalCut(8, 1))
        assertEquals(true, TrackSplit.isLegalCut(2, 2))
    }
}
