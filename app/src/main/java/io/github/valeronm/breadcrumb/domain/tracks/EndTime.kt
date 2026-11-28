package io.github.valeronm.breadcrumb.domain.tracks

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The time a hand-entered trip's end holds of its own, before any estimate along a road stands in
 * for it. A date and a time are picked one at a time, so an end can hold a date alone; a recorded
 * bound is held as the instant it is, since the clock an end is read on changes with its pin.
 */
sealed interface EndTime {
    /** The instant this time names on [zone]'s clock, or null where it names none. */
    fun epochIn(zone: ZoneId): Long?

    /** The day this time falls on, on [zone]'s clock, or null where it names none. */
    fun dateIn(zone: ZoneId): LocalDate?

    data object None : EndTime {
        override fun epochIn(zone: ZoneId): Long? = null

        override fun dateIn(zone: ZoneId): LocalDate? = null
    }

    data class DateOnly(val date: LocalDate) : EndTime {
        override fun epochIn(zone: ZoneId): Long? = null

        override fun dateIn(zone: ZoneId): LocalDate = date
    }

    /** A recorded bound: a gap's own, or a neighboring track's. */
    data class Held(val ms: Long) : EndTime {
        override fun epochIn(zone: ZoneId): Long = ms

        override fun dateIn(zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    }

    data class Typed(val date: LocalDate, val time: LocalTime) : EndTime {
        override fun epochIn(zone: ZoneId): Long = ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()

        override fun dateIn(zone: ZoneId): LocalDate = date
    }

    companion object {
        /**
         * [date] picked for an end showing [shownMs] on [zone]'s clock: the time it shows is kept,
         * to the millisecond, and an end showing none holds the date alone.
         */
        fun withDate(shownMs: Long?, date: LocalDate, zone: ZoneId): EndTime =
            shownMs?.let { Typed(date, Instant.ofEpochMilli(it).atZone(zone).toLocalTime()) } ?: DateOnly(date)
    }
}
