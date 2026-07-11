package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.ActivityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Maps the trusted [Confirmed] signal onto track-lifecycle actions over the sealed [Phase]. */
class TrackControllerTest {

    private val WALKING = ActivityType.WALKING
    private val RUNNING = ActivityType.RUNNING
    private val DRIVING = ActivityType.DRIVING

    private fun recording(activity: ActivityType): TrackController =
        TrackController().apply { onRecordingStarted(activity) }

    private fun paused(activity: ActivityType): TrackController =
        recording(activity).apply { onPaused(activity) }

    // --- onConfirmed → action -------------------------------------------

    @Test fun `started from idle opens a new track`() {
        assertEquals(RecordingAction.StartNew(WALKING), TrackController().onConfirmed(Confirmed.Started(WALKING)))
    }

    @Test fun `started while recording a different-family activity switches track`() {
        assertEquals(RecordingAction.StartNew(DRIVING), recording(WALKING).onConfirmed(Confirmed.Started(DRIVING)))
    }

    @Test fun `started while recording a same-family activity continues the track`() {
        // Walking ⇄ running (a common Activity-Recognition flip) stays one track, new segment.
        assertEquals(
            RecordingAction.ContinueSameTrack(RUNNING),
            recording(WALKING).onConfirmed(Confirmed.Started(RUNNING)),
        )
    }

    @Test fun `a same-family activity after a brief pause resumes the track`() {
        // Walk → stop → run within the grace window resumes the walk track rather than splitting.
        assertEquals(RecordingAction.Resume, paused(WALKING).onConfirmed(Confirmed.Started(RUNNING)))
    }

    @Test fun `a different-family activity after a pause starts fresh`() {
        assertEquals(RecordingAction.StartNew(DRIVING), paused(WALKING).onConfirmed(Confirmed.Started(DRIVING)))
    }

    @Test fun `onContinuedSameTrack tracks the new sub-activity`() {
        val c = recording(WALKING)
        c.onContinuedSameTrack(RUNNING)
        assertEquals(TrackController.Phase.Recording(RUNNING), c.phase)
    }

    @Test fun `stopped while recording pauses, carrying the resume deadline`() {
        assertEquals(
            RecordingAction.Pause(WALKING, 240_000L),
            recording(WALKING).onConfirmed(Confirmed.Stopped(240_000L)),
        )
    }

    @Test fun `stopped while idle does nothing`() {
        assertEquals(RecordingAction.Noop, TrackController().onConfirmed(Confirmed.Stopped(240_000L)))
    }

    @Test fun `expiry while paused finalizes`() {
        assertEquals(RecordingAction.Finalize, paused(WALKING).onConfirmed(Confirmed.Expired))
    }

    @Test fun `a stale expiry wake is a noop`() {
        // The wake landed after a resume (Recording) or after the track closed (Idle).
        assertEquals(RecordingAction.Noop, recording(WALKING).onConfirmed(Confirmed.Expired))
        assertEquals(RecordingAction.Noop, TrackController().onConfirmed(Confirmed.Expired))
    }

    @Test fun `continuing a paused track resumes it`() {
        assertEquals(RecordingAction.Resume, paused(WALKING).onConfirmed(Confirmed.Continuing(WALKING)))
    }

    @Test fun `continuing with no paused track starts fresh`() {
        // The grace window said "resume" but the finalize timer already closed the track.
        assertEquals(RecordingAction.StartNew(WALKING), TrackController().onConfirmed(Confirmed.Continuing(WALKING)))
    }

    @Test fun `non-changes are noops`() {
        val c = recording(WALKING)
        assertEquals(RecordingAction.Noop, c.onConfirmed(Confirmed.NoChange))
    }

    // --- Phase bookkeeping ----------------------------------------------

    @Test fun `lifecycle callbacks move through the phases`() {
        val c = TrackController()
        assertEquals(TrackController.Phase.Idle, c.phase)

        c.onRecordingStarted(WALKING)
        assertEquals(TrackController.Phase.Recording(WALKING), c.phase)
        assertFalse(c.isPaused)

        c.onPaused(WALKING)
        assertEquals(TrackController.Phase.Paused(WALKING), c.phase)
        assertTrue(c.isPaused)

        c.onResumed(WALKING)
        assertEquals(TrackController.Phase.Recording(WALKING), c.phase)
        assertFalse(c.isPaused)

        c.onClosed()
        assertEquals(TrackController.Phase.Idle, c.phase)
    }
}
