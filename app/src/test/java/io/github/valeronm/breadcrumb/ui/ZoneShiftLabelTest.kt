package io.github.valeronm.breadcrumb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The muted tag beside a row's times: how far that row's clock sat from the reader's own.
 *
 * The instant matters as much as the two zones — summer time moves one side and not the other — so
 * every case here names the moment it is asking about.
 */
class ZoneShiftLabelTest {

    private val lisbon = ZoneId.of("Europe/Lisbon")
    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val kolkata = ZoneId.of("Asia/Kolkata")
    private val utc = ZoneId.of("UTC")

    private fun at(date: LocalDate) = date.atStartOfDay(utc).toInstant().toEpochMilli()

    private val midsummer = at(LocalDate.of(2026, 7, 18))
    private val midwinter = at(LocalDate.of(2026, 1, 18))

    @Test fun `the same clock says nothing`() {
        assertNull(zoneShiftLabel(midsummer, lisbon, lisbon, "h"))
    }

    @Test fun `two zones that happen to agree at that instant say nothing either`() {
        // Lisbon is on UTC in January and an hour ahead in July: the tag is about the clocks, not
        // about the names of the zones, so in winter there is nothing to say.
        assertNull(zoneShiftLabel(midwinter, lisbon, utc, "h"))
        assertEquals("+1h", zoneShiftLabel(midsummer, lisbon, utc, "h"))
    }

    @Test fun `ahead and behind read as themselves`() {
        assertEquals("+9h", zoneShiftLabel(midsummer, tokyo, utc, "h"))
        assertEquals("−9h", zoneShiftLabel(midsummer, utc, tokyo, "h"))
    }

    @Test fun `a half-hour zone keeps its minutes`() {
        assertEquals("+5h30", zoneShiftLabel(midsummer, kolkata, utc, "h"))
    }

    @Test fun `summer time moves the answer, and only on the side that observes it`() {
        // Tokyo keeps one offset all year; Lisbon does not. A reader in Lisbon is eight hours behind
        // Tokyo in July and nine in January — and a July row must keep saying eight forever after,
        // which is what reading both zones at the row's own instant buys.
        assertEquals("+8h", zoneShiftLabel(midsummer, tokyo, lisbon, "h"))
        assertEquals("+9h", zoneShiftLabel(midwinter, tokyo, lisbon, "h"))
    }

    @Test fun `the hour is written with the language's own symbol`() {
        // The "h" is not part of the shape: the caller hands in its language's hour symbol, the
        // same one the duration ladder writes.
        assertEquals("+9ч", zoneShiftLabel(midsummer, tokyo, utc, "ч"))
        assertEquals("+5ч30", zoneShiftLabel(midsummer, kolkata, utc, "ч"))
    }
}
