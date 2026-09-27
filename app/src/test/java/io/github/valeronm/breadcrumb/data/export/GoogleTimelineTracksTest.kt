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

    private fun buildFlat(activities: List<GoogleTimelineActivity>, path: SortedPath) =
        GoogleTimelineTracks.build(activities, path, flatDistance)

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

    @Test fun `fixes carry no receiver readings`() {
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
}
