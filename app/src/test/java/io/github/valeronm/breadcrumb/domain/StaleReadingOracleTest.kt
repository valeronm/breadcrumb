package io.github.valeronm.breadcrumb.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Detecting a silently-deaf AR registration from a stale, clock-advancing replay. */
class StaleReadingOracleTest {

    private val armedAtMs = 1_000_000L
    private val nowMs = 10_000_000L
    private val staleMs = 60_000L
    private val minAdvanceMs = 1_000L

    private fun provesDeaf(
        readingMs: Long,
        lastReadingMs: Long,
        eventTimeMs: Long? = readingMs,
    ) = StaleReadingOracle.provesDeaf(
        eventTimeMs, readingMs, lastReadingMs, armedAtMs, nowMs, staleMs, minAdvanceMs,
    )

    @Test fun `a stale reading that advances the clock proves deafness`() {
        // Applied ~2 h behind its event while armed, well past the last reading — a missed live
        // transition that only surfaced via replay.
        assertTrue(provesDeaf(readingMs = 2_100_000, lastReadingMs = 2_000_000))
    }

    @Test fun `a replay of the already-applied event is ignored`() {
        // The steady-state case: the watchdog re-registers and GMS replays the current STILL; the
        // clock does not advance, so it is not proof of anything.
        assertFalse(provesDeaf(readingMs = 2_100_000, lastReadingMs = 2_100_000))
    }

    @Test fun `a sub-second advance from wall-clock jitter is ignored`() {
        // The field false-positive: the same replayed event, re-timed 50 min later, drifts a few ms
        // past the stored reading. Below the advance floor, so no spurious restart.
        assertFalse(provesDeaf(readingMs = 2_100_050, lastReadingMs = 2_100_000))
    }

    @Test fun `an advance exactly at the floor does not count`() {
        assertFalse(provesDeaf(readingMs = 2_101_000, lastReadingMs = 2_100_000))
    }

    @Test fun `an advance just past the floor counts`() {
        assertTrue(provesDeaf(readingMs = 2_101_001, lastReadingMs = 2_100_000))
    }

    @Test fun `a fresh live delivery is not stale enough`() {
        // Arrives 10 s behind its event — normal live latency, not a replay.
        assertFalse(provesDeaf(readingMs = nowMs - 10_000, lastReadingMs = 2_000_000))
    }

    @Test fun `a reading predating the arm is exempt`() {
        // The arm-time replay legitimately reports an event from before we armed.
        assertFalse(provesDeaf(readingMs = 500_000, lastReadingMs = 400_000))
    }

    @Test fun `a missing event time never fires`() {
        assertFalse(provesDeaf(readingMs = 2_100_000, lastReadingMs = 2_000_000, eventTimeMs = null))
    }
}
