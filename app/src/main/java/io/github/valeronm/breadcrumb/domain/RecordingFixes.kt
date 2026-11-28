package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackPoint

/**
 * A recording track's usable fixes, read a batch of stored rows at a time and reduced by the rule
 * [SegmentBreaks.goodWithCarriedBreaks] applies to a whole track: a break left on ignored fixes at one batch's end
 * passes to the next batch's first good fix.
 */
internal class RecordingFixes private constructor(
    val good: List<TrackPoint>,
    private val last: TrackPoint?,
    private val pending: Boolean,
) {
    /** The newest stored row read, ignored or not; null before any. */
    val lastId: Long? get() = last?.id

    /**
     * Null where [rows] reach back before the rows already read, which only a full read places in order. [good] is the
     * same list where [rows] hold no usable fix.
     */
    fun plus(rows: List<TrackPoint>): RecordingFixes? {
        if (rows.isEmpty()) return this
        if (last != null && rows.first().timestamp < last.timestamp) return null
        val carried = SegmentBreaks.carryBreaks(rows, pending)
        return RecordingFixes(
            good = if (carried.good.isEmpty()) good else good + carried.good,
            last = rows.last(),
            pending = carried.pending,
        )
    }

    companion object {
        /** [rows] are every stored row of the track, in time order. */
        fun of(rows: List<TrackPoint>): RecordingFixes = RecordingFixes(emptyList(), last = null, pending = false).plus(rows)!!
    }
}
