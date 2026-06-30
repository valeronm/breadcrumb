package io.github.valeronm.breadcrumb.location

import io.github.valeronm.breadcrumb.data.ActivityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Branch + state coverage for [RecordingStateMachine], driven through its real API: feed readings via
 * [RecordingStateMachine.onActivity] and play the service's role of reporting the track lifecycle back.
 */
class RecordingStateMachineTest {

    private val STILL = ActivityType.STILL
    private val WALKING = ActivityType.WALKING
    private val RUNNING = ActivityType.RUNNING
    private val DRIVING = ActivityType.DRIVING

    private fun cfg(
        resumeWindowMs: Long = 180_000,
        pollEnabled: Boolean = true,
        startConfirmations: Int = 2,
    ) = RecordingStateMachine.Config(resumeWindowMs, pollEnabled, startConfirmations)

    /** A machine actively recording [activity] (instant start, then track opened). */
    private fun recording(activity: ActivityType): RecordingStateMachine {
        val sm = RecordingStateMachine()
        sm.onActivity(activity, 0, cfg(startConfirmations = 1))
        sm.onRecordingStarted()
        return sm
    }

    /** A machine with a paused [activity] track, paused at [sinceMs]. */
    private fun paused(activity: ActivityType, sinceMs: Long): RecordingStateMachine {
        val sm = recording(activity)
        sm.onActivity(STILL, sinceMs, cfg())
        sm.onPaused(activity, sinceMs)
        return sm
    }

    // --- Stops -------------------------------------------------------------

    @Test fun `still while idle and unpending is a noop`() {
        assertEquals(RecordingAction.Noop, RecordingStateMachine().onActivity(STILL, 0, cfg()))
    }

    @Test fun `still cancels a pending start`() {
        val sm = RecordingStateMachine()
        sm.onActivity(WALKING, 0, cfg()) // pending 1/2
        assertEquals(RecordingAction.CancelledPending, sm.onActivity(STILL, 30_000, cfg()))
        assertEquals(STILL, sm.currentActivity)
    }

    @Test fun `still while recording pauses the track`() {
        val sm = recording(WALKING)
        val action = sm.onActivity(STILL, 100, cfg())
        assertEquals(RecordingAction.Pause(WALKING), action)
        assertEquals(STILL, sm.currentActivity)
    }

    @Test fun `still while recording closes the track when the window is off`() {
        val sm = recording(WALKING)
        assertEquals(RecordingAction.Close, sm.onActivity(STILL, 100, cfg(resumeWindowMs = 0)))
    }

    // --- Resume (never gated) ---------------------------------------------

    @Test fun `moving resumes a paused track of the same activity within the window`() {
        val sm = paused(WALKING, 60_000)
        val action = sm.onActivity(WALKING, 120_000, cfg())
        assertEquals(RecordingAction.Resume, action)
        sm.onResumed()
        assertEquals(WALKING, sm.currentActivity)
        assertFalse(sm.isPaused)
    }

    @Test fun `resume beats the confirmation gate`() {
        val sm = paused(WALKING, 60_000)
        assertEquals(RecordingAction.Resume, sm.onActivity(WALKING, 120_000, cfg(startConfirmations = 2)))
    }

    @Test fun `paused too long does not resume, falls into the start gate`() {
        val sm = paused(WALKING, 60_000)
        assertEquals(RecordingAction.AwaitingConfirmation(WALKING, 1, 2), sm.onActivity(WALKING, 300_000, cfg()))
        assertTrue(sm.isPaused) // still paused until the service confirms + closes
    }

    @Test fun `a different paused activity does not resume`() {
        val sm = paused(DRIVING, 10_000)
        assertEquals(RecordingAction.AwaitingConfirmation(WALKING, 1, 2), sm.onActivity(WALKING, 20_000, cfg()))
    }

    @Test fun `a zero resume window never resumes`() {
        val sm = paused(WALKING, 0)
        assertEquals(RecordingAction.AwaitingConfirmation(WALKING, 1, 2), sm.onActivity(WALKING, 0, cfg(resumeWindowMs = 0)))
    }

    // --- Already recording -------------------------------------------------

    @Test fun `same moving activity while recording is a noop`() {
        val sm = recording(WALKING)
        assertEquals(RecordingAction.Noop, sm.onActivity(WALKING, 100, cfg()))
        assertEquals(WALKING, sm.currentActivity)
    }

    // --- Start confirmation streak ----------------------------------------

    @Test fun `first moving reading awaits confirmation, opens nothing`() {
        val sm = RecordingStateMachine()
        assertEquals(RecordingAction.AwaitingConfirmation(WALKING, 1, 2), sm.onActivity(WALKING, 0, cfg()))
        assertEquals(STILL, sm.currentActivity) // unchanged so the next reading re-enters
    }

    @Test fun `second matching reading confirms and starts a new track`() {
        val sm = RecordingStateMachine()
        sm.onActivity(WALKING, 0, cfg())
        val action = sm.onActivity(WALKING, 30_000, cfg())
        assertEquals(RecordingAction.StartNew(WALKING), action)
        assertEquals(WALKING, sm.currentActivity)
    }

    @Test fun `a different moving activity restarts the streak`() {
        val sm = RecordingStateMachine()
        sm.onActivity(WALKING, 0, cfg())
        assertEquals(RecordingAction.AwaitingConfirmation(RUNNING, 1, 2), sm.onActivity(RUNNING, 30_000, cfg()))
    }

    @Test fun `a higher streak needs three readings`() {
        val sm = RecordingStateMachine()
        val c = cfg(startConfirmations = 3)
        assertEquals(RecordingAction.AwaitingConfirmation(WALKING, 1, 3), sm.onActivity(WALKING, 0, c))
        assertEquals(RecordingAction.AwaitingConfirmation(WALKING, 2, 3), sm.onActivity(WALKING, 30_000, c))
        assertEquals(RecordingAction.StartNew(WALKING), sm.onActivity(WALKING, 60_000, c))
    }

    @Test fun `confirmations of one starts instantly`() {
        assertEquals(RecordingAction.StartNew(WALKING), RecordingStateMachine().onActivity(WALKING, 0, cfg(startConfirmations = 1)))
    }

    @Test fun `poll disabled forces an instant start regardless of confirmations`() {
        val sm = RecordingStateMachine()
        assertEquals(RecordingAction.StartNew(WALKING), sm.onActivity(WALKING, 0, cfg(pollEnabled = false, startConfirmations = 3)))
        assertEquals(WALKING, sm.currentActivity)
    }

    @Test fun `switching activity while recording is debounced, not instant`() {
        val sm = recording(WALKING)
        assertEquals(RecordingAction.AwaitingConfirmation(DRIVING, 1, 2), sm.onActivity(DRIVING, 10, cfg()))
        assertEquals(WALKING, sm.currentActivity) // keep recording WALKING until confirmed
    }

    @Test fun `a confirmed activity switch starts a new track`() {
        val sm = recording(WALKING)
        sm.onActivity(DRIVING, 10, cfg())
        assertEquals(RecordingAction.StartNew(DRIVING), sm.onActivity(DRIVING, 20, cfg()))
    }

    // --- Lifecycle bookkeeping --------------------------------------------

    @Test fun `closing a paused track clears the paused state`() {
        val sm = paused(WALKING, 60_000)
        assertTrue(sm.isPaused)
        sm.onClosed() // finalize timer fired
        assertFalse(sm.isPaused)
        // No paused track to resume now → a fresh walk goes through the streak again.
        assertEquals(RecordingAction.AwaitingConfirmation(WALKING, 1, 2), sm.onActivity(WALKING, 600_000, cfg()))
    }

    @Test fun `arming resets the activity and streak but leaves an open track`() {
        val sm = recording(WALKING)
        sm.onActivity(DRIVING, 40_000, cfg()) // arm a pending switch
        sm.onArmed()
        assertEquals(STILL, sm.currentActivity)
        // Streak cleared: next DRIVING starts the count at 1, not 2.
        assertEquals(RecordingAction.AwaitingConfirmation(DRIVING, 1, 2), sm.onActivity(DRIVING, 50_000, cfg()))
    }
}
