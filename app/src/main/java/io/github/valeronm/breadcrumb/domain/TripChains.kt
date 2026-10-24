package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackSummary

/**
 * One trip as the reader sees it: tracks [StayDeriver.joined] end to start and of one type — a start
 * or an end entered by hand around the trip it belongs to — oldest first. Most trips are one track.
 */
data class Trip(val parts: List<TrackSummary>) {
    constructor(track: TrackSummary) : this(listOf(track))

    init {
        require(parts.isNotEmpty()) { "a trip has a track" }
    }

    /** The part the trip is *of*: the first one not entered by hand, or the first when all were. */
    val main: TrackSummary = parts.firstOrNull { TrackOrigin.fromCode(it.source) != TrackOrigin.MANUAL } ?: parts.first()

    val startedAt: Long get() = parts.first().startedAt

    /** Null while the newest part is still open. */
    val endedAt: Long? get() = parts.last().endedAt

    val distanceMeters: Double = parts.sumOf { it.distanceMeters }
}

object TripChains {

    /** [summaries] newest first, as the timeline holds them; the trips newest first. */
    fun of(summaries: List<TrackSummary>): List<Trip> {
        val out = ArrayList<Trip>(summaries.size)
        // Walked oldest first, so a part joins the trip the track before it ended.
        val oldestFirst = summaries.asReversed()
        var from = 0
        for (i in 1..oldestFirst.size) {
            if (i == oldestFirst.size || !continues(oldestFirst[i - 1], oldestFirst[i])) {
                out += Trip(oldestFirst.subList(from, i).toList())
                from = i
            }
        }
        out.reverse()
        return out
    }

    /** The trip [trackId] is the [Trip.main] part of, out of [summaries] newest first; null where it is not one. */
    fun tripOf(summaries: List<TrackSummary>, trackId: Long): Trip? =
        containing(summaries, trackId)?.takeIf { it.main.id == trackId }

    /** The trip [trackId] is any part of, out of [summaries] newest first; null where it is not among them. */
    fun containing(summaries: List<TrackSummary>, trackId: Long): Trip? {
        val at = summaries.indexOfFirst { it.id == trackId }
        if (at < 0) return null
        var newest = at
        while (newest > 0 && continues(summaries[newest], summaries[newest - 1])) newest--
        var oldest = at
        while (oldest < summaries.lastIndex && continues(summaries[oldest + 1], summaries[oldest])) oldest++
        return Trip(summaries.subList(newest, oldest + 1).asReversed().toList())
    }

    private fun continues(before: TrackSummary, after: TrackSummary) =
        StayDeriver.joined(before.endedAt, after.startedAt) && before.activityType == after.activityType
}
