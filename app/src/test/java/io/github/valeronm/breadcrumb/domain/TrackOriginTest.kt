package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackOriginTest {

    private fun pt(t: Long, accuracy: Float?, ignored: Boolean = false) = TrackPoint(
        trackId = 1,
        latitude = 1.0,
        longitude = 0.0,
        altitude = null,
        accuracy = accuracy,
        speed = null,
        bearing = null,
        timestamp = t,
        ignored = ignored,
        ignoreReason = if (ignored) IgnoreReason.ACCURACY.code else null,
    )

    @Test fun `codes survive a round trip through the stored string`() {
        for (origin in TrackOrigin.entries) {
            assertEquals(origin, TrackOrigin.fromCode(origin.code))
        }
    }

    @Test fun `an unknown or absent code is no writer, not a default one`() {
        assertNull(TrackOrigin.fromCode(null))
        assertNull(TrackOrigin.fromCode("synced"))
    }

    @Test fun `fixes carrying an accuracy radius are the recorder's`() {
        val points = (0 until 5).map { pt(it * 1000L, 8f) }
        assertEquals(TrackOrigin.RECORDED, TrackOrigin.inferFrom(points))
    }

    @Test fun `points without a single accuracy radius came from a file`() {
        val points = (0 until 5).map { pt(it * 1000L, null) }
        assertEquals(TrackOrigin.IMPORTED, TrackOrigin.inferFrom(points))
    }

    @Test fun `one accuracy among nulls still names the recorder`() {
        // The platform can withhold a radius on a fix, so a missing one proves nothing while a
        // single present one proves the writer stores them.
        val points = listOf(pt(0, null), pt(1000, null), pt(2000, 12f), pt(3000, null))
        assertEquals(TrackOrigin.RECORDED, TrackOrigin.inferFrom(points))
    }

    @Test fun `an ignored fix is evidence like any other`() {
        val points = listOf(pt(0, null), pt(1000, 90f, ignored = true))
        assertEquals(TrackOrigin.RECORDED, TrackOrigin.inferFrom(points))
    }

    @Test fun `an empty track names no writer`() {
        assertNull(TrackOrigin.inferFrom(emptyList()))
    }
}
