package io.github.valeronm.breadcrumb.location

import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.Motion
import io.github.valeronm.breadcrumb.domain.NoFixGuard
import io.github.valeronm.breadcrumb.domain.ORIGIN_LAT
import io.github.valeronm.breadcrumb.domain.Speed
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

    @Test fun `a same-family switch keeps the track and only breaks a segment`() {
        startWalking()

        // No EnsureGps in the list: GPS is already running, and a same-family switch only breaks a
        // segment — so nothing here asks anything of it.
        assertEquals(
            listOf(Effect.StampReading(T0 + MINUTE), Effect.Publish),
            reading(ActivityType.RUNNING, T0 + MINUTE),
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

    // --- GPS is asked for, never commanded --------------------------------------

    @Test fun `a return that also clears a no-fix suspension asks for GPS once`() {
        startWalking()
        stop(T0 + MINUTE)
        // The guard gave up before the stop, so both the new stretch and the re-probe want GPS.
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
        assertTrue("the track keeps recording", core.recording)
    }

    @Test fun `the held stop applies once the ground stops contradicting it`() {
        startWalking()
        feedVehicleSpeedGround()
        reading(ActivityType.STILL, T0 + 30_000)

        val out = core.onMotion(Motion.Stopped, T0 + 60_000, settings)

        assertNull("the slot is emptied", core.parked)
        assertFalse("and the stop lands, closing the track", core.recording)
        assertTrue(out.any { it is Effect.CloseTrack })
    }

    // --- The crossing the cross-check exists for --------------------------------

    @Test fun `a drive Activity Recognition calls stationary is held, not acted on`() {
        reading(ActivityType.DRIVING, T0)
        driveFrom(T0, until = T0 + MINUTE)

        // Aboard a carrier the body genuinely is still while the journey is not. Acting on the
        // label would end the recording mid-drive and turn GPS off for the rest of it.
        val out = reading(ActivityType.STILL, T0 + MINUTE)

        assertEquals("nothing acted on", listOf(Effect.StampReading(T0 + MINUTE)), out)
        assertEquals("the stop is held rather than dropped", ActivityType.STILL, core.parked)
        assertTrue("the drive is still recording", core.recording)
        assertEquals("under its own label", ActivityType.DRIVING, core.confirmed)
    }

    @Test fun `a crossing costs the drive neither a split nor a close`() {
        reading(ActivityType.DRIVING, T0)
        val out = ArrayList<Effect>()
        // Nine minutes of moving ground, with a stationary announcement every third minute — the
        // edge-triggered stream repeating a stop the ground keeps contradicting.
        for (minute in 1..9) {
            driveFrom(T0 + (minute - 1) * MINUTE + 1000L, until = T0 + minute * MINUTE)
            if (minute % 3 == 0) out += reading(ActivityType.STILL, T0 + minute * MINUTE)
        }

        assertTrue("one track throughout", out.none { it is Effect.OpenTrack || it is Effect.CloseTrack })
        assertTrue(core.recording)
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
        assertFalse("and the stop finally lands", core.recording)
        // At the drive's last good fix, which is where the carrier came to rest — not at the
        // release, minutes later, which the phone spent parked.
        assertEquals(
            Effect.CloseTrack(endedAt = T0 + MINUTE, renameTo = null),
            out.first { it is Effect.CloseTrack },
        )
    }

    /** Vehicle-speed ground: a fix a second at [VEHICLE], positioned off [T0] so calls chain. */
    private fun driveFrom(fromMs: Long, until: Long) = groundAt(VEHICLE, fromMs, until)

    /** Ground moving away from the origin at a steady [speed], one fix a second. */
    private fun groundAt(speed: Speed, fromMs: Long, until: Long) {
        var atMs = fromMs
        while (atMs <= until) {
            fix(atMs, (atMs - T0) / 1000.0 * speed.mps)
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

    @Test fun `a closed track's silence is not a failed probe`() {
        startWalking()
        noFixGuard.onProbeStarted(E0)
        stop(T0 + MINUTE)

        val out = core.onGnssTick(T0 + MINUTE + GIVE_UP_MS, E0 + GIVE_UP_MS, GIVE_UP_MS, settings)

        assertTrue("nothing is recording, so there is no probe to fail", out.isEmpty())
        assertFalse(noFixGuard.suspended)
    }

    @Test fun `a promotion that closes the track leaves the resume signals unarmed`() {
        // The hazard: a close stops GPS and arms the departure triggers, so arming the no-fix
        // signals on top would leave one recorder with two sets of signals waiting to revive it.
        reading(ActivityType.DRIVING, T0)
        driveFrom(T0, until = T0 + MINUTE)
        reading(ActivityType.STILL, T0 + MINUTE)
        noFixGuard.onProbeStarted(E0)

        // The carrier has stopped: the window has aged out, so the hold is released here, on the way
        // down — this tick is the last one there will be until GPS comes back.
        val arrivedAt = T0 + 3 * MINUTE
        val out = core.onGnssTick(arrivedAt, E0 + GIVE_UP_MS, GIVE_UP_MS, settings)

        assertFalse("the held stop lands, closing the track", core.recording)
        assertTrue("and the close alone winds GPS down", out.none { it is Effect.ArmResumeSignals })
        assertEquals(
            listOf(
                Effect.StopGps,
                Effect.CloseTrack(endedAt = T0 + MINUTE, renameTo = null),
                // A minute at [VEHICLE] from the origin — where the carrier came to rest.
                Effect.ArmDepartureFence(Coordinate(ORIGIN_LAT, lonAt(VEHICLE.mps * 60))),
                Effect.ArmSignificantMotion,
                Effect.Publish,
            ),
            out,
        )
        assertFalse(noFixGuard.suspended)
    }

    @Test fun `a resume signal probes again`() {
        givenGpsSuspended()

        val out = core.onResumeSignal(ResumeSignals.Signal.MOTION, E0 + GIVE_UP_MS + NoFixGuard.RETRY_BASE_MS, settings)

        assertEquals(listOf(Effect.EnsureGps, Effect.Publish), out)
    }

    @Test fun `motion too soon after a failed probe re-arms the trigger instead of probing`() {
        givenGpsSuspended()

        val out = core.onResumeSignal(ResumeSignals.Signal.MOTION, E0 + GIVE_UP_MS + 1, settings)

        assertEquals(
            "the one-shot trigger has fired and disarmed itself; the passive listener still stands",
            listOf(Effect.ArmSignificantMotion),
            out,
        )
    }

    @Test fun `a passive fix ignores the backoff, being evidence rather than a suggestion`() {
        givenGpsSuspended()

        val out = core.onResumeSignal(ResumeSignals.Signal.PASSIVE_FIX, E0 + GIVE_UP_MS + 1, settings)

        assertEquals(listOf(Effect.EnsureGps, Effect.Publish), out)
    }

    @Test fun `a signal arriving while GPS was never suspended asks for nothing`() {
        startWalking()
        noFixGuard.onProbeStarted(E0)

        assertTrue(core.onResumeSignal(ResumeSignals.Signal.PASSIVE_FIX, E0 + GIVE_UP_MS, settings).isEmpty())
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

    // --- a stop the ground never vouched for ------------------------------------------------------

    /**
     * The failure this exists for: a carrier pulls away seconds after a resume, the witness's window
     * has not refilled, and a STILL applied on that silence turns GPS off for the rest of the
     * journey. Silence is not consent to stop.
     */
    @Test fun `a stop the ground cannot vouch for is held rather than applied`() {
        startWalking()

        val out = reading(ActivityType.STILL, T0 + MINUTE)

        assertEquals("nothing but the delivery stamp", listOf(Effect.StampReading(T0 + MINUTE)), out)
        assertTrue("the track keeps recording", core.recording)
        assertEquals(ActivityType.STILL, core.parked)
    }

    @Test fun `the cap lands it, closing the track at its last good fix`() {
        startWalking()
        reading(ActivityType.STILL, T0 + MINUTE)

        val out = core.onMotion(Motion.Unknown, T0 + MINUTE + HOLD_CAP_MS, settings)

        // The hold is the recorder waiting, not evidence the stop had yet to begin, so it must not
        // push the track's boundary later than the last thing actually measured.
        assertTrue(out.contains(Effect.CloseTrack(endedAt = T0, renameTo = null)))
        assertFalse(core.recording)
    }

    /**
     * Field shape this exists for: indoors with no fix arriving, Play Services alternates STILL and
     * WALKING every half-minute or so. Each flip used to stop GPS, schedule a wake, re-anchor the
     * departure watch, then start GPS again — dozens of times across one stationary stretch that
     * finished as a discarded track. A return inside the hold restores the activity the gate never
     * left, so the whole cycle is now nothing at all.
     */
    @Test fun `a return inside the hold costs the track nothing`() {
        startWalking()
        reading(ActivityType.STILL, T0 + MINUTE)

        val returnedAt = T0 + MINUTE + HOLD_CAP_MS - 1
        val out = reading(ActivityType.WALKING, returnedAt)

        assertEquals("the delivery stamp and nothing else", listOf(Effect.StampReading(returnedAt)), out)
        assertNull("the held stop is dropped, not left to land later", core.parked)
        assertTrue(core.recording)
    }

    /**
     * …and the reason that matters beyond the churn: GPS is never torn down, so the no-fix guard's
     * probe clock is not restarted either. Every flip used to reset it, which is how a phone with no
     * sky kept a GPS request alive through a stationary stretch far longer than the give-up allows.
     */
    @Test fun `jitter no longer defers the no-fix give-up`() {
        startWalking()
        noFixGuard.onProbeStarted(E0)
        reading(ActivityType.STILL, T0 + MINUTE)
        reading(ActivityType.WALKING, T0 + MINUTE + HOLD_CAP_MS - 1)

        val out = core.onGnssTick(T0 + 2 * MINUTE, E0 + GIVE_UP_MS, GIVE_UP_MS, settings)

        assertTrue("the probe ran its window uninterrupted", out.contains(Effect.StopGps))
        assertTrue(noFixGuard.suspended)
    }

    @Test fun `it is not landed a moment early`() {
        startWalking()
        reading(ActivityType.STILL, T0 + MINUTE)

        assertTrue(core.onMotion(Motion.Unknown, T0 + MINUTE + HOLD_CAP_MS - 1, settings).isEmpty())
        assertTrue(core.recording)
    }

    /** The verdict the cap exists to wait for — and the one job [Motion.Stopped] has. */
    @Test fun `ground that confirms the standstill lands it at once`() {
        startWalking()
        reading(ActivityType.STILL, T0 + MINUTE)

        val out = core.onMotion(Motion.Stopped, T0 + MINUTE + 1, settings)

        assertTrue(out.contains(Effect.StopGps))
        assertFalse(core.recording)
    }

    /**
     * The contradicted hold keeps its own rule: it is released by ground that has stopped *saying*
     * anything, which is what the no-fix guard leans on as it takes GPS down. Capping it instead
     * would end a crossing still under way — see [ActivityGate.releaseHeld].
     */
    @Test fun `a contradicted hold is not subject to the cap`() {
        reading(ActivityType.DRIVING, T0)
        feedVehicleSpeedGround()
        reading(ActivityType.STILL, T0 + 30_000)
        assertEquals(ActivityType.STILL, core.parked)

        val tooSoonToBeSilent = core.onMotion(Motion.Moving(VEHICLE), T0 + 30_000 + HOLD_CAP_MS, settings)

        assertTrue("the ground is still moving, cap or no cap", tooSoonToBeSilent.isEmpty())
        assertEquals(ActivityType.STILL, core.parked)
    }

    // --- a foot reading at carrier speed ----------------------------------------------------------
    //
    // Every speed here is a motorway speed, because that is the whole of what the rule covers — the
    // bar and why it cannot come down are argued once, in [ActivityGate.tooFastFor].

    @Test fun `a walking reading at motorway speed does not split the drive`() {
        reading(ActivityType.DRIVING, T0)
        feedVehicleSpeedGround()

        val out = reading(ActivityType.WALKING, T0 + 30_000)

        assertTrue("nothing closed", out.none { it is Effect.CloseTrack })
        assertTrue("and nothing opened", out.none { it is Effect.OpenTrack })
        assertEquals(ActivityType.WALKING, core.parked)
        assertEquals(ActivityType.DRIVING, core.confirmed)
    }

    /** …and the drive continues through the vehicle reading that follows, as one track. */
    @Test fun `the drive that jittered is still one track when the vehicle reading returns`() {
        reading(ActivityType.DRIVING, T0)
        feedVehicleSpeedGround()
        reading(ActivityType.WALKING, T0 + 30_000)

        val out = reading(ActivityType.DRIVING, T0 + 60_000)

        assertTrue("no split", out.none { it is Effect.CloseTrack || it is Effect.OpenTrack })
        assertNull("and the stale foot reading is dropped, not left to land later", core.parked)
    }

    /**
     * The bound on the hold, and the answer to the objection that killed this rule before: the
     * walk that follows every drive is delayed only until the trailing window slows to a human
     * pace, not until it falls silent.
     */
    @Test fun `the held walk lands as soon as the ground slows to a human pace`() {
        reading(ActivityType.DRIVING, T0)
        feedVehicleSpeedGround()
        reading(ActivityType.WALKING, T0 + 30_000)

        val out = core.onMotion(Motion.Moving(WALKING), T0 + 40_000, settings)

        assertTrue("the walk opens its own track", out.any { it is Effect.OpenTrack })
        assertEquals(ActivityType.WALKING, core.confirmed)
    }

    @Test fun `a walk beginning from a standstill is never delayed`() {
        val out = reading(ActivityType.WALKING, T0)

        assertTrue(out.any { it is Effect.OpenTrack })
        assertNull(core.parked)
    }

    // --- …and where the rule deliberately does not reach -------------------------------------------

    /**
     * **The urban split stands, and this pins that it does** — not an oversight, and not fixable by
     * lowering the bar; see [ActivityGate.tooFastFor] for why the ground cannot tell this reading
     * from the genuine drive-to-walk that ends every trip. Repaired afterwards by merging, which is
     * visible and undoable.
     */
    @Test fun `a walking reading at a crawl still splits the drive, as it must`() {
        reading(ActivityType.DRIVING, T0)
        groundAt(CRAWL, T0, until = T0 + 30_000)

        val out = reading(ActivityType.WALKING, T0 + 30_000)

        assertNull("nothing is held", core.parked)
        assertTrue("the drive is closed", out.any { it is Effect.CloseTrack })
        assertTrue("and a foot track opened in its place", out.any { it is Effect.OpenTrack })
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
        /** Ordinary motorway speed, well under the ceiling a DRIVING label carries. */
        val VEHICLE = Speed.kmh(90.0)

        /** A pace the foot family's own ceiling explains, so no foot reading is held at it. */
        val WALKING = Speed.kmh(5.0)

        /** A car in city traffic — under the foot rule's bar, and the speed a real drive-to-walk
         *  arrives at, which is why nothing may be held there. */
        val CRAWL = Speed.kmh(22.0)

        const val GIVE_UP_MS = 120_000L
    }
}
