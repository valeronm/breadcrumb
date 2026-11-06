package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackPoint

/** How a writer's fixes got their times. */
enum class FixTiming {
    /** Each fix carries the time its position was taken, which a GPX file does even with no speed field. */
    OBSERVED,

    /** Fixes carry their writer's times, some restamped to the minute. */
    RESTAMPED,

    /** The app spread the times between two typed ends at one constant speed. */
    SPREAD,
}

/**
 * Who wrote a track's fixes. [code] is the stable string stored on the track row; it is **declared
 * by whoever inserts the row**, because provenance is known at that moment and is not a property of
 * the fixes that can be measured later.
 *
 * Unknown is a first-class state rather than one more entry, following untagged places and
 * reason-less ignored points: null means no writer is named — a row from before the column, or a
 * code this build doesn't know, which reads as unknown and survives a backup round trip instead of
 * being rewritten to something this build prefers.
 */
enum class TrackOrigin(
    val code: String,
    val measuresFixQuality: Boolean,
    val timing: FixTiming,
    /**
     * Whether the writer ended each trip at its stop itself, which is what the overrun rule reads.
     * A flag of its own beside [measuresMotion] though the two coincide on every writer: one asks
     * whether speed metrics apply and the other whether the overrun rule runs, and a writer's
     * reason for the second — Google ending a trip at the visit, a typed end being the stop — is
     * no fact about its motion.
     */
    val endsAtStops: Boolean,
    /**
     * Whether the writer's altitudes are known to be heights above the WGS84 ellipsoid, as the
     * platform reports a fix's. False is no claim of sea level: a GPX file states no datum for its
     * elevations.
     */
    val altitudeAboveEllipsoid: Boolean,
) {
    /** The recorder's own fixes, each with what the receiver said about its own measurement. */
    RECORDED(
        "recorded",
        measuresFixQuality = true,
        timing = FixTiming.OBSERVED,
        endsAtStops = false,
        altitudeAboveEllipsoid = true,
    ),

    /** Parsed from a GPX file the user shared into the app: a path, not a measurement of one. */
    IMPORTED(
        "imported",
        measuresFixQuality = false,
        timing = FixTiming.OBSERVED,
        endsAtStops = false,
        altitudeAboveEllipsoid = false,
    ),

    /** Two endpoints the user typed into the add-trip form, with a router's path between them
     *  where they asked for one — a leg nothing recorded, held as a track so the timeline and
     *  journeys read it like any other. The typed ends are the trip's stops. */
    MANUAL(
        "manual",
        measuresFixQuality = false,
        timing = FixTiming.SPREAD,
        endsAtStops = true,
        altitudeAboveEllipsoid = false,
    ),

    /** Read from a Google Timeline export: the phone's own fixes over the export's last month, and
     *  before it Google's reconstruction, whose samples are restamped to the minute and so imply
     *  speeds the trip never had. */
    GOOGLE_TIMELINE(
        "google_timeline",
        measuresFixQuality = false,
        timing = FixTiming.RESTAMPED,
        // Google ends a trip at the instant its visit begins.
        endsAtStops = true,
        altitudeAboveEllipsoid = false,
    ),
    ;

    /** Whether the fixes' motion was observed: motion implied by spread or restamped times is an
     *  assumption no metric should be drawn from. */
    val measuresMotion: Boolean get() = timing == FixTiming.OBSERVED

    companion object {
        fun fromCode(code: String?): TrackOrigin? = entries.firstOrNull { it.code == code }

        /** An unknown or absent [code] reads as measured. */
        fun impliesMotion(code: String?): Boolean = fromCode(code)?.measuresMotion == false

        /**
         * The writer reconstructed from the fixes, for rows that never got to declare one — which
         * is a backup file made before the column existed. **Not a substitute for the declaration**:
         * it reads a side effect rather than a fact, so it is wrong wherever the side effect and the
         * writer come apart.
         *
         * The side effect is the accuracy radius: the recorder stores the platform's on every fix,
         * and a GPX file carries none to store. Deliberately "any point has one" rather than "all
         * do" — the platform may withhold a radius on a fix ([TrackPoint.accuracy] is nullable for
         * that reason), so a single one is proof of the recorder while a missing one proves nothing.
         *
         * What it cannot see: our own GPX export re-imported reads [IMPORTED], which is at least
         * honest — those rows no longer hold what the recorder measured — and a track holding both
         * writers' fixes reads [RECORDED], being indistinguishable from a recording whose platform
         * withheld some radii. A [MANUAL] track's typed endpoints carry no radius either, so a
         * source-less copy of one reads [IMPORTED] — also honest: a path, not a measurement.
         * Null when there are no points at all: no evidence, so no answer.
         */
        fun inferFrom(points: List<TrackPoint>): TrackOrigin? = when {
            points.isEmpty() -> null
            points.any { it.accuracy != null } -> RECORDED
            else -> IMPORTED
        }
    }
}
