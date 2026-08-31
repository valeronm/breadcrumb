package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.Track

/**
 * Whether movement returning after a stop belongs to the track that just ended, or to a new one.
 *
 * The recorder keeps nothing across a stop — a stop is a close, and the track reaches the timeline
 * with its stats and stays derived — so this is asked of the stored history rather than of anything
 * held in memory. That is also why it needs no companion on the recorder side: after a process death
 * the same question has the same answer.
 *
 * The caller hands the newest row **by id**, which is the row the recorder wrote last. By time it
 * would hand the wrong one after a merge, which stamps the merged row with the earlier half's
 * `startedAt` and so sorts it behind the discarded later original.
 *
 * Pure and Android-free.
 */
object StitchRule {

    /**
     * The label to record under if [last] should take the fixes of a stretch opening as [opening] at
     * [nowMs], or null to open a track of its own. It is the *stored* label rather than [opening],
     * which is what the fix ceilings and the finish's rename verdict have to read: a carrier-proven
     * foot track finishes as UNKNOWN, and its fixes were kept under that ceiling.
     *
     * [lastPointMs] is the timestamp of [last]'s newest point, ignored ones included.
     * **Timed from that, never from `endedAt`**: [TrackBounds] pulls `endedAt` back to the last good
     * fix, so on a track whose overrun was trimmed it sits minutes before the recorder actually
     * stopped — `endedAt` says when the journey ended, and this asks how long ago the recording did.
     */
    fun continuedLabel(
        last: Track,
        lastPointMs: Long?,
        opening: ActivityType,
        nowMs: Long,
        windowMs: Long,
    ): ActivityType? {
        // Still open: a row the recorder never closed is one it is already recording into, or one a
        // crash left behind for finalizeDangling.
        if (last.endedAt == null) return null
        if (TrackOrigin.fromCode(last.source) != TrackOrigin.RECORDED) return null
        // A track filed by the keep thresholds is exactly what a return may yet make long enough; a
        // discard the *user* made is not, and neither is one whose reason no longer reads (see
        // Track.discardReason), since nothing then says whose decision it was.
        if (last.discardedAt != null && last.discardReason != Track.REASON_FILTERED) return null
        val label = ActivityType.ofName(last.activityType) ?: return null
        if (!label.sharesTrackWith(opening)) return null
        // A track with no points at all has nothing to date the window from.
        val lastPoint = lastPointMs ?: return null
        // Strictly greater, so a zero window continues nothing.
        if (lastPoint + windowMs <= nowMs) return null
        return label
    }
}
