package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.domain.KeepRule.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The keep/delete rule as a pure thresholds table. Extent is a lazy supplier, so two cases assert it
 * is *not* invoked (the supplier throws) — pinning the short-circuit that skips the bounding-box pass
 * when the extent gate is off or an earlier gate already failed.
 */
class KeepRuleTest {

    private val thresholds = KeepRule.Thresholds(minDurationSec = 60, minLengthM = 100, minExtentM = 50)

    private fun verdict(
        pointCount: Int = 10,
        ignoredCount: Int = 0,
        durationSec: Long = 120,
        distanceMeters: Double = 500.0,
        thresholds: KeepRule.Thresholds = this.thresholds,
        extent: () -> Double = { 200.0 },
    ) = KeepRule.verdict(pointCount, ignoredCount, durationSec, distanceMeters, thresholds, extent)

    @Test fun `a track clearing every bar is kept`() {
        assertEquals(Verdict.KEEP, verdict())
    }

    @Test fun `up to two points in total is purged, three escapes the floor`() {
        assertEquals(Verdict.PURGE, verdict(pointCount = 0))
        assertEquals(Verdict.PURGE, verdict(pointCount = 1))
        assertEquals(Verdict.PURGE, verdict(pointCount = 2))
        assertEquals(Verdict.PURGE, verdict(pointCount = 1, ignoredCount = 1))
        assertEquals(Verdict.KEEP, verdict(pointCount = 3))
    }

    @Test fun `ignored points are evidence - a noisy track is discarded for review, not purged`() {
        // No drawable line, but the rejected fixes are worth a look in Recently deleted.
        assertEquals(Verdict.DISCARD, verdict(pointCount = 0, ignoredCount = 3))
        assertEquals(Verdict.DISCARD, verdict(pointCount = 1, ignoredCount = 2))
    }

    @Test fun `fewer than two good points is discarded even when every threshold is off`() {
        val off = KeepRule.Thresholds(minDurationSec = 0, minLengthM = 0, minExtentM = 0)
        assertEquals(Verdict.DISCARD, verdict(pointCount = 1, ignoredCount = 10, thresholds = off))
    }

    @Test fun `too-short duration is discarded, exactly the minimum is kept`() {
        assertEquals(Verdict.DISCARD, verdict(durationSec = 59))
        assertEquals(Verdict.KEEP, verdict(durationSec = 60))
    }

    @Test fun `too-short length is discarded, exactly the minimum is kept`() {
        assertEquals(Verdict.DISCARD, verdict(distanceMeters = 99.0))
        assertEquals(Verdict.KEEP, verdict(distanceMeters = 100.0))
    }

    @Test fun `too-small extent is discarded when the gate is on, exactly the minimum is kept`() {
        assertEquals(Verdict.DISCARD, verdict(extent = { 49.0 }))
        assertEquals(Verdict.KEEP, verdict(extent = { 50.0 }))
    }

    @Test fun `extent is not evaluated when the gate is disabled`() {
        val off = thresholds.copy(minExtentM = 0)
        assertEquals(
            Verdict.KEEP,
            verdict(thresholds = off, extent = { error("extent must not be computed when the gate is off") }),
        )
    }

    @Test fun `extent is not evaluated once an earlier gate has failed`() {
        assertEquals(
            Verdict.DISCARD,
            verdict(distanceMeters = 10.0, extent = { error("extent must not be computed once length fails") }),
        )
    }
}
