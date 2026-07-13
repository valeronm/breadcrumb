package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackSummary

/**
 * Decides whether a short stay between two tracks can be closed by merging them — the fix for a
 * continuous outing that got split (e.g. a walk broken by a brief run misdetection whose track was
 * then discarded, leaving a 1-min stay between two walk tracks). Pure; the UI offers the merge on
 * a swipe and the repository performs it.
 */
object TrackMerge {

    /** A stay this short (or shorter) between same-activity tracks is a candidate for merging. */
    const val MAX_STAY_MS = 5 * 60_000L

    /** Merge the two tracks bracketing the stay into a new track; [earlierId] precedes [laterId]. */
    data class Plan(val earlierId: Long, val laterId: Long)

    /**
     * A plan to merge across the stay between [before] (ends into the stay) and [after] (starts out
     * of it), or null if the stay is too long, still ongoing, on a named place ([stayIsNamedPlace]
     * — merging would delete a real visit), or the two tracks aren't the same activity.
     */
    fun plan(
        before: TrackSummary,
        after: TrackSummary,
        stayStart: Long,
        stayEnd: Long?,
        stayIsNamedPlace: Boolean = false,
    ): Plan? {
        if (stayIsNamedPlace) return null
        if (stayEnd == null || stayEnd - stayStart > MAX_STAY_MS) return null
        if (before.activityType != after.activityType) return null
        return Plan(earlierId = before.id, laterId = after.id)
    }
}
