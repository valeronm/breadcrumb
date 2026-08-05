package io.github.valeronm.breadcrumb.ui

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Which clock times a stay row states — the decision on its own, carrying none of the wording that
 * renders it, in the shape [StayCard] can render with one exhaustive `when`.
 *
 * Apart from the card so every case can be asked on a plain JVM. Inside it each branch was reachable
 * only by composing a row, and the two the day boundary produces needed a stay crossing midnight in
 * the row's own zone to reach at all — so the cases most likely to be got wrong were the ones a test
 * was least likely to cover.
 */
internal sealed interface StayBounds {

    /** Midnight to midnight, or midnight until now — a day this stay covers entirely. */
    data object AllDay : StayBounds

    /** Still going, so stated from its start to now. */
    data class Since(val start: Long) : StayBounds

    /** Began before the day did; only its end is this day's to state. */
    data class Until(val end: Long) : StayBounds

    /** Runs on past the day's end; only its start is. */
    data class From(val start: Long) : StayBounds

    /**
     * Both bounds land on one clock minute — a stop the recorder caught only the tail end of. Stated
     * once, because "09:11 – 09:11" reads as a rendering fault rather than as a moment.
     */
    data class At(val moment: Long) : StayBounds

    /** A start and an end, both the day's own. */
    data class Between(val start: Long, val end: Long) : StayBounds

    /**
     * Whether the row may also state how long the stay lasted. False for every case a midnight slice
     * produced: a duration there both restates the clock time and understates the stay, whose real
     * length runs on past the seam the day drew through it. Written as the one split rather than as
     * six declarations, so which side a case falls on is read at a glance — and still exhaustive, so
     * a case added later is a compile error here rather than a silent `false`.
     */
    val withDuration: Boolean
        get() = when (this) {
            AllDay, is Until, is From -> false
            is Since, is At, is Between -> true
        }
}

/**
 * [start] and [end] as the bounds a row states, on the [zone] that row is read in — a null [end]
 * being a stay still in progress. The zone must be the one the day slicing used, which is the row's
 * own rather than the reader's: a stay abroad ends its day when that country's day ended.
 */
internal fun stayBounds(start: Long, end: Long?, zone: ZoneId): StayBounds {
    val startsAtMidnight = isLocalMidnight(start, zone)
    val endsAtMidnight = end != null && isLocalMidnight(end, zone)
    return when {
        // Ongoing from midnight is all of today so far; a completed midnight-to-midnight slice of a
        // longer stay reads the same, and neither states a clock time.
        startsAtMidnight && (end == null || endsAtMidnight) -> StayBounds.AllDay
        end == null -> StayBounds.Since(start)
        startsAtMidnight -> StayBounds.Until(end)
        endsAtMidnight -> StayBounds.From(start)
        // Compared as minutes rather than as rendered text: the same question, without formatting
        // both ends again to ask it.
        end / 60_000 == start / 60_000 -> StayBounds.At(start)
        else -> StayBounds.Between(start, end)
    }
}

/**
 * Whether [epochMs] lands on a day boundary in [zone] — a bound the day slicing put there, rather
 * than a time anything happened at. See [stayBounds] for which zone that has to be.
 *
 * **Two rows read this, and only one of them has its answer extracted.** The gap row asks the same
 * question of its own two ends and then decides the same four ways inline, where its verdict reaches
 * further than a phrase: it also picks which ends the add-trip form is handed. That decision belongs
 * beside [stayBounds] and is not here yet, which is why this predicate is in a file named for the
 * timeline's bounds rather than for the stay row's.
 *
 * Both are recoveries: `StayDeriver` is the code that cut the day and the only one that knows where,
 * and a genuine stay beginning at 00:00 is not distinguishable here from a seam. Stamping the fact
 * at the cut would retire this function outright.
 */
internal fun isLocalMidnight(epochMs: Long, zone: ZoneId): Boolean =
    Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime() == LocalTime.MIDNIGHT
