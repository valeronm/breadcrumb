package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.domain.StayDeriver

/**
 * Which clock times a stay row states — the decision on its own, carrying none of the wording that
 * renders it, in the shape [StayCard] can render with one exhaustive `when`.
 *
 * Apart from the card so every case can be asked on a plain JVM: composed, a branch is reachable
 * only by building a row, and the ones the day boundary produces need a stay the slicer has cut.
 */
internal sealed interface StayBounds {

    /** Cut at both ends, or cut at its start and still running — a day this stay covers entirely. */
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
     * Whether the row may also state how long the stay lasted. False for every case a cut produced:
     * a duration there both restates the clock time and understates the stay, whose real length runs
     * on past the boundary the day drew through it. Written as the one split rather than as
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
 * [start] and [end] as the bounds a row states — a null [end] being a stay still in progress, and
 * [holdsStart]/[holdsEnd] saying which of the two are the stay's own rather than boundaries the day
 * slicing put there. Those are read, never worked out here: [StayDeriver.Slice.holdsStart] stamps
 * them at the cut and says why nothing downstream may decide it from a timestamp.
 */
internal fun stayBounds(start: Long, end: Long?, holdsStart: Boolean, holdsEnd: Boolean): StayBounds = when {
    // Cut at both ends, or cut at the start and still running: neither states a clock time.
    !holdsStart && (end == null || !holdsEnd) -> StayBounds.AllDay
    end == null -> StayBounds.Since(start)
    !holdsStart -> StayBounds.Until(end)
    !holdsEnd -> StayBounds.From(start)
    // Compared as minutes rather than as rendered text: the same question, without formatting
    // both ends again to ask it.
    end / 60_000 == start / 60_000 -> StayBounds.At(start)
    else -> StayBounds.Between(start, end)
}
