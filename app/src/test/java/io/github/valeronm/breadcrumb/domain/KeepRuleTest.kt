package io.github.valeronm.breadcrumb.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keep/delete rule as a pure thresholds table. Extent is a lazy supplier, so two cases assert it
 * is *not* invoked (the supplier throws) — pinning the short-circuit that skips the bounding-box pass
 * when the extent gate is off or an earlier gate already failed.
 */
class KeepRuleTest {

    private val thresholds = KeepRule.Thresholds(minDurationSec = 60, minLengthM = 100, minExtentM = 50)

    private fun keep(
        pointCount: Int = 10,
        durationSec: Long = 120,
        distanceMeters: Double = 500.0,
        thresholds: KeepRule.Thresholds = this.thresholds,
        extent: () -> Double = { 200.0 },
    ) = KeepRule.shouldKeep(pointCount, durationSec, distanceMeters, thresholds, extent)

    @Test fun `a track clearing every bar is kept`() {
        assertTrue(keep())
    }

    @Test fun `fewer than two points is dropped`() {
        assertFalse(keep(pointCount = 1))
        assertFalse(keep(pointCount = 0))
    }

    @Test fun `too-short duration is dropped, exactly the minimum is kept`() {
        assertFalse(keep(durationSec = 59))
        assertTrue(keep(durationSec = 60))
    }

    @Test fun `too-short length is dropped, exactly the minimum is kept`() {
        assertFalse(keep(distanceMeters = 99.0))
        assertTrue(keep(distanceMeters = 100.0))
    }

    @Test fun `too-small extent is dropped when the gate is on, exactly the minimum is kept`() {
        assertFalse(keep(extent = { 49.0 }))
        assertTrue(keep(extent = { 50.0 }))
    }

    @Test fun `extent is not evaluated when the gate is disabled`() {
        val off = thresholds.copy(minExtentM = 0)
        assertTrue(keep(thresholds = off, extent = { error("extent must not be computed when the gate is off") }))
    }

    @Test fun `extent is not evaluated once an earlier gate has failed`() {
        assertFalse(keep(distanceMeters = 10.0, extent = { error("extent must not be computed once length fails") }))
    }
}
