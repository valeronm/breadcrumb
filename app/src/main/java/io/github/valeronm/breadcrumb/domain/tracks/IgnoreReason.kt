package io.github.valeronm.breadcrumb.domain.tracks

/**
 * Why a fix was flagged ignored. [code] is the stable DB string (null for points from before
 * reasons were tracked). The rejections are the recorder's bad-fix rule (`TrackQuality.badFixReason`);
 * the rest deliberately share the mechanism — the fix is fine, just not part of the journey's path.
 */
enum class IgnoreReason(
    val code: String,
    /** A bad fix, as opposed to a good one that is simply off the journey's path. */
    val isRejection: Boolean,
) {
    /** Accuracy radius at or beyond the configured gate. */
    ACCURACY("accuracy", isRejection = true),

    /** Reaching the fix from the last good point would need an implausible speed (GPS teleport). */
    JUMP("jump", isRejection = true),

    /** No recent satellite fix backing it (provider fabrication — tunnel dead-reckoning etc.). */
    NO_GNSS("no_gnss", isRejection = true),

    /**
     * Recorded at a track's edge after arrival (or before true departure): Activity Recognition
     * lagged the stop and the recorder ran on. Not a quality rejection — a good fix of somewhere
     * the journey isn't; [TrackSettle] applies it and re-derives it whenever the rule moves.
     */
    EDGE_STAY("edge_stay", isRejection = false),

    /**
     * Inside a stop, from builds that thinned stops. Nothing writes it, and [TrackSettle] hands the
     * flag back on a track restored from an older backup.
     */
    STATIONARY("stationary", isRejection = false),
    ;

    companion object {
        fun fromCode(code: String?): IgnoreReason? = entries.firstOrNull { it.code == code }
    }
}
