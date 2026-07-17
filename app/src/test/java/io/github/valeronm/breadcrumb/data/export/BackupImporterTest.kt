package io.github.valeronm.breadcrumb.data.export

import io.github.valeronm.breadcrumb.data.db.LivenessEvent
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.data.db.Track
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

/**
 * The restore parser, fed either real [BackupExporter.writeJson] output (round-trip — the
 * backup/restore contract) or hand-built documents (format evolution and rejection). Pure host
 * tests, like the writer's.
 */
class BackupImporterTest {

    private class Collected {
        val tracks = mutableListOf<Pair<Track, List<TrackPoint>>>()
        val totals = mutableListOf<Int?>()
        var places: List<Place> = emptyList()
        var liveness: List<LivenessEvent> = emptyList()
    }

    private fun parse(json: String): Collected {
        val out = Collected()
        runTest {
            BackupImporter.parse(
                StringReader(json),
                onTrack = { t, p, total -> out.tracks.add(t to p); out.totals.add(total) },
                onPlaces = { out.places = it },
                onLiveness = { out.liveness = it },
            )
        }
        return out
    }

    @Test fun `round-trips an export exactly, every field of every row`() {
        val track = Track(
            id = 3, activityType = "IN_VEHICLE", startedAt = 1_000L, endedAt = 9_000L,
            distanceMeters = 42.5, pointCount = 2, ignoredCount = 1,
            startLat = 1.25, startLon = 2.5, endLat = 3.75, endLon = -4.5,
        )
        val points = listOf(
            TrackPoint(
                id = 10, trackId = 3, latitude = 38.75, longitude = -9.25, altitude = 55.5,
                accuracy = 4.5f, speed = 1.25f, bearing = 270.5f, timestamp = 1_000L,
                verticalAccuracy = 2.5f, speedAccuracy = 0.5f, bearingAccuracy = 10.25f,
                satellitesInFix = 17, cn0 = 33.75f, segmentStart = true,
            ),
            TrackPoint(
                id = 11, trackId = 3, latitude = 38.76, longitude = -9.26, altitude = null,
                accuracy = null, speed = null, bearing = null, timestamp = 2_000L,
                ignored = true, ignoreReason = "JUMP",
            ),
        )
        val place = Place(id = 7, label = """Joe's "Bar"""", lat = 1.5, lon = 2.5, createdAt = 100L, radiusM = 60.0)
        val outage = LivenessEvent(id = 1, type = "OUTAGE", at = 10L, until = 20L)
        val armed = LivenessEvent(id = 2, type = "ARMED", at = 30L)

        val result = parse(
            exportJson(listOf(track), mapOf(3L to points), listOf(place), listOf(outage, armed)),
        )

        val (parsedTrack, parsedPoints) = result.tracks.single()
        assertEquals(track, parsedTrack)
        // Ids don't survive (insertion re-keys), everything else must.
        assertEquals(points.map { it.copy(id = 0) }, parsedPoints.map { it.copy(id = 0) })
        assertEquals(listOf<Int?>(1), result.totals) // trackCount rode along
        assertEquals(place, result.places.single().copy(id = place.id))
        assertEquals(listOf(outage, armed), result.liveness.mapIndexed { i, e -> e.copy(id = (i + 1).toLong()) })
    }

    @Test fun `null endpoint coordinates survive the round-trip`() {
        val track = Track(id = 1, activityType = "WALKING", startedAt = 1_000L, endedAt = 2_000L)
        val (parsed, _) = parse(exportJson(listOf(track), emptyMap())).tracks.single()
        assertNull(parsed.startLat)
        assertNull(parsed.endLon)
    }

    @Test fun `a future export with extra point fields in a new order still maps by name`() {
        val json = """
            {"format":"breadcrumb-export","version":1,"exportedAt":1,
             "pointFields":["newThing","lon","lat","timestamp"],
             "tracks":[{"id":1,"activityType":"WALKING","startedAt":1,"endedAt":2,
                        "futureFlag":true,"points":[[99,-9.25,38.75,1000]]}],
             "places":[],"liveness":[]}
        """.trimIndent()
        val (_, points) = parse(json).tracks.single()
        val p = points.single()
        assertEquals(38.75, p.latitude, 0.0)
        assertEquals(-9.25, p.longitude, 0.0)
        assertEquals(1_000L, p.timestamp)
        assertNull(p.altitude) // absent field -> null, not a crash
    }

    @Test fun `a foreign json file is rejected`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            parse("""{"format":"something-else","version":1,"pointFields":[],"tracks":[]}""")
        }
        assertTrue(e.message!!.contains("not a Breadcrumb export"))
    }

    @Test fun `a file without a format marker is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            parse("""{"version":1,"pointFields":[],"tracks":[]}""")
        }
    }

    @Test fun `a newer format version is rejected`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            parse("""{"format":"breadcrumb-export","version":2,"pointFields":[],"tracks":[]}""")
        }
        assertTrue(e.message!!.contains("newer app"))
    }

    @Test fun `truncated input fails rather than restoring half a file`() {
        val json = exportJson(
            listOf(Track(id = 1, activityType = "WALKING", startedAt = 1L, endedAt = 2L)),
            emptyMap(),
        )
        assertThrows(Exception::class.java) { parse(json.dropLast(10)) }
    }
}
