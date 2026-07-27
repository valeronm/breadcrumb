package io.github.valeronm.breadcrumb.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The activity signal filter: it reports a change, or nothing. Everything about tracks — resume
 * windows, motion families, pausing — belongs to [TrackController] and is tested there.
 */
class ActivityGateTest {

    private val STILL = ActivityType.STILL
    private val WALKING = ActivityType.WALKING
    private val RUNNING = ActivityType.RUNNING
    private val DRIVING = ActivityType.DRIVING

    @Test fun `still while already still is no change`() {
        assertNull(ActivityGate().onReading(STILL))
    }

    @Test fun `a moving activity is a change on the first reading`() {
        val g = ActivityGate()
        assertEquals(WALKING, g.onReading(WALKING))
        assertEquals(WALKING, g.confirmed)
    }

    @Test fun `the same activity while confirmed is no change`() {
        val g = started(WALKING)
        assertNull(g.onReading(WALKING))
        assertEquals(WALKING, g.confirmed)
    }

    @Test fun `stopping is a change`() {
        val g = started(WALKING)
        assertEquals(STILL, g.onReading(STILL))
        assertEquals(STILL, g.confirmed)
    }

    @Test fun `switching to another moving activity is a change`() {
        val g = started(WALKING)
        assertEquals(RUNNING, g.onReading(RUNNING))
        assertEquals(RUNNING, g.confirmed)
    }

    @Test fun `repeated readings of the same activity are filtered out`() {
        val g = ActivityGate()
        assertEquals(WALKING, g.onReading(WALKING))
        assertNull(g.onReading(WALKING))
        assertNull(g.onReading(WALKING))
        assertEquals(STILL, g.onReading(STILL))
        assertNull(g.onReading(STILL))
    }

    @Test fun `arming resets the confirmed activity`() {
        val g = started(WALKING)
        g.onArmed()
        assertEquals(STILL, g.confirmed)
        // Walking now reads as a change again, since the gate has forgotten it.
        assertEquals(WALKING, g.onReading(WALKING))
    }

    // --- Parking a reading the ground contradicts ---------------------------
    //
    // Everything above this line is the gate as it was before there was a second witness, and is
    // deliberately left untouched: an unedited green test is what pins the behaviour the
    // cross-check's off state has to reproduce. A test mechanically updated to pass
    // `Motion.Unknown` would pin nothing, having been touched by the same change it checks.

    private val MOVING = Motion.Moving(6.0)

    @Test fun `stopping while the ground still moves is parked, not applied`() {
        val g = started(WALKING)
        assertNull(g.onReading(STILL, MOVING))
        assertEquals(WALKING, g.confirmed)
        assertEquals(STILL, g.parked)
    }

    @Test fun `the parked stop is released once the ground stops`() {
        val g = started(WALKING)
        g.onReading(STILL, MOVING)
        assertEquals(STILL, g.onMotion(Motion.Stopped))
        assertEquals(STILL, g.confirmed)
        assertNull(g.parked)
    }

    @Test fun `abstention releases the parked stop too`() {
        // The recorder relies on this shape wherever it is about to turn GPS off: with no fixes
        // arriving nothing could ever release the slot, so the absence of evidence must.
        val g = started(WALKING)
        g.onReading(STILL, MOVING)
        assertEquals(STILL, g.onMotion(Motion.Unknown))
        assertEquals(STILL, g.confirmed)
    }

    @Test fun `the parked stop is held for as long as the ground keeps moving`() {
        val g = started(WALKING)
        g.onReading(STILL, MOVING)
        assertNull(g.onMotion(MOVING))
        assertNull(g.onMotion(MOVING))
        assertEquals(WALKING, g.confirmed)
        assertEquals(STILL, g.parked)
    }

    @Test fun `a newer credible reading supersedes the parked one`() {
        val g = started(WALKING)
        g.onReading(STILL, MOVING)
        assertEquals(DRIVING, g.onReading(DRIVING, MOVING))
        assertNull(g.parked)
        // The superseded stop must not resurface later.
        assertNull(g.onMotion(Motion.Stopped))
        assertEquals(DRIVING, g.confirmed)
    }

    @Test fun `a reading that merely restates the trusted activity clears the parked one`() {
        val g = started(WALKING)
        g.onReading(STILL, MOVING)
        assertNull(g.onReading(WALKING, MOVING))
        assertNull(g.parked)
        assertEquals(WALKING, g.confirmed)
    }

    @Test fun `only a stop is weighed against the ground`() {
        // A foot reading at vehicle speed is equally incredible; parking it is deliberately not
        // done, since the trailing window still reads vehicle-scale just after a drive ends.
        val g = started(DRIVING)
        assertEquals(WALKING, g.onReading(WALKING, MOVING))
        assertNull(g.parked)
    }

    @Test fun `a stop already believed is not parked`() {
        // Nothing to lose by dropping it: there is no edge here, the gate is already still.
        val g = ActivityGate()
        assertNull(g.onReading(STILL, MOVING))
        assertNull(g.parked)
    }

    @Test fun `motion with nothing parked reports no change`() {
        val g = started(WALKING)
        assertNull(g.onMotion(Motion.Stopped))
        assertEquals(WALKING, g.confirmed)
    }

    @Test fun `arming drops a parked reading`() {
        val g = started(WALKING)
        g.onReading(STILL, MOVING)
        g.onArmed()
        assertNull(g.parked)
        assertNull(g.onMotion(Motion.Stopped))
        assertEquals(STILL, g.confirmed)
    }

    /** A gate with [activity] confirmed. */
    private fun started(activity: ActivityType): ActivityGate {
        val g = ActivityGate()
        g.onReading(activity)
        return g
    }
}
