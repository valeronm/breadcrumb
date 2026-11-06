package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class SeaLevelTest {

    private fun pt(latitude: Double, altitude: Double?) = TrackPoint(
        trackId = 1,
        latitude = latitude,
        longitude = 0.0,
        altitude = altitude,
        accuracy = null,
        speed = null,
        bearing = null,
        timestamp = 0,
    )

    /** A geoid that rises a meter per tenth of a degree north, so each point's own position shows. */
    private val sloped = GeoidHeight { latitude, _ -> 50.0 + latitude * 10 }

    private val track = listOf(pt(0.0, 60.0), pt(0.5, 62.0), pt(1.0, null))

    @Test fun `a recorded track's altitudes are lowered by the geoid height where each fix was taken`() {
        assertEquals(listOf(10.0, 7.0, null), SeaLevel.altitudes(track, TrackOrigin.RECORDED, sloped))
    }

    @Test fun `a recorded track has no altitudes where the geoid height is unknown`() {
        assertEquals(listOf(null, null, null), SeaLevel.altitudes(track, TrackOrigin.RECORDED) { _, _ -> null })
    }

    @Test fun `every other writer's altitudes are shown as stored`() {
        for (origin in TrackOrigin.entries.filter { it != TrackOrigin.RECORDED } + null) {
            assertEquals(listOf(60.0, 62.0, null), SeaLevel.altitudes(track, origin, sloped))
        }
    }
}
