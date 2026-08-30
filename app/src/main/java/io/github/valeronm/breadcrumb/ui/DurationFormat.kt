package io.github.valeronm.breadcrumb.ui

import kotlin.math.roundToLong

/**
 * How long something took, as the screens say it. Kept apart from the widget files — whose
 * top-level state can reach the Android framework — so these stay reachable from a plain JVM test
 * on any dev box, the way the domain rules are.
 */

/**
 * Average lengths, used to express a *magnitude* ("about three years here") and never to do calendar
 * arithmetic — nothing is being scheduled, and a place's total spans too many real months for any
 * one of them to be the right length.
 */
private const val AVG_DAYS_PER_MONTH = 30.44
private const val MONTHS_PER_YEAR = 12L
private const val DAYS_BEFORE_MONTHS = 30L
private const val MINUTES_PER_DAY = 24 * 60L

/**
 * How the host spells each rung of the ladder — the seam that keeps this file free of language.
 * Deliberately not a duration formatter: the rungs, the dropped zero and the padded minute below
 * are this app's own decisions, and a formatter would re-decide them.
 */
interface DurationSymbols {
    val year: String
    val month: String
    val day: String
    val hour: String
    val minute: String
    val second: String

    /** What a track still being recorded reads as, where a finished one would show its length. */
    val recording: String
}

/** Plain ASCII, for tests and for any surface with no resources to reach. */
object AsciiDurations : DurationSymbols {
    override val year = "y"
    override val month = "mo"
    override val day = "d"
    override val hour = "h"
    override val minute = "m"
    override val second = "s"
    override val recording = "recording"
}

internal fun formatDuration(startedAt: Long, endedAt: Long?, symbols: DurationSymbols): String {
    val end = endedAt ?: return symbols.recording
    return formatDurationMs(end - startedAt, symbols)
}

/**
 * A duration at whatever scale it happens to be, in at most two units.
 *
 * The ladder climbs — h m, then d h, then mo d, then y mo — because each step *drops* the unit that
 * has stopped carrying information rather than adding one beside it. A place's cumulative total runs
 * to years, and at that size the hours are noise measured in hundredths of a percent: "1254d 11h"
 * spends its width on a figure that changes nothing and asks the reader to divide by 365 themselves.
 * Holding two units keeps the second one worth roughly a percent of the first, wherever the value
 * lands.
 */
/** A rung of the ladder: the smaller unit is dropped rather than shown as a zero. */
private fun twoUnits(big: Long, bigUnit: String, rest: Long, restUnit: String): String =
    if (rest == 0L) "$big$bigUnit" else "$big$bigUnit $rest$restUnit"

internal fun formatDurationMs(durationMs: Long, symbols: DurationSymbols): String {
    val minutes = (durationMs / 60000.0).roundToLong()
    if (minutes < MINUTES_PER_DAY) {
        return when {
            // Zero-padded, alone on the ladder: these line up in a column of stat cells.
            minutes >= 60 && minutes % 60 != 0L ->
                "%d${symbols.hour} %02d${symbols.minute}".format(minutes / 60, minutes % 60)
            minutes >= 60 -> "${minutes / 60}${symbols.hour}"
            else -> "$minutes${symbols.minute}"
        }
    }
    // A day or more: minutes stop mattering — round to whole hours and split off days.
    val hours = (minutes + 30) / 60
    val days = hours / 24
    if (days < DAYS_BEFORE_MONTHS) return twoUnits(days, symbols.day, hours % 24, symbols.hour)

    // A day remainder that has rounded up to a whole month is one, so a year's worth of days reads
    // "1y" rather than "11mo 30d" — two figures that together say a year while looking like less.
    var months = (days / AVG_DAYS_PER_MONTH).toLong()
    var restDays = (days - months * AVG_DAYS_PER_MONTH).roundToLong()
    if (restDays >= AVG_DAYS_PER_MONTH.roundToLong()) {
        months++
        restDays = 0
    }
    return if (months < MONTHS_PER_YEAR) {
        twoUnits(months, symbols.month, restDays, symbols.day)
    } else {
        twoUnits(months / MONTHS_PER_YEAR, symbols.year, months % MONTHS_PER_YEAR, symbols.month)
    }
}
