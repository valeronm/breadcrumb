package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.domain.PathSmoother
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class CasedLineTest {

    @Test fun `a run turning less than a quarter circle is one stretch`() {
        val line = recordedLine(walk(0.0, 10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0), oneColor(10))

        assertEquals(listOf(0..9), colorStrokes(listOf(line)).map { it.fixes })
    }

    @Test fun `a run is cut where it has turned a quarter circle, both stretches keeping that fix`() {
        val line = recordedLine(walk(0.0, 40.0, 80.0, 120.0, 160.0), oneColor(6))

        assertEquals(listOf(0..3, 3..5), colorStrokes(listOf(line)).map { it.fixes })
    }

    @Test fun `where the line folds back on itself, the return is drawn over the way out, casing and all`() {
        val line = recordedLine(walkLegs(List(5) { 0.0 to 0.1 } + List(5) { 175.0 to 0.1 }), oneColor(11))
        val strokes = casedStrokes(listOf(line), CLOSE)

        val out = strokes.indexOfFirst { it.pen == Pen.COLOR && 0 in it.fixes }
        val back = strokes.indexOfFirst { it.pen == Pen.CASING_BORDER && 10 in it.fixes }
        assertTrue(back > out)
    }

    @Test fun `a fold narrower than the casing at the next band's zoom is no fold`() {
        val line = recordedLine(walkLegs(List(5) { 0.0 to 0.1 } + List(5) { 175.0 to 0.1 }), oneColor(11))

        assertEquals(listOf(0..10), casedStrokes(listOf(line), zoom = 14).filter { it.pen == Pen.COLOR }.map { it.fixes })
    }

    @Test fun `a leg shorter than the band's casing but wider than the next band's turns two corners, not a fold`() {
        val line = recordedLine(walkLegs(List(3) { 90.0 to 1.0 } + (0.0 to 0.3) + List(3) { 270.0 to 1.0 }), oneColor(8))

        assertCasedBeforeColored(casedStrokes(listOf(line), zoom = 16))
    }

    @Test fun `a stop's flicker of colors is drawn as one piece, no casing across it`() {
        val stop = List(6) { i -> (if (i % 2 == 0) 90.0 else 270.0) to 0.01 }
        val path = walkLegs(List(3) { 0.0 to 1.0 } + stop + List(3) { 0.0 to 1.0 })
        val colors = IntArray(13) { i ->
            when {
                i !in 4..9 -> RED
                i % 2 == 0 -> GREEN
                else -> BLUE
            }
        }
        val strokes = casedStrokes(listOf(recordedLine(path, colors)), zoom = 16)

        assertCasedBeforeColored(strokes, strokes.filter { it.fixes.first >= 3 && it.fixes.last <= 9 }.map { it.fixes }.toSet())
    }

    @Test fun `a band draws a straight run as its two ends`() {
        val path = walkLegs(List(10) { 0.0 to 0.1 })
        val drawn = bandLine(recordedLine(path, oneColor(11)), zoom = 16)

        assertEquals(listOf(0, 10).map { path[it].latitude to path[it].longitude }, drawn.positions.map { it.lat to it.lon })
    }

    @Test fun `a band keeps the fix a straight line changes color at`() {
        val path = walkLegs(List(10) { 0.0 to 0.1 })
        val colors = IntArray(11) { if (it <= 5) RED else BLUE }
        val drawn = bandLine(recordedLine(path, colors), zoom = 16)

        assertEquals(listOf(0, 5, 10).map { path[it].latitude to path[it].longitude }, drawn.positions.map { it.lat to it.lon })
        assertEquals(listOf(RED, RED, BLUE), drawn.positions.indices.map(drawn::color))
    }

    @Test fun `a band keeps a sharp corner's fix, dropping the near fix before it instead`() {
        val path = walkLegs(List(3) { 0.0 to 1.0 } + (0.0 to 0.3) + List(3) { 170.0 to 1.0 })
        val drawn = bandLine(recordedLine(path, oneColor(8)), zoom = 16)

        assertEquals(listOf(0, 4, 7).map { path[it].latitude to path[it].longitude }, drawn.positions.map { it.lat to it.lon })
    }

    @Test fun `a band keeps a jog whose legs are shorter than half the casing`() {
        val path = walkLegs(List(3) { 0.0 to 1.0 } + (135.0 to 0.33) + List(3) { 180.0 to 1.0 })
        val drawn = bandLine(recordedLine(path, oneColor(8)), zoom = 16)

        assertEquals(listOf(0, 3, 4, 7).map { path[it].latitude to path[it].longitude }, drawn.positions.map { it.lat to it.lon })
    }

    @Test fun `a band draws a stop within its tolerance from its first fix to its last`() {
        val path = walkLegs(List(6) { i -> (if (i % 2 == 0) 90.0 else 270.0) to 0.01 })
        val drawn = bandLine(recordedLine(path, oneColor(7)), zoom = 16)

        assertEquals(listOf(0, 6).map { path[it].latitude to path[it].longitude }, drawn.positions.map { it.lat to it.lon })
    }

    @Test fun `where the track crosses itself, the later pass is drawn over the earlier one`() {
        val strokes = casedStrokes(listOf(recordedLine(triangle(), oneColor(27))), CLOSE)

        val earlier = strokes.indexOfFirst { it.pen == Pen.COLOR && 1 in it.fixes }
        val later = strokes.indexOfFirst { it.pen == Pen.CASING_BAND && 25 in it.fixes }
        assertTrue(later > earlier)
    }

    @Test fun `where two stretches meet, both casings are down before either color`() {
        val colors = IntArray(11) { i ->
            when {
                i <= 3 -> RED
                i <= 6 -> GREEN
                else -> BLUE
            }
        }
        val line = recordedLine(walk(*DoubleArray(10)), colors)

        assertCasedBeforeColored(casedStrokes(listOf(line), CLOSE))
    }

    @Test fun `a trip's parts are one sequence, the later part over the earlier where they cross`() {
        val path = triangle()
        val parts = listOf(
            recordedLine(path.subList(0, 17), oneColor(17)),
            recordedLine(path.subList(16, 27), oneColor(11)),
        )
        val strokes = casedStrokes(parts, CLOSE)

        val earlier = strokes.indexOfFirst { it.line == 0 && it.pen == Pen.COLOR && 1 in it.fixes }
        val later = strokes.indexOfFirst { it.line == 1 && it.pen == Pen.CASING_BAND && 25 - 16 in it.fixes }
        assertTrue(later > earlier)
        assertCasedBeforeColored(strokes)
    }

    @Test fun `a dashed line keeps a solid casing, only its color dashed`() {
        val strokes = casedStrokes(listOf(recordedLine(walk(0.0, 0.0), oneColor(3), unmeasured = true)), CLOSE)

        assertEquals(listOf(Pen.COLOR), strokes.filter { it.dashed }.map { it.pen })
    }

    private fun assertCasedBeforeColored(strokes: List<Stroke>) {
        for ((a, b) in strokes.filter { it.pen == Pen.COLOR }.zipWithNext()) {
            val pair = setOf(a.line to a.fixes, b.line to b.fixes)
            assertCasedBeforeColored(strokes, "${a.fixes} and ${b.fixes}") { (it.line to it.fixes) in pair }
        }
    }

    private fun assertCasedBeforeColored(strokes: List<Stroke>, fixes: Set<IntRange>) =
        assertCasedBeforeColored(strokes, "$fixes") { it.fixes in fixes }

    private fun assertCasedBeforeColored(strokes: List<Stroke>, drawn: String, of: (Stroke) -> Boolean) {
        fun at(pen: Pen) = strokes.withIndex().filter { (_, s) -> s.pen == pen && of(s) }.map { it.index }
        assertTrue("$drawn: borders under bands", at(Pen.CASING_BORDER).max() < at(Pen.CASING_BAND).min())
        assertTrue("$drawn: bands under colors", at(Pen.CASING_BAND).max() < at(Pen.COLOR).min())
    }

    private fun colorStrokes(lines: List<CasedLine>) = casedStrokes(lines, CLOSE).filter { it.pen == Pen.COLOR }

    /** East along 10 legs, back up-left at 110° for 6, then down-left at 220°, leg 24→25 crossing leg 1→2. */
    private fun triangle() = walk(*DoubleArray(10) { 0.0 }, *DoubleArray(6) { 110.0 }, *DoubleArray(10) { 220.0 })

    private fun walk(vararg headings: Double) = walkLegs(headings.map { it to 1.0 })

    /** Fixes from a neutral origin, one leg per heading, in degrees counterclockwise from east, and length in steps. */
    private fun walkLegs(legs: List<Pair<Double, Double>>): List<TrackPoint> {
        var lat = 0.0
        var lon = 0.0
        val fixes = mutableListOf(fix(0, lat, lon))
        legs.forEachIndexed { i, (heading, steps) ->
            lat += steps * STEP * sin(Math.toRadians(heading))
            lon += steps * STEP * cos(Math.toRadians(heading))
            fixes += fix(i + 1, lat, lon)
        }
        return fixes
    }

    private fun fix(i: Int, lat: Double, lon: Double) = TrackPoint(
        trackId = 1,
        latitude = lat,
        longitude = lon,
        altitude = null,
        accuracy = null,
        speed = null,
        bearing = null,
        timestamp = i * 1000L,
    )

    private fun recordedLine(fixes: List<TrackPoint>, colors: IntArray, unmeasured: Boolean = false) =
        CasedLine(fixes, PathSmoother.lonLat(fixes, null, unmeasured, smoothed = false), colors, unmeasured)

    private fun oneColor(count: Int) = IntArray(count) { RED }

    private companion object {
        /** Close enough that a meter-long leg is wider on screen than the casing. */
        const val CLOSE = 20
        const val STEP = 0.0001
        const val RED = 0xFFFF0000.toInt()
        const val GREEN = 0xFF00FF00.toInt()
        const val BLUE = 0xFF0000FF.toInt()
    }
}
