package io.github.valeronm.breadcrumb.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

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
    /** Running total distance in metres, maintained as points arrive. */
    val distanceMeters: Double = 0.0,
)

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
    indices = [Index("trackId")],
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
    /** Location provider that produced the fix ("gps", "fused", "network", …). */
    val provider: String? = null,
    /**
     * True for a fix judged unreliable by [io.github.valeronm.breadcrumb.data.TrackQuality]
     * (too-coarse accuracy or an implausible jump). Stored but excluded from distance, the
     * rendered track line, and exports.
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

/** Lightweight summary row for the track list (no point geometry loaded). */
data class TrackSummary(
    val id: Long,
    val activityType: String,
    val startedAt: Long,
    val endedAt: Long?,
    val distanceMeters: Double,
    /** Number of usable (non-ignored) points. */
    val pointCount: Int,
    /** Number of ignored "bad fix" points — a signal that the track is questionable. */
    val ignoredCount: Int,
)
