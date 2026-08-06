package io.github.valeronm.breadcrumb.ui

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/**
 * That the reader's 12/24 setting reaches the clock, and that the locale still arranges what is
 * around it. Robolectric rather than plain JVM because the pattern comes from the framework:
 * `getBestDateTimePattern` is what turns a skeleton into a locale's field order, and there is no
 * mocking it — see `localizedDateFormat`, which says the same about dates generally.
 *
 * The assertions read the *shape* of the output — which hour it names, whether a day period follows
 * — rather than a literal string, because a literal would pin one Android version's idea of where
 * ICU puts the separator and would fail on the next. The zone case is the deliberate exception: a
 * 24-hour British time is `HH:mm` in every ICU version that has shipped, and comparing the two zones
 * as whole strings is what makes it obvious they name different hours.
 */
@RunWith(RobolectricTestRunner::class)
class ReaderClockTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun `a 24-hour reader is given the 24-hour cycle`() {
        val time = ReaderClock(Locale.UK, hour24 = true, shiftHourSymbol = "h").time(afternoon, UTC)
        assertTrue("expected a 24-hour clock, got \"$time\"", time.contains("14"))
        assertNoDayPeriod(time)
    }

    @Test fun `a 12-hour reader is given the 12-hour cycle and a day period`() {
        val time = ReaderClock(Locale.US, hour24 = false, shiftHourSymbol = "h").time(afternoon, UTC)
        assertTrue("expected a 12-hour clock, got \"$time\"", time.contains("2"))
        assertTrue("expected \"$time\" to name its half of the day", hasDayPeriod(time))
    }

    /**
     * The morning is where a 12-hour clock is easiest to get wrong without noticing: 09:05 reads
     * "9:05" either way, and only the day period tells the two cycles apart.
     */
    @Test fun `a morning time differs between the cycles only by its day period`() {
        val twelve = ReaderClock(Locale.US, hour24 = false, shiftHourSymbol = "h").time(morning, UTC)
        val twentyFour = ReaderClock(Locale.UK, hour24 = true, shiftHourSymbol = "h").time(morning, UTC)
        assertTrue("expected \"$twelve\" to name its half of the day", hasDayPeriod(twelve))
        assertNoDayPeriod(twentyFour)
    }

    /** The clock is read in the zone it is given, not the host's — the same rule every row obeys. */
    @Test fun `the zone decides which hour is named`() {
        val clock = ReaderClock(Locale.UK, hour24 = true, shiftHourSymbol = "h")
        assertEquals("14:05", clock.time(afternoon, UTC))
        assertEquals("23:05", clock.time(afternoon, ZoneId.of("Asia/Tokyo")))
    }

    /**
     * What the app actually calls: the setting is read from the device, not passed in. Written
     * through `Settings.System` because that is the store `is24HourFormat` reads, so this pins the
     * wiring rather than restating the case above.
     */
    @Test fun `the device setting is what readerClockOf reads`() {
        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "24")
        assertNoDayPeriod(readerClockOf(context).time(afternoon, UTC))

        Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "12")
        assertTrue(
            "a phone set to 12-hour should not be shown a 24-hour clock",
            hasDayPeriod(readerClockOf(context).time(afternoon, UTC)),
        )
    }

    private fun hasDayPeriod(time: String) = time.any { it.isLetter() }

    private fun assertNoDayPeriod(time: String) =
        assertTrue("expected no day period in \"$time\"", time.none { it.isLetter() })

    private companion object {
        val UTC: ZoneId = ZoneId.of("UTC")
        val DAY: LocalDate = LocalDate.of(2021, 3, 4)

        fun at(hour: Int, minute: Int): Long =
            DAY.atTime(LocalTime.of(hour, minute)).atZone(UTC).toInstant().toEpochMilli()

        val afternoon: Long = at(14, 5)
        val morning: Long = at(9, 5)
    }
}
