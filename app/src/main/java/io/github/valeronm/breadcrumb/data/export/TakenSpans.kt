package io.github.valeronm.breadcrumb.data.export

import io.github.valeronm.breadcrumb.data.db.TrackSpan

/**
 * [io.github.valeronm.breadcrumb.data.db.TrackDao.countTracksOverlapping]'s rule held in memory, since
 * that query scans the history once per trip. A track's bounds are its first and last good fix, which
 * is what the query asks of its points, and a track still recording runs to the end of time.
 */
internal class TakenSpans(spans: List<TrackSpan>) {

    private val starts: LongArray
    private val endsSoFar: LongArray

    init {
        val sorted = spans.sortedBy { it.startedAt }
        starts = LongArray(sorted.size) { sorted[it].startedAt }
        endsSoFar = LongArray(sorted.size)
        var reach = Long.MIN_VALUE
        sorted.forEachIndexed { i, span ->
            reach = maxOf(reach, span.endedAt ?: Long.MAX_VALUE)
            endsSoFar[i] = reach
        }
    }

    /** Both ends compare strictly: a trip merely touching a track at one instant does not overlap it. */
    fun overlaps(startMs: Long, endMs: Long): Boolean {
        var lo = 0
        var hi = starts.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] < endMs) lo = mid + 1 else hi = mid
        }
        return lo > 0 && endsSoFar[lo - 1] > startMs
    }
}
