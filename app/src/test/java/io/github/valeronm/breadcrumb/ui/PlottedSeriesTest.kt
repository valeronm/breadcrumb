package io.github.valeronm.breadcrumb.ui

import androidx.test.core.app.ApplicationProvider
import io.github.valeronm.breadcrumb.data.TrackQuality
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.util.Measures
import io.github.valeronm.breadcrumb.util.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlottedSeriesTest {

    private val measures get() = Measures(UnitSystem.METRIC, unitSymbols(ApplicationProvider.getApplicationContext()))

    /** A drive whose speed and altitude swing between neighbors, a fix a second. */
    private val seams = TrackQuality.seams(
        List(60) { i ->
            TrackPoint(
                trackId = 1,
                latitude = 1.0,
                longitude = i * 0.0001,
                altitude = if (i % 2 == 0) 100.0 else 110.0,
                accuracy = 5f,
                speed = if (i % 2 == 0) 10f else 15f,
                bearing = null,
                timestamp = i * 1_000L,
                speedAccuracy = 0.3f,
            )
        },
    )

    private fun plotted(mode: ColorMode, smoothed: Boolean, unmeasured: Boolean = false) =
        plottedSeries(PlottedPoints(seams, seams.points.map { it.altitude }), mode, measures, unmeasured, smoothed).values

    @Test
    fun `an unsmoothed speed graph plots the speeds as recorded`() {
        assertEquals(List(60) { if (it % 2 == 0) 36f else 54f }, plotted(ColorMode.SPEED, smoothed = false))
    }

    @Test
    fun `an unsmoothed elevation graph plots the altitudes as recorded`() {
        assertEquals(List(60) { if (it % 2 == 0) 100f else 110f }, plotted(ColorMode.ELEVATION, smoothed = false))
    }

    @Test
    fun `an elevation graph plots the altitudes it is handed rather than the stored ones`() {
        val shown = List(60) { if (it % 2 == 0) 45.0 else null }
        assertEquals(
            List(60) { if (it % 2 == 0) 45f else null },
            plottedSeries(PlottedPoints(seams, shown), ColorMode.ELEVATION, measures, unmeasured = false, smoothed = false).values,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `altitudes that do not match the points one for one are refused`() {
        PlottedPoints(seams, List(59) { 0.0 })
    }

    @Test
    fun `an unmeasured track's graph plots its values as recorded even when smoothing is on`() {
        assertEquals(plotted(ColorMode.ELEVATION, smoothed = false), plotted(ColorMode.ELEVATION, smoothed = true, unmeasured = true))
    }

    @Test
    fun `a smoothed graph evens out the swing in speed and elevation`() {
        fun swing(values: List<Float?>) = values.subList(20, 40).let { it.maxOf { v -> v!! } - it.minOf { v -> v!! } }

        assertTrue(swing(plotted(ColorMode.SPEED, smoothed = true)) < swing(plotted(ColorMode.SPEED, smoothed = false)) / 4)
        assertTrue(swing(plotted(ColorMode.ELEVATION, smoothed = true)) < swing(plotted(ColorMode.ELEVATION, smoothed = false)) / 4)
    }
}
