package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.at
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class GapDraftsTest {
    private val gap = StayDeriver.Gap(
        start = 1_000_000L, end = 4_600_000L, afterTrackId = 7,
        from = StayDeriver.Gap.Side(at(0.0), clusterId = 0), to = StayDeriver.Gap.Side(at(450.0), clusterId = 1),
    )
    private val day = LocalDate.of(2020, 1, 1)

    @Test fun `the whole gap holds both instants`() {
        val d = GapDrafts.wholeGap(gap, day, null, null, nextTrackId = 8)
        assertEquals(gap.start, d.origin!!.timeMs)
        assertEquals(gap.end, d.destination!!.timeMs)
        assertEquals(7L, d.trackBefore)
        assertEquals(8L, d.trackAfter)
    }

    @Test fun `the missed start arrives at the next track and leaves its departure untimed`() {
        val d = GapDrafts.missedStart(gap, day, null, null, nextTrackId = 8)
        assertEquals(gap.from?.at, d.origin!!.at)
        assertNull(d.origin.timeMs)
        assertEquals(gap.to?.at, d.destination!!.at)
        assertEquals(gap.end, d.destination.timeMs)
    }
}
