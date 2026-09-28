package io.github.valeronm.breadcrumb.data.export

import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.IgnoreReason
import io.github.valeronm.breadcrumb.domain.MIN
import io.github.valeronm.breadcrumb.domain.TrackOrigin
import io.github.valeronm.breadcrumb.domain.flatDistance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoogleTimelineTracksTest {

    private fun activity(startMs: Long, endMs: Long, type: String? = "WALKING") =
        GoogleTimelineActivity(startMs, endMs, Coordinate(1.0, -2.0), Coordinate(1.01, -2.0), type)

    private fun path(vararg times: Long) = PathSamples().apply {
        times.forEachIndexed { i, t -> add(t, 1.0 + i * 0.001, -2.0) }
    }.sortedUnique()

    private fun samplesAt(vararg timeAndLat: Pair<Long, Double>) = PathSamples().apply {
        timeAndLat.forEach { (t, lat) -> add(t, lat, -2.0) }
    }.sortedUnique()

    private val noFixes = PathSamples(withAccuracy = true).sortedUnique()

    private fun buildFlat(
        activities: List<GoogleTimelineActivity>,
        path: SortedPath,
        fixes: SortedPath = noFixes,
    ) = GoogleTimelineTracks.build(
        GoogleTimelineExport(activities, emptyList(), path, fixes, skipped = 0),
        { null },
        GATE_M,
        flatDistance,
    )

    @Test fun `a sample the activity could not have reached is a jump, and the next is judged from the last good one`() {
        val walk = GoogleTimelineActivity(0, 3 * MIN, Coordinate(1.0, -2.0), Coordinate(1.002, -2.0), "WALKING")
        val (_, points) = buildFlat(
            listOf(walk),
            samplesAt(MIN to 1.05, 2 * MIN to 1.001),
        ).single()
        assertEquals(listOf(false, true, false, false), points.map { it.ignored })
        assertEquals(IgnoreReason.JUMP.code, points[1].ignoreReason)
    }

    @Test fun `the ceiling is the activity's own`() {
        fun flagsFor(type: String) = buildFlat(
            listOf(GoogleTimelineActivity(0, 2 * MIN, Coordinate(1.0, -2.0), Coordinate(1.02, -2.0), type)),
            samplesAt(MIN to 1.01),
        ).single().second.map { it.ignored }
        assertEquals(listOf(false, true, true), flagsFor("WALKING"))
        assertEquals(listOf(false, false, false), flagsFor("IN_PASSENGER_VEHICLE"))
    }

    @Test fun `a track is its start, the samples strictly inside, and its end`() {
        val (track, points) =
            buildFlat(listOf(activity(100, 400)), path(50, 100, 200, 300, 400, 500)).single()
        assertEquals(listOf(100L, 200L, 300L, 400L), points.map { it.timestamp })
        assertEquals(1.0, points.first().latitude, 0.0)
        assertEquals(1.01, points.last().latitude, 0.0)
        assertEquals(100L, track.startedAt)
        assertEquals(400L, track.endedAt)
        assertEquals(TrackOrigin.GOOGLE_TIMELINE.code, track.source)
        assertEquals(ActivityType.WALKING.name, track.activityType)
    }

    @Test fun `an activity with no samples inside is its two endpoints`() {
        val (_, points) = buildFlat(listOf(activity(100, 400)), path(50, 500)).single()
        assertEquals(listOf(100L, 400L), points.map { it.timestamp })
    }

    @Test fun `samples between activities belong to neither`() {
        val tracks =
            buildFlat(listOf(activity(100, 200), activity(300, 400)), path(150, 250, 350)).toList()
        assertEquals(listOf(100L, 150L, 200L), tracks[0].second.map { it.timestamp })
        assertEquals(listOf(300L, 350L, 400L), tracks[1].second.map { it.timestamp })
    }

    @Test fun `tracks come out in start order whatever the file order`() {
        val tracks = buildFlat(listOf(activity(300, 400), activity(100, 200)), path()).toList()
        assertEquals(listOf(100L, 300L), tracks.map { it.first.startedAt })
    }

    @Test fun `path samples carry no receiver readings`() {
        val (_, points) = buildFlat(listOf(activity(100, 400)), path(200)).single()
        points.forEach {
            assertNull(it.accuracy)
            assertNull(it.speed)
            assertNull(it.altitude)
        }
    }

    @Test fun `Google's activities map onto the app's`() {
        val expected = mapOf(
            "WALKING" to ActivityType.WALKING,
            "RUNNING" to ActivityType.RUNNING,
            "CYCLING" to ActivityType.CYCLING,
            "IN_PASSENGER_VEHICLE" to ActivityType.DRIVING,
            "MOTORCYCLING" to ActivityType.DRIVING,
            "IN_TAXI" to ActivityType.TAXI,
            "IN_BUS" to ActivityType.TRANSIT,
            "IN_TRAM" to ActivityType.TRANSIT,
            "IN_TRAIN" to ActivityType.TRANSIT,
            "IN_SUBWAY" to ActivityType.TRANSIT,
            "IN_GONDOLA_LIFT" to ActivityType.TRANSIT,
            "IN_FUNICULAR" to ActivityType.TRANSIT,
            "IN_FERRY" to ActivityType.FERRY,
            "BOATING" to ActivityType.FERRY,
            "FLYING" to ActivityType.FLIGHT,
            "UNKNOWN_ACTIVITY_TYPE" to ActivityType.UNKNOWN,
            "SKIING" to ActivityType.UNKNOWN,
        )
        expected.forEach { (google, app) -> assertEquals(google, app, GoogleTimelineTracks.activityFor(google)) }
        assertEquals(ActivityType.UNKNOWN, GoogleTimelineTracks.activityFor(null))
    }

    // --- The phone's own fixes -------------------------------------------------------------------

    private fun fixesAt(vararg times: Long, accuracyM: Float = 10f) = PathSamples(withAccuracy = true).apply {
        times.forEachIndexed { i, t -> add(t, 1.0 + i * 0.001, -2.001, accuracyM) }
    }.sortedUnique()

    @Test fun `fixes spanning the activity replace the path samples inside it`() {
        val (_, points) = buildFlat(
            listOf(activity(100, 400)),
            path(200, 300),
            fixesAt(50, 150, 250, 350, 450),
        ).single()
        assertEquals(listOf(100L, 150L, 250L, 350L, 400L), points.map { it.timestamp })
    }

    @Test fun `fixes carry their accuracy alone, the endpoints nothing`() {
        val (_, points) = buildFlat(listOf(activity(100, 400)), path(), fixesAt(50, 150, 450)).single()
        assertEquals(listOf(null, 10f, null), points.map { it.accuracy })
        points.forEach {
            assertNull(it.altitude)
            assertNull(it.speed)
        }
    }

    @Test fun `an activity the fixes begin or end inside keeps its path samples`() {
        val beganInside = buildFlat(listOf(activity(100, 400)), path(200), fixesAt(150, 250, 450)).single()
        assertEquals(listOf(100L, 200L, 400L), beganInside.second.map { it.timestamp })
        val endedInside = buildFlat(listOf(activity(100, 400)), path(200), fixesAt(50, 250, 350)).single()
        assertEquals(listOf(100L, 200L, 400L), endedInside.second.map { it.timestamp })
    }

    @Test fun `an activity with no fix inside keeps its path samples`() {
        val (_, points) = buildFlat(listOf(activity(100, 400)), path(200), fixesAt(50, 450)).single()
        assertEquals(listOf(100L, 200L, 400L), points.map { it.timestamp })
    }

    @Test fun `a fix at the accuracy gate is ignored for accuracy`() {
        val (_, points) = buildFlat(
            listOf(activity(0, 3 * MIN, type = "IN_PASSENGER_VEHICLE")),
            path(),
            fixesAt(-MIN, MIN, 4 * MIN, accuracyM = GATE_M),
        ).single()
        assertEquals(listOf(false, true, false), points.map { it.ignored })
        assertEquals(IgnoreReason.ACCURACY.code, points[1].ignoreReason)
    }

    // --- Stated ends -----------------------------------------------------------------------------

    private fun visit(placeId: String, startMs: Long, endMs: Long, topLevel: Boolean = true) =
        GoogleTimelineVisit(startMs, endMs, placeId, "UNKNOWN", Coordinate(1.0, -2.0), topLevel)

    private val rows = mapOf("home" to 11L, "shop" to 12L, "mall" to 13L)

    private fun stated(activity: GoogleTimelineActivity, vararg visits: GoogleTimelineVisit) =
        GoogleTimelineTracks.build(
            GoogleTimelineExport(listOf(activity), visits.toList(), path(), noFixes, skipped = 0),
            rows::get,
            GATE_M,
            flatDistance,
        )
            .single().first.let { it.startPlaceId to it.endPlaceId }

    @Test fun `a trip between two visits is stated to both`() {
        assertEquals(
            11L to 12L,
            stated(activity(100, 400), visit("home", 0, 100), visit("shop", 400, 900)),
        )
    }

    @Test fun `a visit a second off the trip states nothing`() {
        assertEquals(
            null to null,
            stated(activity(100, 400), visit("home", 0, 99), visit("shop", 401, 900)),
        )
    }

    @Test fun `a nested visit is not what a trip is stated to`() {
        assertEquals(
            null to 13L,
            stated(
                activity(100, 400),
                visit("shop", 0, 100, topLevel = false),
                visit("mall", 400, 900),
                visit("shop", 400, 500, topLevel = false),
            ),
        )
    }

    private companion object {
        const val GATE_M = 50f
    }
}
