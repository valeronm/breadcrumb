package io.github.valeronm.breadcrumb.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.valeronm.breadcrumb.domain.PlaceClusterer

/**
 * A single continuous recording session for one activity type (e.g. one drive, one walk).
 * A new track is opened whenever the detected activity changes, and closed when it ends.
 */
@Entity(tableName = "tracks")
data class Track(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val activityType: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    // --- Aggregates of the track's points, denormalized -----------------------------------------
    // Written only by TrackRepository.refreshStats, when a track is finished (or merged, imported,
    // repaired) — never per fix, which is what keeps the observed queries off `track_points`; see
    // [TrackDao]. Meaningless while a track is open: nothing reads an open track's row, and
    // finishing it — including `finalizeDangling` after a crash — recomputes them from the points.
    /** Total distance in metres over the good points, segment gaps excluded. */
    val distanceMeters: Double = 0.0,
    /** Usable (non-ignored) points. */
    val pointCount: Int = 0,
    /** Ignored points: bad fixes — a signal that the track is questionable — plus the recorder's
     *  overrun at the edges, which is not. [ignoreReason][TrackPoint.ignoreReason] separates them. */
    val ignoredCount: Int = 0,
    /** First/last good point — the stay deriver's endpoints. Null for a track with no good points. */
    val startLat: Double? = null,
    val startLon: Double? = null,
    val endLat: Double? = null,
    val endLon: Double? = null,
    /**
     * Set when the track was soft-deleted (user delete, keep-threshold filter, or merge original).
     * Excluded from the UI, stats, stays, and export; restorable from Recently deleted until the
     * retention purge hard-deletes it.
     */
    val discardedAt: Long? = null,
    /** Why it was discarded — [REASON_DELETED] | [REASON_FILTERED] | [REASON_MERGED] |
     *  [REASON_TRIMMED]; null on rows discarded before reasons were tracked. */
    val discardReason: String? = null,
    /**
     * Dormant, and kept on purpose. It marked a track whose edge stay was waiting on the user to
     * accept or reject; the recorder's overrun is now flagged on the points themselves as it is
     * found ([io.github.valeronm.breadcrumb.domain.EdgeStayIgnore]), which needs no pending
     * decision, so nothing writes this today. It is reserved for the mid-track dwell split, whose
     * cut *does* need confirming — splitting a track in two is not undone by clearing a flag — and
     * which wants exactly this: one boolean saying a decision is pending here. Don't drop it to
     * tidy up; the next feature is the reader — and it should re-derive rather than trust what it
     * finds, since the one release that wrote this left marks on installed devices that no pass
     * ever cleared.
     */
    val needsReview: Boolean = false,
) {
    companion object {
        const val REASON_DELETED = "deleted"
        const val REASON_FILTERED = "filtered"
        const val REASON_MERGED = "merged"

        /** An edge stay split off a track, back when trimming moved the stay's points to their
         *  own discarded track. Nothing writes it any more — the overrun stays on its track as
         *  ignored points — but rows discarded under the old scheme still carry it until the
         *  retention purge takes them. */
        const val REASON_TRIMMED = "trimmed"
    }
}

@Entity(
    tableName = "track_points",
    foreignKeys = [
        ForeignKey(
            entity = Track::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    // Composite: serves the FK (trackId prefix) and makes first/last-point-per-track
    // subqueries index-order walks instead of per-track sorts.
    indices = [Index("trackId", "timestamp")],
)
data class TrackPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val accuracy: Float?,
    val speed: Float?,
    val bearing: Float?,
    val timestamp: Long,
    // --- Fix-quality metadata (nullable: null when the source didn't report it) ----------------
    /** Estimated vertical / speed / bearing accuracy, the confidence siblings of [accuracy]. */
    val verticalAccuracy: Float? = null,
    val speedAccuracy: Float? = null,
    val bearingAccuracy: Float? = null,
    /** Satellites used in the fix at capture time, from GnssStatus (null = no GNSS status seen). */
    val satellitesInFix: Int? = null,
    /** Average C/N0 (dB-Hz) of the 4 strongest satellites used in the fix — signal strength. */
    val cn0: Float? = null,
    /**
     * True for a fix that isn't part of the track's path: one judged unreliable by
     * [io.github.valeronm.breadcrumb.data.TrackQuality] (too-coarse accuracy or an implausible
     * jump), or one recorded past the stop at a track edge
     * ([io.github.valeronm.breadcrumb.domain.EdgeStayIgnore]). Stored but excluded from distance,
     * the rendered track line, the endpoints, and exports.
     */
    val ignored: Boolean = false,
    /**
     * Why the fix was ignored — an [io.github.valeronm.breadcrumb.data.IgnoreReason.code] string,
     * null for good points and for ignored points recorded before reasons were tracked.
     */
    val ignoreReason: String? = null,
    /**
     * True for the first point of a new segment within a track — i.e. the fix right after recording
     * resumed from an auto-pause. Marks a GPX `<trkseg>` boundary; the gap before it isn't counted
     * in distance (the segments are logically disconnected).
     */
    val segmentStart: Boolean = false,
)

/**
 * Recorder-lifecycle evidence for deriving stays: a gap between tracks only counts as "stayed
 * here" if the app was alive and armed throughout. Low volume — a few rows per day at most;
 * the high-frequency liveness signal is the heartbeat timestamp in Settings, which only
 * materializes as an OUTAGE row here when a restart discovers it went stale.
 */
@Entity(tableName = "liveness_events", indices = [Index("at")])
data class LivenessEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** "ARMED" | "DISARMED" | "OUTAGE". */
    val type: String,
    /** Event time (epoch ms). For OUTAGE: when the app was last known alive before dying. */
    val at: Long,
    /** OUTAGE only: when the app came back (the restart time). Null for ARMED/DISARMED. */
    val until: Long? = null,
) {
    companion object {
        const val TYPE_ARMED = "ARMED"
        const val TYPE_DISARMED = "DISARMED"
        const val TYPE_OUTAGE = "OUTAGE"
    }
}

/**
 * A user-named place. Created/renamed/deleted from the stay-naming dialog; stays, clusters and
 * visit counts stay derived on read — labels are the only persisted layer of the places feature.
 */
@Entity(tableName = "places")
data class Place(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    /** Cluster centroid at naming time. Never updated on rename — a stable pin. */
    val lat: Double,
    val lon: Double,
    val createdAt: Long,
    /** Capture radius (metres): endpoints within it cluster to this place. User-tunable per
     *  place — widen for big venues (malls, garages) whose GPS scatter exceeds the default. */
    val radiusM: Double = DEFAULT_RADIUS_M,
) {
    companion object {
        /** Matches the organic cluster radius. */
        const val DEFAULT_RADIUS_M = PlaceClusterer.DEFAULT_RADIUS_M
    }
}

/** A finished track projected to what stay derivation needs: interval + endpoint coordinates. */
data class TrackEndpoints(
    val id: Long,
    val activityType: String,
    val startedAt: Long,
    val endedAt: Long,
    val startLat: Double?,
    val startLon: Double?,
    val endLat: Double?,
    val endLon: Double?,
)

/** A Recently-deleted row: the summary plus when and why it was discarded. */
data class DiscardedSummary(
    val id: Long,
    val activityType: String,
    val startedAt: Long,
    val endedAt: Long?,
    val distanceMeters: Double,
    val pointCount: Int,
    val ignoredCount: Int,
    val discardedAt: Long,
    val discardReason: String?,
)

/** Lightweight summary row for the track list (no point geometry loaded). */
data class TrackSummary(
    val id: Long,
    val activityType: String,
    val startedAt: Long,
    val endedAt: Long?,
    val distanceMeters: Double,
    /** Number of usable (non-ignored) points. */
    val pointCount: Int,
    /** Number of ignored points — bad fixes, plus any the recorder ran on past the stop for. */
    val ignoredCount: Int,
)
