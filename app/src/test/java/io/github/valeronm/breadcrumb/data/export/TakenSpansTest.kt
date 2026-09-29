package io.github.valeronm.breadcrumb.data.export

import io.github.valeronm.breadcrumb.data.db.TrackSpan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TakenSpansTest {

    private fun taken(vararg spans: TrackSpan) = TakenSpans(spans.toList())

    @Test fun `a trip inside, around or across a track overlaps it`() {
        val spans = taken(TrackSpan(100, 200))
        assertTrue(spans.overlaps(120, 180))
        assertTrue(spans.overlaps(0, 300))
        assertTrue(spans.overlaps(150, 250))
        assertTrue(spans.overlaps(50, 150))
    }

    @Test fun `a trip touching a track at one instant does not overlap it`() {
        val spans = taken(TrackSpan(100, 200))
        assertFalse(spans.overlaps(200, 300))
        assertFalse(spans.overlaps(0, 100))
    }

    @Test fun `a trip between tracks overlaps neither`() {
        assertFalse(taken(TrackSpan(0, 100), TrackSpan(300, 400)).overlaps(150, 250))
    }

    @Test fun `a track still recording covers everything after its start`() {
        val spans = taken(TrackSpan(100, null))
        assertTrue(spans.overlaps(1_000_000, 1_000_100))
        assertFalse(spans.overlaps(0, 100))
    }

    @Test fun `a long track reaches past a later, shorter one`() {
        assertTrue(taken(TrackSpan(200, 300), TrackSpan(0, 1_000)).overlaps(500, 600))
    }

    @Test fun `an empty history overlaps nothing`() {
        assertFalse(taken().overlaps(0, Long.MAX_VALUE))
    }
}
