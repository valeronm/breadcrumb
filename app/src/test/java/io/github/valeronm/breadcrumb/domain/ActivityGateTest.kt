package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.domain.ActivityType
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

    /** A gate with [activity] confirmed. */
    private fun started(activity: ActivityType): ActivityGate {
        val g = ActivityGate()
        g.onReading(activity)
        return g
    }
}
