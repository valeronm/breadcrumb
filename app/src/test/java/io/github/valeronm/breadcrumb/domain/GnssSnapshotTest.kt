package io.github.valeronm.breadcrumb.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GnssSnapshotTest {

    // --- The per-callback reduction ------------------------------------------

    @Test
    fun `no satellites means zero used and no signal strength`() {
        val snapshot = GnssSnapshot()
        assertEquals(0, snapshot.usedInFix)
        assertNull(snapshot.topCn0Mean())
    }

    @Test
    fun `satellites not used in the fix are skipped entirely`() {
        val snapshot = GnssSnapshot()
        snapshot.add(used = false, cn0DbHz = 45f)
        snapshot.add(used = true, cn0DbHz = 30f)
        assertEquals(1, snapshot.usedInFix)
        assertEquals(30f, snapshot.topCn0Mean()!!, 0f)
    }

    @Test
    fun `an unreported strength counts as used but not toward the mean`() {
        val snapshot = GnssSnapshot()
        snapshot.add(used = true, cn0DbHz = 0f)
        snapshot.add(used = true, cn0DbHz = -1f)
        assertEquals(2, snapshot.usedInFix)
        assertNull(snapshot.topCn0Mean())
    }

    @Test
    fun `the mean is over the strongest four, whatever order they arrive in`() {
        val snapshot = GnssSnapshot()
        for (cn0 in floatArrayOf(20f, 45f, 10f, 40f, 35f, 30f)) snapshot.add(used = true, cn0DbHz = cn0)
        assertEquals(6, snapshot.usedInFix)
        assertEquals((45f + 40f + 35f + 30f) / 4, snapshot.topCn0Mean()!!, 1e-4f)
    }

    @Test
    fun `fewer than four strengths average over what there is`() {
        val snapshot = GnssSnapshot()
        snapshot.add(used = true, cn0DbHz = 20f)
        snapshot.add(used = true, cn0DbHz = 40f)
        assertEquals(30f, snapshot.topCn0Mean()!!, 0f)
    }

    @Test
    fun `reset clears the pass so the instance can be reused`() {
        val snapshot = GnssSnapshot()
        snapshot.add(used = true, cn0DbHz = 45f)
        snapshot.reset()
        assertEquals(0, snapshot.usedInFix)
        assertNull(snapshot.topCn0Mean())
        snapshot.add(used = true, cn0DbHz = 20f)
        assertEquals(20f, snapshot.topCn0Mean()!!, 0f) // no leak from before the reset
    }

    // --- The fix-freshness cross-check ---------------------------------------

    @Test
    fun `fails open while no satellite fix has ever been seen`() {
        assertTrue(GnssSnapshot.backed(lastGnssElapsedMs = 0L, fixElapsedMs = 500_000L, maxAgeMs = 5_000L))
    }

    @Test
    fun `a fix within the age window is backed, at the boundary included`() {
        assertTrue(GnssSnapshot.backed(lastGnssElapsedMs = 100_000L, fixElapsedMs = 103_000L, maxAgeMs = 5_000L))
        assertTrue(GnssSnapshot.backed(lastGnssElapsedMs = 100_000L, fixElapsedMs = 105_000L, maxAgeMs = 5_000L))
    }

    @Test
    fun `a fix past the age window is a fabrication`() {
        assertFalse(GnssSnapshot.backed(lastGnssElapsedMs = 100_000L, fixElapsedMs = 105_001L, maxAgeMs = 5_000L))
    }
}
