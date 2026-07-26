package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackPoint

/**
 * Decides where a user's manual cut lands and whether it is allowed — the counterpart to
 * [TrackMerge] on the other side of the same question. The recorder breaks a journey when Activity
 * Recognition tells it to; this is how the user breaks one it ran straight through, by picking the
 * fix the second track should start at. Pure; the track screen previews the plan and the repository
 * performs it.
 *
 * One rule, two callers, because they must agree: the screen grays the scissors on a cut this
 * refuses, and the same refusal in the repository is what makes an enabled button that shouldn't be
 * impossible rather than merely unlikely. The boundary timestamps matter for the same reason — a
 * dialog promising an end time the write doesn't produce is a bug the user can see on the timeline.
 */
object TrackSplit {

    /**
     * Both halves must keep enough good fixes to be a line of their own — otherwise the cut leaves
     * behind a track no screen can draw. This is the *only* floor a split enforces: the keep
     * thresholds are deliberately not applied, since the user chose the cut (see
     * `TrackRepository.splitTrack`).
     */
    fun isLegalCut(firstGoodPoints: Int, secondGoodPoints: Int): Boolean =
        firstGoodPoints >= KeepRule.MIN_LINE_POINTS && secondGoodPoints >= KeepRule.MIN_LINE_POINTS

    /** The two tracks a cut at [cutTs] would leave behind. Counts are good fixes, as track stats count them. */
    data class Plan(
        /** Every fix before this instant stays with the first track; the rest move to the second. */
        val cutTs: Long,
        /**
         * The halves' inner bounds — the outermost fix each keeps, ignored fixes included, because
         * that is the raw extent the overrun rule then pulls in ([EdgeStayIgnore]). Taking the last
         * *good* fix instead would report an end the stored row disagrees with whenever a rejected
         * fix sits against the cut.
         */
        val firstEndTs: Long,
        val secondStartTs: Long,
        val firstGoodPoints: Int,
        val secondGoodPoints: Int,
    )

    /**
     * [points] is a track's fixes, good and ignored alike; order doesn't matter — every value here
     * is a count or an extreme, so a caller holding its points in separate lists can concatenate
     * them without sorting. Null when [isLegalCut] refuses the cut, in which case nothing about the
     * halves is worth reporting.
     */
    fun plan(points: List<TrackPoint>, cutTs: Long): Plan? {
        var firstGood = 0
        var secondGood = 0
        var firstEnd = Long.MIN_VALUE
        var secondStart = Long.MAX_VALUE
        for (p in points) {
            if (p.timestamp < cutTs) {
                if (!p.ignored) firstGood++
                if (p.timestamp > firstEnd) firstEnd = p.timestamp
            } else {
                if (!p.ignored) secondGood++
                if (p.timestamp < secondStart) secondStart = p.timestamp
            }
        }
        if (!isLegalCut(firstGood, secondGood)) return null
        return Plan(
            cutTs = cutTs,
            firstEndTs = firstEnd,
            secondStartTs = secondStart,
            firstGoodPoints = firstGood,
            secondGoodPoints = secondGood,
        )
    }
}
