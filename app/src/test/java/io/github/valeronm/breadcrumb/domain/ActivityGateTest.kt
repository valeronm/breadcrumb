package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.ActivityType
import org.junit.Assert.assertEquals
import org.junit.Test

/** The activity debouncer: instant to start and to stop; a prompt return in grace is a resume. */
class ActivityGateTest {

    private val STILL = ActivityType.STILL
    private val WALKING = ActivityType.WALKING
    private val RUNNING = ActivityType.RUNNING
    private val DRIVING = ActivityType.DRIVING

    private val GRACE_MS = 180_000L

    @Test fun `still while already still is no change`() {
        assertEquals(Confirmed.NoChange, ActivityGate().onReading(STILL, 0, GRACE_MS))
    }

    @Test fun `a moving activity starts on the first reading`() {
        val g = ActivityGate()
        assertEquals(Confirmed.Started(WALKING), g.onReading(WALKING, 0, GRACE_MS))
        assertEquals(WALKING, g.confirmed)
    }

    @Test fun `stopping is trusted immediately and reports the resume deadline`() {
        val g = started(WALKING)
        assertEquals(Confirmed.Stopped(100 + GRACE_MS), g.onReading(STILL, 100, GRACE_MS))
        assertEquals(STILL, g.confirmed)
    }

    @Test fun `the same activity while confirmed is no change`() {
        val g = started(WALKING)
        assertEquals(Confirmed.NoChange, g.onReading(WALKING, 100, GRACE_MS))
    }

    @Test fun `switching directly to another moving activity is a fresh start`() {
        val g = started(WALKING)
        assertEquals(Confirmed.Started(RUNNING), g.onReading(RUNNING, 30_000, GRACE_MS))
        assertEquals(RUNNING, g.confirmed)
    }

    // --- The grace window (resume as signal hysteresis) -------------------

    @Test fun `a prompt return to the just-ended activity continues instantly`() {
        val g = started(WALKING)
        g.onReading(STILL, 60_000, GRACE_MS)              // stop at 60s, grace until 240s
        assertEquals(Confirmed.Continuing(WALKING), g.onReading(WALKING, 120_000, GRACE_MS))
        assertEquals(WALKING, g.confirmed)
    }

    @Test fun `a return after the grace window is a fresh start`() {
        val g = started(WALKING)
        g.onReading(STILL, 60_000, GRACE_MS)              // grace until 240s
        assertEquals(Confirmed.Started(WALKING), g.onReading(WALKING, 300_000, GRACE_MS))
    }

    @Test fun `a same-family activity within grace continues`() {
        // Stop mid-walk, return as a run: still the same outing — the track layer keeps the
        // foot family on one track, so the gate reports a resume, not a fresh start.
        val g = started(WALKING)
        g.onReading(STILL, 60_000, GRACE_MS)
        assertEquals(Confirmed.Continuing(RUNNING), g.onReading(RUNNING, 120_000, GRACE_MS))
    }

    @Test fun `a different-family activity within grace does not continue`() {
        val g = started(WALKING)
        g.onReading(STILL, 60_000, GRACE_MS)
        assertEquals(Confirmed.Started(DRIVING), g.onReading(DRIVING, 120_000, GRACE_MS))
    }

    @Test fun `a same-family activity past grace is a fresh start, not a resume`() {
        // The whole point of the window: once it lapses, the return is a new outing — even
        // though the track layer might still be sitting on a paused track (Doze-deferred timer).
        val g = started(WALKING)
        g.onReading(STILL, 60_000, GRACE_MS)
        assertEquals(Confirmed.Started(RUNNING), g.onReading(RUNNING, 300_000, GRACE_MS))
    }

    @Test fun `a zero grace window never continues`() {
        val g = started(WALKING)
        g.onReading(STILL, 60_000, 0)
        assertEquals(Confirmed.Started(WALKING), g.onReading(WALKING, 60_000, 0))
    }

    @Test fun `starting fresh clears the grace state`() {
        val g = started(WALKING)
        g.onReading(STILL, 60_000, GRACE_MS)              // WALKING in grace until 240s
        g.onReading(DRIVING, 120_000, GRACE_MS)           // fresh start (other family) clears it
        g.onReading(STILL, 130_000, GRACE_MS)             // now DRIVING is the recent activity
        // Walking is no longer the remembered activity, so it can't resume into the drive.
        assertEquals(Confirmed.Started(WALKING), g.onReading(WALKING, 140_000, GRACE_MS))
    }

    // --- onTick (the pause wake's expiry question) -------------------------

    @Test fun `a tick before the deadline changes nothing`() {
        val g = started(WALKING)
        g.onReading(STILL, 60_000, GRACE_MS)              // grace until 240s
        assertEquals(Confirmed.NoChange, g.onTick(120_000))
        // The window is still live — a return after the early tick still continues.
        assertEquals(Confirmed.Continuing(WALKING), g.onReading(WALKING, 130_000, GRACE_MS))
    }

    @Test fun `a tick at the deadline expires the window`() {
        val g = started(WALKING)
        g.onReading(STILL, 60_000, GRACE_MS)              // grace until 240s
        assertEquals(Confirmed.Expired, g.onTick(240_000))
        // Expired means a later return is a fresh start.
        assertEquals(Confirmed.Started(WALKING), g.onReading(WALKING, 250_000, GRACE_MS))
    }

    @Test fun `expiry fires once — a duplicate wake is no change`() {
        val g = started(WALKING)
        g.onReading(STILL, 60_000, GRACE_MS)
        g.onTick(240_000)
        assertEquals(Confirmed.NoChange, g.onTick(300_000))
    }

    @Test fun `a tick with nothing stopped is no change`() {
        assertEquals(Confirmed.NoChange, ActivityGate().onTick(240_000))
        assertEquals(Confirmed.NoChange, started(WALKING).onTick(240_000))
    }

    @Test fun `a tick after a resume is no change`() {
        val g = started(WALKING)
        g.onReading(STILL, 60_000, GRACE_MS)
        g.onReading(WALKING, 120_000, GRACE_MS)           // resumed — old deadline is moot
        assertEquals(Confirmed.NoChange, g.onTick(240_000))
    }

    @Test fun `a zero grace window expires on the immediate tick`() {
        val g = started(WALKING)
        g.onReading(STILL, 60_000, 0)                     // deadline = now
        assertEquals(Confirmed.Expired, g.onTick(60_000))
    }

    @Test fun `arming resets the confirmed activity and grace state`() {
        val g = started(WALKING)
        g.onReading(STILL, 60_000, GRACE_MS)              // WALKING in grace
        g.onArmed()
        assertEquals(STILL, g.confirmed)
        assertEquals(Confirmed.Started(WALKING), g.onReading(WALKING, 70_000, GRACE_MS))
    }

    /** A gate with [activity] confirmed. */
    private fun started(activity: ActivityType): ActivityGate {
        val g = ActivityGate()
        g.onReading(activity, 0, GRACE_MS)
        return g
    }
}
