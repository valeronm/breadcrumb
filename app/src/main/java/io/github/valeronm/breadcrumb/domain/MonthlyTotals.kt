package io.github.valeronm.breadcrumb.domain

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** What one month came to — [TimelineTotals] over the rows that month holds. */
class MonthTotals(val month: YearMonth, val totals: TimelineTotals)

/**
 * One metric across a window of months — what it came to in each, in the window's order, so a value
 * and the month under it are matched by position and nothing has to re-date a bar.
 *
 * [values] is in the metric's own unit (meters for an activity, milliseconds for a category): the
 * question a window answers is the same shape in both, and only the words differ. Never empty — a
 * series is built from a window, and a window is never empty.
 *
 * Every derived figure is computed **once, here**, rather than on each read: [values] cannot change,
 * and the alternative is `total` being re-summed on every comparison of a sort and `peak` boxing a
 * `Double` on every access from a composable that recomposes per frame.
 */
class MonthlySeries<K>(
    val key: K,
    val values: List<Double>,
    /**
     * The shown month measured the other way — how long an activity took, how many visits a category
     * held. A single figure rather than a series: it is stated beside [latest] and never plotted,
     * two shapes on one strip being two questions on one axis.
     */
    val secondary: Double,
) {
    /** The month the window ends at — the one the page is reporting on. */
    val latest: Double = values.last()

    val total: Double = values.sum()

    /** What the bars are scaled against. Each series is scaled to **itself**: a walk must not
     *  vanish under a flight, the two being different questions that happen to share a screen. */
    val peak: Double = values.max()

    /**
     * [values] as shares of [peak], ready to draw — normalized here rather than at the drawing,
     * which is where an all-zero series would otherwise divide by nothing. Such a series never
     * reaches a screen ([MonthlyTotals.activitySeries] and its sibling drop it), and that is a fact
     * about those two functions rather than about this class, so it is answered here as well.
     */
    val fractions: List<Float> =
        if (peak > 0.0) values.map { (it / peak).toFloat() } else values.map { 0f }
}

/**
 * Which months the figures may report on, and where the reader may step from the one they are on.
 *
 * A value rather than four expressions inside the page, because it is one rule: the bounds, the
 * clamp holding a selection inside them, whether each arrow is live, and which month a tapped bar
 * means are the same question asked four ways, and spelled separately they drift apart under edit.
 * Stated here they are also total over their own cases on a plain JVM, where composed they were
 * reachable only by moving the device's clock.
 */
class MonthReach(
    val first: YearMonth,
    val last: YearMonth,
    /** The month to report on: what was selected, held inside [first]..[last]. */
    val shown: YearMonth,
) {
    val canStepBack: Boolean get() = shown > first
    val canStepForward: Boolean get() = shown < last

    /**
     * The month [months] from [shown], or **null where the page may not go there** — which is what
     * makes this one answer serve both an arrow and a tapped bar. A tap outside the history does
     * nothing rather than landing on the nearest month it could: a tap that moves somewhere other
     * than where it was aimed is worse than one that misses.
     */
    fun stepped(months: Long): YearMonth? =
        shown.plusMonths(months).takeIf { it in first..last }

    companion object {
        /**
         * The reach over a history's [months], which **must not be empty** — a page with nothing to
         * report on has no month to be on either, and says so instead of drawing a selector.
         *
         * [nowMonth] is the far end even where the history stops short of it, so a page opens on the
         * present; and the history is the far end where it runs *past* the present — an imported
         * plan, a phone whose date was wrong — so nothing is stranded beyond the last arrow.
         */
        fun of(months: List<MonthTotals>, nowMonth: YearMonth, selected: YearMonth): MonthReach {
            val first = months.first().month
            val last = maxOf(months.last().month, nowMonth)
            return MonthReach(first, last, selected.coerceIn(first, last))
        }
    }
}

/**
 * A month's figures, summed over the **timeline's own rows** — the same rows the Timeline draws,
 * filed under the month each is filed under. That is what keeps the two surfaces from disagreeing
 * about which side of a boundary a night abroad fell on: the rows arrive already cut at midnight on
 * the clock they were lived in ([StayDeriver.slicePerDay]), so no interval straddles a month either.
 */
object MonthlyTotals {

    /** How many months a page compares at once — a year, so a season is read against its own. */
    const val WINDOW_MONTHS = 12

    /**
     * Every month the history holds something in, oldest first. Months with nothing recorded are
     * absent here and filled in by [window]; a caller wanting the shape of a stretch of time asks
     * for a window, and one wanting the bounds of the history asks for the ends of this.
     *
     * What each month comes to is [TimelineTotalsBuilder]'s answer, the same one a day header reads
     * — this only decides which bucket a row falls in. The one thing the two scales differ on is the
     * floor [dayCategoryTotals] applies and this does not: that floor keeps a day's line legible,
     * and a month is the scale at which a handful of short errands is exactly the thing worth
     * seeing.
     *
     * [deviceZone] dates a row nothing placed — the same fallback the Timeline reads rows on.
     */
    fun derive(items: List<TimelineItem>, nowMs: Long, deviceZone: ZoneId): List<MonthTotals> {
        val byMonth = HashMap<YearMonth, TimelineTotalsBuilder>()

        // Asked only where the answer is used: a gap contributes nothing, and resolving a zone and a
        // calendar date is the whole per-row cost of a walk over the entire history.
        fun builderFor(item: TimelineItem): TimelineTotalsBuilder {
            val at = Instant.ofEpochMilli(item.filedAt).atZone(item.zone ?: deviceZone)
            return byMonth.getOrPut(YearMonth.from(at)) { TimelineTotalsBuilder() }
        }
        for (item in items) builderFor(item).add(item, nowMs)
        // A month whose only rows were stays nothing can be attributed to — untagged, or a category
        // out of the totals — holds no figures, and a page that listed it would offer a reader a
        // month to step to and then tell them it was empty. Dropped before building rather than
        // after, so an empty month costs no value object.
        return byMonth.keys.sorted()
            .filter { !byMonth.getValue(it).isEmpty }
            .map { MonthTotals(it, byMonth.getValue(it).build()) }
    }

    /**
     * [WINDOW_MONTHS] months ending at [endingAt], oldest first. A month nothing was recorded in is
     * present and empty rather than absent: an empty month is a fact about that month, and dropping
     * it would slide its neighbours together and misdate every bar drawn from the result.
     */
    fun window(months: List<MonthTotals>, endingAt: YearMonth): List<MonthTotals> {
        val byMonth = months.associateBy { it.month }
        return (WINDOW_MONTHS - 1 downTo 0).map { back ->
            val month = endingAt.minusMonths(back.toLong())
            byMonth[month] ?: MonthTotals(month, TimelineTotals.EMPTY)
        }
    }

    /** Distance per activity over [window], with the time it took beside it — a series per stored
     *  activity code, the biggest first. */
    fun activitySeries(window: List<MonthTotals>): List<MonthlySeries<String>> =
        seriesOf(window, { it.activities }, { it.meters }, { it.durationMs.toDouble() })

    /** Time per place category over [window], with the number of visits beside it. */
    fun categorySeries(window: List<MonthTotals>): List<MonthlySeries<PlaceCategory>> =
        seriesOf(window, { it.categories }, { it.durationMs.toDouble() }, { it.visits.toDouble() })

    /**
     * A series per key that **any** month in [window] holds something for, biggest total first.
     *
     * The shown month does not decide which rows exist, and that is the point of a window: a month
     * with no cycling in it is a fact about the year's cycling, and a row that vanished would leave
     * the reader unable to tell "none this month" from "never". Ranking by the window's total rather
     * than by the shown month's figure follows from the same thing — it holds the rows still as the
     * reader steps months, so what changes between two months is the bars and not the order.
     *
     * [secondaryOf] is read off the **last month alone**, that being the only one whose second
     * figure is ever stated. Both figures are pulled straight out of each month's own map: a series
     * per key over a window of months, so re-projecting every month into a map of one field first
     * would build a dozen throwaway maps for values read once each.
     */
    private fun <K, V> seriesOf(
        window: List<MonthTotals>,
        mapOf: (TimelineTotals) -> Map<K, V>,
        valueOf: (V) -> Double,
        secondaryOf: (V) -> Double,
    ): List<MonthlySeries<K>> {
        val perMonth = window.map { mapOf(it.totals) }
        val secondary = perMonth.last()
        // Insertion order over the window, so equal totals come out in a stable order rather than
        // whatever the hash tables above happened to hold.
        val keys = perMonth.flatMapTo(LinkedHashSet()) { it.keys }
        return keys.map { key ->
            MonthlySeries(
                key = key,
                values = perMonth.map { month -> month[key]?.let(valueOf) ?: 0.0 },
                secondary = secondary[key]?.let(secondaryOf) ?: 0.0,
            )
        }
            .filter { it.total > 0.0 }
            .sortedByDescending { it.total }
    }
}
