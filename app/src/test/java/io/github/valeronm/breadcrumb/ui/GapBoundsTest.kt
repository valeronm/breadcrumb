package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.DistanceFn
import io.github.valeronm.breadcrumb.domain.StayDeriver
import org.junit.Assert.assertEquals
import org.junit.Test

class GapBoundsTest {

    private val flat = DistanceFn { aLat, _, bLat, _ -> (bLat - aLat) * 1_000 }

    private fun gap(start: Long, end: Long, from: Coordinate? = FROM, to: Coordinate? = TO) = StayDeriver.Gap(
        start = start,
        end = end,
        afterTrackId = 1,
        from = from?.let { StayDeriver.Gap.Side(it, clusterId = 0) },
        to = to?.let { StayDeriver.Gap.Side(it, clusterId = 1) },
    )

    private fun both(gap: StayDeriver.Gap) = gapBounds(gap, holdsDeparture = true, holdsArrival = true, flat)

    @Test fun `a row holding both ends states how long and how far apart`() {
        assertEquals(GapBounds.Both(START, START + HOUR, HOUR, 1_000.0), both(gap(START, START + HOUR)))
    }

    @Test fun `a gap under a minute states no duration`() {
        assertEquals(GapBounds.Both(START, START + 30_000, null, 1_000.0), both(gap(START, START + 30_000)))
    }

    @Test fun `a side with no fix states no distance`() {
        assertEquals(GapBounds.Both(START, START + HOUR, HOUR, null), both(gap(START, START + HOUR, to = null)))
    }

    @Test fun `a departure half states only where it runs on from`() {
        assertEquals(
            GapBounds.From(START),
            gapBounds(gap(START, START + HOUR), holdsDeparture = true, holdsArrival = false, flat),
        )
    }

    @Test fun `an arrival half states only what it ran until`() {
        assertEquals(
            GapBounds.Until(START + HOUR),
            gapBounds(gap(START, START + HOUR), holdsDeparture = false, holdsArrival = true, flat),
        )
    }

    @Test(expected = IllegalStateException::class)
    fun `a row holding neither end is refused`() {
        gapBounds(gap(START, START + HOUR), holdsDeparture = false, holdsArrival = false, flat)
    }

    private companion object {
        const val START = 1_000_000L
        const val HOUR = 3_600_000L
        val FROM = Coordinate(lat = 1.0, lon = -2.0)
        val TO = Coordinate(lat = 2.0, lon = -2.0)
    }
}
