package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.domain.ActivityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The receiver's decision logic: how raw transitions/snapshots become forwarded activities. */
class ActivityInterpreterTest {

    private val STILL = ActivityType.STILL
    private val WALKING = ActivityType.WALKING

    // --- Transitions ------------------------------------------------------

    @Test fun `an ENTER forwards the activity`() {
        assertEquals(
            ActivityInterpreter.TransitionDecision.Forward(WALKING),
            ActivityInterpreter.interpretTransition(WALKING, isExit = false),
        )
    }

    @Test fun `EXIT of a moving activity maps to STILL`() {
        assertEquals(
            ActivityInterpreter.TransitionDecision.Forward(STILL, exitMapped = true),
            ActivityInterpreter.interpretTransition(WALKING, isExit = true),
        )
    }

    @Test fun `EXIT of a non-moving activity is ignored`() {
        assertEquals(
            ActivityInterpreter.TransitionDecision.Ignore,
            ActivityInterpreter.interpretTransition(STILL, isExit = true),
        )
    }

    // --- Snapshots --------------------------------------------------------

    @Test fun `a confident moving snapshot is forwarded`() {
        assertEquals(WALKING, snapshot(WALKING, confidence = 88))
    }

    @Test fun `a confident STILL snapshot is forwarded too`() {
        assertEquals(STILL, snapshot(STILL, confidence = 96))
    }

    @Test fun `a low-confidence snapshot is ignored`() {
        assertNull(snapshot(WALKING, confidence = 24))
    }

    @Test fun `an UNKNOWN snapshot is ignored even when confident`() {
        assertNull(snapshot(ActivityType.UNKNOWN, confidence = 90))
    }

    @Test fun `confidence exactly at the threshold is forwarded`() {
        assertEquals(WALKING, snapshot(WALKING, confidence = 50))
    }

    @Test fun `any snapshot is ignored once a transition has been applied`() {
        // The stale-STILL case: a replayed ENTER IN_VEHICLE started a drive, then the cached
        // snapshot reports STILL — it must not pause the track.
        assertNull(snapshot(STILL, confidence = 100, transitionApplied = true))
        assertNull(snapshot(WALKING, confidence = 100, transitionApplied = true))
    }

    private fun snapshot(activity: ActivityType, confidence: Int, transitionApplied: Boolean = false) =
        ActivityInterpreter.interpretSnapshot(activity, confidence, confidenceThreshold = 50, transitionApplied)
}
