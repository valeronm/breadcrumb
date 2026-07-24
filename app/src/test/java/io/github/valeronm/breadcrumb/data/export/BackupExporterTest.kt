package io.github.valeronm.breadcrumb.data.export

import io.github.valeronm.breadcrumb.data.db.LivenessEvent
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.data.db.Track
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.StringReader

/**
 * The backup-export JSON writer, driven directly through [BackupExporter.writeJson] with
 * in-memory data — it's pure, so this runs on the host with no Robolectric. Output is checked by
 * parsing it back with the production [JsonPullReader] (the same parser restore uses), so
 * well-formedness is proven against the reader that actually has to read it. Asserts the format
 * contract: header fields, per-point arrays in `pointFields` order, nulls kept, strings escaped.
 */
class BackupExporterTest {

    private fun track(id: Long, activityType: String = "WALKING") = Track(
        id = id,
        activityType = activityType,
        startedAt = 1_000L,
        endedAt = 2_000L,
        distanceMeters = 123.5,
        pointCount = 2,
        ignoredCount = 1,
        startLat = 1.0,
        startLon = 2.0,
        endLat = 3.0,
        endLon = 4.0,
    )

    private fun point(
        timestamp: Long,
        ignored: Boolean = false,
        ignoreReason: String? = null,
        segmentStart: Boolean = false,
    ) = TrackPoint(
        trackId = 1,
        latitude = 1.05,
        longitude = -2.05,
        altitude = null,
        accuracy = 5.5f,
        speed = null,
        bearing = null,
        timestamp = timestamp,
        satellitesInFix = 12,
        cn0 = 33.25f,
        ignored = ignored,
        ignoreReason = ignoreReason,
        segmentStart = segmentStart,
    )

    /** Builds the whole document tree through the production pull-reader. */
    private fun JsonPullReader.readValue(): Any? = when (peekChar()) {
        '{' -> LinkedHashMap<String, Any?>().also { map ->
            beginObject()
            while (hasNext()) map[nextName()] = readValue()
            endObject()
        }
        '[' -> ArrayList<Any?>().also { list ->
            beginArray()
            while (hasNext()) list.add(readValue())
            endArray()
        }
        else -> nextPrimitive()
    }

    @Suppress("UNCHECKED_CAST")
    private fun export(
        tracks: List<Track> = emptyList(),
        points: Map<Long, List<TrackPoint>> = emptyMap(),
        places: List<Place> = emptyList(),
        liveness: List<LivenessEvent> = emptyList(),
    ): Map<String, Any?> {
        val reader = JsonPullReader(StringReader(exportJson(tracks, points, places, liveness)))
        val doc = reader.readValue() as Map<String, Any?>
        reader.expectEnd()
        return doc
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.obj() = this as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun Any?.arr() = this as List<Any?>

    @Test fun `header carries format, version, stamp and the point field order`() {
        val doc = export()
        assertEquals("breadcrumb-export", doc["format"])
        assertEquals(1L, doc["version"])
        assertEquals(5_000L, doc["exportedAt"])
        assertEquals(0L, doc["trackCount"])
        val fields = doc["pointFields"].arr()
        assertEquals(BackupExporter.POINT_FIELDS, fields)
        assertEquals(0, doc["tracks"].arr().size)
    }

    @Test fun `a track carries its row fields and its points as arrays in field order`() {
        val doc = export(
            tracks = listOf(track(1)),
            points = mapOf(1L to listOf(point(1_000), point(1_500, segmentStart = true))),
        )
        val t = doc["tracks"].arr().single().obj()
        assertEquals(1L, t["id"])
        assertEquals("WALKING", t["activityType"])
        assertEquals(2_000L, t["endedAt"])
        assertEquals(123.5, t["distanceMeters"])
        val points = t["points"].arr()
        assertEquals(2, points.size)
        val p = points[0].arr()
        assertEquals(BackupExporter.POINT_FIELDS.size, p.size)
        assertEquals(1_000L, p[0]) // timestamp
        assertEquals(1.05, p[1]) // lat
        assertEquals(-2.05, p[2]) // lon
        assertNull(p[3]) // altitude was null
        assertEquals(5.5, p[4]) // accuracy
        assertEquals(12L, p[10]) // satellitesInFix
        assertEquals(0L, p[14]) // segmentStart
        assertEquals(1L, points[1].arr()[14])
    }

    @Test fun `ignored points ride along with their reason`() {
        val doc = export(
            tracks = listOf(track(1)),
            points = mapOf(1L to listOf(point(1_000, ignored = true, ignoreReason = "JUMP"))),
        )
        val p = doc["tracks"].arr().single().obj()["points"].arr().single().arr()
        assertEquals(1L, p[12]) // ignored
        assertEquals("JUMP", p[13])
    }

    @Test fun `places and liveness events are carried whole`() {
        val doc = export(
            places = listOf(Place(id = 7, label = "Home", lat = 1.5, lon = 2.5, createdAt = 100L, radiusM = 60.0)),
            liveness = listOf(LivenessEvent(id = 1, type = "OUTAGE", at = 10L, until = 20L)),
        )
        val place = doc["places"].arr().single().obj()
        assertEquals(7L, place["id"])
        assertEquals("Home", place["label"])
        assertEquals(60.0, place["radiusM"])
        val event = doc["liveness"].arr().single().obj()
        assertEquals("OUTAGE", event["type"])
        assertEquals(20L, event["until"])
    }

    @Test fun `an ARMED event's open until stays null`() {
        val doc = export(liveness = listOf(LivenessEvent(id = 1, type = "ARMED", at = 10L)))
        assertNull(doc["liveness"].arr().single().obj()["until"])
    }

    @Test fun `labels with quotes and backslashes survive escaping`() {
        val doc = export(
            places = listOf(Place(id = 1, label = """Joe's "Bar" \ Grill""", lat = 0.0, lon = 0.0, createdAt = 0L, radiusM = 150.0)),
        )
        assertEquals("""Joe's "Bar" \ Grill""", doc["places"].arr().single().obj()["label"])
    }
}
