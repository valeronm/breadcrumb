package io.github.valeronm.breadcrumb.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The track lifecycle: trusted activity changes map onto actions over the sealed
 * [TrackController.Phase]. A stop closes, so the one rule left here is the motion family — whether a
 * returning stretch belongs to the last track is [StitchRule]'s question, asked of the stored
 * history rather than of a phase, which is why nothing in this suite has a clock in it.
 */
class TrackControllerTest {

    private val STILL = ActivityType.STILL
    private val WALKING = ActivityType.WALKING
    private val RUNNING = ActivityType.RUNNING
    private val DRIVING = ActivityType.DRIVING

    private fun recording(activity: ActivityType): TrackController =
        TrackController().apply { onRecording(activity) }

    // --- Starting and switching while live -------------------------------

    @Test fun `a moving activity from idle asks for a track`() {
        assertEquals(RecordingAction.StartNew(WALKING), TrackController().onActivity(WALKING))
    }

    @Test fun `a different-family activity while recording switches track`() {
        assertEquals(RecordingAction.StartNew(DRIVING), recording(WALKING).onActivity(DRIVING))
    }

    @Test fun `a same-family activity while recording continues the track`() {
        // Walking ⇄ running (a common Activity-Recognition flip) stays one track, new segment.
        assertEquals(RecordingAction.ContinueSameTrack(RUNNING), recording(WALKING).onActivity(RUNNING))
    }

    // --- Stopping ---------------------------------------------------------

    @Test fun `stopping while recording closes the track`() {
        assertEquals(RecordingAction.Close, recording(WALKING).onActivity(STILL))
    }

    @Test fun `stopping while idle does nothing`() {
        assertEquals(RecordingAction.Noop, TrackController().onActivity(STILL))
    }

    // --- Phase bookkeeping ------------------------------------------------

    @Test fun `lifecycle callbacks move through the phases`() {
        val c = TrackController()
        assertEquals(TrackController.Phase.Idle, c.phase)

        c.onRecording(WALKING)
        assertEquals(TrackController.Phase.Recording(WALKING), c.phase)

        c.onClosed()
        assertEquals(TrackController.Phase.Idle, c.phase)
    }

    @Test fun `a same-family switch tracks the new sub-activity`() {
        val c = recording(WALKING)
        c.onRecording(RUNNING)
        assertEquals(TrackController.Phase.Recording(RUNNING), c.phase)
    }
}
