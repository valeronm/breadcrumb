package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.ActivityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The track lifecycle: trusted activity changes (timestamped) map onto actions over the sealed
 * [TrackController.Phase]. Both rules that decide continue-vs-split live here — the resume window
 * and the motion family — so they're tested together against the clock.
 */
class TrackControllerTest {

    private val STILL = ActivityType.STILL
    private val WALKING = ActivityType.WALKING
    private val RUNNING = ActivityType.RUNNING
    private val DRIVING = ActivityType.DRIVING

    private val WINDOW = 180_000L

    private fun recording(activity: ActivityType): TrackController =
        TrackController().apply { onRecording(activity) }

    /** Recording [activity], then stopped at 60s — so the resume window runs until 240s. */
    private fun paused(activity: ActivityType): TrackController =
        recording(activity).apply {
            onActivity(STILL, 60_000, WINDOW)
            onPaused(activity, 60_000 + WINDOW)
        }

    // --- Starting and switching while live -------------------------------

    @Test fun `a moving activity from idle opens a new track`() {
        assertEquals(
            RecordingAction.StartNew(WALKING),
            TrackController().onActivity(WALKING, 0, WINDOW),
        )
    }

    @Test fun `a different-family activity while recording switches track`() {
        assertEquals(
            RecordingAction.StartNew(DRIVING),
            recording(WALKING).onActivity(DRIVING, 30_000, WINDOW),
        )
    }

    @Test fun `a same-family activity while recording continues the track`() {
        // Walking ⇄ running (a common Activity-Recognition flip) stays one track, new segment.
        assertEquals(
            RecordingAction.ContinueSameTrack(RUNNING),
            recording(WALKING).onActivity(RUNNING, 30_000, WINDOW),
        )
    }

    // --- Stopping ---------------------------------------------------------

    @Test fun `stopping while recording pauses, deriving the deadline from the reading`() {
        // The deadline is the reading's own time + the window — not the apply time, so a reading
        // drained late from a frozen queue can't extend the window it belongs to.
        assertEquals(
            RecordingAction.Pause(WALKING, 60_000 + WINDOW),
            recording(WALKING).onActivity(STILL, 60_000, WINDOW),
        )
    }

    @Test fun `stopping while idle does nothing`() {
        assertEquals(RecordingAction.Noop, TrackController().onActivity(STILL, 0, WINDOW))
    }

    // --- The resume window ------------------------------------------------

    @Test fun `a prompt return resumes the paused track`() {
        assertEquals(RecordingAction.Resume, paused(WALKING).onActivity(WALKING, 120_000, WINDOW))
    }

    @Test fun `a same-family return within the window resumes too`() {
        // Stop mid-walk, return as a run: still the same outing, one track.
        assertEquals(RecordingAction.Resume, paused(WALKING).onActivity(RUNNING, 120_000, WINDOW))
    }

    @Test fun `a return after the window splits, even for the same activity`() {
        // The whole point of the window. This must hold no matter how late the expiry tick runs
        // (Doze defers it by minutes) — the decision is made from the reading's timestamp, not
        // from whether a timer fired.
        assertEquals(
            RecordingAction.StartNew(WALKING),
            paused(WALKING).onActivity(WALKING, 300_000, WINDOW),
        )
    }

    @Test fun `the deadline itself has already lapsed`() {
        // Strictly before: at the deadline the window is over (a zero window never resumes).
        assertEquals(
            RecordingAction.StartNew(WALKING),
            paused(WALKING).onActivity(WALKING, 240_000, WINDOW),
        )
    }

    @Test fun `a different-family activity within the window splits`() {
        assertEquals(
            RecordingAction.StartNew(DRIVING),
            paused(WALKING).onActivity(DRIVING, 120_000, WINDOW),
        )
    }

    @Test fun `a return with no paused track starts fresh`() {
        assertEquals(
            RecordingAction.StartNew(WALKING),
            TrackController().onActivity(WALKING, 120_000, WINDOW),
        )
    }

    // --- onTick (the pause wake's expiry question) ------------------------

    @Test fun `a tick before the deadline changes nothing`() {
        val c = paused(WALKING)
        assertEquals(RecordingAction.Noop, c.onTick(120_000))
        // The window is still live — a return after the early tick still resumes.
        assertEquals(RecordingAction.Resume, c.onActivity(WALKING, 130_000, WINDOW))
    }

    @Test fun `a tick at the deadline finalizes`() {
        assertEquals(RecordingAction.Finalize, paused(WALKING).onTick(240_000))
    }

    @Test fun `a tick well past the deadline still finalizes — a late wake is not a lost one`() {
        assertEquals(RecordingAction.Finalize, paused(WALKING).onTick(20 * 60_000))
    }

    @Test fun `a stale tick is a noop`() {
        // The wake landed after a resume (Recording) or after the track closed (Idle).
        assertEquals(RecordingAction.Noop, recording(WALKING).onTick(240_000))
        assertEquals(RecordingAction.Noop, TrackController().onTick(240_000))
    }

    @Test fun `a zero window is already lapsed at the stop`() {
        // Auto-pause off: the deadline is the stop itself, so nothing ever resumes into the track.
        val c = recording(WALKING)
        val pause = c.onActivity(STILL, 60_000, 0) as RecordingAction.Pause
        assertEquals(60_000L, pause.resumeDeadlineMs)
        c.onPaused(WALKING, pause.resumeDeadlineMs)
        assertEquals(RecordingAction.Finalize, c.onTick(60_000))
        assertEquals(RecordingAction.StartNew(WALKING), c.onActivity(WALKING, 60_000, 0))
    }

    // --- Phase bookkeeping ------------------------------------------------

    @Test fun `lifecycle callbacks move through the phases`() {
        val c = TrackController()
        assertEquals(TrackController.Phase.Idle, c.phase)

        c.onRecording(WALKING)
        assertEquals(TrackController.Phase.Recording(WALKING), c.phase)
        assertFalse(c.isPaused)

        c.onPaused(WALKING, 240_000)
        assertEquals(TrackController.Phase.Paused(WALKING, 240_000), c.phase)
        assertTrue(c.isPaused)

        c.onRecording(WALKING)
        assertEquals(TrackController.Phase.Recording(WALKING), c.phase)
        assertFalse(c.isPaused)

        c.onClosed()
        assertEquals(TrackController.Phase.Idle, c.phase)
    }

    @Test fun `a same-family switch tracks the new sub-activity`() {
        val c = recording(WALKING)
        c.onRecording(RUNNING)
        assertEquals(TrackController.Phase.Recording(RUNNING), c.phase)
    }
}
