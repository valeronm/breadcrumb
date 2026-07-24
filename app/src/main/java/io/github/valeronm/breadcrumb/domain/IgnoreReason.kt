package io.github.valeronm.breadcrumb.domain

/**
 * Why a fix was flagged ignored. [code] is the stable string stored in the DB (and null for
 * points recorded before reasons were tracked).
 *
 * The first three are the recorder's bad-fix rule (`TrackQuality.badFixReason`). [EDGE_STAY] is
 * the odd one out and deliberately shares the mechanism: the fix is fine, it just isn't part of
 * the journey.
 */
enum class IgnoreReason(val code: String) {
    /** Accuracy radius at or beyond the configured gate. */
    ACCURACY("accuracy"),

    /** Reaching the fix from the last good point would need an implausible speed (GPS teleport). */
    JUMP("jump"),

    /** No recent satellite fix backing it (provider fabrication — tunnel dead-reckoning etc.). */
    NO_GNSS("no_gnss"),

    /**
     * Recorded at a track's edge after the user had already arrived (or before they truly left):
     * Activity Recognition lagged the stop and the recorder ran on. Not a quality rejection — a
     * good fix of somewhere the journey isn't. Applied by [EdgeStayIgnore] once a track is
     * finished, and re-derived whenever the rule moves.
     */
    EDGE_STAY("edge_stay"),
    ;

    companion object {
        fun fromCode(code: String?): IgnoreReason? = entries.firstOrNull { it.code == code }
    }
}
