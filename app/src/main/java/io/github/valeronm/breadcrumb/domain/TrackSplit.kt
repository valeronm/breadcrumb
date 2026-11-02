package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackPoint

/**
 * Decides where a user's manual cut divides a track — [TrackMerge]'s counterpart: the recorder
 * breaks a journey when Activity Recognition tells it to; this is how the user breaks one it ran
 * straight through, by picking the fix the second track should start at. Pure; the repository's
 * preview and its write both cut through [plan], because a dialog promising a half the write
 * doesn't produce is a bug the user can see on the timeline.
 */
object TrackSplit {

    /** Where a cut at [cutTs] divides a track's fixes. */
    data class Plan(
        /** Every fix before this instant stays with the first track; the rest move to the second. */
        val cutTs: Long,
        /**
         * The halves' inner bounds — the outermost fix each keeps, ignored fixes included: that is
         * the raw extent the overrun rule then pulls in ([TrackSettle]); the last *good* fix
         * would report an end the stored row disagrees with when a rejected fix sits against the cut.
         */
        val firstEndTs: Long,
        val secondStartTs: Long,
    )

    /**
     * [points] is a track's fixes, good and ignored alike, in any order — every value here is an
     * extreme. Null when either side of [cutTs] holds no fix, which leaves nothing to cut off.
     */
    fun plan(points: List<TrackPoint>, cutTs: Long): Plan? {
        var firstEnd: Long? = null
        var secondStart: Long? = null
        for (p in points) {
            if (p.timestamp < cutTs) {
                if (firstEnd == null || p.timestamp > firstEnd) firstEnd = p.timestamp
            } else {
                if (secondStart == null || p.timestamp < secondStart) secondStart = p.timestamp
            }
        }
        return Plan(cutTs, firstEnd ?: return null, secondStart ?: return null)
    }

    /** The overrun rule trims each half's new inner edge at a stop, and runs only for some writers
     *  ([EdgeStayDetector.runsFor]). */
    fun cuttable(source: String?): Boolean = EdgeStayDetector.runsFor(source)
}
