package io.github.valeronm.breadcrumb.util

import org.junit.Assert.assertEquals
import org.junit.Test

class UnitsTest {

    // --- Choice resolution ---------------------------------------------------

    @Test
    fun `system choice follows the country`() {
        assertEquals(UnitSystem.IMPERIAL, UnitChoice.SYSTEM.resolve("US"))
        assertEquals(UnitSystem.IMPERIAL, UnitChoice.SYSTEM.resolve("us"))
        assertEquals(UnitSystem.IMPERIAL, UnitChoice.SYSTEM.resolve("LR"))
        assertEquals(UnitSystem.IMPERIAL, UnitChoice.SYSTEM.resolve("MM"))
        assertEquals(UnitSystem.UK, UnitChoice.SYSTEM.resolve("GB"))
        assertEquals(UnitSystem.METRIC, UnitChoice.SYSTEM.resolve("DE"))
        assertEquals(UnitSystem.METRIC, UnitChoice.SYSTEM.resolve(""))
    }

    @Test
    fun `forced choices ignore the country`() {
        assertEquals(UnitSystem.METRIC, UnitChoice.METRIC.resolve("US"))
        assertEquals(UnitSystem.IMPERIAL, UnitChoice.IMPERIAL.resolve("DE"))
        assertEquals(UnitSystem.UK, UnitChoice.UK.resolve("US"))
    }

    @Test
    fun `the UK system mixes miles with metres`() {
        assertEquals("1 mi", UnitSystem.UK.distance(1_609.344))
        assertEquals("60 mph", UnitSystem.UK.speedFromKmh(96.56))
        assertEquals("35 m", UnitSystem.UK.shortDistance(35.0))
        assertEquals("mph", UnitSystem.UK.speedUnit)
        assertEquals("m", UnitSystem.UK.shortUnit)
        assertEquals(50f, UnitSystem.UK.fromMeters(50f), 0f)
        // The display-table selectors derive from the units above: UK is mph but metres.
        assertEquals("mph table", UnitSystem.UK.bySpeedUnit(kmh = "kmh table", mph = "mph table"))
        assertEquals("m table", UnitSystem.UK.byShortUnit(metres = "m table", feet = "ft table"))
        assertEquals("ft table", UnitSystem.IMPERIAL.byShortUnit(metres = "m table", feet = "ft table"))
        assertEquals("kmh table", UnitSystem.METRIC.bySpeedUnit(kmh = "kmh table", mph = "mph table"))
        // Its sliders are the metric ones: short-range settings stay in metres.
        val scale = UnitSystem.UK.sliderScale(SliderStops(0, 500, 50), SliderStops(0, 1650, 150), zeroIsOff = true)
        assertEquals(500f, scale.range.endInclusive, 0f)
        assertEquals("50 m", scale.label(50f))
    }

    @Test
    fun `unknown stored name falls back to system`() {
        assertEquals(UnitChoice.SYSTEM, UnitChoice.fromName(null))
        assertEquals(UnitChoice.SYSTEM, UnitChoice.fromName("bogus"))
        assertEquals(UnitChoice.IMPERIAL, UnitChoice.fromName("IMPERIAL"))
    }

    // --- Distance ------------------------------------------------------------

    @Test
    fun `metric distance keeps one decimal under 100 km and drops a zero tenth`() {
        assertEquals("0.3 km", UnitSystem.METRIC.distance(300.0).dotSeparated())
        assertEquals("4 km", UnitSystem.METRIC.distance(4_000.0))
        assertEquals("12.5 km", UnitSystem.METRIC.distance(12_500.0).dotSeparated())
        assertEquals("103 km", UnitSystem.METRIC.distance(103_400.0))
    }

    @Test
    fun `imperial distance is in miles with the same precision rules`() {
        // 1609.344 m = exactly 1 mile
        assertEquals("1 mi", UnitSystem.IMPERIAL.distance(1_609.344))
        assertEquals("0.5 mi", UnitSystem.IMPERIAL.distance(804.672).dotSeparated())
        assertEquals("2.5 mi", UnitSystem.IMPERIAL.distance(4_023.36).dotSeparated())
        assertEquals("124 mi", UnitSystem.IMPERIAL.distance(200_000.0))
    }

    // --- Speed ---------------------------------------------------------------

    @Test
    fun `speed is a whole number in the system's unit`() {
        assertEquals("42 km/h", UnitSystem.METRIC.speedFromKmh(42.4))
        assertEquals("60 mph", UnitSystem.IMPERIAL.speedFromKmh(96.56))
    }

    // --- Short distance ------------------------------------------------------

    @Test
    fun `short distance is whole metres or feet`() {
        assertEquals("35 m", UnitSystem.METRIC.shortDistance(35.0))
        assertEquals("164 ft", UnitSystem.IMPERIAL.shortDistance(50.0))
        assertEquals("3 ft", UnitSystem.IMPERIAL.shortDistance(1.0))
    }

    // --- Slider scales -------------------------------------------------------

    @Test
    fun `metric slider scale is the identity over its own stops`() {
        val scale = UnitSystem.METRIC.sliderScale(SliderStops(0, 500, 50), SliderStops(0, 1650, 150), zeroIsOff = true)
        assertEquals(0f, scale.range.start, 0f)
        assertEquals(500f, scale.range.endInclusive, 0f)
        assertEquals(50f, scale.displayOf(50), 0f)
        assertEquals(50, scale.metersOf(50f))
        assertEquals("Off", scale.label(0f))
        assertEquals("50 m", scale.label(50f))
    }

    @Test
    fun `imperial slider scale has round-feet stops storing converted metres`() {
        val scale = UnitSystem.IMPERIAL.sliderScale(SliderStops(0, 500, 50), SliderStops(0, 1650, 150), zeroIsOff = true)
        assertEquals(1650f, scale.range.endInclusive, 0f)
        // The metric default (50 m) lands on the nearest round-feet stop.
        assertEquals(150f, scale.displayOf(50), 0f)
        assertEquals(46, scale.metersOf(150f))
        assertEquals("Off", scale.label(0f))
        assertEquals("150 ft", scale.label(150f))
        assertEquals("1,650 ft", scale.label(1650f))
    }

    @Test
    fun `every committed imperial stop reads back as the same stop`() {
        // 50 ft → 15 m → back: the label must not drift to 49 ft.
        val scales = mapOf(
            UnitSystem.IMPERIAL.sliderScale(SliderStops(1, 50, 1), SliderStops(5, 165, 5)) to (5..165 step 5),
            UnitSystem.IMPERIAL.sliderScale(SliderStops(10, 150, 10), SliderStops(25, 500, 25)) to (25..500 step 25),
            UnitSystem.IMPERIAL.sliderScale(SliderStops(50, 500, 25), SliderStops(150, 1650, 75)) to (150..1650 step 75),
        )
        for ((scale, stops) in scales) {
            for (stop in stops) {
                assertEquals(stop.toFloat(), scale.displayOf(scale.metersOf(stop.toFloat())), 0f)
            }
        }
    }

    @Test
    fun `raw drag positions snap to the step`() {
        val scale = UnitSystem.IMPERIAL.sliderScale(SliderStops(10, 150, 10), SliderStops(25, 500, 25))
        assertEquals(25f, scale.snap(13f), 0f)
        assertEquals(75f, scale.snap(80f), 0f)
        assertEquals(500f, scale.snap(9999f), 0f)
    }

    // --- Series conversion ---------------------------------------------------

    @Test
    fun `numeric conversions feed graph values and ramp anchors alike`() {
        assertEquals(30f, UnitSystem.METRIC.fromKmh(30f), 0f)
        assertEquals(18.64f, UnitSystem.IMPERIAL.fromKmh(30f), 0.01f)
        assertEquals(50f, UnitSystem.METRIC.fromMeters(50f), 0f)
        assertEquals(164.04f, UnitSystem.IMPERIAL.fromMeters(50f), 0.01f)
        assertEquals("km/h", UnitSystem.METRIC.speedUnit)
        assertEquals("mph", UnitSystem.IMPERIAL.speedUnit)
        assertEquals("m", UnitSystem.METRIC.shortUnit)
        assertEquals("ft", UnitSystem.IMPERIAL.shortUnit)
    }

    /** The decimal separator is locale-dependent; tests assert the dot form. */
    private fun String.dotSeparated() = replace(',', '.')
}
