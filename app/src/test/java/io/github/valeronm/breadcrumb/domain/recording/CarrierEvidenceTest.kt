package io.github.valeronm.breadcrumb.domain.recording

import io.github.valeronm.breadcrumb.data.TrackQuality
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.tracks.Speed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The witness's case that a track's label is wrong — each channel sufficient alone and immune to the
 * honest cases: an interval run must never build a case, nor may a lone forged window. The speed channel's
 * bar is the shipping ceiling table, not a copy — a retuned ceiling must move these tests with it.
 */
class CarrierEvidenceTest {

    /** A carrier above the walking ceiling, below the group's. */
    private val CARRIED = Motion.Moving(Speed.kmh(20.0))

    /** A carrier above the foot group's ceiling. */
    private val FAST_CARRIER = Motion.Moving(Speed.kmh(40.0))

    /** An honest run — above WALKING's own ceiling, far under the group's. */
    private val RUNNING = Motion.Moving(Speed.kmh(14.4))

    private val WALKING = Motion.Moving(Speed.kmh(6.0))

    private val WALK_FLOOR = TrackQuality.labelCeiling(ActivityType.WALKING)

    private val RUN_FLOOR = TrackQuality.labelCeiling(ActivityType.RUNNING)

    private fun evidence() = CarrierEvidence(TrackQuality.FOOT_CEILING)

    private fun CarrierEvidence.sample(atMs: Long, motion: Motion, parkedUnder: Speed? = null) =
        onSample(atMs, motion, WALK_FLOOR, parkedUnder, stepsInWindow = null)

    /** Feed [motion] every 5 s from [fromSec] for [totalSec] seconds. */
    private fun CarrierEvidence.ride(
        motion: Motion,
        totalSec: Long,
        parked: Boolean,
        trusted: Speed = WALK_FLOOR,
        floor: Speed = WALK_FLOOR,
        fromSec: Long = 0,
        stepsInWindow: Long? = null,
    ) {
        for (sec in fromSec..fromSec + totalSec step 5) {
            onSample(sec * 1000, motion, floor, if (parked) trusted else null, stepsInWindow)
        }
    }

    // --- The body-still channel ---------------------------------------------

    @Test fun `a stop parked under a moving ground makes the case`() {
        val e = evidence()
        e.ride(CARRIED, totalSec = 60, parked = true)
        assertTrue(e.proven)
        assertEquals(60_000, e.bodyStillMs)
    }

    @Test fun `one window's worth of parked stop is not enough`() {
        // A teleport-forged Moving decays within a confirmer window and the STILL promotes — the
        // threshold must sit out of that reach.
        val e = evidence()
        e.ride(CARRIED, totalSec = 20, parked = true)
        assertFalse(e.proven)
    }

    @Test fun `moving ground with nothing parked builds no body-still case`() {
        val e = evidence()
        e.ride(CARRIED, totalSec = 300, parked = false)
        assertEquals(0, e.bodyStillMs)
    }

    @Test fun `a stop misreported mid-walk builds no body-still case`() {
        val e = evidence()
        e.ride(WALKING, totalSec = 300, parked = true)
        assertFalse(e.proven)
        assertEquals(0, e.bodyStillMs)
    }

    @Test fun `a stop misreported mid-run builds no body-still case`() {
        val e = evidence()
        e.ride(RUNNING, totalSec = 300, parked = true, floor = RUN_FLOOR)
        assertFalse(e.proven)
        assertEquals(0, e.bodyStillMs)
    }

    @Test fun `a run on a walking-labelled track builds no body-still case`() {
        val e = evidence()
        e.ride(RUNNING, totalSec = 300, parked = true, trusted = RUN_FLOOR)
        assertFalse(e.proven)
        assertEquals(0, e.bodyStillMs)
    }

    @Test fun `a run whose readings flip to walking keeps the run's floor`() {
        val e = evidence()
        e.ride(RUNNING, totalSec = 300, parked = true, trusted = WALK_FLOOR, floor = RUN_FLOOR)
        assertFalse(e.proven)
        assertEquals(0, e.bodyStillMs)
    }

    // --- The body-moving channel --------------------------------------------

    @Test fun `speed beyond the group ceiling makes the case on its own`() {
        val e = evidence()
        e.ride(FAST_CARRIER, totalSec = 120, parked = false)
        assertTrue(e.proven)
        assertEquals(120_000, e.bodyMovingMs)
    }

    @Test fun `an honest interval run never builds a case`() {
        // Above WALKING's own 12 km/h for as long as you like — the channel measures against the
        // group's ceiling precisely so a run inside a walking-labelled track cannot flip it.
        val e = evidence()
        e.ride(RUNNING, totalSec = 1_800, parked = false)
        assertFalse(e.proven)
        assertEquals(0, e.bodyMovingMs)
    }

    @Test fun `a carried pace under the group ceiling leans on the still channel alone`() {
        val e = evidence()
        e.ride(CARRIED, totalSec = 120, parked = false)
        assertFalse(e.proven)
        assertEquals(0, e.bodyMovingMs)
    }

    // --- The rename verdict -------------------------------------------------

    @Test fun `a proven case renames a foot label to Moving`() {
        val e = evidence()
        e.ride(FAST_CARRIER, totalSec = 300, parked = true)
        assertEquals(ActivityType.UNKNOWN, e.renameFor(ActivityType.WALKING))
        assertEquals(ActivityType.UNKNOWN, e.renameFor(ActivityType.RUNNING))
    }

    @Test fun `a vehicle label is never a rename candidate`() {
        // A drive already carries the ceiling and the group the witness would grant; renaming it
        // to "Moving" would discard real information.
        val e = evidence()
        e.ride(FAST_CARRIER, totalSec = 300, parked = true)
        assertNull(e.renameFor(ActivityType.DRIVING))
        assertNull(e.renameFor(ActivityType.CYCLING))
    }

    @Test fun `an unproven case renames nothing`() {
        val e = evidence()
        e.ride(CARRIED, totalSec = 20, parked = true)
        assertNull(e.renameFor(ActivityType.WALKING))
    }

    // --- The stepless channel -----------------------------------------------

    @Test fun `a ride with no steps makes the case under the group ceiling`() {
        val e = evidence()
        e.ride(CARRIED, totalSec = 150, parked = false, stepsInWindow = 0)
        assertTrue(e.proven)
        assertEquals(150_000, e.bodySteplessMs)
    }

    @Test fun `road bumps counted as steps still read as no steps`() {
        val e = evidence()
        e.ride(CARRIED, totalSec = 150, parked = false, stepsInWindow = 4)
        assertTrue(e.proven)
    }

    @Test fun `walking cadence at a carried pace credits nothing`() {
        val e = evidence()
        e.ride(CARRIED, totalSec = 150, parked = false, stepsInWindow = 0)
        e.ride(CARRIED, totalSec = 600, parked = false, fromSec = 155, stepsInWindow = 40)
        assertEquals(150_000, e.bodySteplessMs)
    }

    @Test fun `a walking pace with no steps credits nothing`() {
        val e = evidence()
        e.ride(CARRIED, totalSec = 150, parked = false, stepsInWindow = 0)
        e.ride(WALKING, totalSec = 600, parked = false, fromSec = 155, stepsInWindow = 0)
        assertEquals(150_000, e.bodySteplessMs)
    }

    @Test fun `a pace under the walking label's ceiling with no steps credits nothing`() {
        val e = evidence()
        e.ride(Motion.Moving(Speed.kmh(11.0)), totalSec = 300, parked = false, stepsInWindow = 0)
        assertEquals(0, e.bodySteplessMs)
    }

    @Test fun `a run with no steps credits nothing under the running label's ceiling`() {
        val e = evidence()
        e.ride(RUNNING, totalSec = 300, parked = false, floor = RUN_FLOOR, stepsInWindow = 0)
        assertEquals(0, e.bodySteplessMs)
    }

    @Test fun `a window the counter did not cover credits nothing`() {
        val e = evidence()
        e.ride(CARRIED, totalSec = 150, parked = false, stepsInWindow = 0)
        e.ride(CARRIED, totalSec = 600, parked = false, fromSec = 155, stepsInWindow = null)
        assertEquals(150_000, e.bodySteplessMs)
    }

    // --- Abstention and hygiene ---------------------------------------------

    @Test fun `abstention accumulates nothing on any channel`() {
        val e = evidence()
        e.ride(Motion.Unknown, totalSec = 3_600, parked = true, stepsInWindow = 0)
        assertFalse(e.proven)
        assertEquals(0, e.bodyStillMs)
        assertEquals(0, e.bodyMovingMs)
        assertEquals(0, e.bodySteplessMs)
    }

    @Test fun `a standstill accumulates nothing`() {
        val e = evidence()
        e.ride(Motion.Stopped, totalSec = 3_600, parked = true)
        assertFalse(e.proven)
    }

    @Test fun `the first sample credits nothing however strong it is`() {
        val e = evidence()
        e.onSample(0, FAST_CARRIER, WALK_FLOOR, parkedUnder = WALK_FLOOR, stepsInWindow = 0)
        assertEquals(0, e.bodyStillMs)
        assertEquals(0, e.bodyMovingMs)
        assertEquals(0, e.bodySteplessMs)
    }

    @Test fun `credit needs the condition at both ends of the pair`() {
        val e = evidence()
        e.sample(0, FAST_CARRIER)
        e.sample(5_000, FAST_CARRIER)
        e.sample(65_000, Motion.Unknown)
        e.sample(70_000, FAST_CARRIER)
        e.sample(75_000, FAST_CARRIER)
        assertEquals(10_000, e.bodyMovingMs)
    }

    @Test fun `a single pair cannot credit more than the slowest cadence delivers`() {
        // Two Moving samples five minutes apart credit the cap, not the five minutes — evidence
        // accrues at the rate fixes arrive, so a stale window cannot credit a delivery gap.
        val e = evidence()
        e.sample(0, FAST_CARRIER)
        e.sample(300_000, FAST_CARRIER)
        assertTrue("credited ${e.bodyMovingMs}", e.bodyMovingMs in 1..35_000)
    }

    @Test fun `an out-of-order sample is refused`() {
        val e = evidence()
        e.sample(60_000, FAST_CARRIER, WALK_FLOOR)
        e.sample(1_000, FAST_CARRIER, WALK_FLOOR)
        assertEquals(0, e.bodyStillMs)
    }
}
