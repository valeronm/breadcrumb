package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.ActivityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Maps the trusted [Confirmed] signal onto track-lifecycle actions over the sealed [Phase]. */
class TrackControllerTest {

    private val WALKING = ActivityType.WALKING
    private val DRIVING = ActivityType.DRIVING

    private fun recording(activity: ActivityType): TrackController =
        TrackController().apply { onRecordingStarted(activity) }

    private fun paused(activity: ActivityType): TrackController =
        recording(activity).apply { onPaused(activity) }

    // --- onConfirmed → action -------------------------------------------

    @Test fun `started from idle opens a new track`() {
        assertEquals(RecordingAction.StartNew(WALKING), TrackController().onConfirmed(Confirmed.Started(WALKING)))
    }

    @Test fun `started while recording another activity switches track`() {
        assertEquals(RecordingAction.StartNew(DRIVING), recording(WALKING).onConfirmed(Confirmed.Started(DRIVING)))
    }

    @Test fun `stopped while recording pauses`() {
        assertEquals(RecordingAction.Pause(WALKING), recording(WALKING).onConfirmed(Confirmed.Stopped))
    }

    @Test fun `stopped while idle does nothing`() {
        assertEquals(RecordingAction.Noop, TrackController().onConfirmed(Confirmed.Stopped))
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
