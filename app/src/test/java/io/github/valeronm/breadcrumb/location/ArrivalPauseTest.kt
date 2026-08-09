package io.github.valeronm.breadcrumb.location

import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.ArrivalWatch
import io.github.valeronm.breadcrumb.domain.Motion
import io.github.valeronm.breadcrumb.domain.at
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How an end is noticed when Play Services reports none — the counterpart of [DepartureTriggerTest]
 * on the other edge of the same journey, wiring-level cases for the rule whose argument lives in
 * [ArrivalWatch]'s KDoc. The asymmetry pinned here is the rule's whole shape: **a reading-opened
 * track is never paused this way**, however loudly the ground stops.
 */
class ArrivalPauseTest : ActivityIngestFixture() {

    private fun arrivalTick(atMs: Long) =
        core.onArrivalTick(core.motionVerdict(atMs), atMs, settings)

    /** Four fixes standing still at [eastM] — enough window for a [Motion.Stopped] verdict. */
    private fun standstill(fromMs: Long, eastM: Double): Long {
        fix(fromMs, eastM)
        fix(fromMs + 5_000, eastM)
        fix(fromMs + 10_000, eastM)
        fix(fromMs + 20_000, eastM)
        return fromMs + 20_000
    }

    @Test fun `a proven standstill pauses a signal-opened track, backdated to the stop`() {
        core.onDeparture(T0, settings)
        val provenAt = standstill(T0 + 10 * MINUTE, eastM = 500.0)
        assertEquals(Motion.Stopped, core.motionVerdict(provenAt))

        assertTrue("the floor is not met yet", arrivalTick(provenAt).isEmpty())

        // By the fire the witness has long drained back to silence — the standstill is held
        // evidence, not a live verdict.
        val firedAt = provenAt + ArrivalWatch.STANDSTILL_FLOOR_MS
        assertEquals(Motion.Unknown, core.motionVerdict(firedAt))
        assertEquals(
            listOf(
                Effect.StopGps,
                // Backdated: the pause deadline runs from the standstill's start, not the fire.
                Effect.SchedulePauseWake(provenAt + RESUME_WINDOW_MS),
                Effect.ArmDepartureFence(at(500.0)),
                Effect.ArmSignificantMotion,
                Effect.Publish,
            ),
            arrivalTick(firedAt),
        )

        // The backdated window has already lapsed, so the next tick ends the track at the arrival.
        val closed = core.onTick(firedAt + 1_000, settings).filterIsInstance<Effect.CloseTrack>().single()
        assertEquals(provenAt, closed.endedAt)
    }

    @Test fun `a reading-opened track is never paused by the ground`() {
        startWalking()
        val provenAt = standstill(T0 + 10 * MINUTE, eastM = 500.0)
        assertEquals("the evidence is there; the gate is what refuses", Motion.Stopped, core.motionVerdict(provenAt))
        assertFalse(core.watchingArrival)

        assertTrue(arrivalTick(provenAt).isEmpty())
        assertTrue(arrivalTick(provenAt + ArrivalWatch.STANDSTILL_FLOOR_MS).isEmpty())
    }

    @Test fun `a departure after the fired pause starts a fresh signal track, watched again`() {
        core.onDeparture(T0, settings)
        val provenAt = standstill(T0 + 10 * MINUTE, eastM = 500.0)
        arrivalTick(provenAt)
        val firedAt = provenAt + ArrivalWatch.STANDSTILL_FLOOR_MS
        arrivalTick(firedAt)

        // The stop outlasted the resume window before the pause even landed, so leaving again is a
        // new journey, not a stitch.
        val out = core.onDeparture(firedAt + MINUTE, settings)
        assertEquals(provenAt, out.filterIsInstance<Effect.CloseTrack>().single().endedAt)
        assertEquals(
            ActivityType.UNKNOWN,
            out.filterIsInstance<Effect.OpenTrack>().single().activity,
        )
        // The opener's provenance follows the new track, and the fire spent the old standstill:
        // pausing again takes a full fresh floor.
        assertTrue(core.watchingArrival)
        assertTrue(arrivalTick(firedAt + 2 * MINUTE).isEmpty())
    }
}
