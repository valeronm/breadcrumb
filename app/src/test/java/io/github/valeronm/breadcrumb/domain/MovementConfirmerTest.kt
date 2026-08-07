package io.github.valeronm.breadcrumb.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The second witness: what the position stream alone says the ground is doing. Nothing here knows about
 * activities or tracks — how a verdict is *used* belongs to [ActivityGate], the jump ceiling and the
 * no-fix guard, tested there. Fixtures lay fixes out in seconds and metres east of [TestGeo]'s neutral origin.
 */
class MovementConfirmerTest {

    private fun confirmer(params: MovementConfirmer.Params = MovementConfirmer.Params()) =
        MovementConfirmer(flatDistance, params)

    /** Feed a fix [sec] seconds in, [meters] east of the origin. */
    private fun MovementConfirmer.fix(sec: Long, meters: Double) =
        onFix(sec * 1000L, ORIGIN_LAT, lonAt(meters))

    /** Lay out one fix per pair, then read the verdict at the last fix's time. */
    private fun verdictOf(vararg fixes: Pair<Long, Double>): Motion {
        val c = confirmer()
        for ((sec, meters) in fixes) c.fix(sec, meters)
        return c.verdict(fixes.last().first * 1000L)
    }

    private fun movingSpeed(motion: Motion): Double {
        assertTrue("expected Moving, was $motion", motion is Motion.Moving)
        return (motion as Motion.Moving).speed.mps
    }

    // --- Abstention ---------------------------------------------------------

    @Test fun `an empty window abstains`() {
        assertEquals(Motion.Unknown, confirmer().verdict(0L))
    }

    @Test fun `too few fixes abstain however far apart they are`() {
        assertEquals(Motion.Unknown, verdictOf(0L to 0.0, 30L to 200.0, 60L to 400.0))
    }

    @Test fun `too short a span abstains however many fixes it holds`() {
        // Four fixes covering plainly more ground than the threshold, inside 15 s.
        assertEquals(
            Motion.Unknown,
            verdictOf(0L to 0.0, 5L to 100.0, 10L to 200.0, 15L to 300.0),
        )
    }

    // --- Movement -----------------------------------------------------------

    @Test fun `steady progress reads as moving at the pace it was made`() {
        val motion = verdictOf(0L to 0.0, 5L to 30.0, 10L to 60.0, 15L to 90.0, 20L to 120.0)
        assertEquals(6.0, movingSpeed(motion), 0.01)
    }

    @Test fun `the speed is the ground's, not diluted by the window's own length`() {
        // Averaging the halves' *times* along with their positions is what keeps this honest: a
        // naive first-to-last-over-window-span would report the same number, but a naive
        // half-means-over-window-span would report half of it.
        val motion = verdictOf(0L to 0.0, 10L to 100.0, 20L to 200.0, 30L to 300.0)
        assertEquals(10.0, movingSpeed(motion), 0.01)
    }

    @Test fun `a walk at ordinary pace is not what trips the moving verdict`() {
        // ~1.4 m/s over the base window — under the displacement threshold, so an everyday stop on
        // foot pauses the track exactly as it does without a cross-check.
        val motion = verdictOf(0L to 0.0, 5L to 7.0, 10L to 14.0, 15L to 21.0, 20L to 28.0)
        assertEquals(Motion.Unknown, motion)
    }

    @Test fun `a carrier's pace clears the threshold several times over`() {
        // A vessel at roughly 20 km/h, the case the cross-check exists for.
        val motion = verdictOf(0L to 0.0, 5L to 28.0, 10L to 56.0, 15L to 84.0, 20L to 112.0)
        assertEquals(5.6, movingSpeed(motion), 0.01)
    }

    // --- Standstill ---------------------------------------------------------

    @Test fun `a parked phone's jitter reads as stopped`() {
        assertEquals(
            Motion.Stopped,
            verdictOf(0L to 0.0, 5L to 3.0, 10L to -2.0, 15L to 5.0, 20L to 1.0),
        )
    }

    @Test fun `jitter wider than the standstill spread abstains rather than claiming either`() {
        assertEquals(
            Motion.Unknown,
            verdictOf(0L to 0.0, 5L to 20.0, 10L to -20.0, 15L to 25.0, 20L to 0.0),
        )
    }

    @Test fun `a departure and return is not a standstill`() {
        // Net displacement between the halves is nil, but the ground was plainly covered — the
        // spread is what catches it, and abstaining is the honest answer.
        assertEquals(
            Motion.Unknown,
            verdictOf(0L to 0.0, 4L to 100.0, 8L to 200.0, 12L to 200.0, 16L to 100.0, 20L to 0.0),
        )
    }

    // --- The window's shelf life --------------------------------------------

    @Test fun `silence drains a moving window back to abstention`() {
        val c = confirmer()
        for (i in 0..4) c.fix(i * 5L, i * 30.0)
        assertTrue(c.verdict(20_000L) is Motion.Moving)
        // A minute later nothing has arrived: the evidence has expired rather than standing.
        assertEquals(Motion.Unknown, c.verdict(20_000L + MovementConfirmer.Params().maxFixAgeMs + 1))
    }

    @Test fun `a fix out of order is refused rather than reordering the halves`() {
        val c = confirmer()
        for (i in 0..4) c.fix(i * 5L, i * 30.0)
        val before = c.verdict(20_000L)
        c.fix(1L, 500.0)
        assertEquals(before, c.verdict(20_000L))
    }

    /**
     * The rule that replaced a clear-on-every-GPS-start: the recorder pauses at every stop and
     * resumes at every start, so clearing there emptied the window seconds before a carrier pulled
     * away — abstaining through the departure the witness exists to catch.
     */
    @Test fun `reshaping keeps the evidence, because a pause is not a different journey`() {
        val c = confirmer()
        for (i in 0..4) c.fix(i * 5L, i * 30.0)
        assertTrue(c.verdict(20_000L) is Motion.Moving)
        c.reshape(MovementConfirmer.Params())
        assertTrue(c.verdict(20_000L) is Motion.Moving)
    }

    /** …and what does the forgetting instead: age, which a real gap crosses and a pause does not. */
    @Test fun `evidence that describes an older stretch has already drained itself`() {
        val c = confirmer()
        for (i in 0..4) c.fix(i * 5L, i * 30.0)
        c.reshape(MovementConfirmer.Params())
        assertEquals(Motion.Unknown, c.verdict(20_000L + MovementConfirmer.Params().maxFixAgeMs + 1))
    }

    // --- Across the sampling ladder -----------------------------------------

    @Test fun `the span floor scales with the cadence but the displacement does not`() {
        val quick = MovementConfirmer.forSampling(1)
        val slow = MovementConfirmer.forSampling(30)
        assertEquals(20_000L, quick.minSpanMs)
        // Four fixes 30 s apart span 90 s; a window stated in seconds alone would never fill.
        assertEquals(90_000L, slow.minSpanMs)
        assertEquals(quick.movingMinDisplacementM, slow.movingMinDisplacementM, 0.0)
        assertTrue(slow.maxFixAgeMs > slow.minSpanMs)
    }

    @Test fun `a carrier is still confirmed at the slow end of the ladder`() {
        val c = confirmer(MovementConfirmer.forSampling(30))
        for (i in 0..3) c.fix(i * 30L, i * 200.0)
        assertEquals(6.667, movingSpeed(c.verdict(90_000L)), 0.01)
    }

    @Test fun `the same journey abstains until the slow ladder's window has filled`() {
        val c = confirmer(MovementConfirmer.forSampling(30))
        for (i in 0..2) c.fix(i * 30L, i * 200.0)
        assertEquals(Motion.Unknown, c.verdict(60_000L))
    }

    // --- The feed contract's price ------------------------------------------

    @Test fun `a lone teleport can carry the window on its own`() {
        // Not a defect to fix here: fixes rejected as jumps *must* be fed (see the feed contract),
        // so an outlier is evidence like any other and averaging the halves only damps it. This
        // pins the cost, and the ceiling's clamp in TrackQuality is what bounds what it can buy.
        val motion = verdictOf(0L to 0.0, 7L to 0.0, 14L to 0.0, 21L to 300.0)
        assertTrue(motion is Motion.Moving)
    }
}
