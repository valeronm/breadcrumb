package io.github.valeronm.breadcrumb.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single continuous recording session for one activity type (e.g. one drive, one walk).
 * A new track is opened whenever the detected activity changes, and closed when it ends.
 */
@Entity(tableName = "tracks", indices = [Index("startedAt")])
data class Track(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val activityType: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    /**
     * Who wrote the fixes — an [io.github.valeronm.breadcrumb.domain.TrackOrigin.code] string, set
     * by whoever inserts the row and never re-derived from the points afterwards. Null is unknown:
     * a row this build's vocabulary doesn't cover, or one from before the column that held no points
     * to reconstruct an answer from.
     */
    val source: String? = null,
    // --- Aggregates of the track's points, denormalized -----------------------------------------
    // Written only by TrackRepository.refreshStats — on finish, merge, split, import, retype, or
    // overrun re-derivation, never per fix, which keeps the observed queries off `track_points`
    // ([TrackDao]). Meaningless while open: nothing reads an open track's row, and finishing it —
    // `finalizeDangling` after a crash included — recomputes them from the points.
    /** Total distance in meters over the good points, the legs spanning a segment gap included. */
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
    /** Why it was discarded — [REASON_DELETED] | [REASON_FILTERED] | [REASON_MERGED]; null on
     *  rows discarded before reasons were tracked, and on rows whose reason's code has since
     *  been retired. */
    val discardReason: String? = null,
    /**
     * Dormant, kept on purpose. It marked a track whose edge stay awaited the user's accept/reject;
     * nothing writes it since the overrun became a per-point flag applied as it is found
     * ([io.github.valeronm.breadcrumb.domain.EdgeStayIgnore]), needing no pending decision.
     * Reserved for the mid-track dwell split, whose cut *does* need confirming — splitting a track
     * in two is not undone by clearing a flag — and wants exactly this: one boolean saying a
     * decision is pending here. Don't drop it to tidy up; its next reader should re-derive rather
     * than trust what it finds, since the one release that wrote this left marks on installed
     * devices that no pass ever cleared.
     */
    val needsReview: Boolean = false,
) {
    companion object {
        const val REASON_DELETED = "deleted"
        const val REASON_FILTERED = "filtered"
        const val REASON_MERGED = "merged"
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
     * True for a fix that isn't part of the track's path — judged unreliable by
     * [io.github.valeronm.breadcrumb.data.TrackQuality] (too-coarse accuracy, implausible jump) or
     * recorded past the stop at a track edge ([io.github.valeronm.breadcrumb.domain.EdgeStayIgnore])
     * — and excluded from distance, the rendered track line, the endpoints, and exports, but stored.
     */
    val ignored: Boolean = false,
    /**
     * Why the fix was ignored — an [io.github.valeronm.breadcrumb.domain.IgnoreReason.code] string,
     * null for good points and for ignored points recorded before reasons were tracked.
     */
    val ignoreReason: String? = null,
    /**
     * True for the first point of a new segment within a track — i.e. the fix right after recording
     * resumed from an auto-pause, or the join a merge made. Nobody watched the ground on the leg
     * into it, though distance counts that leg like any other: unobserved is not untravelled.
     */
    val segmentStart: Boolean = false,
)

/**
 * Recorder-lifecycle evidence for deriving stays: a gap between tracks only counts as "stayed
 * here" if the app was alive and armed throughout. Low volume (a few rows per day at most); the
 * high-frequency liveness signal is the heartbeat timestamp in Settings, which materializes as
 * an OUTAGE row here only when a restart discovers it went stale.
 */
// The composite is what lets a reader ask about one stretch of time rather than the whole log — see
// [LivenessDao.eventsAround], which is the query it exists for and where the argument lives.
@Entity(tableName = "liveness_events", indices = [Index("at"), Index("type", "at")])
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
 * A group of track endpoints that are one spot, and **the durable entity a stay belongs to** — the
 * place row beside it holds only what the user called it.
 *
 * [anchorLat]/[anchorLon] is the first member's position (or a named place's pin) and decides
 * membership: an endpoint joins the nearest cluster whose [radiusM] covers it. The centroid — where
 * the cluster is *reported* to be — is [sumLat]/[sumLon] over [memberCount], kept as running sums so
 * adding or removing one member is an exact O(1) update in either direction, and so that placing a
 * cluster on a map reads this row alone rather than joining [ClusterMember].
 *
 * A row with a [placeId] is a **seed**, and that is the whole difference user curation makes to the
 * grouping: a seed's anchor and radius are given, where an unnamed cluster's are whatever its
 * members made them.
 */
@Entity(tableName = "derived_clusters", indices = [Index("placeId")])
data class DerivedCluster(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The place naming this cluster, or null while nothing has named it. No foreign key: the two
     *  are maintained together in one transaction, and a place delete clears this by hand. */
    val placeId: Long? = null,
    val anchorLat: Double,
    val anchorLon: Double,
    val radiusM: Double,
    /** Running sums of member positions; the centroid is these over [memberCount], and the anchor
     *  when that is zero — a named cluster whose stays were all deleted keeps its pin. */
    val sumLat: Double,
    val sumLon: Double,
    val memberCount: Int,
)

/**
 * One track endpoint's membership of a cluster — two rows per track, its start and its end.
 *
 * The assignment made durable: which spot an endpoint belongs to is a fact about that endpoint,
 * held here rather than recomputed from every other endpoint in the history. That is what makes the
 * neighbourhood of one track answerable on its own. [atMs] is the endpoint's own time, which orders
 * them.
 */
@Entity(
    tableName = "cluster_members",
    foreignKeys = [
        ForeignKey(
            entity = DerivedCluster::class,
            parentColumns = ["id"],
            childColumns = ["clusterId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    // A track's start and its end are one row each, so that pair *is* the row's identity — carried
    // as the key rather than beside a generated one, which would have been a second index over the
    // largest of these tables for a column nothing reads.
    primaryKeys = ["trackId", "isStart"],
    indices = [Index("clusterId")],
)
data class ClusterMember(
    val clusterId: Long,
    val trackId: Long,
    /** True for the track's first good fix, false for its last. */
    val isStart: Boolean,
    val lat: Double,
    val lon: Double,
    val atMs: Long,
)

/**
 * A stay or a gap between two kept tracks, as derived. One row per track — the one that *follows*
 * [afterTrackId] — which is why that column is the key, and why an interval is identified by the
 * track before it rather than by its own bounds, which the day slicing rewrites.
 *
 * **No row here is open-ended.** The stay still running after the newest track closes at the clock,
 * or when recording starts, so it is not a fact about two finished tracks and has no row to be
 * stale in ([io.github.valeronm.breadcrumb.domain.StayDeriver.tail] answers it).
 *
 * Which columns carry meaning depends on [type], the two shapes differing in the way stays and gaps
 * differ: a stay names the [clusterId] its endpoints agreed on and no position, a gap names the two
 * positions whose disagreement made it one — the ends a trip entered by hand to fill it runs
 * between — beside the clusters they fell into, and no single place.
 */
@Entity(
    tableName = "derived_intervals",
    primaryKeys = ["afterTrackId"],
    indices = [Index("start")],
)
data class DerivedInterval(
    /** [TYPE_STAY] | [TYPE_GAP]. */
    val type: String,
    val start: Long,
    /** Always closed. Named unlike the domain's `end`, which is a SQLite keyword every hand-written
     *  query would have to quote, and matches `tracks.endedAt` besides. */
    val endedAt: Long,
    /** The kept track this interval follows. */
    val afterTrackId: Long,
    /** STAY: [PROVENANCE_OBSERVED] | [PROVENANCE_INFERRED] — whether the app was alive throughout,
     *  or only knows that the two ends agree. */
    val provenance: String? = null,
    /** STAY: the cluster both endpoints agreed on. */
    val clusterId: Long? = null,
    /** GAP: [REASON_MOVED_UNRECORDED] | [REASON_UNKNOWN_ENDPOINT]. */
    val reason: String? = null,
    /** GAP: the clusters either side, null on a side no fix was had for. */
    val fromClusterId: Long? = null,
    val toClusterId: Long? = null,
    /** GAP: where the recording left off and where it picked up — the phone's own positions at
     *  those instants, not the pins of the places holding them. Null on a side with no fix. */
    val fromLat: Double? = null,
    val fromLon: Double? = null,
    val toLat: Double? = null,
    val toLon: Double? = null,
) {
    /**
     * The stored spellings of the three vocabularies these columns hold. Constants rather than
     * literals at each writer, and declared here rather than taken from the domain enums whose
     * names they echo: what is on disk outlives any one build's Kotlin identifiers, so a rename
     * there must stay a rename and not a silent data migration.
     */
    companion object {
        const val TYPE_STAY = "STAY"
        const val TYPE_GAP = "GAP"
        const val PROVENANCE_OBSERVED = "OBSERVED"
        const val PROVENANCE_INFERRED = "INFERRED"
        const val REASON_MOVED_UNRECORDED = "MOVED_UNRECORDED"
        const val REASON_UNKNOWN_ENDPOINT = "UNKNOWN_ENDPOINT"
    }
}

/**
 * A user-named place, created/renamed/deleted from the stay-naming dialog — the places feature's
 * only persisted layer, carrying what the user said about the spot (its name, what it is for,
 * how wide it captures) and nothing derived; stays, clusters and visit counts derive on read.
 */
@Entity(tableName = "places")
data class Place(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    /** Cluster centroid at naming time. Never updated on rename — a stable pin. */
    val lat: Double,
    val lon: Double,
    val createdAt: Long,
    /** Capture radius (meters): endpoints within it cluster to this place. User-tunable per
     *  place — widen for big venues (malls, garages) whose GPS scatter exceeds the default
     *  (PlaceClusterer.DEFAULT_RADIUS_M — callers pass it; the entity carries no default so the
     *  db package stays free of domain imports). */
    val radiusM: Double,
    /** What the place is for (`PlaceCategory.code`), or null when untagged. Deliberately the raw
     *  string rather than the enum: a code this build doesn't know reads as untagged but survives
     *  the round trip through a backup, which mapping at the column would erase. */
    val category: String? = null,
)

/** A finished track projected to what stay derivation needs: interval + endpoint coordinates. */
data class TrackEndpoints(
    val id: Long,
    val startedAt: Long,
    val endedAt: Long,
    val startLat: Double?,
    val startLon: Double?,
    val endLat: Double?,
    val endLon: Double?,
)

/**
 * A Recently-deleted row: [track] plus when and why it was discarded. Composed rather than
 * restated — the two projections had drifted into identical field lists, so a column added to one
 * had to be remembered in the other, and the same screen renders a discarded track by handing its
 * [track] on. The query still names its own columns, so that is where a new one must be added.
 */
data class DiscardedSummary(
    @Embedded val track: TrackSummary,
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
    /** [Track.source]: who wrote the fixes. Undefaulted like the columns beside it — a projection
     *  that leaves it out reads as a track whose writer is unknown, which is a claim, not a gap. */
    val source: String?,
)
