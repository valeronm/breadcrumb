package io.github.valeronm.breadcrumb.location.core

import io.github.valeronm.breadcrumb.domain.recording.CarrierEvidence

/**
 * The step counter's running totals at the elapsed-realtime moments it reported them, so a fix
 * buffered behind others reads the total as of its own time rather than its delivery's.
 *
 * The counter reports only when the total changes, so a reading stands until the next one. Readings
 * more than [RETENTION_MS] older than the newest are dropped, except the newest of them, which still
 * answers for the time up to its successor.
 */
class StepHistory private constructor(private val readings: List<Reading>) {

    private class Reading(val atMs: Long, val total: Long)

    /** The total as of [elapsedMs], or null where no reading at or before it is kept. */
    fun totalAt(elapsedMs: Long): Long? = readings.lastOrNull { it.atMs <= elapsedMs }?.total

    /**
     * The steps counted over the [CarrierEvidence.STEP_WINDOW_MS] up to [endMs], or null where no
     * reading at or before the window's start is kept or the counter restarted inside it.
     */
    fun countedInWindowTo(endMs: Long): Long? {
        val first = readings.indexOfLast { it.atMs <= endMs - CarrierEvidence.STEP_WINDOW_MS }
        if (first < 0) return null
        val last = readings.indexOfLast { it.atMs <= endMs }
        val inWindow = readings.subList(first, last + 1)
        if (inWindow.zipWithNext().any { (a, b) -> b.total < a.total }) return null
        return inWindow.last().total - inWindow.first().total
    }

    /** This history with a reading at [atMs] added; one older than the newest is refused. */
    fun with(atMs: Long, total: Long): StepHistory {
        val newest = readings.lastOrNull()
        if (newest != null && atMs < newest.atMs) return this
        val cutoff = atMs - RETENTION_MS
        val anchor = readings.indexOfLast { it.atMs <= cutoff }
        return StepHistory(readings.subList(maxOf(anchor, 0), readings.size) + Reading(atMs, total))
    }

    companion object {
        val EMPTY = StepHistory(emptyList())

        /** A buffered fix can be delivered long after it is stamped, since the location request sets no batching limit. */
        private const val DELIVERY_DELAY_MS = 570_000L

        private const val RETENTION_MS = CarrierEvidence.STEP_WINDOW_MS + DELIVERY_DELAY_MS
    }
}
