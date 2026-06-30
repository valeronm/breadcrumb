package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.ActivityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The receiver's decision logic: how raw transitions/snapshots become forwarded activities. */
class ActivityInterpreterTest {

    private val STILL = ActivityType.STILL
    private val WALKING = ActivityType.WALKING

    private fun cfg(pollEnabled: Boolean = true, pollIntervalMs: Long = 30_000) =
        ActivityInterpreter.TransitionConfig(pollEnabled, pollIntervalMs)

    // --- Transitions ------------------------------------------------------

    @Test fun `a fresh ENTER forwards the activity`() {
        assertEquals(
            ActivityInterpreter.TransitionDecision.Forward(WALKING),
            ActivityInterpreter.interpretTransition(WALKING, isExit = false, ageMs = 2_000, config = cfg()),
        )
    }

    @Test fun `EXIT of a moving activity maps to STILL`() {
        assertEquals(
            ActivityInterpreter.TransitionDecision.Forward(STILL, exitMapped = true),
            ActivityInterpreter.interpretTransition(WALKING, isExit = true, ageMs = 2_000, config = cfg()),
        )
    }

    @Test fun `EXIT of a non-moving activity is ignored`() {
        assertEquals(
            ActivityInterpreter.TransitionDecision.Ignore,
            ActivityInterpreter.interpretTransition(STILL, isExit = true, ageMs = 2_000, config = cfg()),
        )
    }

    @Test fun `a stale transition is dropped while the poll is on`() {
        assertEquals(
            ActivityInterpreter.TransitionDecision.Stale(45_000),
            ActivityInterpreter.interpretTransition(WALKING, isExit = false, ageMs = 45_000, config = cfg()),
        )
    }

    @Test fun `with the poll off a stale transition is honoured at any age`() {
        assertEquals(
            ActivityInterpreter.TransitionDecision.Forward(WALKING),
            ActivityInterpreter.interpretTransition(
                WALKING, isExit = false, ageMs = 5_000_000, config = cfg(pollEnabled = false),
            ),
        )
    }

    @Test fun `age exactly at the cutoff is not stale`() {
        // Uses strictly-greater-than, so age == interval still forwards.
        assertEquals(
            ActivityInterpreter.TransitionDecision.Forward(WALKING),
            ActivityInterpreter.interpretTransition(WALKING, isExit = false, ageMs = 30_000, config = cfg()),
        )
    }

    // --- Snapshots --------------------------------------------------------

    @Test fun `a confident moving snapshot is forwarded`() {
        assertEquals(WALKING, ActivityInterpreter.interpretSnapshot(WALKING, confidence = 88, confidenceThreshold = 50))
    }

    @Test fun `a confident STILL snapshot is forwarded too`() {
        assertEquals(STILL, ActivityInterpreter.interpretSnapshot(STILL, confidence = 96, confidenceThreshold = 50))
    }

    @Test fun `a low-confidence snapshot is ignored`() {
        assertNull(ActivityInterpreter.interpretSnapshot(WALKING, confidence = 24, confidenceThreshold = 50))
    }

    @Test fun `an UNKNOWN snapshot is ignored even when confident`() {
        assertNull(ActivityInterpreter.interpretSnapshot(ActivityType.UNKNOWN, confidence = 90, confidenceThreshold = 50))
    }

    @Test fun `confidence exactly at the threshold is forwarded`() {
        assertEquals(WALKING, ActivityInterpreter.interpretSnapshot(WALKING, confidence = 50, confidenceThreshold = 50))
    }
}
