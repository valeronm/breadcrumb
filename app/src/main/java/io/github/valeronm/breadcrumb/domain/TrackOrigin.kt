package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackPoint

/**
 * Who wrote a track's fixes. [code] is the stable string stored on the track row; it is **declared
 * by whoever inserts the row**, because provenance is known at that moment and is not a property of
 * the fixes that can be measured later.
 *
 * Unknown is a first-class state rather than a third entry, following untagged places and
 * reason-less ignored points: null means no writer is named — a row from before the column, or a
 * code this build doesn't know, which reads as unknown and survives a backup round trip instead of
 * being rewritten to something this build prefers.
 */
enum class TrackOrigin(val code: String, val measuresFixQuality: Boolean) {
    /** The recorder's own fixes, each with what the receiver said about its own measurement. */
    RECORDED("recorded", measuresFixQuality = true),

    /** Parsed from a GPX file the user shared into the app: a path, not a measurement of one. */
    IMPORTED("imported", measuresFixQuality = false),
    ;

    companion object {
        fun fromCode(code: String?): TrackOrigin? = entries.firstOrNull { it.code == code }

        /**
         * The writer reconstructed from the fixes, for rows that never got to declare one — points
         * written before the column existed, and backup files made before it did. **Not a substitute
         * for the declaration**: it reads a side effect rather than a fact, so it is wrong wherever
         * the side effect and the writer come apart.
         *
         * The side effect is the accuracy radius: the recorder stores the platform's on every fix,
         * and a GPX file carries none to store. Deliberately "any point has one" rather than "all
         * do" — the platform may withhold a radius on a fix ([TrackPoint.accuracy] is nullable for
         * that reason), so a single one is proof of the recorder while a missing one proves nothing.
         *
         * What it cannot see: our own GPX export re-imported reads [IMPORTED], which is at least
         * honest — those rows no longer hold what the recorder measured — and a track holding both
         * writers' fixes reads [RECORDED], being indistinguishable from a recording whose platform
         * withheld some radii. Null when there are no points at all: no evidence, so no answer.
         */
        fun inferFrom(points: List<TrackPoint>): TrackOrigin? = when {
            points.isEmpty() -> null
            points.any { it.accuracy != null } -> RECORDED
            else -> IMPORTED
        }
    }
}
