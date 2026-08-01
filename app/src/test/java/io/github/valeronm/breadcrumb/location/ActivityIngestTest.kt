package io.github.valeronm.breadcrumb.location

import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.Motion
import io.github.valeronm.breadcrumb.domain.NoFixGuard
import io.github.valeronm.breadcrumb.domain.ORIGIN_LAT
import io.github.valeronm.breadcrumb.domain.flatDistance
import io.github.valeronm.breadcrumb.domain.lonAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The activity path off the device. Where tracks begin, pause, stitch and end used to be answerable
 * only by walking around with the phone for an afternoon: the gate, the controller and the deafness
 * oracle each had a suite, but the loop that sequences them — which reading opens a track, what a
 * stop schedules, whether a return stitches or splits — had none.
 *
 * Times are milliseconds from an arbitrary [T0]; fixtures sit at the domain suites' neutral origin.
 */
class ActivityIngestTest {

    private val ingest = FixIngest(flatDistance)
    private val noFixGuard = NoFixGuard()
    private val core = ActivityIngest(ingest, noFixGuard)

    private val settings = ActivitySettings(resumeWindowMs = RESUME_WINDOW_MS, crossCheckMotion = false)

    /**
     * A reading whose event time is its apply time — the ordinary live delivery. The registration is
     * long-established, so neither the replay window nor the armed bound swallows one.
     */
    private fun reading(
        raw: ActivityType,
        atMs: Long,
        eventTimeMs: Long? = atMs,
        crossCheckMotion: Boolean = false,
    ) = core.onReading(
        raw = raw,
        eventTimeMs = eventTimeMs,
        nowMs = atMs,
        registration = Registration(armedAtMs = T0 - MINUTE, lastRegisteredAtMs = T0 - HOUR),
        settings = settings.copy(crossCheckMotion = crossCheckMotion),
    )

    /** Feeds one accepted fix into the fix path, so the track has a last-good point to end at. */
    private fun fix(atMs: Long, eastM: Double) {
        ingest.onFixes(
            trackId = 1L,
            fixes = listOf(
                Fix(
                    latitude = ORIGIN_LAT,
                    longitude = lonAt(eastM),
                    altitude = null,
                    accuracy = 5f,
                    speed = null,
                    bearing = null,
                    timeMs = atMs,
                    verticalAccuracy = null,
                    speedAccuracy = null,
                    bearingAccuracy = null,
                    elapsedRealtimeMs = atMs,
                ),
            ),
            gate = GateState(core.confirmed, stillParked = core.parked != null),
            settings = IngestSettings(maxAccuracyM = 50f, requireGnss = false, crossCheckMotion = false),
            gnss = GnssState(satellitesInFix = null, cn0Top4 = null, lastFixElapsedMs = atMs),
        )
    }

    /** Puts a walk on the road: the track is open, GPS is on, and one fix has landed. */
    private fun startWalking(atMs: Long = T0) {
        reading(ActivityType.WALKING, atMs)
        fix(atMs, 0.0)
    }

    // --- Opening, pausing, stitching -------------------------------------------

    @Test fun `the first moving reading opens a track and asks for GPS`() {
        val out = reading(ActivityType.WALKING, T0)

        assertEquals(
            listOf(
                Effect.StampReading(T0),
                // The close runs ahead of every open; with nothing open it still stops GPS and
                // stamps the heartbeat, and emits no CloseTrack.
                Effect.StopGps,
                Effect.StampHeartbeat,
                Effect.OpenTrack(ActivityType.WALKING, T0),
                Effect.EnsureGps,
                Effect.Publish,
            ),
            out,
        )
    }

    @Test fun `a stop pauses the track and schedules the wake, rather than closing it`() {
        startWalking()

        val out = reading(ActivityType.STILL, T0 + MINUTE)

        assertEquals(
            listOf(
                Effect.StampReading(T0 + MINUTE),
                Effect.StopGps,
                Effect.SchedulePauseWake(T0 + MINUTE + RESUME_WINDOW_MS),
                Effect.Publish,
            ),
            out,
        )
        assertTrue("the track stays open across the stop", core.isPaused)
        assertTrue("no close", out.none { it is Effect.CloseTrack })
    }

    @Test fun `a same-family return inside the window stitches back into the open track`() {
        startWalking()
        reading(ActivityType.STILL, T0 + MINUTE)

        val out = reading(ActivityType.RUNNING, T0 + MINUTE + RESUME_WINDOW_MS - 1)

        assertEquals(listOf(Effect.EnsureGps, Effect.Publish), out.drop(1))
        assertTrue("nothing opened or closed", out.none { it is Effect.OpenTrack || it is Effect.CloseTrack })
    }

    @Test fun `a return after the window lapsed closes the stitch and starts a new track`() {
        startWalking()
        reading(ActivityType.STILL, T0 + MINUTE)
        val returnedAt = T0 + MINUTE + RESUME_WINDOW_MS

        val out = reading(ActivityType.WALKING, returnedAt)

        assertEquals(
            listOf(
                Effect.StopGps,
                Effect.StampHeartbeat,
                // The paused track ended when its last fix arrived, not at the return — the idle
                // gap belongs to neither track.
                Effect.CloseTrack(endedAt = T0, renameTo = null),
                Effect.OpenTrack(ActivityType.WALKING, returnedAt),
                Effect.EnsureGps,
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
                Effect.StampHeartbeat,
                // Recording, not paused: the walk ended now rather than at its last fix.
                Effect.CloseTrack(endedAt = T0 + MINUTE, renameTo = null),
                Effect.OpenTrack(ActivityType.DRIVING, T0 + MINUTE),
                Effect.EnsureGps,
                Effect.Publish,
            ),
            out.drop(1),
        )
    }

    @Test fun `a same-family switch keeps the track and only breaks a segment`() {
        startWalking()

        val out = reading(ActivityType.RUNNING, T0 + MINUTE)

        assertEquals(listOf(Effect.StampReading(T0 + MINUTE), Effect.Publish), out)
        assertTrue("GPS is already on, so nothing is asked of it", out.none { it == Effect.EnsureGps })
    }

    @Test fun `a reading the gate already believes asks for nothing but the liveness stamp`() {
        startWalking()

        assertEquals(listOf(Effect.StampReading(T0 + MINUTE)), reading(ActivityType.WALKING, T0 + MINUTE))
    }

    // --- The reading's own clock ------------------------------------------------

    @Test fun `a reading drained late out of Doze is timed by its own event, not its arrival`() {
        startWalking()
        // The stop happened a minute in; the phone was frozen and only applied it an hour later.
        reading(ActivityType.STILL, atMs = T0 + HOUR, eventTimeMs = T0 + MINUTE)

        // The return arrives well inside the window measured from *now*, and well outside the one
        // measured from the stop's own time. It must split, not stitch through the real stop.
        val out = reading(ActivityType.WALKING, T0 + HOUR + 1)

        assertTrue("the genuine stop splits the track", out.any { it is Effect.OpenTrack })
    }

    @Test fun `a late-drained change still stamps its tracks at the wall clock`() {
        startWalking()
        reading(ActivityType.STILL, atMs = T0 + HOUR, eventTimeMs = T0 + MINUTE)

        val out = reading(ActivityType.WALKING, T0 + HOUR + 1)

        assertEquals(
            "the new track begins when it is opened, not when the stale reading claims",
            Effect.OpenTrack(ActivityType.WALKING, T0 + HOUR + 1),
            out.first { it is Effect.OpenTrack },
        )
    }

    // --- The pause wake ---------------------------------------------------------

    @Test fun `the wake closes a track whose window lapsed, and publishes with it`() {
        startWalking()
        reading(ActivityType.STILL, T0 + MINUTE)

        val out = core.onTick(T0 + MINUTE + RESUME_WINDOW_MS)

        assertEquals(
            listOf(
                Effect.StopGps,
                Effect.StampHeartbeat,
                Effect.CloseTrack(endedAt = T0, renameTo = null),
                Effect.Publish,
            ),
            out,
        )
    }

    @Test fun `an early wake does nothing, and a stale one after a resume does nothing`() {
        startWalking()
        reading(ActivityType.STILL, T0 + MINUTE)

        assertTrue("before the deadline", core.onTick(T0 + MINUTE + RESUME_WINDOW_MS - 1).isEmpty())

        reading(ActivityType.WALKING, T0 + MINUTE + 1)
        assertTrue(
            "the wake fires anyway, on a track that resumed",
            core.onTick(T0 + MINUTE + RESUME_WINDOW_MS).isEmpty(),
        )
    }

    // --- GPS is asked for, never commanded --------------------------------------

    @Test fun `a resume that also clears a no-fix suspension asks for GPS once`() {
        startWalking()
        reading(ActivityType.STILL, T0 + MINUTE)
        // The guard gave up while the track was paused, so both the resume and the re-probe want GPS.
        noFixGuard.onProbeStarted(0L)
        noFixGuard.onGaveUp(0L)

        val out = reading(ActivityType.WALKING, T0 + MINUTE + 1)

        assertEquals("one request, not two", 1, out.count { it == Effect.EnsureGps })
    }

    @Test fun `a moving reading that changes no track state still asks GPS back on`() {
        startWalking()
        reading(ActivityType.STILL, T0 + MINUTE)
        reading(ActivityType.WALKING, T0 + MINUTE + 1)
        noFixGuard.onProbeStarted(0L)
        noFixGuard.onGaveUp(0L)

        // Same family, same track: the controller does nothing, and the request is the only effect
        // that matters — this is the path that revives a suspended probe.
        val out = reading(ActivityType.RUNNING, T0 + 2 * MINUTE)

        assertEquals(listOf(Effect.StampReading(T0 + 2 * MINUTE), Effect.EnsureGps, Effect.Publish), out)
    }

    // --- The ground as a second witness -----------------------------------------

    @Test fun `a stop the ground contradicts is held, not applied`() {
        startWalking()
        // A vehicle's speed under a walking label: the body is still, the journey is not.
        feedVehicleSpeedGround()

        val out = reading(ActivityType.STILL, T0 + 30_000, crossCheckMotion = true)

        assertEquals(listOf(Effect.StampReading(T0 + 30_000)), out)
        assertEquals("held for later", ActivityType.STILL, core.parked)
        assertFalse("the track keeps recording", core.isPaused)
    }

    @Test fun `the held stop applies once the ground stops contradicting it`() {
        startWalking()
        feedVehicleSpeedGround()
        reading(ActivityType.STILL, T0 + 30_000, crossCheckMotion = true)

        val out = core.onMotion(Motion.Stopped, T0 + 60_000, settings)

        assertNull("the slot is emptied", core.parked)
        assertTrue("and the stop lands", core.isPaused)
        assertEquals(
            "the window is measured from the release, not from the held reading",
            Effect.SchedulePauseWake(T0 + 60_000 + RESUME_WINDOW_MS),
            out.first { it is Effect.SchedulePauseWake },
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
        val second = deafReading(atMs = T0 + STALE_RESTART_GAP_MS)

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

        val later = deafReading(atMs = T0 + STALE_RESTART_GAP_MS + 2)

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

    /**
     * Ground moving at 10 m/s for 30 s. Shaped to what [io.github.valeronm.breadcrumb.domain.MovementConfirmer]
     * needs before it will say anything at all — four fixes, a 20 s span, and 40 m between the
     * window's averaged halves — so a thinner fixture abstains and proves nothing.
     */
    private fun feedVehicleSpeedGround() {
        for (sec in 0..30 step 5) fix(T0 + sec * 1000L, sec * 10.0)
    }

    private companion object {
        const val T0 = 1_700_000_000_000L
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val RESUME_WINDOW_MS = 90_000L

        /** Just inside [ActivityIngest.STALE_RESTART_MIN_GAP_MS], which the rule compares strictly. */
        const val STALE_RESTART_GAP_MS = ActivityIngest.STALE_RESTART_MIN_GAP_MS
    }
}
