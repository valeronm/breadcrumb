package io.github.valeronm.breadcrumb.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The no-fix give-up guard: when to abandon a fixless GPS probe, and which signals restart one. */
class NoFixGuardTest {

    private val GIVE_UP_MS = 240_000L // the default 4-minute window

    // --- Giving up ---------------------------------------------------------

    @Test fun `does not give up before the window`() {
        val g = probing()
        assertFalse(g.shouldGiveUp(GIVE_UP_MS - 1, GIVE_UP_MS))
    }

    @Test fun `gives up once the window passes with nothing accepted`() {
        val g = probing()
        assertTrue(g.shouldGiveUp(GIVE_UP_MS, GIVE_UP_MS))
    }

    @Test fun `a give-up window of zero disables the guard`() {
        val g = probing()
        assertFalse(g.shouldGiveUp(10_000_000, 0))
    }

    @Test fun `an accepted fix restarts the window`() {
        val g = probing()
        g.onFixAccepted(200_000)
        assertFalse(g.shouldGiveUp(200_000 + GIVE_UP_MS - 1, GIVE_UP_MS))
        assertTrue(g.shouldGiveUp(200_000 + GIVE_UP_MS, GIVE_UP_MS))
    }

    @Test fun `never gives up while already suspended`() {
        val g = probing()
        g.onGaveUp(GIVE_UP_MS)
        assertFalse(g.shouldGiveUp(GIVE_UP_MS * 10, GIVE_UP_MS))
    }

    @Test fun `a new probe restarts the window`() {
        val g = probing()
        g.onGaveUp(GIVE_UP_MS)
        g.onProbeStarted(500_000)
        assertFalse(g.shouldGiveUp(500_000 + GIVE_UP_MS - 1, GIVE_UP_MS))
        assertTrue(g.shouldGiveUp(500_000 + GIVE_UP_MS, GIVE_UP_MS))
    }

    // --- Suspension and resume signals --------------------------------------

    @Test fun `giving up suspends and a new probe clears it`() {
        val g = probing()
        assertFalse(g.suspended)
        g.onGaveUp(GIVE_UP_MS)
        assertTrue(g.suspended)
        g.onProbeStarted(GIVE_UP_MS + 1_000)
        assertFalse(g.suspended)
    }

    @Test fun `no signal probes while not suspended`() {
        val g = probing()
        assertFalse(g.shouldProbe(1_000_000, respectBackoff = true))
        assertFalse(g.shouldProbe(1_000_000, respectBackoff = false))
    }

    @Test fun `motion respects the backoff gate`() {
        val g = probing()
        g.onGaveUp(0) // gate = 120s
        assertFalse(g.shouldProbe(119_999, respectBackoff = true))
        assertTrue(g.shouldProbe(120_000, respectBackoff = true))
    }

    @Test fun `a transition or passive fix bypasses the gate`() {
        val g = probing()
        g.onGaveUp(0)
        assertTrue(g.shouldProbe(1, respectBackoff = false))
    }

    @Test fun `pausing or closing clears the suspension`() {
        val g = probing()
        g.onGaveUp(0)
        g.onStopped()
        assertFalse(g.suspended)
        assertFalse(g.shouldProbe(1_000_000, respectBackoff = false))
    }

    // --- Backoff progression ------------------------------------------------

    @Test fun `consecutive failed probes back off 2-4-8 minutes, capped`() {
        val g = probing()
        assertEquals(120_000, g.onGaveUp(0))
        g.onProbeStarted(1_000_000)
        assertEquals(240_000, g.onGaveUp(1_240_000))
        g.onProbeStarted(2_000_000)
        assertEquals(480_000, g.onGaveUp(2_240_000))
        g.onProbeStarted(3_000_000)
        assertEquals(480_000, g.onGaveUp(3_240_000)) // capped
    }

    @Test fun `an accepted fix resets the backoff progression`() {
        val g = probing()
        g.onGaveUp(0)
        g.onProbeStarted(1_000_000)
        g.onGaveUp(1_240_000) // 2nd failure → 240s
        g.onProbeStarted(2_000_000)
        g.onFixAccepted(2_010_000)
        g.onGaveUp(3_000_000)
        // Back to the base gate: the receiver had converged in between.
        assertFalse(g.shouldProbe(3_000_000 + 119_999, respectBackoff = true))
        assertTrue(g.shouldProbe(3_000_000 + 120_000, respectBackoff = true))
    }

    @Test fun `a new track resets the backoff progression`() {
        val g = probing()
        g.onGaveUp(0)
        g.onProbeStarted(1_000_000)
        g.onGaveUp(1_240_000) // 2nd failure → 240s
        g.onStopped()
        g.onTrackOpened()
        g.onProbeStarted(2_000_000)
        assertEquals(120_000, g.onGaveUp(2_240_000)) // fresh track → base gate again
    }

    @Test fun `custom base and cap are honored`() {
        val g = NoFixGuard(retryBaseMs = 1_000, retryCapMs = 3_000)
        g.onProbeStarted(0)
        assertEquals(1_000, g.onGaveUp(0))
        g.onProbeStarted(1)
        assertEquals(2_000, g.onGaveUp(1))
        g.onProbeStarted(2)
        assertEquals(3_000, g.onGaveUp(2)) // 4_000 capped to 3_000
    }

    /** A guard with a probe running since t=0. */
    private fun probing(): NoFixGuard = NoFixGuard().apply { onProbeStarted(0) }
}
