package io.github.valeronm.breadcrumb.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** Sanitizing AR event timestamps into gate reading times. */
class ReadingClockTest {

    private val clock = ReadingClock()
    private val maxAge = 6 * 60 * 60_000L

    private fun sanitize(eventMs: Long?, nowMs: Long) = clock.sanitize(eventMs, nowMs, maxAge)

    @Test fun `a fresh event time passes through`() {
        assertEquals(9_000L, sanitize(eventMs = 9_000, nowMs = 10_000))
    }

    @Test fun `a missing event time falls back to now`() {
        assertEquals(10_000L, sanitize(eventMs = null, nowMs = 10_000))
    }

    @Test fun `a future event time falls back to now`() {
        // Small negative "ago" values occur from clock skew between the stamp and delivery.
        assertEquals(10_000L, sanitize(eventMs = 10_400, nowMs = 10_000))
    }

    @Test fun `an implausibly old event time falls back to now`() {
        // A live EXIT once claimed to be 22.5 hours old; garbage stamps must not time-travel.
        val now = maxAge + 100_000
        assertEquals(now, sanitize(eventMs = 50_000, nowMs = now))
    }

    @Test fun `age exactly at the cap is still honoured`() {
        assertEquals(1_000L, sanitize(eventMs = 1_000, nowMs = 1_000 + maxAge))
    }

    @Test fun `a genuinely delayed event keeps its original time`() {
        // The drained-burst case: a 10-minute-old STILL applied late must read as 10 minutes old,
        // or the following resume looks like a quick return and stitches through a real stop.
        assertEquals(10_000L, sanitize(eventMs = 10_000, nowMs = 10_000 + 600_000))
    }

    @Test fun `readings never regress`() {
        assertEquals(50_000L, sanitize(eventMs = 50_000, nowMs = 60_000))
        // An older stamp after a newer reading is lifted to the newer one.
        assertEquals(50_000L, sanitize(eventMs = 40_000, nowMs = 61_000))
        // And time can still move forward afterwards.
        assertEquals(55_000L, sanitize(eventMs = 55_000, nowMs = 62_000))
    }

    @Test fun `equal consecutive stamps are allowed`() {
        // Batched events share one delivery; ENTER and EXIT can carry the same stamp.
        assertEquals(30_000L, sanitize(eventMs = 30_000, nowMs = 31_000))
        assertEquals(30_000L, sanitize(eventMs = 30_000, nowMs = 31_000))
    }
}
