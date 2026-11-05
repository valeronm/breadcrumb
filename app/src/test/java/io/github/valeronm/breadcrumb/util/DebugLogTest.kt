package io.github.valeronm.breadcrumb.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

class DebugLogTest {

    private val saved = TimeZone.getDefault()

    @After fun restoreZone() = TimeZone.setDefault(saved)

    private val noonUtc = Instant.parse("2026-11-04T12:00:00Z").toEpochMilli()

    @Test fun `a line carries the zone in force when it is written, with its offset`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Bangkok"))
        assertEquals(
            "11-04 19:00:00.000 +0700 I first",
            DebugLog.fileLine(DebugLog.Entry(noonUtc, 'I', "first")),
        )
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Lisbon"))
        assertEquals(
            "11-04 12:00:00.000 +0000 I second",
            DebugLog.fileLine(DebugLog.Entry(noonUtc, 'I', "second")),
        )
    }
}
