package io.github.valeronm.breadcrumb.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.function.IntPredicate
import kotlin.math.asin
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Rows are invented and scattered over the whole globe rather than set at the neutral origin, since the
 * poles and the antimeridian are where a bound in degrees of longitude breaks.
 */
class LatitudeIndexTest {

    private class Points(private val lats: IntArray, private val lons: IntArray) : LatitudeIndex.Rows {
        override val count get() = lats.size

        override fun latE6(row: Int) = lats[row]

        override fun lonE6(row: Int) = lons[row]

        fun distanceTo(row: Int, lat: Double, lon: Double, distance: DistanceFn) =
            distance.meters(lat, lon, lats[row] / 1e6, lons[row] / 1e6)
    }

    /** Uniform over the sphere, in microdegrees. */
    private fun Random.pointE6(): Pair<Int, Int> {
        val lat = Math.toDegrees(asin(nextDouble(-1.0, 1.0)))
        return (lat * 1e6).roundToInt() to (nextDouble(-180.0, 180.0) * 1e6).roundToInt()
    }

    private fun Random.points(count: Int): Points {
        val sorted = List(count) { pointE6() }.sortedBy { it.first }
        return Points(IntArray(count) { sorted[it].first }, IntArray(count) { sorted[it].second })
    }

    private val distances = listOf("sphere" to sphereDistance, "flat" to flatDistance)

    @Test fun `the nearest row a filter accepts is the one brute force finds, anywhere on earth`() {
        val random = Random(42)
        for ((name, distance) in distances) {
            for (count in listOf(30, 3_000)) {
                val points = random.points(count)
                val index = LatitudeIndex(points)
                repeat(500) {
                    val (latE6, lonE6) = random.pointE6()
                    val lat = latE6 / 1e6
                    val lon = lonE6 / 1e6
                    val every = random.nextInt(1, 4)
                    val accept = IntPredicate { row -> row % every == 0 }
                    val expected = (0 until count).filter(accept::test).minBy { points.distanceTo(it, lat, lon, distance) }
                    assertEquals("$name, query $lat, $lon", expected, index.nearest(lat, lon, distance, accept)?.row)
                }
            }
        }
    }

    @Test fun `every row within reach is visited, anywhere on earth`() {
        val random = Random(43)
        for ((name, distance) in distances) {
            val points = random.points(3_000)
            val index = LatitudeIndex(points)
            repeat(500) {
                val (latE6, lonE6) = random.pointE6()
                val lat = latE6 / 1e6
                val lon = lonE6 / 1e6
                val reachM = 10.0.pow(random.nextDouble(3.0, 6.7))
                val visited = HashSet<Int>()
                index.forEachNear(lat, lon, reachM, distance) { visited += it }
                val within = (0 until points.count).filter { points.distanceTo(it, lat, lon, distance) <= reachM }
                assertTrue("$name, query $lat, $lon, reach $reachM", visited.containsAll(within))
            }
        }
    }

    @Test fun `an empty index finds nothing`() {
        assertNull(LatitudeIndex(Points(IntArray(0), IntArray(0))).nearest(0.0, 0.0, sphereDistance) { true })
    }

    @Test fun `a filter that accepts no row finds nothing`() {
        val index = LatitudeIndex(Random(44).points(100))

        assertNull(index.nearest(0.0, 0.0, sphereDistance) { false })
    }

    @Test fun `rows out of latitude order are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            LatitudeIndex(Points(intArrayOf(2_000_000, 1_000_000), intArrayOf(0, 0)))
        }
    }
}
