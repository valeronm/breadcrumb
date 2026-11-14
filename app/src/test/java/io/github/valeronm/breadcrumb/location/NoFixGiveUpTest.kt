package io.github.valeronm.breadcrumb.location

import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.MeasuredPosition
import io.github.valeronm.breadcrumb.domain.NoFixGuard
import io.github.valeronm.breadcrumb.domain.ORIGIN_LAT
import io.github.valeronm.breadcrumb.domain.at
import io.github.valeronm.breadcrumb.domain.lonAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a GPS search that finds nothing costs the open track, and what turns GPS back on. */
class NoFixGiveUpTest : ActivityIngestFixture() {

    // --- The no-fix give-up guard -----------------------------------------------

    @Test fun `a probe that runs its window with nothing accepted hands GPS to the cheap signals`() {
        startWalking()
        core.onProbeStarted(E0)

        val out = core.onGnssTick(T0 + GIVE_UP_MS, E0 + GIVE_UP_MS, GIVE_UP_MS, settings)

        assertEquals(
            listOf(
                Effect.StopGps,
                Effect.ArmResumeSignals,
                Effect.ArmDepartureFence(null),
                Effect.StartDepartureProbe(DepartureTriggers.MOTION_INTERVAL_MS, DepartureTriggers.ANCHOR_WINDOW_MS),
                Effect.ArmSignificantMotion,
                Effect.Publish,
            ),
            out,
        )
        assertEquals(ActivityIngest.GiveUp.KeptOpen(NoFixGuard.RETRY_BASE_MS, noFixAtAll = true), core.giveUp)
        assertTrue(noFixGuard.suspended)
        assertTrue("the track stays open", core.recording)
    }

    @Test fun `a walk with no fix at all gives up at the first-fix wait`() {
        reading(ActivityType.WALKING, T0)
        core.onProbeStarted(E0)

        val waited = NoFixGuard.FIRST_FIX_WAIT_MS
        assertTrue(core.onGnssTick(T0 + waited - 1, E0 + waited - 1, GIVE_UP_MS, settings).isEmpty())
        val out = core.onGnssTick(T0 + waited, E0 + waited, GIVE_UP_MS, settings)

        assertTrue(Effect.StopGps in out)
        assertTrue(noFixGuard.suspended)
    }

    @Test fun `a drive waits the whole window for its first fix`() {
        reading(ActivityType.DRIVING, T0)
        core.onProbeStarted(E0)

        val waited = NoFixGuard.FIRST_FIX_WAIT_MS
        assertTrue(core.onGnssTick(T0 + waited, E0 + waited, GIVE_UP_MS, settings).isEmpty())
        assertFalse(noFixGuard.suspended)
    }

    @Test fun `leaving the give-up spot resumes GPS on the open track`() {
        givenGpsSuspended()

        val out = departure(T0 + 5 * MINUTE)

        assertEquals(
            listOf(
                Effect.EnsureGps,
                Effect.DisarmDepartureFence,
                Effect.StopDepartureProbe,
                Effect.Publish,
            ),
            out,
        )
        assertTrue("the same track carries on", core.recording)
    }

    @Test fun `a probe position a short walk from the give-up spot resumes GPS rather than opening a track`() {
        givenGpsSuspended()
        probeFix(MeasuredPosition(at(0.0), 10.0), T0 + 3 * MINUTE)

        val out = probeFix(MeasuredPosition(at(50.0), 10.0), T0 + 4 * MINUTE)

        assertTrue(Effect.EnsureGps in out)
        assertTrue(out.none { it is Effect.OpenTrack })
    }

    @Test fun `moving ground vetoes the give-up, however long the probe has run`() {
        reading(ActivityType.DRIVING, T0)
        core.onProbeStarted(E0)
        // Fixes are arriving and the ground is provably moving: the guard's premise — GPS cannot get
        // a fix here — is simply false, whatever its own window says.
        driveFrom(T0, until = T0 + MINUTE)

        val out = core.onGnssTick(T0 + MINUTE, E0 + GIVE_UP_MS, GIVE_UP_MS, settings)

        assertTrue("GPS stays on", out.isEmpty())
        assertFalse(noFixGuard.suspended)
    }

    @Test fun `a stop and a return inside the hold leave the probe's window running`() {
        startWalking()
        core.onProbeStarted(E0)
        reading(ActivityType.STILL, T0 + MINUTE)
        reading(ActivityType.WALKING, T0 + MINUTE + HOLD_CAP_MS - 1)

        val out = core.onGnssTick(T0 + 2 * MINUTE, E0 + GIVE_UP_MS, GIVE_UP_MS, settings)

        assertTrue("the probe ran its window uninterrupted", out.contains(Effect.StopGps))
        assertTrue(noFixGuard.suspended)
    }

    @Test fun `a closed track's silence is not a failed probe`() {
        startWalking()
        core.onProbeStarted(E0)
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
        core.onProbeStarted(E0)

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

        val out = core.onResumeSignal(ResumeSignal.MOTION, E0 + GIVE_UP_MS + NoFixGuard.RETRY_BASE_MS, settings)

        assertEquals(resumed, out)
    }

    @Test fun `motion too soon after a failed probe asks whether the phone left instead of probing`() {
        givenGpsSuspended()

        val out = core.onResumeSignal(ResumeSignal.MOTION, E0 + GIVE_UP_MS + 1, settings)

        assertEquals(listOf(signalBurst, Effect.ArmSignificantMotion), out)
    }

    @Test fun `motion too soon leaves the standing request running`() {
        val continuous = settings.copy(triggers = TRIGGERS.copy(continuous = true))
        startWalking()
        core.onProbeStarted(E0)
        core.onGnssTick(T0 + GIVE_UP_MS, E0 + GIVE_UP_MS, GIVE_UP_MS, continuous)

        val out = core.onResumeSignal(ResumeSignal.MOTION, E0 + GIVE_UP_MS + 1, continuous)

        assertEquals(listOf(Effect.ArmSignificantMotion), out)
    }

    @Test fun `motion too soon with that trigger off only re-arms the sensor`() {
        givenGpsSuspended()
        val motionOff = settings.copy(triggers = TRIGGERS.copy(motion = false))

        val out = core.onResumeSignal(ResumeSignal.MOTION, E0 + GIVE_UP_MS + 1, motionOff)

        assertEquals(
            "the one-shot trigger has fired and disarmed itself; the passive listener still stands",
            listOf(Effect.ArmSignificantMotion),
            out,
        )
    }

    @Test fun `a passive fix ignores the backoff, being evidence rather than a suggestion`() {
        givenGpsSuspended()

        val out = core.onResumeSignal(ResumeSignal.PASSIVE_FIX, E0 + GIVE_UP_MS + 1, settings)

        assertEquals(resumed, out)
    }

    @Test fun `a lost Wi-Fi network ignores the backoff`() {
        givenGpsSuspended()

        val out = core.onResumeSignal(ResumeSignal.WIFI_LOST, E0 + GIVE_UP_MS + 1, settings)

        assertEquals(resumed, out)
    }

    @Test fun `a lost Wi-Fi network resumes nothing when that trigger is switched off`() {
        givenGpsSuspended()
        val off = settings.copy(triggers = settings.triggers.copy(wifi = false))

        assertTrue(core.onResumeSignal(ResumeSignal.WIFI_LOST, E0 + GIVE_UP_MS + 1, off).isEmpty())
    }

    @Test fun `a lost Wi-Fi network restarts a walk's wait for its first fix`() {
        givenWalkAwaitingFirstFix()
        val lostAt = 50_000L

        val out = core.onResumeSignal(ResumeSignal.WIFI_LOST, E0 + lostAt, settings)

        assertTrue(out.isEmpty())
        assertTrue(core.firstFixWaitRestarted)
        val waited = lostAt + NoFixGuard.FIRST_FIX_WAIT_MS
        assertTrue(core.onGnssTick(T0 + waited - 1, E0 + waited - 1, GIVE_UP_MS, settings).isEmpty())
        assertTrue(Effect.StopGps in core.onGnssTick(T0 + waited, E0 + waited, GIVE_UP_MS, settings))
    }

    @Test fun `a lost Wi-Fi network restarts no wait when that trigger is switched off`() {
        givenWalkAwaitingFirstFix()
        val off = settings.copy(triggers = settings.triggers.copy(wifi = false))

        core.onResumeSignal(ResumeSignal.WIFI_LOST, E0 + 50_000, off)

        assertFalse(core.firstFixWaitRestarted)
        val waited = NoFixGuard.FIRST_FIX_WAIT_MS
        assertTrue(Effect.StopGps in core.onGnssTick(T0 + waited, E0 + waited, GIVE_UP_MS, settings))
    }

    @Test fun `a lost Wi-Fi network restarts no wait on a drive`() {
        reading(ActivityType.DRIVING, T0)
        core.onProbeStarted(E0)

        core.onResumeSignal(ResumeSignal.WIFI_LOST, E0 + 50_000, settings)

        assertFalse(core.firstFixWaitRestarted)
    }

    @Test fun `motion restarts no wait for a first fix`() {
        givenWalkAwaitingFirstFix()

        core.onResumeSignal(ResumeSignal.MOTION, E0 + 50_000, settings)

        assertFalse(core.firstFixWaitRestarted)
    }

    @Test fun `a later signal clears the restart it did not make`() {
        givenWalkAwaitingFirstFix()
        core.onResumeSignal(ResumeSignal.WIFI_LOST, E0 + 50_000, settings)

        core.onResumeSignal(ResumeSignal.MOTION, E0 + 51_000, settings)

        assertFalse(core.firstFixWaitRestarted)
    }

    @Test fun `a probe resuming a suspended track opens a new segment`() {
        givenGpsSuspended()

        core.onProbeStarted(E0 + GIVE_UP_MS + NoFixGuard.RETRY_BASE_MS)

        assertTrue(fix(T0 + 5 * MINUTE, 50.0).points.single().segmentStart)
        assertFalse(noFixGuard.suspended)
    }

    @Test fun `a probe starting on a watched track keeps its segment`() {
        startWalking()

        core.onProbeStarted(E0)

        assertFalse(fix(T0 + 1000, 1.0).points.single().segmentStart)
    }

    @Test fun `a signal arriving while GPS was never suspended asks for nothing`() {
        startWalking()
        core.onProbeStarted(E0)

        assertTrue(core.onResumeSignal(ResumeSignal.PASSIVE_FIX, E0 + GIVE_UP_MS, settings).isEmpty())
    }

    /** A resume: GPS back on, and the watch over the give-up spot torn down with it. */
    private val resumed = listOf(Effect.EnsureGps, Effect.DisarmDepartureFence, Effect.StopDepartureProbe, Effect.Publish)

    private fun givenWalkAwaitingFirstFix() {
        reading(ActivityType.WALKING, T0)
        core.onProbeStarted(E0)
    }

    /** A walk whose probe ran its window with nothing accepted, so GPS is off and waiting. */
    private fun givenGpsSuspended() {
        startWalking()
        core.onProbeStarted(E0)
        core.onGnssTick(T0 + GIVE_UP_MS, E0 + GIVE_UP_MS, GIVE_UP_MS, settings)
        assertTrue("fixture precondition", noFixGuard.suspended)
    }

    // --- A stop with GPS given up -----------------------------------------------

    @Test fun `a stop arriving after the no-fix give-up is applied at once`() {
        givenGpsSuspended()

        val out = reading(ActivityType.STILL, T0 + GIVE_UP_MS + MINUTE)

        assertTrue(out.contains(Effect.CloseTrack(endedAt = T0, renameTo = null)))
        assertNull(core.parked)
        assertFalse(core.recording)
        assertFalse(noFixGuard.suspended)
    }

    @Test fun `a stop arriving after the no-fix give-up watches from no anchor`() {
        givenGpsSuspended()

        val out = reading(ActivityType.STILL, T0 + GIVE_UP_MS + MINUTE)

        assertTrue(Effect.ArmDepartureFence(null) in out)
        assertTrue(
            Effect.StartDepartureProbe(DepartureTriggers.MOTION_INTERVAL_MS, DepartureTriggers.ANCHOR_WINDOW_MS) in out,
        )
    }

    @Test fun `a stop still held when the no-fix guard gives up lands on the way down`() {
        startWalking()
        core.onProbeStarted(E0)
        reading(ActivityType.STILL, T0 + GIVE_UP_MS - 1000)
        assertEquals("fixture precondition", ActivityType.STILL, core.parked)

        val out = core.onGnssTick(T0 + GIVE_UP_MS, E0 + GIVE_UP_MS, GIVE_UP_MS, settings)

        assertTrue(out.contains(Effect.CloseTrack(endedAt = T0, renameTo = null)))
        assertTrue(out.none { it is Effect.ArmResumeSignals })
        assertNull(core.parked)
        assertFalse(core.recording)
        assertEquals(ActivityIngest.GiveUp.ClosedByRelease, core.giveUp)
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

        assertEquals(listOf(Effect.StampReading(T0 + 2 * MINUTE)) + resumed, out)
    }
}
