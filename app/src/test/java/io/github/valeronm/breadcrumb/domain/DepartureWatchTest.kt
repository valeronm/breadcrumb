package io.github.valeronm.breadcrumb.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule both position-based departure triggers are decided by. Distances are meter offsets off a
 * neutral origin, so nothing here names a real place.
 */
class DepartureWatchTest {

    private val watch = DepartureWatch(flatDistance)

    /**
     * A position [metersEast] of the origin, known to [accuracyM] — the same value goes to `watch`
     * and to `judge`. Deliberately not named `at`, which `TestGeo` already uses for a track
     * endpoint in this package.
     */
    private fun pos(metersEast: Double, accuracyM: Double = 0.0) =
        MeasuredPosition(Coordinate(ORIGIN_LAT, lonAt(metersEast)), accuracyM)

    private fun DepartureWatch.departedAt(pos: MeasuredPosition) =
        judge(pos) is DepartureWatch.Verdict.Departed

    /** Watching begins at [T0]; only the cases about latency read the stamp back. */
    private fun DepartureWatch.begin(from: MeasuredPosition?) = watch(from, T0)

    @Test
    fun `nothing is a departure while nothing is being watched for`() {
        assertFalse(watch.watching)
        // Adopted as the anchor rather than judged — but only once the watch is running, which it
        // is not. A stray delivery after the request was torn down must decide nothing.
        assertFalse(watch.departedAt(pos(10_000.0)))
    }

    @Test
    fun `a position beyond the margin is a departure`() {
        watch.begin(pos(0.0))
        assertTrue(watch.departedAt(pos(DepartureWatch.MARGIN_M + 50)))
    }

    @Test
    fun `a position inside the margin is not`() {
        watch.begin(pos(0.0))
        assertFalse(watch.departedAt(pos(DepartureWatch.MARGIN_M - 50)))
    }

    @Test
    fun `both uncertainties are spent before the margin is`() {
        watch.begin(pos(0.0, accuracyM = 60.0))
        // Far enough on its own, and not once the anchor's own 60 m and the fix's 40 m are taken
        // off it: a coarse position beside a coarse anchor is no evidence of having moved.
        val gap = DepartureWatch.MARGIN_M + 80
        assertFalse(watch.departedAt(pos(gap, accuracyM = 40.0)))
        assertTrue("the same distance, known better", watch.departedAt(pos(gap, accuracyM = 0.0)))
    }

    @Test
    fun `an anchorless watch is watching, and that is the point`() {
        // Reported dormant, it would swallow the very positions it was started to judge — and the
        // anchorless case is the arming one, where GPS is off and no fix of the recorder's own
        // exists to anchor on.
        watch.begin(from = null)
        assertTrue(watch.watching)
    }

    @Test
    fun `an anchorless watch adopts its first position and judges from there`() {
        // The arming case: no track behind it and no fix of the recorder's own, where "wait for a
        // good fix" resolves to never because GPS is off.
        watch.begin(from = null)
        // Reported rather than swallowed: this position is the freshest thing the fence can be
        // re-centred on, and the caller has no other way to learn it arrived.
        assertEquals(
            DepartureWatch.Verdict.Anchored(pos(5_000.0, accuracyM = 0.0)),
            watch.judge(pos(5_000.0)),
        )
        assertFalse(watch.departedAt(pos(5_000.0 + DepartureWatch.MARGIN_M - 10)))
        assertTrue(watch.departedAt(pos(5_000.0 + DepartureWatch.MARGIN_M + 10)))
    }

    /** So a reader asking at the wrong moment is told there is nothing to say, not handed a
     *  plausible-looking measurement from a watch that has since been torn down. */
    @Test
    fun `the last verdict does not outlive the watch that produced it`() {
        watch.begin(pos(0.0))
        watch.judge(pos(100.0))
        assertTrue(watch.lastVerdict is DepartureWatch.Verdict.Near)

        watch.stop()
        assertEquals(DepartureWatch.Verdict.Dormant, watch.lastVerdict)

        watch.begin(pos(0.0))
        assertEquals("a fresh watch has said nothing yet", DepartureWatch.Verdict.Dormant, watch.lastVerdict)
    }

    @Test
    fun `stopping ends the watch, and a later restart re-anchors`() {
        watch.begin(pos(0.0))
        watch.stop()
        assertFalse(watch.watching)
        assertEquals(
            "torn down, so it must not decide anything — nor become an anchor",
            DepartureWatch.Verdict.Dormant,
            watch.judge(pos(10_000.0)),
        )

        watch.begin(pos(10_000.0))
        assertTrue(watch.watching)
        assertFalse("back where the new anchor is", watch.departedAt(pos(10_000.0)))
    }

    @Test
    fun `the stamp is when watching began, not when a supplier started`() {
        // The whole point of holding it here: the motion burst starts long after the stop, and a
        // latency measured from the burst would flatter it by exactly the delay being measured.
        watch.begin(pos(0.0))
        assertEquals(T0, watch.startedAtMs)
    }

    /**
     * The measurement the rule turns on, which the log had no way of stating: a burst that ends
     * without a departure could not say whether the phone stayed put or merely fell short.
     */
    @Test
    fun `a holding verdict reports the distance and the bar it was judged against`() {
        watch.begin(pos(0.0, accuracyM = 20.0))

        val verdict = watch.judge(pos(100.0, accuracyM = 30.0)) as DepartureWatch.Verdict.Near

        assertEquals(100.0, verdict.gapM, 1.0)
        assertEquals(DepartureWatch.MARGIN_M + 20.0 + 30.0, verdict.barM, 1e-9)
    }

    @Test
    fun `the margin is set above the jump a stationary phone reports`() {
        // The false positive this exists for: a phone that has not moved re-derives its position
        // from a changed Wi-Fi environment and lands a couple of hundred meters away. It clears a
        // bare accuracy test, and must not clear this one.
        watch.begin(pos(0.0, accuracyM = 5.0))
        assertFalse(watch.departedAt(pos(120.0, accuracyM = 45.0)))
    }

    private companion object {
        const val T0 = 1_700_000_000_000L
    }
}
