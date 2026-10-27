package io.github.valeronm.breadcrumb.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.random.Random

class CountryBordersTest {

    private val neighbors = bordersOf(
        listOf(box("PT", west = -10_000.0, east = 0.0), box("ES", west = 0.0, east = 10_000.0)),
    )

    @Test fun `a point takes the country of the outline it sits in`() {
        assertEquals("PT", neighbors.countryAt(ORIGIN_LAT, lonAt(-1_000.0)))
        assertEquals("ES", neighbors.countryAt(ORIGIN_LAT, lonAt(1_000.0)))
    }

    @Test fun `a point outside every outline has no country`() {
        assertNull(neighbors.countryAt(ORIGIN_LAT, lonAt(20_000.0)))
    }

    @Test fun `an enclave is a hole in its neighbor and an outline of its own`() {
        val borders = bordersOf(
            listOf(
                box("ZA", west = -10_000.0, east = 10_000.0, holes = listOf(rectangle(-1_000.0, 1_000.0, -1_000.0, 1_000.0))),
                box("LS", west = -1_000.0, east = 1_000.0, south = -1_000.0, north = 1_000.0),
            ),
        )

        assertEquals("LS", borders.countryAt(ORIGIN_LAT, lonAt(0.0)))
        assertEquals("ZA", borders.countryAt(ORIGIN_LAT, lonAt(5_000.0)))
    }

    @Test fun `where outlines overlap, the first in the file holds the point`() {
        val borders = bordersOf(
            listOf(box("PT", west = -10_000.0, east = 10_000.0), box("ES", west = 0.0, east = 20_000.0)),
        )

        assertEquals("PT", borders.countryAt(ORIGIN_LAT, lonAt(5_000.0)))
        assertEquals("ES", borders.countryAt(ORIGIN_LAT, lonAt(15_000.0)))
    }

    @Test fun `a point is inside exactly where a ray from it crosses the rings an odd number of times`() {
        val random = Random(3)
        val star = List(40) { k ->
            val angle = 2 * PI * k / 40
            val reach = if (k % 2 == 0) 60_000.0 else 15_000.0 + random.nextDouble() * 20_000.0
            latAt(reach * sin(angle)) to lonAt(reach * cos(angle))
        }
        val hole = rectangle(-5_000.0, 5_000.0, -5_000.0, 5_000.0)
        val borders = bordersOf(listOf(TestOutline("PT", listOf(star, hole))))

        repeat(2_000) {
            val lat = latAt(random.nextDouble(-70_000.0, 70_000.0))
            val lon = lonAt(random.nextDouble(-70_000.0, 70_000.0))
            val inside = listOf(star, hole).count { crossedOddly(it, lat, lon) } % 2 == 1
            assertEquals("at $lat, $lon", if (inside) "PT" else null, borders.countryAt(lat, lon))
        }
    }

    @Test fun `the nearest outline is measured to its edge`() {
        val borders = bordersOf(
            listOf(box("ES", west = -20_000.0, east = -10_000.0), box("PT", west = 0.0, east = 10_000.0)),
        )

        val nearest = borders.nearestOutline(ORIGIN_LAT, lonAt(-3_000.0), flatDistance)!!
        assertEquals("PT", nearest.country)
        assertEquals(3_000.0, nearest.distanceM, 1.0)
    }

    @Test fun `no outline at all is no nearest outline`() {
        assertNull(bordersOf(emptyList()).nearestOutline(ORIGIN_LAT, lonAt(0.0), flatDistance))
    }

    @Test fun `every outline reads back in degrees, in file order`() {
        val borders = bordersOf(
            listOf(box("PT", west = -10_000.0, east = 0.0), box("ES", west = 20_000.0, east = 30_000.0)),
        )

        val shapes = borders.shapes()

        assertEquals(listOf("PT", "ES"), shapes.map { it.country })
        val ring = shapes.first().rings.single()
        assertEquals(latAt(-50_000.0), ring[0], 1e-5)
        assertEquals(lonAt(-10_000.0), ring[1], 1e-5)
    }

    @Test fun `a borders file labeled against another atlas is rejected at parse`() {
        assertThrows(IllegalArgumentException::class.java) {
            bordersOf(listOf(box("PT", west = 0.0, east = 1.0)), rowCount = 3, parsedFor = 4)
        }
    }

    @Test fun `anything that is not a borders file is rejected at parse`() {
        assertThrows(IllegalArgumentException::class.java) { CountryBorders.parse(ByteArray(0), 0) }
        assertThrows(IllegalArgumentException::class.java) {
            CountryBorders.parse("BCTY1-not-borders".toByteArray(), 0)
        }
    }

    @Test fun `a truncated borders file is rejected at parse`() {
        val whole = bordersBytes(listOf(box("PT", west = 0.0, east = 1_000.0)))

        assertThrows(IllegalArgumentException::class.java) {
            CountryBorders.parse(whole.copyOf(whole.size - 3), 0)
        }
    }

    @Test fun `a country code that is not two capital letters is rejected at parse`() {
        assertThrows(IllegalArgumentException::class.java) {
            bordersOf(listOf(box("p1", west = 0.0, east = 1_000.0)))
        }
    }
}

/** The crossing count read off every edge of [ring], with no index. */
private fun crossedOddly(ring: List<Pair<Double, Double>>, lat: Double, lon: Double): Boolean {
    val points = ring.map { (a, b) -> (a * 1e5).roundToLong() / 1e5 to (b * 1e5).roundToLong() / 1e5 }
    var odd = false
    for (i in points.indices) {
        val (latI, lonI) = points[i]
        val (latJ, lonJ) = points[(i + points.size - 1) % points.size]
        if ((latI > lat) != (latJ > lat) && lon < (lonJ - lonI) * (lat - latI) / (latJ - latI) + lonI) odd = !odd
    }
    return odd
}
