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

    private val settings = ActivitySettings(resumeWindowMs = RESUME_WINDOW_MS)

    /**
     * A reading whose event time is its apply time — the ordinary live delivery. The registration is
     * long-established, so neither the replay window nor the armed bound swallows one.
     */
    private fun reading(
        raw: ActivityType,
        atMs: Long,
        eventTimeMs: Long? = atMs,
    ) = core.onReading(
        raw = raw,
        eventTimeMs = eventTimeMs,
        nowMs = atMs,
        registration = Registration(armedAtMs = T0 - MINUTE, lastRegisteredAtMs = T0 - HOUR),
        settings = settings,
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
            settings = IngestSettings(maxAccuracyM = 50f, requireGnss = false),
            gnss = GnssState(satellitesInFix = null, cn0Top4 = null, lastFixElapsedMs = atMs),
        )
    }

    /** Puts a walk on the road: the track is open, GPS is on, and one fix has landed. */
    private fun startWalking() {
        reading(ActivityType.WALKING, T0)
        fix(T0, 0.0)
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

        // No EnsureGps in the list: GPS is already running, and a same-family switch only breaks a
        // segment — so nothing here asks anything of it.
        assertEquals(
            listOf(Effect.StampReading(T0 + MINUTE), Effect.Publish),
            reading(ActivityType.RUNNING, T0 + MINUTE),
        )
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

        val out = reading(ActivityType.STILL, T0 + 30_000)

        assertEquals(listOf(Effect.StampReading(T0 + 30_000)), out)
        assertEquals("held for later", ActivityType.STILL, core.parked)
        assertFalse("the track keeps recording", core.isPaused)
    }

    @Test fun `the held stop applies once the ground stops contradicting it`() {
        startWalking()
        feedVehicleSpeedGround()
        reading(ActivityType.STILL, T0 + 30_000)

        val out = core.onMotion(Motion.Stopped, T0 + 60_000, settings)

        assertNull("the slot is emptied", core.parked)
        assertTrue("and the stop lands", core.isPaused)
        assertEquals(
            "the window is measured from the release, not from the held reading",
            Effect.SchedulePauseWake(T0 + 60_000 + RESUME_WINDOW_MS),
            out.first { it is Effect.SchedulePauseWake },
        )
    }

    // --- The crossing the cross-check exists for --------------------------------

    @Test fun `a drive Activity Recognition calls stationary is held, not paused`() {
        reading(ActivityType.DRIVING, T0)
        driveFrom(T0, until = T0 + MINUTE)

        // Aboard a carrier the body genuinely is still while the journey is not. Acting on the
        // label would pause the recorder mid-drive and turn GPS off for the rest of it.
        val out = reading(ActivityType.STILL, T0 + MINUTE)

        assertEquals("nothing acted on", listOf(Effect.StampReading(T0 + MINUTE)), out)
        assertEquals("the stop is held rather than dropped", ActivityType.STILL, core.parked)
        assertFalse("the drive is still recording", core.isPaused)
        assertEquals("under its own label", ActivityType.DRIVING, core.confirmed)
    }

    @Test fun `a crossing costs the drive neither a split nor a pause`() {
        reading(ActivityType.DRIVING, T0)
        val out = ArrayList<Effect>()
        // Nine minutes of moving ground, with a stationary announcement every third minute — the
        // edge-triggered stream repeating a stop the ground keeps contradicting.
        for (minute in 1..9) {
            driveFrom(T0 + (minute - 1) * MINUTE + 1000L, until = T0 + minute * MINUTE)
            if (minute % 3 == 0) out += reading(ActivityType.STILL, T0 + minute * MINUTE)
        }

        assertTrue("one track throughout", out.none { it is Effect.OpenTrack || it is Effect.CloseTrack })
        assertTrue("and never paused", out.none { it is Effect.SchedulePauseWake })
        assertFalse(core.isPaused)
        assertEquals(ActivityType.DRIVING, core.confirmed)
    }

    @Test fun `the drive's real end releases the held stop, timed from the release`() {
        reading(ActivityType.DRIVING, T0)
        driveFrom(T0, until = T0 + MINUTE)
        reading(ActivityType.STILL, T0 + MINUTE)

        // The carrier actually stops, so fixes cease and the window ages out to abstention — which
        // releases the hold, overruling the activity stream taking positive evidence to sustain.
        val arrivedAt = T0 + 3 * MINUTE
        val out = core.onMotion(core.motionVerdict(arrivedAt), arrivedAt, settings)

        assertNull("the slot is emptied", core.parked)
        assertTrue("and the stop finally lands", core.isPaused)
        // Timed from the held reading instead, the deadline would be T0 + 2m30s — already past, so
        // the promotion would close the track outright rather than open a window on it. The hold is
        // evidence the stop had not begun yet; the window after it is the first measuring a real one.
        assertEquals(
            Effect.SchedulePauseWake(arrivedAt + RESUME_WINDOW_MS),
            out.first { it is Effect.SchedulePauseWake },
        )
    }

    /** Vehicle-speed ground: a fix a second at [VEHICLE_MPS], positioned off [T0] so calls chain. */
    private fun driveFrom(fromMs: Long, until: Long) {
        var atMs = fromMs
        while (atMs <= until) {
            fix(atMs, (atMs - T0) / 1000.0 * VEHICLE_MPS)
            atMs += 1000L
        }
    }

    // --- The no-fix give-up guard -----------------------------------------------

    @Test fun `a probe that runs its window with nothing accepted hands GPS to the cheap signals`() {
        startWalking()
        noFixGuard.onProbeStarted(E0)

        val out = core.onGnssTick(T0 + GIVE_UP_MS, E0 + GIVE_UP_MS, GIVE_UP_MS, settings)

        assertEquals(
            listOf(Effect.StopGps, Effect.ArmResumeSignals(NoFixGuard.RETRY_BASE_MS), Effect.Publish),
            out,
        )
        assertTrue(noFixGuard.suspended)
    }

    @Test fun `moving ground vetoes the give-up, however long the probe has run`() {
        reading(ActivityType.DRIVING, T0)
        noFixGuard.onProbeStarted(E0)
        // Fixes are arriving and the ground is provably moving: the guard's premise — GPS cannot get
        // a fix here — is simply false, whatever its own window says.
        driveFrom(T0, until = T0 + MINUTE)

        val out = core.onGnssTick(T0 + MINUTE, E0 + GIVE_UP_MS, GIVE_UP_MS, settings)

        assertTrue("GPS stays on", out.isEmpty())
        assertFalse(noFixGuard.suspended)
    }

    @Test fun `a paused track's silence is not a failed probe`() {
        startWalking()
        noFixGuard.onProbeStarted(E0)
        reading(ActivityType.STILL, T0 + MINUTE)

        val out = core.onGnssTick(T0 + MINUTE + GIVE_UP_MS, E0 + GIVE_UP_MS, GIVE_UP_MS, settings)

        assertTrue("the pause owns the silence, and its own wake", out.isEmpty())
        assertFalse(noFixGuard.suspended)
    }

    @Test fun `a promotion that pauses the track leaves the resume signals unarmed`() {
        // The hazard: a pause stops GPS and arms its own resume-deadline wake, so arming the no-fix
        // signals on top would leave one track with two mechanisms waiting to revive it.
        reading(ActivityType.DRIVING, T0)
        driveFrom(T0, until = T0 + MINUTE)
        reading(ActivityType.STILL, T0 + MINUTE)
        noFixGuard.onProbeStarted(E0)

        // The carrier has stopped: the window has aged out, so the hold is released here, on the way
        // down — this tick is the last one there will be until GPS comes back.
        val arrivedAt = T0 + 3 * MINUTE
        val out = core.onGnssTick(arrivedAt, E0 + GIVE_UP_MS, GIVE_UP_MS, settings)

        assertTrue("the held stop lands", core.isPaused)
        assertTrue("and only the pause waits on it", out.none { it is Effect.ArmResumeSignals })
        assertEquals(
            listOf(Effect.StopGps, Effect.SchedulePauseWake(arrivedAt + RESUME_WINDOW_MS), Effect.Publish),
            out,
        )
        assertFalse(noFixGuard.suspended)
    }

    @Test fun `a resume signal probes again`() {
        givenGpsSuspended()

        val out = core.onResumeSignal(ResumeSignals.Signal.MOTION, E0 + GIVE_UP_MS + NoFixGuard.RETRY_BASE_MS)

        assertEquals(listOf(Effect.EnsureGps, Effect.Publish), out)
    }

    @Test fun `motion too soon after a failed probe re-arms the trigger instead of probing`() {
        givenGpsSuspended()

        val out = core.onResumeSignal(ResumeSignals.Signal.MOTION, E0 + GIVE_UP_MS + 1)

        assertEquals(
            "the one-shot trigger has fired and disarmed itself; the passive listener still stands",
            listOf(Effect.ArmSignificantMotion),
            out,
        )
    }

    @Test fun `a passive fix ignores the backoff, being evidence rather than a suggestion`() {
        givenGpsSuspended()

        val out = core.onResumeSignal(ResumeSignals.Signal.PASSIVE_FIX, E0 + GIVE_UP_MS + 1)

        assertEquals(listOf(Effect.EnsureGps, Effect.Publish), out)
    }

    @Test fun `a signal arriving while GPS was never suspended asks for nothing`() {
        startWalking()
        noFixGuard.onProbeStarted(E0)

        assertTrue(core.onResumeSignal(ResumeSignals.Signal.PASSIVE_FIX, E0 + GIVE_UP_MS).isEmpty())
    }

    /** A walk whose probe ran its window with nothing accepted, so GPS is off and waiting. */
    private fun givenGpsSuspended() {
        startWalking()
        noFixGuard.onProbeStarted(E0)
        core.onGnssTick(T0 + GIVE_UP_MS, E0 + GIVE_UP_MS, GIVE_UP_MS, settings)
        assertTrue("fixture precondition", noFixGuard.suspended)
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

    /**
     * Thirty seconds of moving ground — comfortably past what
     * [io.github.valeronm.breadcrumb.domain.MovementConfirmer] needs before it will say anything at
     * all (four fixes, a 20 s span, 40 m between the window's averaged halves), so a thinner fixture
     * would abstain and prove nothing.
     */
    private fun feedVehicleSpeedGround() = driveFrom(T0, until = T0 + 30_000)

    private companion object {
        const val T0 = 1_700_000_000_000L
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val RESUME_WINDOW_MS = 90_000L

        /** 90 km/h — ordinary road speed, well under the 220 km/h ceiling a DRIVING label carries. */
        const val VEHICLE_MPS = 25.0

        /** The guard's clock is monotonic and unrelated to [T0]; only differences are ever read. */
        const val E0 = 500_000L
        const val GIVE_UP_MS = 120_000L
    }
}
