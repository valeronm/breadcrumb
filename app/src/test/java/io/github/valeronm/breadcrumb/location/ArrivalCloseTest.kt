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
 * track is never closed this way**, however loudly the ground stops.
 */
class ArrivalCloseTest : ActivityIngestFixture() {

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

    @Test fun `a proven standstill closes a signal-opened track, at the stop it proved`() {
        departure(T0, settings)
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
                // At the last good fix, which is the standstill the watch proved — not at the fire,
                // a floor's worth of parked minutes later.
                Effect.CloseTrack(endedAt = provenAt, renameTo = null),
                Effect.ArmDepartureFence(at(500.0)),
                Effect.ArmSignificantMotion,
                Effect.Publish,
            ),
            arrivalTick(firedAt),
        )
    }

    @Test fun `a reading-opened track is never closed by the ground`() {
        startWalking()
        val provenAt = standstill(T0 + 10 * MINUTE, eastM = 500.0)
        assertEquals("the evidence is there; the gate is what refuses", Motion.Stopped, core.motionVerdict(provenAt))
        assertFalse(core.watchingArrival)

        assertTrue(arrivalTick(provenAt).isEmpty())
        assertTrue(arrivalTick(provenAt + ArrivalWatch.STANDSTILL_FLOOR_MS).isEmpty())
    }

    @Test fun `a departure after the arrival asks for a track, watched again`() {
        departure(T0, settings)
        val provenAt = standstill(T0 + 10 * MINUTE, eastM = 500.0)
        arrivalTick(provenAt)
        val firedAt = provenAt + ArrivalWatch.STANDSTILL_FLOOR_MS

        // The arrival closed it here, at the standstill — the departure below has nothing left to
        // end, which is the whole difference from a track merely held open.
        assertEquals(provenAt, arrivalTick(firedAt).filterIsInstance<Effect.CloseTrack>().single().endedAt)

        val out = departure(firedAt + MINUTE, settings)
        assertTrue("nothing left to close", out.none { it is Effect.CloseTrack })
        assertEquals(
            ActivityType.UNKNOWN,
            out.filterIsInstance<Effect.OpenTrack>().single().activity,
        )
        // The opener's provenance follows the new stretch, and the fire spent the old standstill:
        // arriving again takes a full fresh floor.
        assertTrue(core.watchingArrival)
        assertTrue(arrivalTick(firedAt + 2 * MINUTE).isEmpty())
    }
}
