package io.github.valeronm.breadcrumb.data.export

import io.github.valeronm.breadcrumb.data.db.Track
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The GPX serializer, driven directly through [GpxExporter.buildGpx] on the host — it's pure
 * (SimpleDateFormat/Date over the track and points, no Android). Asserts the structural rules:
 * a <trkseg> split at each segment start, <ele> only when altitude is present, ISO-8601 UTC <time>.
 */
class GpxExporterTest {

    private fun track(activityType: String = "WALKING", startedAt: Long = 0L) =
        Track(activityType = activityType, startedAt = startedAt)

    private fun point(
        timestamp: Long,
        lat: Double = 1.0,
        lon: Double = 2.0,
        altitude: Double? = null,
        speed: Float? = null,
        segmentStart: Boolean = false,
    ) = TrackPoint(
        trackId = 1,
        latitude = lat,
        longitude = lon,
        altitude = altitude,
        accuracy = null,
        speed = speed,
        bearing = null,
        timestamp = timestamp,
        segmentStart = segmentStart,
    )

    private fun countOf(haystack: String, needle: String): Int = haystack.split(needle).size - 1

    @Test fun `renders a gpx 1_1 document wrapping one trkseg`() {
        val gpx = GpxExporter.buildGpx(track(), listOf(point(0), point(1_000)))
        assertTrue(gpx.startsWith("""<?xml version="1.0" encoding="UTF-8"?>"""))
        assertTrue(gpx.contains("""<gpx version="1.1" creator="Breadcrumb""""))
        assertTrue(gpx.trimEnd().endsWith("</gpx>"))
        assertEquals(1, countOf(gpx, "<trkseg>"))
        assertEquals(1, countOf(gpx, "</trkseg>"))
        assertEquals(2, countOf(gpx, "<trkpt"))
    }

    @Test fun `a segment start after the first point opens a new trkseg`() {
        val gpx = GpxExporter.buildGpx(
            track(),
            listOf(point(0), point(1_000, segmentStart = true), point(2_000)),
        )
        assertEquals(2, countOf(gpx, "<trkseg>"))
        assertEquals(2, countOf(gpx, "</trkseg>"))
    }

    @Test fun `a segment start on the very first point does not open an extra trkseg`() {
        val gpx = GpxExporter.buildGpx(track(), listOf(point(0, segmentStart = true), point(1_000)))
        assertEquals(1, countOf(gpx, "<trkseg>"))
    }

    @Test fun `altitude is emitted only when present`() {
        val gpx = GpxExporter.buildGpx(
            track(),
            listOf(point(0, altitude = 30.5), point(1_000, altitude = null)),
        )
        assertEquals(1, countOf(gpx, "<ele>"))
        assertTrue(gpx.contains("<ele>30.5</ele>"))
    }

    @Test fun `speed is emitted as a TrackPointExtension only when present`() {
        val gpx = GpxExporter.buildGpx(
            track(),
            listOf(point(0, speed = 1.25f), point(1_000, speed = null)),
        )
        assertEquals(1, countOf(gpx, "<extensions>"))
        assertTrue(gpx.contains("<gpxtpx:TrackPointExtension><gpxtpx:speed>1.25</gpxtpx:speed>"))
    }

    @Test fun `timestamps are ISO-8601 UTC`() {
        // 1000 ms past the epoch is 1970-01-01T00:00:01Z.
        val gpx = GpxExporter.buildGpx(track(), listOf(point(1_000), point(2_000)))
        assertTrue(gpx.contains("<time>1970-01-01T00:00:01Z</time>"))
    }

    @Test fun `a trackpoint carries its lat and lon`() {
        val gpx = GpxExporter.buildGpx(track(), listOf(point(0, lat = 12.5, lon = -3.25), point(1_000)))
        assertTrue(gpx.contains("""<trkpt lat="12.5" lon="-3.25">"""))
    }
}
