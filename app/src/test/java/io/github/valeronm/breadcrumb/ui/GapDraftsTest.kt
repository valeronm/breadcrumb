package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.domain.ActivityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

class GapDraftsTest {
    private val detail = GapFixtures.detail(ZoneId.of("UTC"))

    @Test fun `the whole gap holds both instants and leaves the type unset`() {
        val d = GapDrafts.wholeGap(detail)
        assertEquals(detail.gap.start, d.origin!!.timeMs)
        assertEquals(detail.gap.end, d.destination!!.timeMs)
        assertEquals(detail.before.id to detail.after.id, d.tracksAround)
        assertNull(d.activity)
    }

    @Test fun `the missed start arrives at the next track as its type and leaves its departure untimed`() {
        val d = GapDrafts.missedStart(detail)
        assertEquals(detail.gap.from?.at, d.origin!!.at)
        assertNull(d.origin.timeMs)
        assertEquals(detail.gap.to?.at, d.destination!!.at)
        assertEquals(detail.gap.end, d.destination.timeMs)
        assertEquals(ActivityType.ofName(detail.after.activityType), d.activity)
    }
}
