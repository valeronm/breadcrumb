package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackSummary

/** A stay the user hid, between the track [beforeId] and the track [afterId]. */
data class HiddenStay(val beforeId: Long, val afterId: Long)

object HiddenStays {
    /** Every hidden stay among [summaries], which run newest first, keyed by the track before it. */
    fun byEarlierTrack(summaries: List<TrackSummary>): Map<Long, HiddenStay> = buildMap {
        for (i in 0 until summaries.lastIndex) {
            val after = summaries[i]
            val before = summaries[i + 1]
            if (StayDeriver.hidden(beforeHidesAfter = before.hidesStayAfter, afterHidesBefore = after.hidesStayBefore)) {
                put(before.id, HiddenStay(before.id, after.id))
            }
        }
    }
}
