package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.TrackQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The witness's case that a track's label is wrong — two channels, either sufficient, both immune to the
 * honest cases: an interval run must never build a case, nor may a lone forged window. The speed channel's
 * bar is the shipping ceiling table, not a copy — a retuned ceiling must move these tests with it.
 */
class CarrierEvidenceTest {

    /** The foot group's most permissive ceiling (RUNNING's), as the recorder derives it. */
    private val FOOT_CEILING = TrackQuality.groupCeilingKmh(ActivityType.WALKING)

    /** A carrier at ~20 km/h — above the walking ceiling, below the group's. */
    private val CARRIED = Motion.Moving(5.6)

    /** A carrier at ~40 km/h — above the foot group's ceiling. */
    private val FAST_CARRIER = Motion.Moving(11.0)

    /** An honest run at ~14 km/h — above WALKING's own ceiling, far under the group's. */
    private val RUNNING = Motion.Moving(4.0)

    private fun evidence() = CarrierEvidence().apply { restart(FOOT_CEILING) }

    /** Feed [motion] every 5 s from t=0 for [totalSec] seconds. */
    private fun CarrierEvidence.ride(motion: Motion, totalSec: Long, parked: Boolean) {
        for (sec in 0..totalSec step 5) onSample(sec * 1000, motion, parked)
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
        // The slow-carrier case that pins the two channels' relationship: a bus in traffic barely
        // moves the speed channel while the parked stop lights the still one.
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

    // --- Abstention and hygiene ---------------------------------------------

    @Test fun `abstention accumulates nothing on either channel`() {
        val e = evidence()
        e.ride(Motion.Unknown, totalSec = 3_600, parked = true)
        assertFalse(e.proven)
        assertEquals(0, e.bodyStillMs)
        assertEquals(0, e.bodyMovingMs)
    }

    @Test fun `a standstill accumulates nothing`() {
        val e = evidence()
        e.ride(Motion.Stopped, totalSec = 3_600, parked = true)
        assertFalse(e.proven)
    }

    @Test fun `the first sample credits nothing however strong it is`() {
        val e = evidence()
        e.onSample(0, FAST_CARRIER, true)
        assertEquals(0, e.bodyStillMs)
        assertEquals(0, e.bodyMovingMs)
    }

    @Test fun `credit needs the condition at both ends of the pair`() {
        // Moving, then a gap the window went stale across, then Moving again: the abstaining
        // sample in between fences the gap off both channels.
        val e = evidence()
        e.onSample(0, FAST_CARRIER, false)
        e.onSample(5_000, FAST_CARRIER, false)
        e.onSample(65_000, Motion.Unknown, false)
        e.onSample(70_000, FAST_CARRIER, false)
        e.onSample(75_000, FAST_CARRIER, false)
        assertEquals(10_000, e.bodyMovingMs)
    }

    @Test fun `a single pair cannot credit more than the slowest cadence delivers`() {
        // Two Moving samples five minutes apart credit the cap, not the five minutes — evidence
        // accrues at the rate fixes arrive, so a stale window cannot credit a delivery gap.
        val e = evidence()
        e.onSample(0, FAST_CARRIER, false)
        e.onSample(300_000, FAST_CARRIER, false)
        assertTrue("credited ${e.bodyMovingMs}", e.bodyMovingMs in 1..35_000)
    }

    @Test fun `an out-of-order sample is refused`() {
        val e = evidence()
        e.onSample(60_000, FAST_CARRIER, true)
        e.onSample(1_000, FAST_CARRIER, true)
        assertEquals(0, e.bodyStillMs)
    }

    @Test fun `restart forgets the case`() {
        val e = evidence()
        e.ride(FAST_CARRIER, totalSec = 300, parked = true)
        assertTrue(e.proven)
        e.restart(FOOT_CEILING)
        assertFalse(e.proven)
        assertEquals(0, e.bodyStillMs)
        assertEquals(0, e.bodyMovingMs)
    }
}
