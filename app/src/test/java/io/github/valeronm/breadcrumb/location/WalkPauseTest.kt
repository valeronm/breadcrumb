package io.github.valeronm.breadcrumb.location

import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.domain.ActivityGate
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.Motion
import io.github.valeronm.breadcrumb.domain.ORIGIN_LAT
import io.github.valeronm.breadcrumb.domain.Speed
import io.github.valeronm.breadcrumb.domain.lonAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How long a walk's pause must last before it ends the track. */
class WalkPauseTest : ActivityIngestFixture() {

    @Test fun `a walk's confirmed stop is held for the continuation window`() {
        val stillAt = moveThenStand(ActivityType.WALKING, WALKING)

        val out = reading(ActivityType.STILL, stillAt)

        assertEquals(listOf(Effect.StampReading(stillAt)), out)
        assertTrue(core.recording)
        assertEquals(ActivityGate.Held(ActivityType.STILL, ActivityGate.Hold.CORROBORATED), core.held)
    }

    @Test fun `the held stop closes the walk at its last good fix once the window has passed`() {
        val stillAt = moveThenStand(ActivityType.WALKING, WALKING)
        reading(ActivityType.STILL, stillAt)

        assertTrue(core.onMotion(Motion.Stopped, stillAt + STITCH_WINDOW_MS - 1, settings).isEmpty())
        val out = core.onMotion(Motion.Stopped, stillAt + STITCH_WINDOW_MS, settings)

        assertTrue(out.contains(Effect.CloseTrack(endedAt = stillAt, renameTo = null)))
        assertFalse(core.recording)
    }

    @Test fun `the witness falling silent does not cut the wait short`() {
        val stillAt = moveThenStand(ActivityType.WALKING, WALKING)
        reading(ActivityType.STILL, stillAt)

        assertTrue(core.onMotion(Motion.Unknown, stillAt + HOLD_CAP_MS, settings).isEmpty())
        assertTrue(core.recording)
    }

    @Test fun `walking on inside the window leaves the walk open`() {
        val stillAt = moveThenStand(ActivityType.WALKING, WALKING)
        reading(ActivityType.STILL, stillAt)
        val walkedOnAt = stillAt + STITCH_WINDOW_MS - 1

        assertEquals(listOf(Effect.StampReading(walkedOnAt)), reading(ActivityType.WALKING, walkedOnAt))
        assertNull(core.held)
        assertTrue(core.recording)
    }

    @Test fun `moving ground restarts the wait`() {
        val stillAt = moveThenStand(ActivityType.WALKING, WALKING)
        reading(ActivityType.STILL, stillAt)
        val movedAt = stillAt + MINUTE
        core.onMotion(Motion.Moving(WALKING), movedAt, settings)

        assertTrue(core.onMotion(Motion.Stopped, movedAt + STITCH_WINDOW_MS - 1, settings).isEmpty())
        assertTrue(core.onMotion(Motion.Stopped, movedAt + STITCH_WINDOW_MS, settings).any { it is Effect.CloseTrack })
    }

    @Test fun `GPS giving up during the wait closes the walk on the way down`() {
        val stillAt = moveThenStand(ActivityType.WALKING, WALKING)
        core.onProbeStarted(E0)
        reading(ActivityType.STILL, stillAt)

        val out = core.onGnssTick(stillAt + MINUTE, E0 + GIVE_UP_MS, GIVE_UP_MS, settings)

        assertTrue(out.contains(Effect.CloseTrack(endedAt = stillAt, renameTo = null)))
        assertEquals(ActivityIngest.GiveUp.ClosedByRelease, core.giveUp)
    }

    @Test fun `a walk still under the keep bar closes on a confirmed stop at once`() {
        reading(ActivityType.WALKING, T0)
        standAt(0.0, fromMs = T0, until = T0 + MINUTE)

        val out = reading(ActivityType.STILL, T0 + MINUTE)

        assertTrue(out.contains(Effect.CloseTrack(endedAt = T0 + MINUTE, renameTo = null)))
    }

    @Test fun `a drive's confirmed stop closes it at once`() {
        val stillAt = moveThenStand(ActivityType.DRIVING, VEHICLE)

        val out = reading(ActivityType.STILL, stillAt)

        assertTrue(out.contains(Effect.CloseTrack(endedAt = stillAt, renameTo = null)))
    }

    @Test fun `with continuation off a walk's confirmed stop closes it at once`() {
        val stillAt = moveThenStand(ActivityType.WALKING, WALKING)
        val off = settings.copy(stitchWindowMs = 0)

        val out = reading(ActivityType.STILL, stillAt, settings = off)

        assertTrue(out.contains(Effect.CloseTrack(endedAt = stillAt, renameTo = null)))
    }

    @Test fun `a reopened walk's live distance carries on from the fixes it holds`() {
        reopenWalk(at = T0 + 3 * MINUTE)

        assertEquals(WALKING.mps * 60, ingest.distanceMeters, 0.5)
    }

    @Test fun `a reopened walk past the keep bar holds its next pause`() {
        val reopenedAt = T0 + 3 * MINUTE
        reopenWalk(at = reopenedAt)
        val stillAt = reopenedAt + MINUTE
        standAt(WALKING.mps * 60, fromMs = reopenedAt, until = stillAt)
        assertEquals("fixture precondition", Motion.Stopped, core.motionVerdict(stillAt))

        reading(ActivityType.STILL, stillAt)

        assertEquals(ActivityGate.Held(ActivityType.STILL, ActivityGate.Hold.CORROBORATED), core.held)
    }

    /** A walk resuming [at] into a track that already holds a minute's walk from the origin. */
    private fun reopenWalk(at: Long) {
        val stored = (0..60).map { s ->
            TrackPoint(
                trackId = 1L,
                latitude = ORIGIN_LAT,
                longitude = lonAt(WALKING.mps * s),
                altitude = null,
                accuracy = 5f,
                speed = null,
                bearing = null,
                timestamp = T0 + s * 1000L,
            )
        }
        core.onReading(ActivityType.WALKING, at, at, REGISTRATION, settings)
        core.onTrackResolved(ActivityType.WALKING, stitched = true, points = stored)
    }

    /**
     * A minute under [activity] at [speed], then a minute standing where it ended — long enough for
     * the witness to call the ground stopped. Returns that moment.
     */
    private fun moveThenStand(activity: ActivityType, speed: Speed): Long {
        reading(activity, T0)
        groundAt(speed, T0, until = T0 + MINUTE)
        val stillAt = T0 + 2 * MINUTE
        standAt(speed.mps * 60, fromMs = T0 + MINUTE + STANDING_FIX_MS, until = stillAt)
        assertEquals("fixture precondition", Motion.Stopped, core.motionVerdict(stillAt))
        return stillAt
    }
}
