package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.domain.TrackOrigin
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A chip is an offer, so it must not lead to a gray line and a "no data" legend. The two reasons a
 * metric is dead are checked apart: what the track's writer never measures, and what these
 * particular fixes happen not to carry.
 */
class ColorModeAvailabilityTest {

    private fun pt(
        t: Long,
        altitude: Double? = null,
        accuracy: Float? = null,
        satellitesInFix: Int? = null,
        cn0: Float? = null,
    ) = TrackPoint(
        trackId = 1,
        latitude = 1.0,
        longitude = 0.0,
        altitude = altitude,
        accuracy = accuracy,
        speed = null,
        bearing = null,
        timestamp = t,
        satellitesInFix = satellitesInFix,
        cn0 = cn0,
    )

    private val fullyRecorded = listOf(
        pt(0, altitude = 12.0, accuracy = 5f, satellitesInFix = 9, cn0 = 30f),
        pt(1000, altitude = 14.0, accuracy = 6f, satellitesInFix = 8, cn0 = 28f),
    )

    @Test fun `a recording that carries everything is offered every metric`() {
        assertEquals(
            ColorMode.entries.toList(),
            availableColorModes(fullyRecorded, TrackOrigin.RECORDED),
        )
    }

    @Test fun `an import is not offered the recorder's own fix quality`() {
        // Even where the fixes somehow carry them: they describe a receiver measuring its own fix,
        // which a file did not do.
        assertEquals(
            listOf(ColorMode.SPEED, ColorMode.ELEVATION),
            availableColorModes(fullyRecorded, TrackOrigin.IMPORTED),
        )
    }

    @Test fun `an import without elevation is offered speed alone`() {
        val points = listOf(pt(0), pt(1000))
        assertEquals(listOf(ColorMode.SPEED), availableColorModes(points, TrackOrigin.IMPORTED))
    }

    @Test fun `an import with elevation keeps it`() {
        val points = listOf(pt(0, altitude = 3.0), pt(1000, altitude = 9.0))
        assertEquals(
            listOf(ColorMode.SPEED, ColorMode.ELEVATION),
            availableColorModes(points, TrackOrigin.IMPORTED),
        )
    }

    @Test fun `a recording that saw no satellites drops those two chips`() {
        // The source can't answer this one — the track really was recorded, it just ran without a
        // GnssStatus to count from.
        val points = listOf(pt(0, altitude = 1.0, accuracy = 4f), pt(1000, altitude = 2.0, accuracy = 5f))
        assertEquals(
            listOf(ColorMode.SPEED, ColorMode.ELEVATION, ColorMode.ACCURACY),
            availableColorModes(points, TrackOrigin.RECORDED),
        )
    }

    @Test fun `one fix carrying a metric is enough to offer it`() {
        val points = listOf(pt(0), pt(1000, satellitesInFix = 7), pt(2000))
        assertEquals(
            listOf(ColorMode.SPEED, ColorMode.SATELLITES),
            availableColorModes(points, TrackOrigin.RECORDED),
        )
    }

    @Test fun `an unknown writer claims nothing - the fixes decide alone`() {
        assertEquals(ColorMode.entries.toList(), availableColorModes(fullyRecorded, null))
    }

    @Test fun `a track with no points still offers speed, never an empty row`() {
        assertEquals(listOf(ColorMode.SPEED), availableColorModes(emptyList(), TrackOrigin.RECORDED))
    }
}
