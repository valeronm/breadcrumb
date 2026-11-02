package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.domain.ORIGIN_LAT
import io.github.valeronm.breadcrumb.domain.lonAt
import org.junit.Assert.assertEquals
import org.junit.Test

class TripEndsTest {

    private fun pt(east: Double) = TrackPoint(
        trackId = 1,
        latitude = ORIGIN_LAT,
        longitude = lonAt(east),
        altitude = null,
        accuracy = null,
        speed = null,
        bearing = null,
        timestamp = 0,
        ignored = false,
    )

    private val out = listOf(pt(0.0), pt(100.0))
    private val back = listOf(pt(200.0), pt(300.0))

    @Test fun `an empty first part is passed over`() {
        assertEquals(listOf(out.first(), back.last()), tripEnds(listOf(emptyList(), out, back)))
    }

    @Test fun `an empty last part is passed over`() {
        assertEquals(listOf(out.first(), back.last()), tripEnds(listOf(out, back, emptyList())))
    }

    @Test fun `a trip with no fixes has no ends`() {
        assertEquals(emptyList<TrackPoint>(), tripEnds(listOf(emptyList(), emptyList())))
    }

    @Test fun `one part's ends are its first and last fix`() {
        assertEquals(listOf(out.first(), out.last()), tripEnds(listOf(out)))
    }
}
