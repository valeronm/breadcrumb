package io.github.valeronm.breadcrumb.location

import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.MeasuredPosition
import io.github.valeronm.breadcrumb.domain.ORIGIN_LAT
import io.github.valeronm.breadcrumb.domain.lonAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a start is noticed when Play Services reports none — which aboard a train is every start there
 * is, because activity recognition describes the body and a seated passenger is genuinely still.
 *
 * Three triggers answer one question ([io.github.valeronm.breadcrumb.domain.DepartureWatch]) by
 * different means, and what these cases pin is the wiring between them: which are armed under which
 * switches, what a position is worth, and — the part no single file states — where each is torn down
 * again. A trigger left armed under a running track is not a wrong answer; it is a standing request
 * beside a live GPS one, which is the cost this whole design is judged on.
 */
class DepartureTriggerTest : ActivityIngestFixture() {

    private fun triggers(fence: Boolean = false, continuous: Boolean = false, motion: Boolean = false) =
        settings.copy(triggers = DepartureTriggers(fence, continuous, motion))

    private val burst = Effect.StartDepartureProbe(
        DepartureTriggers.MOTION_INTERVAL_MS,
        DepartureTriggers.ANCHOR_WINDOW_MS,
    )

    /** A probe delivery [eastM] of the origin. */
    private fun probeAt(eastM: Double) =
        MeasuredPosition(Coordinate(ORIGIN_LAT, lonAt(eastM)), accuracyM = 10.0)

    // --- Which triggers go up, and from where -----------------------------------

    @Test fun `each trigger is armed only when it is switched on`() {
        // The anchor burst rides along with the two that need somewhere to measure from; the
        // continuous request supplies its own anchor rather than being interrupted by a burst.
        assertEquals(
            listOf(Effect.ArmDepartureFence(from = null), burst),
            ActivityIngest(ingest, noFixGuard).onArmed(T0, triggers(fence = true)),
        )
        assertEquals(
            listOf(Effect.StartDepartureProbe(DepartureTriggers.CONTINUOUS_INTERVAL_MS, durationMs = 0)),
            ActivityIngest(ingest, noFixGuard).onArmed(T0, triggers(continuous = true)),
        )
        assertEquals(
            listOf(burst, Effect.ArmSignificantMotion),
            ActivityIngest(ingest, noFixGuard).onArmed(T0, triggers(motion = true)),
        )
        // Every trigger off is a recorder that will not notice a departure at all — which is the
        // user's to choose, and must not quietly leave one of them running.
        assertTrue(ActivityIngest(ingest, noFixGuard).onArmed(T0, triggers()).isEmpty())
    }

    @Test fun `a pause arms the fence at the last good fix`() {
        reading(ActivityType.WALKING, atMs = T0)
        fix(atMs = T0 + 1_000, eastM = 0.0)
        // A walking pace: ten metres in ten seconds. Covering it in one would be a jump the
        // WALKING ceiling rejects, leaving the fence on the fix before it.
        fix(atMs = T0 + 11_000, eastM = 10.0)

        val effects = stop(atMs = T0 + 12_000)

        val anchor = effects.filterIsInstance<Effect.ArmDepartureFence>().single().from
        assertEquals(ORIGIN_LAT, anchor?.lat ?: 0.0, 1e-9)
        assertEquals(lonAt(10.0), anchor?.lon ?: 0.0, 1e-9)
    }

    @Test fun `a pause with a fix of its own needs no burst`() {
        startWalking()

        val out = stop(T0 + MINUTE)

        assertTrue(
            "the anchor is the recorder's own last good fix",
            out.none { it is Effect.StartDepartureProbe },
        )
    }

    /**
     * The GNSS-starved case, and the one a fence is the only thing that can notice: a pause with no
     * fix behind it still asks to be watched, handing the anchor to the platform's last known.
     */
    @Test fun `a pause with no fix behind it still asks to be watched, from nowhere of its own`() {
        reading(ActivityType.WALKING, atMs = T0)

        val effects = stop(atMs = T0 + 3_000)

        assertNull(effects.filterIsInstance<Effect.ArmDepartureFence>().single().from)
    }

    /**
     * Arming has no fix of its own, so the fence goes up on whatever the platform last saw — which
     * can be hours old. **A fence registered where the phone no longer is sits already outside
     * itself**, and `EXIT` fires only on inside→outside, so it has no transition left to make and
     * reports nothing however far the phone then travels. The burst ends that class of failure, and
     * the second registration replaces the first on the one fence id.
     */
    @Test fun `an anchorless arming buys a burst, and re-arms the fence on what it finds`() {
        assertEquals(
            listOf(Effect.ArmDepartureFence(from = null), burst, Effect.ArmSignificantMotion),
            core.onArmed(T0, settings),
        )

        assertEquals(
            listOf(
                Effect.ArmDepartureFence(Coordinate(ORIGIN_LAT, lonAt(0.0))),
                // The burst asked one question and has its answer.
                Effect.StopDepartureProbe,
            ),
            core.onProbeFix(probeAt(0.0), T0 + 5_000, settings),
        )
    }

    @Test fun `the standing request is not torn down by the position that anchors the watch`() {
        val continuous = triggers(fence = true, continuous = true)
        core.onArmed(T0, continuous)

        val out = core.onProbeFix(probeAt(0.0), T0 + 5_000, continuous)

        assertTrue("the fence is re-centred", out.any { it is Effect.ArmDepartureFence })
        assertFalse(
            "and the continuous trigger survives its own first delivery",
            out.contains(Effect.StopDepartureProbe),
        )
    }

    // --- What the motion sensor buys --------------------------------------------

    @Test fun `motion while idle buys a burst of positions, and re-arms the sensor`() {
        core.onArmed(T0, settings)

        assertEquals(
            listOf(
                Effect.StartDepartureProbe(
                    DepartureTriggers.MOTION_INTERVAL_MS,
                    DepartureTriggers.MOTION_WINDOW_MS,
                ),
                Effect.ArmSignificantMotion,
            ),
            core.onResumeSignal(ResumeSignals.Signal.MOTION, E0, settings),
        )
    }

    @Test fun `motion while a track is running is not worth asking about`() {
        reading(ActivityType.WALKING, T0)

        assertTrue(core.onResumeSignal(ResumeSignals.Signal.MOTION, E0, settings).isEmpty())
    }

    @Test fun `motion buys nothing when that trigger is switched off`() {
        val off = triggers(fence = true)
        core.onArmed(T0, off)

        assertTrue(core.onResumeSignal(ResumeSignals.Signal.MOTION, E0, off).isEmpty())
    }

    /**
     * With both position triggers on there is one request and one window between them, so a burst
     * would have to rebuild the standing request at its own cadence — and then stop it outright when
     * its window lapsed, leaving the continuous trigger silently dead until the next pause. The
     * cadence a burst would buy is not worth that, and positions are already arriving.
     */
    @Test fun `motion buys nothing while the standing request is already running`() {
        val both = triggers(fence = true, continuous = true, motion = true)
        core.onArmed(T0, both)

        assertTrue(core.onResumeSignal(ResumeSignals.Signal.MOTION, E0, both).isEmpty())
    }

    /**
     * The gap the pause does not cover. A finalize stops GPS, which disarms the resume signals
     * wholesale — so without this the motion trigger dies exactly when the recorder settles into the
     * idle state it is meant to watch.
     */
    @Test fun `a finalized track leaves the motion trigger armed`() {
        startWalking()
        stop(T0 + MINUTE)

        val out = core.onTick(T0 + MINUTE + RESUME_WINDOW_MS, settings)

        assertTrue(out.contains(Effect.StopGps))
        assertTrue(
            "the arm must survive the stop that precedes it",
            out.indexOf(Effect.ArmSignificantMotion) > out.indexOf(Effect.StopGps),
        )
    }

    // --- What a position is worth -----------------------------------------------

    @Test fun `a probe position past the margin opens a track, one inside it does not`() {
        core.onArmed(T0, settings)
        // The anchorless case: the first position establishes where the phone is.
        core.onProbeFix(probeAt(0.0), T0, settings)

        val near = core.onProbeFix(probeAt(100.0), T0 + 30_000, settings)
        assertTrue("still inside the margin", near.isEmpty())

        val far = core.onProbeFix(probeAt(1_000.0), T0 + 60_000, settings)
        assertEquals(ActivityType.UNKNOWN, far.filterIsInstance<Effect.OpenTrack>().single().activity)
    }

    @Test fun `a probe position decides nothing once the watch has been torn down`() {
        core.onArmed(T0, settings)
        core.onProbeFix(probeAt(0.0), T0, settings)
        // Each of these stops the watch; a delivery can outlive the request that asked for it.
        reading(ActivityType.WALKING, T0 + 10_000)
        stop(T0 + 20_000)
        reading(ActivityType.WALKING, T0 + 30_000)

        assertTrue(core.onProbeFix(probeAt(50_000.0), T0 + 40_000, settings).isEmpty())
    }

    // --- Opening on the trigger alone -------------------------------------------

    @Test fun `a departure opens a Moving track and stops watching`() {
        val effects = core.onDeparture(nowMs = T0, settings = settings)

        val opened = effects.filterIsInstance<Effect.OpenTrack>().single()
        assertEquals(ActivityType.UNKNOWN, opened.activity)
        assertTrue(effects.contains(Effect.DisarmDepartureFence))
        assertTrue(effects.contains(Effect.StopDepartureProbe))
    }

    @Test fun `a departure while recording is not news`() {
        reading(ActivityType.WALKING, atMs = T0)

        assertTrue(core.onDeparture(nowMs = T0 + 1_000, settings = settings).isEmpty())
    }

    /**
     * The reconciliation this trigger cannot ship without. Play Services announces that the user
     * *became* still and never says so again, so a track opened while the gate still believes STILL
     * has no stop edge left to spend — the STILL that ends the journey would be no change at all.
     */
    @Test fun `a STILL after a departure pauses the track it opened`() {
        core.onDeparture(nowMs = T0, settings = settings)

        val effects = stop(atMs = T0 + 60_000)

        assertTrue(effects.contains(Effect.StopGps))
        assertTrue(effects.any { it is Effect.SchedulePauseWake })
        assertTrue(core.isPaused)
    }

    /**
     * A resume is a departure that has already happened, and the ground is back under continuous
     * observation — so the triggers come down for the same reason they do when a track opens.
     * Without this the fence sat on the pause's anchor for the rest of the track, and a standing
     * probe kept asking for positions alongside a live GPS request.
     */
    @Test fun `a stitch-resume tears the triggers down too`() {
        startWalking()
        stop(T0 + MINUTE)

        val out = reading(ActivityType.WALKING, T0 + MINUTE + 10_000)

        assertTrue(out.contains(Effect.DisarmDepartureFence))
        assertTrue(out.contains(Effect.StopDepartureProbe))
        assertTrue("and it is a resume, not a new track", out.none { it is Effect.OpenTrack })
    }
}
