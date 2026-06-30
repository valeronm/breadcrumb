package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.ActivityType
import org.junit.Assert.assertEquals
import org.junit.Test

/** The activity debouncer: asymmetric trust — slow to start, instant to stop or to resume in grace. */
class ActivityGateTest {

    private val STILL = ActivityType.STILL
    private val WALKING = ActivityType.WALKING
    private val RUNNING = ActivityType.RUNNING

    private fun cfg(
        startConfirmations: Int = 2,
        graceWindowMs: Long = 180_000,
        pollEnabled: Boolean = true,
    ) = ActivityGate.Config(startConfirmations, graceWindowMs, pollEnabled)

    @Test fun `still while already still is no change`() {
        assertEquals(Confirmed.NoChange, ActivityGate().onReading(STILL, 0, cfg()))
    }

    @Test fun `a new activity needs the streak before it is trusted`() {
        val g = ActivityGate()
        assertEquals(Confirmed.Awaiting(WALKING, 1, 2), g.onReading(WALKING, 0, cfg()))
        assertEquals(STILL, g.confirmed) // not trusted yet
        assertEquals(Confirmed.Started(WALKING), g.onReading(WALKING, 30_000, cfg()))
        assertEquals(WALKING, g.confirmed)
    }

    @Test fun `a different activity restarts the streak`() {
        val g = ActivityGate()
        g.onReading(WALKING, 0, cfg())
        assertEquals(Confirmed.Awaiting(RUNNING, 1, 2), g.onReading(RUNNING, 30_000, cfg()))
    }

    @Test fun `confirmations of one starts instantly`() {
        assertEquals(Confirmed.Started(WALKING), ActivityGate().onReading(WALKING, 0, cfg(startConfirmations = 1)))
    }

    @Test fun `poll off starts instantly regardless of confirmations`() {
        assertEquals(
            Confirmed.Started(WALKING),
            ActivityGate().onReading(WALKING, 0, cfg(pollEnabled = false, startConfirmations = 3)),
        )
    }

    @Test fun `a three-deep streak`() {
        val g = ActivityGate(); val c = cfg(startConfirmations = 3)
        assertEquals(Confirmed.Awaiting(WALKING, 1, 3), g.onReading(WALKING, 0, c))
        assertEquals(Confirmed.Awaiting(WALKING, 2, 3), g.onReading(WALKING, 30_000, c))
        assertEquals(Confirmed.Started(WALKING), g.onReading(WALKING, 60_000, c))
    }

    @Test fun `a stop that only abandons an unconfirmed start reports cancelled`() {
        val g = ActivityGate()
        g.onReading(WALKING, 0, cfg())                    // pending 1/2, confirmed still STILL
        assertEquals(Confirmed.Cancelled, g.onReading(STILL, 30_000, cfg()))
        assertEquals(STILL, g.confirmed)
    }

    @Test fun `a redundant still with nothing pending is just no change`() {
        assertEquals(Confirmed.NoChange, ActivityGate().onReading(STILL, 0, cfg()))
    }

    @Test fun `stopping is trusted immediately`() {
        val g = started(WALKING)
        assertEquals(Confirmed.Stopped, g.onReading(STILL, 100, cfg()))
        assertEquals(STILL, g.confirmed)
    }

    @Test fun `the same activity while confirmed is no change`() {
        val g = started(WALKING)
        assertEquals(Confirmed.NoChange, g.onReading(WALKING, 100, cfg()))
    }

    // --- The grace window (resume as signal hysteresis) -------------------

    @Test fun `a prompt return to the just-ended activity continues instantly`() {
        val g = started(WALKING)
        g.onReading(STILL, 60_000, cfg())                 // stop at 60s, grace until 240s
        assertEquals(Confirmed.Continuing(WALKING), g.onReading(WALKING, 120_000, cfg()))
        assertEquals(WALKING, g.confirmed)
    }

    @Test fun `a return after the grace window needs the streak again`() {
        val g = started(WALKING)
        g.onReading(STILL, 60_000, cfg())                 // grace until 240s
        assertEquals(Confirmed.Awaiting(WALKING, 1, 2), g.onReading(WALKING, 300_000, cfg()))
    }

    @Test fun `a different activity within grace does not continue`() {
        val g = started(WALKING)
        g.onReading(STILL, 60_000, cfg())
        assertEquals(Confirmed.Awaiting(RUNNING, 1, 2), g.onReading(RUNNING, 120_000, cfg()))
    }

    @Test fun `a zero grace window never continues`() {
        val g = started(WALKING)
        g.onReading(STILL, 60_000, cfg(graceWindowMs = 0))
        assertEquals(Confirmed.Awaiting(WALKING, 1, 2), g.onReading(WALKING, 60_000, cfg(graceWindowMs = 0)))
    }

    @Test fun `arming resets the confirmed activity and any streak`() {
        val g = ActivityGate()
        g.onReading(WALKING, 0, cfg()) // pending 1/2
        g.onArmed()
        assertEquals(STILL, g.confirmed)
        assertEquals(Confirmed.Awaiting(WALKING, 1, 2), g.onReading(WALKING, 10, cfg())) // count restarts at 1
    }

    /** A gate with [activity] confirmed (instant start). */
    private fun started(activity: ActivityType): ActivityGate {
        val g = ActivityGate()
        g.onReading(activity, 0, cfg(startConfirmations = 1))
        return g
    }
}
