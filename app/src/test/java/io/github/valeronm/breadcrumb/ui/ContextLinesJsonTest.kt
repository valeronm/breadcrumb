package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.domain.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.android.utils.ColorUtils
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString

class ContextLinesJsonTest {

    @Test fun `each run of a line is a feature of its own, longitude first`() {
        val line = line(Coordinate(0.002, 0.001), Coordinate(0.004, 0.003), Coordinate(0.006, 0.005), Coordinate(0.008, 0.007), breakAt = 2)

        assertEquals(listOf(listOf(0.001 to 0.002, 0.003 to 0.004), listOf(0.005 to 0.006, 0.007 to 0.008)), features(contextLinesJson(listOf(line))).map(::lonLats))
    }

    @Test fun `each line is drawn in its own color`() {
        val lines = listOf(line(Coordinate(0.0, 0.0), Coordinate(0.001, 0.0), argb = RED), line(Coordinate(0.0, 0.001), Coordinate(0.001, 0.001), argb = BLUE))

        val colors = features(contextLinesJson(lines)).map { it.getStringProperty("color") }

        assertEquals(listOf(RED, BLUE).map(ColorUtils::colorToRgbaString), colors)
    }

    @Test fun `only an unmeasured line is marked so`() {
        val lines = listOf(line(Coordinate(0.0, 0.0), Coordinate(0.001, 0.0)), line(Coordinate(0.0, 0.001), Coordinate(0.001, 0.001), unmeasured = true))

        val (measured, unmeasured) = features(contextLinesJson(lines))

        assertFalse(measured.hasProperty(UNMEASURED_KEY))
        assertTrue(unmeasured.getBooleanProperty(UNMEASURED_KEY))
    }

    @Test fun `a great-circle line is drawn along its arcs`() {
        val drawn = lonLats(features(contextLinesJson(listOf(line(Coordinate(10.0, 0.0), Coordinate(50.0, 40.0), greatCircle = true)))).single())

        assertTrue(drawn.size > 2)
        assertEquals(0.0, drawn.first().first, TEN_MILLIONTH)
        assertEquals(10.0, drawn.first().second, TEN_MILLIONTH)
        assertEquals(40.0, drawn.last().first, TEN_MILLIONTH)
        assertEquals(50.0, drawn.last().second, TEN_MILLIONTH)
    }

    private fun line(vararg fixes: Coordinate, breakAt: Int? = null, argb: Int = RED, unmeasured: Boolean = false, greatCircle: Boolean = false) =
        CasedLine(fixes.toList(), intArrayOf(argb), BooleanArray(fixes.size) { it == breakAt }, unmeasured, greatCircle)

    private fun features(json: String): List<Feature> = FeatureCollection.fromJson(json).features().orEmpty()

    private fun lonLats(feature: Feature) = (feature.geometry() as LineString).coordinates().map { it.longitude() to it.latitude() }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val BLUE = 0xFF0000FF.toInt()
        const val TEN_MILLIONTH = 1e-7
    }
}
