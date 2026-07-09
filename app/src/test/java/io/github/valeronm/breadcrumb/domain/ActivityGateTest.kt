package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.ActivityType
import org.junit.Assert.assertEquals
import org.junit.Test

/** The activity debouncer: instant to start and to stop; a prompt return in grace is a resume. */
class ActivityGateTest {

    private val STILL = ActivityType.STILL
    private val WALKING = ActivityType.WALKING
    private val RUNNING = ActivityType.RUNNING

    private val GRACE_MS = 180_000L

    @Test fun `still while already still is no change`() {
        assertEquals(Confirmed.NoChange, ActivityGate().onReading(STILL, 0, GRACE_MS))
    }

    @Test fun `a moving activity starts on the first reading`() {
        val g = ActivityGate()
        assertEquals(Confirmed.Started(WALKING), g.onReading(WALKING, 0, GRACE_MS))
        assertEquals(WALKING, g.confirmed)
    }

    @Test fun `stopping is trusted immediately`() {
        val g = started(WALKING)
        assertEquals(Confirmed.Stopped, g.onReading(STILL, 100, GRACE_MS))
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

    @Test fun `a different activity within grace does not continue`() {
        val g = started(WALKING)
        g.onReading(STILL, 60_000, GRACE_MS)
        assertEquals(Confirmed.Started(RUNNING), g.onReading(RUNNING, 120_000, GRACE_MS))
    }

    @Test fun `a zero grace window never continues`() {
        val g = started(WALKING)
        g.onReading(STILL, 60_000, 0)
        assertEquals(Confirmed.Started(WALKING), g.onReading(WALKING, 60_000, 0))
    }

    @Test fun `starting fresh clears the grace state`() {
        val g = started(WALKING)
        g.onReading(STILL, 60_000, GRACE_MS)              // WALKING in grace until 240s
        g.onReading(RUNNING, 120_000, GRACE_MS)           // fresh start clears it
        g.onReading(STILL, 130_000, GRACE_MS)             // now RUNNING is the recent activity
        assertEquals(Confirmed.Started(WALKING), g.onReading(WALKING, 140_000, GRACE_MS))
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
