package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.data.JourneyLine
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.android.utils.ColorUtils
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString

class TouchingLinesJsonTest {

    @Test fun `each watched stretch of a touching track is a line of its own, longitude first`() {
        val track = touching(ActivityType.WALKING, line(doubleArrayOf(0.001, 0.002, 0.003, 0.004), doubleArrayOf(0.005, 0.006, 0.007, 0.008)))

        val features = features(touchingLinesJson(listOf(track), dark = false))

        assertEquals(listOf(listOf(0.001 to 0.002, 0.003 to 0.004), listOf(0.005 to 0.006, 0.007 to 0.008)), features.map(::lonLats))
    }

    @Test fun `a stretch of one fix draws nothing`() {
        val track = touching(ActivityType.WALKING, line(doubleArrayOf(0.001, 0.002), doubleArrayOf(0.005, 0.006, 0.007, 0.008)))

        assertEquals(listOf(listOf(0.005 to 0.006, 0.007 to 0.008)), features(touchingLinesJson(listOf(track), dark = false)).map(::lonLats))
    }

    @Test fun `each track is drawn in its activity's color`() {
        val walk = touching(ActivityType.WALKING, line(doubleArrayOf(0.001, 0.002, 0.003, 0.004)))
        val drive = touching(ActivityType.DRIVING, line(doubleArrayOf(0.005, 0.006, 0.007, 0.008)))

        val colors = features(touchingLinesJson(listOf(walk, drive), dark = true)).map { it.getStringProperty("color") }

        assertEquals(listOf(ActivityType.WALKING, ActivityType.DRIVING).map { ColorUtils.colorToRgbaString(mapActivityArgb(it, dark = true)) }, colors)
    }

    @Test fun `only an unmeasured track's lines are marked so`() {
        val measured = touching(ActivityType.WALKING, line(doubleArrayOf(0.001, 0.002, 0.003, 0.004)))
        val unmeasured = touching(ActivityType.WALKING, line(doubleArrayOf(0.005, 0.006, 0.007, 0.008), unmeasured = true))

        val (first, second) = features(touchingLinesJson(listOf(measured, unmeasured), dark = false))

        assertFalse(first.hasProperty(UNMEASURED_KEY))
        assertTrue(second.getBooleanProperty(UNMEASURED_KEY))
    }

    @Test fun `a great-circle track is drawn along its arcs`() {
        val flight = touching(ActivityType.FLIGHT, line(doubleArrayOf(0.0, 10.0, 40.0, 50.0), greatCircle = true))

        val drawn = lonLats(features(touchingLinesJson(listOf(flight), dark = false)).single())

        assertTrue(drawn.size > 2)
        assertEquals(0.0, drawn.first().first, TEN_MILLIONTH)
        assertEquals(10.0, drawn.first().second, TEN_MILLIONTH)
        assertEquals(40.0, drawn.last().first, TEN_MILLIONTH)
        assertEquals(50.0, drawn.last().second, TEN_MILLIONTH)
    }

    private fun line(vararg stretches: DoubleArray, unmeasured: Boolean = false, greatCircle: Boolean = false) =
        JourneyLine(1, "", greatCircle, segments = stretches.toList(), unmeasured = unmeasured, startedAt = 0)

    private fun touching(activity: ActivityType, vararg lines: JourneyLine) =
        TouchingTrack(lines.toList(), activity.name, Coordinate(0.0, 0.0), boundMs = 0, placeId = null)

    private fun features(json: String): List<Feature> = FeatureCollection.fromJson(json).features().orEmpty()

    private fun lonLats(feature: Feature) = (feature.geometry() as LineString).coordinates().map { it.longitude() to it.latitude() }

    private companion object {
        const val TEN_MILLIONTH = 1e-7
    }
}
