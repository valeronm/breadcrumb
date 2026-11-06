package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

class PathSmootherTest {

    private fun fix(
        second: Int,
        eastM: Double,
        northM: Double,
        accuracy: Float? = 3f,
        segmentStart: Boolean = false,
        originLon: Double = 0.0,
    ) = TrackPoint(
        trackId = 1,
        latitude = ORIGIN_LAT + northM / M_PER_DEG,
        longitude = originLon + eastM / (M_PER_DEG * cos(Math.toRadians(ORIGIN_LAT))),
        altitude = null,
        accuracy = accuracy,
        speed = null,
        bearing = null,
        timestamp = second * 1_000L,
        segmentStart = segmentStart,
    )

    private fun northM(lonLat: DoubleArray, fix: Int) = (lonLat[2 * fix + 1] - ORIGIN_LAT) * M_PER_DEG

    private fun eastM(lonLat: DoubleArray, fix: Int) = lonLat[2 * fix] * M_PER_DEG * cos(Math.toRadians(ORIGIN_LAT))

    private fun northM(fix: TrackPoint) = (fix.latitude - ORIGIN_LAT) * M_PER_DEG

    private fun eastM(fix: TrackPoint) = fix.longitude * M_PER_DEG * cos(Math.toRadians(ORIGIN_LAT))

    private fun walk(count: Int, northM: (Int) -> Double = { 0.0 }) = List(count) { fix(it, 1.4 * it, northM(it)) }

    @Test
    fun `jitter across a straight walk is averaged out`() {
        val smoothed = PathSmoother.lonLat(walk(120) { if (it % 2 == 0) 2.0 else -2.0 }, ActivityType.WALKING, unmeasured = false, smoothed = true)
        for (i in 10 until 110) assertEquals("fix $i", 0.0, northM(smoothed, i), 0.3)
    }

    @Test
    fun `a stray fix moves toward the path by its accuracy and no further`() {
        val points = walk(120).toMutableList()
        points[60] = fix(60, 1.4 * 60, 20.0)
        assertEquals(17.0, northM(PathSmoother.lonLat(points, ActivityType.WALKING, unmeasured = false, smoothed = true), 60), 0.01)
    }

    @Test
    fun `a stray fix far north of where its run began moves by its accuracy in true meters`() {
        val lat = 60.0
        val metersPerDegreeLon = M_PER_DEG * cos(Math.toRadians(lat))
        fun at(latitude: Double, longitude: Double, timestamp: Long) = TrackPoint(
            trackId = 1,
            latitude = latitude,
            longitude = longitude,
            altitude = null,
            accuracy = 3f,
            speed = null,
            bearing = null,
            timestamp = timestamp,
        )
        val walkNorth = List(120) { i ->
            at(lat + 1.4 * i / M_PER_DEG, if (i == 60) 20.0 / metersPerDegreeLon else 0.0, 3_600_000L + i * 1_000L)
        }

        val drawn = PathSmoother.lonLat(listOf(at(0.0, 0.0, 0L)) + walkNorth, ActivityType.WALKING, unmeasured = false, smoothed = true)

        assertEquals(17.0, drawn[2 * 61] * metersPerDegreeLon, 0.01)
    }

    @Test
    fun `a drive's turn at a junction keeps its shape`() {
        val speed = 5.0
        val radius = 12.0
        val straight = 30 * speed
        val arc = PI / 2 * radius
        val points = List(70) { second ->
            val along = second * speed
            val (east, north) = when {
                along <= straight -> along to 0.0
                along <= straight + arc -> ((along - straight) / radius).let { straight + radius * sin(it) to radius - radius * cos(it) }
                else -> straight + radius to radius + along - straight - arc
            }
            fix(second, east, north, accuracy = 5f)
        }
        val smoothed = PathSmoother.lonLat(points, ActivityType.DRIVING, unmeasured = false, smoothed = true)
        for (i in points.indices) {
            val moved = hypot(eastM(smoothed, i) - eastM(points[i]), northM(smoothed, i) - northM(points[i]))
            assertTrue("fix $i moved $moved m", moved < 2.0)
        }
    }

    @Test
    fun `a walk's slow wander is evened out more than a drive's`() {
        val points = walk(120) { 2.0 * sin(2 * PI * it / 10) }
        fun wander(activity: ActivityType): Double {
            val smoothed = PathSmoother.lonLat(points, activity, unmeasured = false, smoothed = true)
            return (10 until 110).sumOf { northM(smoothed, it).pow(2) }
        }
        assertTrue(wander(ActivityType.WALKING) < wander(ActivityType.DRIVING) / 2)
    }

    @Test
    fun `an unmeasured track is drawn through its fixes as recorded`() {
        val points = walk(120) { if (it % 2 == 0) 2.0 else -2.0 }
        val smoothed = PathSmoother.lonLat(points, ActivityType.WALKING, unmeasured = true, smoothed = true)
        assertEquals(points.flatMap { listOf(it.longitude, it.latitude) }, smoothed.toList())
    }

    @Test
    fun `a track drawn unsmoothed passes through its fixes as recorded`() {
        val points = walk(120) { if (it % 2 == 0) 2.0 else -2.0 }
        val drawn = PathSmoother.lonLat(points, ActivityType.WALKING, unmeasured = false, smoothed = false)
        assertEquals(points.flatMap { listOf(it.longitude, it.latitude) }, drawn.toList())
    }

    @Test
    fun `a measured track's fix without an accuracy is smoothed as a 5 m one`() {
        val points = List(120) { fix(it, 1.4 * it, if (it % 2 == 0) 2.0 else -2.0, accuracy = null) }.toMutableList()
        points[60] = fix(60, 1.4 * 60, 20.0, accuracy = null)
        val smoothed = PathSmoother.lonLat(points, ActivityType.WALKING, unmeasured = false, smoothed = true)
        assertEquals(15.0, northM(smoothed, 60), 0.01)
        for (i in 10 until 50) assertEquals("fix $i", 0.0, northM(smoothed, i), 0.3)
    }

    @Test
    fun `each run's first and last fix stay where they were recorded`() {
        val points = walk(120) { if (it % 2 == 0) 2.0 else -2.0 }
        val smoothed = PathSmoother.lonLat(points, ActivityType.WALKING, unmeasured = false, smoothed = true)
        for (i in listOf(0, points.lastIndex)) {
            assertEquals(points[i].latitude, smoothed[2 * i + 1], 0.0)
            assertEquals(points[i].longitude, smoothed[2 * i], 0.0)
        }
    }

    @Test
    fun `no fix is averaged across a break`() {
        val points = List(120) { fix(it, 1.4 * it, if (it < 60) 0.0 else 10.0, segmentStart = it == 60) }
        val smoothed = PathSmoother.lonLat(points, ActivityType.WALKING, unmeasured = false, smoothed = true)
        for (i in 0 until 60) assertEquals("fix $i", 0.0, northM(smoothed, i), 1e-6)
        for (i in 60 until 120) assertEquals("fix $i", 10.0, northM(smoothed, i), 1e-6)
    }

    @Test
    fun `a walk across the antimeridian stays on it`() {
        val points = List(120) { fix(it, 1.4 * (it - 60), if (it % 2 == 0) 2.0 else -2.0, originLon = 180.0) }
            .map { if (it.longitude > 180.0) it.copy(longitude = it.longitude - 360.0) else it }
        val smoothed = PathSmoother.lonLat(points, ActivityType.WALKING, unmeasured = false, smoothed = true)
        for (i in points.indices) assertTrue("fix $i at ${smoothed[2 * i]}", abs(smoothed[2 * i]) > 179.99)
    }

    private companion object {
        const val M_PER_DEG = 111_320.0
    }
}
