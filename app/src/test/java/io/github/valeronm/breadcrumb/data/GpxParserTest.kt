package io.github.valeronm.breadcrumb.data

import io.github.valeronm.breadcrumb.data.db.Track
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.data.export.GpxExporter
import io.github.valeronm.breadcrumb.data.export.GpxParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** GPX import parsing: round-trips our own exports and tolerates foreign files. */
class GpxParserTest {

    private val flatDistance = DistanceFn { aLat, aLon, bLat, bLon ->
        maxOf(Math.abs(aLat - bLat), Math.abs(aLon - bLon)) * 100_000.0
    }

    private fun parse(gpx: String) = GpxParser.parse(gpx.byteInputStream())

    private fun point(trackId: Long, i: Int, ts: Long, segmentStart: Boolean = false) = TrackPoint(
        trackId = trackId, latitude = 1.0 + i * 0.001, longitude = 2.0, altitude = 30.0 + i,
        accuracy = 5f, speed = 1f, bearing = null, timestamp = ts, segmentStart = segmentStart,
    )

    @Test fun `round-trips our own export including segments and type`() {
        val track = Track(id = 7, activityType = "WALKING", startedAt = 1_000_000, endedAt = 1_060_000)
        val points = listOf(
            point(7, 0, 1_000_000),
            point(7, 1, 1_010_000),
            point(7, 2, 1_050_000, segmentStart = true), // auto-pause resume → new <trkseg>
            point(7, 3, 1_060_000),
        )
        val gpx = GpxExporter.buildGpx(track, points)

        val parsed = parse(gpx).single()
        assertEquals("WALKING", parsed.type)
        assertEquals(2, parsed.segments.size)
        assertEquals(listOf(2, 2), parsed.segments.map { it.size })

        val importable = GpxParser.toImportable(parsed, flatDistance)!!
        assertEquals("WALKING", importable.activityTypeName)
        assertEquals(1_000_000, importable.startedAt)
        assertEquals(1_060_000, importable.endedAt)
        assertEquals(4, importable.points.size)
        // The second segment's first point carries the segment break; nothing else does.
        assertEquals(listOf(false, false, true, false), importable.points.map { it.segmentStart })
        // Distance sums within segments only: (0→1) + (2→3) = 100 + 100 in the flat metric.
        assertEquals(200.0, importable.distanceMeters, 1e-6)
        assertEquals(30.0, importable.points.first().ele!!, 1e-6)
    }

    @Test fun `parses a minimal foreign gpx with offsets and fractions`() {
        val gpx = """
            <?xml version="1.0"?>
            <gpx version="1.1" creator="other-app" xmlns="http://www.topografix.com/GPX/1/1">
              <wpt lat="9" lon="9"><name>ignored</name></wpt>
              <trk>
                <name>Morning hike</name>
                <type>hiking</type>
                <trkseg>
                  <trkpt lat="38.7" lon="-9.3"><time>2026-07-01T10:00:00.500Z</time></trkpt>
                  <trkpt lat="38.701" lon="-9.3">
                    <ele>25.5</ele>
                    <time>2026-07-01T12:00:00+01:00</time>
                    <extensions><speed>1.5</speed></extensions>
                  </trkpt>
                </trkseg>
              </trk>
            </gpx>
        """.trimIndent()
        val importable = GpxParser.toImportable(parse(gpx).single(), flatDistance)!!
        assertEquals("WALKING", importable.activityTypeName) // "hiking" alias
        assertEquals(2, importable.points.size)
        // 12:00+01:00 == 11:00Z, one hour after the 10:00:00.500Z start (less the half second).
        assertEquals(3_600_000 - 500, importable.endedAt - importable.startedAt)
        assertEquals(25.5, importable.points[1].ele!!, 1e-6)
    }

    @Test fun `unknown and missing types default to DRIVING`() {
        val gpx = """
            <gpx><trk><type>kayaking</type><trkseg>
              <trkpt lat="1" lon="1"><time>2026-01-01T00:00:00Z</time></trkpt>
              <trkpt lat="1.001" lon="1"><time>2026-01-01T00:01:00Z</time></trkpt>
            </trkseg></trk></gpx>
        """.trimIndent()
        assertEquals("DRIVING", GpxParser.toImportable(parse(gpx).single(), flatDistance)!!.activityTypeName)
    }

    @Test fun `untimed points are dropped and an untimeable track is rejected`() {
        val gpx = """
            <gpx><trk><trkseg>
              <trkpt lat="1" lon="1"/>
              <trkpt lat="1.001" lon="1"><time>2026-01-01T00:01:00Z</time></trkpt>
            </trkseg></trk></gpx>
        """.trimIndent()
        // Only one timed point survives — not enough for a track.
        assertNull(GpxParser.toImportable(parse(gpx).single(), flatDistance))
    }

    @Test fun `points are re-ordered by time within a segment`() {
        val gpx = """
            <gpx><trk><trkseg>
              <trkpt lat="1.001" lon="1"><time>2026-01-01T00:02:00Z</time></trkpt>
              <trkpt lat="1.000" lon="1"><time>2026-01-01T00:00:00Z</time></trkpt>
            </trkseg></trk></gpx>
        """.trimIndent()
        val importable = GpxParser.toImportable(parse(gpx).single(), flatDistance)!!
        assertEquals(1.0, importable.points.first().lat, 1e-9)
        assertTrue(importable.startedAt < importable.endedAt)
    }

    @Test fun `multiple tracks in one file parse independently`() {
        val seg = """
            <trkseg>
              <trkpt lat="1" lon="1"><time>2026-01-01T00:00:00Z</time></trkpt>
              <trkpt lat="1.001" lon="1"><time>2026-01-01T00:01:00Z</time></trkpt>
            </trkseg>
        """
        val gpx = "<gpx><trk><type>RUNNING</type>$seg</trk><trk>$seg</trk></gpx>"
        val parsed = parse(gpx)
        assertEquals(2, parsed.size)
        assertEquals("RUNNING", parsed[0].type)
        assertNull(parsed[1].type)
    }

    @Test fun `a bare local datetime is read as UTC`() {
        val gpx = """
            <gpx><trk><trkseg>
              <trkpt lat="1" lon="1"><time>2026-01-01T00:00:00</time></trkpt>
              <trkpt lat="1.001" lon="1"><time>2026-01-01T00:01:00</time></trkpt>
            </trkseg></trk></gpx>
        """.trimIndent()
        val importable = GpxParser.toImportable(parse(gpx).single(), flatDistance)!!
        assertEquals(60_000, importable.endedAt - importable.startedAt)
    }
}
