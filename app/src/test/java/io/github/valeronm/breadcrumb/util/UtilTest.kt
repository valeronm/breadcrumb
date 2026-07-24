package io.github.valeronm.breadcrumb.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class UtilTest {

    // --- PerLocale -----------------------------------------------------------

    @Test
    fun `builds once per locale and rebuilds when the default switches`() {
        val original = Locale.getDefault()
        try {
            var builds = 0
            val tag by PerLocale { locale ->
                builds++
                locale.country
            }
            Locale.setDefault(Locale.US)
            assertEquals("US", tag)
            assertEquals("US", tag)
            assertEquals(1, builds)

            Locale.setDefault(Locale.GERMANY)
            assertEquals("DE", tag)
            assertEquals(2, builds)

            // One-entry cache: switching back rebuilds rather than serving the stale value.
            Locale.setDefault(Locale.US)
            assertEquals("US", tag)
            assertEquals(3, builds)
        } finally {
            Locale.setDefault(original)
        }
    }

    // --- snapToStep ----------------------------------------------------------

    @Test
    fun `snaps to the nearest multiple of the step`() {
        assertEquals(40f, snapToStep(44f, 10, 0f..100f), 0f)
        assertEquals(50f, snapToStep(47f, 10, 0f..100f), 0f)
        assertEquals(30f, snapToStep(25f, 10, 0f..100f), 0f) // half-step rounds up
        assertEquals(0f, snapToStep(4f, 10, 0f..100f), 0f)
    }

    @Test
    fun `clamps the snapped value into the range`() {
        assertEquals(100f, snapToStep(999f, 10, 0f..100f), 0f)
        assertEquals(0f, snapToStep(-25f, 10, 0f..100f), 0f)
        // A range end that isn't itself a step multiple still wins over the snap.
        assertEquals(95f, snapToStep(99f, 10, 0f..95f), 0f)
    }

    // --- avgSpeedKmh ---------------------------------------------------------

    @Test
    fun `converts meters per second to km per hour`() {
        assertEquals(36.0, avgSpeedKmh(100.0, 10.0), 1e-9)
        assertEquals(5.0, avgSpeedKmh(2_500.0, 1_800.0), 1e-9)
    }

    @Test
    fun `zero or negative duration means zero speed, not a division`() {
        assertEquals(0.0, avgSpeedKmh(100.0, 0.0), 0.0)
        assertEquals(0.0, avgSpeedKmh(100.0, -5.0), 0.0)
    }
}
