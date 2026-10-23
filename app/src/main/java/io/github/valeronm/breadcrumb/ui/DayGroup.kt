package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.domain.TimelineItem
import java.time.LocalDate

/**
 * The rows cut into days, each dated on **its own clock** — a stay abroad falls under the day it was
 * lived in, not the one the device was having.
 *
 * Grouped by *runs* rather than by value, which is the whole difference from a global calendar: once
 * each row answers on its own zone the dates are no longer a partition. Flying east to west, the
 * 18th happens twice with the crossing between; flying the other way, a date is skipped. `groupBy`
 * would weld the two halves of a repeated date into one heading with a flight's worth of rows
 * between them, in the wrong order — so a repeat has to be allowed to stand as two days, because
 * that is what it was.
 */
internal fun <T : TimelineItem> groupTimelineByDay(
    items: List<T>,
    dateOf: (T) -> LocalDate = { it.filedOn },
): List<DayGroup<T>> {
    val groups = mutableListOf<DayGroup<T>>()
    var run = mutableListOf<T>()
    var date: LocalDate? = null
    for (item in items) {
        val itemDate = dateOf(item)
        if (itemDate != date) {
            date?.let { groups += DayGroup(it, run) }
            run = mutableListOf()
            date = itemDate
        }
        run += item
    }
    date?.let { groups += DayGroup(it, run) }
    return groups
}

/**
 * One day's rows, carrying the date and not a heading for it. Naming the day is [rememberDayLabel]'s:
 * a date is what answers whether the day falls inside a travel, while a heading is language, and
 * phrasing it here would put a locale-resolved formatter — which reaches the Android framework —
 * inside a function whose whole point is being testable on a plain JVM.
 */
internal class DayGroup<out T : TimelineItem>(val date: LocalDate, val items: List<T>) {
    /** A date can head two runs ([groupTimelineByDay]), and a lazy list refuses a duplicate key. */
    val headingKey: String get() = "header:${items.first().startedAt}"
}
