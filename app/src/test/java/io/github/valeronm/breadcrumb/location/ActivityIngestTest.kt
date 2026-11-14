package io.github.valeronm.breadcrumb.location

import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.ORIGIN_LAT
import io.github.valeronm.breadcrumb.domain.lonAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where tracks begin and end — the loop that sequences the rules, which used to be answerable only
 * by walking around with the phone for an afternoon. The gate, the controller and the deafness
 * oracle each had a suite; the loop deciding which reading opens a track and what a stop closes had
 * none.
 *
 * How a start is *noticed* in the first place is [DepartureTriggerTest]'s question; the recorder
 * both drive is [ActivityIngestFixture].
 */
class ActivityIngestTest : ActivityIngestFixture() {

    // --- Opening, pausing, stitching -------------------------------------------

    @Test fun `the first moving reading opens a track and asks for GPS`() {
        val out = reading(ActivityType.WALKING, T0)

        assertEquals(
            listOf(
                Effect.StampReading(T0),
                // The close runs ahead of every open; with nothing open it still stops GPS and
                // emits no CloseTrack.
                Effect.StopGps,
                Effect.OpenTrack(ActivityType.WALKING, T0, STITCH_WINDOW_MS),
                Effect.EnsureGps,
                Effect.DisarmDepartureFence,
                Effect.StopDepartureProbe,
                Effect.Publish,
            ),
            out,
        )
    }

    @Test fun `a stop closes the track and starts watching for the next departure`() {
        startWalking()

        val out = stop(T0 + MINUTE)

        assertEquals(
            listOf(
                Effect.StampReading(T0 + MINUTE),
                Effect.StopGps,
                // Ended at its last good fix, not at the reading: Activity Recognition reports a
                // stop minutes after the phone made it.
                Effect.CloseTrack(endedAt = T0, renameTo = null),
                // The stop is also where the next departure will be from — by every means switched
                // on, and after the close that would otherwise have disarmed the sensor.
                Effect.ArmDepartureFence(Coordinate(ORIGIN_LAT, lonAt(0.0))),
                Effect.ArmSignificantMotion,
                Effect.Publish,
            ),
            out,
        )
        assertFalse("nothing is held open across the stop", core.recording)
    }

    /**
     * Whether the returning stretch keeps the closed track or lands on a new row is
     * `StitchRule`'s, decided against the stored history when [Effect.OpenTrack] is dispatched — so
     * the core asks the same thing however long the stop lasted, and this suite pins that it asks
     * rather than which answer it gets.
     */
    @Test fun `a return asks for a track like any other`() {
        startWalking()
        stop(T0 + MINUTE)
        val returnedAt = T0 + 30 * MINUTE

        val out = reading(ActivityType.RUNNING, returnedAt)

        assertEquals(
            listOf(
                // GPS goes off ahead of every open whether or not anything was recording — which is
                // what makes one close path safe to run before all of them. No CloseTrack, though:
                // the stop already closed, and there is nothing left open to end.
                Effect.StopGps,
                Effect.OpenTrack(ActivityType.RUNNING, returnedAt, STITCH_WINDOW_MS),
                Effect.EnsureGps,
                // The departure being watched for has happened, and the ground is back under GPS —
                // leaving either armed would run them alongside a live request.
                Effect.DisarmDepartureFence,
                Effect.StopDepartureProbe,
                Effect.Publish,
            ),
            out.drop(1),
        )
    }

    @Test fun `a cross-family switch splits the track`() {
        startWalking()

        val out = reading(ActivityType.DRIVING, T0 + MINUTE)

        assertEquals(
            listOf(
                Effect.StopGps,
                // Ended now rather than at its last fix: the phone is demonstrably still moving,
                // so unlike a stop there is no overrun to trim back.
                Effect.CloseTrack(endedAt = T0 + MINUTE, renameTo = null),
                Effect.OpenTrack(ActivityType.DRIVING, T0 + MINUTE, STITCH_WINDOW_MS),
                Effect.EnsureGps,
                Effect.DisarmDepartureFence,
                Effect.StopDepartureProbe,
                Effect.Publish,
            ),
            out.drop(1),
        )
    }

    @Test fun `a same-family switch keeps the track and its segment`() {
        startWalking()

        assertEquals(
            listOf(Effect.StampReading(T0 + MINUTE), Effect.Publish),
            reading(ActivityType.RUNNING, T0 + MINUTE),
        )
        assertFalse(fix(T0 + MINUTE + 1000, 3.0).points.single().segmentStart)
    }

    @Test fun `the first reading onto a trigger-opened track names it rather than splitting it`() {
        departure(T0)

        assertEquals(
            listOf(Effect.StampReading(T0 + MINUTE), Effect.RelabelTrack(ActivityType.WALKING), Effect.Publish),
            reading(ActivityType.WALKING, T0 + MINUTE),
        )
    }

    @Test fun `a foot reading onto a Moving track the ground proved carried starts a track of its own`() {
        departure(T0)
        driveFrom(T0, until = T0 + 4 * MINUTE)
        standAt(eastAt(VEHICLE, T0 + 4 * MINUTE), T0 + 4 * MINUTE + STANDING_FIX_MS, until = T0 + 5 * MINUTE)

        val out = reading(ActivityType.WALKING, T0 + 5 * MINUTE)

        assertEquals(Effect.CloseTrack(endedAt = T0 + 5 * MINUTE, renameTo = null), out.filterIsInstance<Effect.CloseTrack>().single())
        assertEquals(ActivityType.WALKING, out.filterIsInstance<Effect.OpenTrack>().single().activity)
        assertTrue(out.none { it is Effect.RelabelTrack })
    }

    @Test fun `a carried stretch held still under the ground starts a track of its own too`() {
        departure(T0)
        feedVehicleSpeedGround()
        reading(ActivityType.STILL, T0 + 30_000)
        driveFrom(T0 + 31_000, until = T0 + 110_000)
        standAt(eastAt(VEHICLE, T0 + 110_000), T0 + 110_000 + STANDING_FIX_MS, until = T0 + 170_000)

        val out = reading(ActivityType.WALKING, T0 + 170_000)

        assertEquals(ActivityType.WALKING, out.filterIsInstance<Effect.OpenTrack>().single().activity)
    }

    @Test fun `a foot reading onto a Moving track walked at foot pace names it`() {
        departure(T0)
        groundAt(WALKING, T0, until = T0 + 4 * MINUTE)

        assertEquals(
            listOf(Effect.StampReading(T0 + 4 * MINUTE), Effect.RelabelTrack(ActivityType.WALKING), Effect.Publish),
            reading(ActivityType.WALKING, T0 + 4 * MINUTE),
        )
    }

    @Test fun `a carried stretch the keep rule would discard is named rather than cut off`() {
        departure(T0)
        driveFrom(T0, until = T0 + 4 * MINUTE)
        standAt(eastAt(VEHICLE, T0 + 4 * MINUTE), T0 + 4 * MINUTE + STANDING_FIX_MS, until = T0 + 5 * MINUTE)
        val strict = settings.copy(keep = KEEP.copy(minDurationSec = 10 * 60))

        val out = reading(ActivityType.WALKING, T0 + 5 * MINUTE, settings = strict)

        assertTrue(out.contains(Effect.RelabelTrack(ActivityType.WALKING)))
        assertTrue(out.none { it is Effect.CloseTrack })
    }

    @Test fun `a vehicle reading late into a drive names the Moving track`() {
        departure(T0)
        driveFrom(T0, until = T0 + 12 * MINUTE)

        val out = reading(ActivityType.DRIVING, T0 + 12 * MINUTE)

        assertTrue(out.contains(Effect.RelabelTrack(ActivityType.DRIVING)))
        assertTrue(out.none { it is Effect.CloseTrack })
    }

    @Test fun `a snapshot opens a track from idle like a transition does`() {
        assertEquals(
            ActivityType.WALKING,
            snapshot(ActivityType.WALKING, T0).filterIsInstance<Effect.OpenTrack>().single().activity,
        )
    }

    @Test fun `a snapshot onto a recording track is dropped`() {
        departure(T0)

        assertEquals(listOf(Effect.StampReading(T0 + MINUTE)), snapshot(ActivityType.WALKING, T0 + MINUTE))
        assertEquals(listOf(Effect.StampReading(T0 + MINUTE)), snapshot(ActivityType.STILL, T0 + MINUTE))
        assertTrue(core.watchingArrival)
        assertNull(core.held)
    }

    @Test fun `a relabelled track is the reporter's, so its family rule applies from there`() {
        departure(T0)
        reading(ActivityType.WALKING, T0 + MINUTE)

        assertEquals(
            listOf(Effect.StampReading(T0 + 2 * MINUTE), Effect.Publish),
            reading(ActivityType.RUNNING, T0 + 2 * MINUTE),
        )
    }

    @Test fun `a reading the gate already believes asks for nothing but the delivery stamp`() {
        startWalking()

        assertEquals(listOf(Effect.StampReading(T0 + MINUTE)), reading(ActivityType.WALKING, T0 + MINUTE))
    }

    // --- The reading's own clock ------------------------------------------------

    @Test fun `a late-drained change still stamps its tracks at the wall clock`() {
        startWalking()
        stop(atMs = T0 + HOUR, eventTimeMs = T0 + MINUTE)

        val out = reading(ActivityType.WALKING, T0 + HOUR + 1)

        assertEquals(
            "the new track begins when it is opened, not when the stale reading claims",
            Effect.OpenTrack(ActivityType.WALKING, T0 + HOUR + 1, STITCH_WINDOW_MS),
            out.first { it is Effect.OpenTrack },
        )
    }

    // --- Deafness ---------------------------------------------------------------

    @Test fun `a stale but advancing reading proves the registration deaf`() {
        val out = deafReading(atMs = T0)

        val restart = out.filterIsInstance<Effect.RestartRegistration>().single()
        assertEquals("the reading's age is the evidence", 10 * MINUTE, restart.readingLateMs)
    }

    @Test fun `the restart is rate-limited while the detection behind it is not`() {
        deafReading(atMs = T0)
        // Inside the cooldown. The detection still counts — a deafness that survives a restart is
        // exactly what the user needs telling about — so this is the reading that raises the alert.
        // Exactly the gap: the rule compares strictly, so this is still inside the cooldown.
        val second = deafReading(atMs = T0 + ActivityIngest.STALE_RESTART_MIN_GAP_MS)

        assertTrue(
            "no second restart inside the cooldown",
            second.none { it is Effect.RestartRegistration },
        )
        assertEquals(
            "but the warning goes up on the second detection",
            listOf(Effect.DeafWarning(show = true)),
            second.filterIsInstance<Effect.DeafWarning>(),
        )
        assertTrue(core.deaf)
    }

    @Test fun `a restart is allowed again once the cooldown has passed`() {
        deafReading(atMs = T0)

        val later = deafReading(atMs = T0 + ActivityIngest.STALE_RESTART_MIN_GAP_MS + 1)

        assertTrue(later.any { it is Effect.RestartRegistration })
    }

    /** A reading late enough to prove deafness, advancing the clock enough to count as a new one. */
    private fun deafReading(atMs: Long) = core.onReading(
        raw = ActivityType.WALKING,
        eventTimeMs = atMs - 10 * MINUTE,
        nowMs = atMs,
        registration = Registration(armedAtMs = T0 - HOUR, lastRegisteredAtMs = T0 - HOUR),
        settings = settings,
    )
}
