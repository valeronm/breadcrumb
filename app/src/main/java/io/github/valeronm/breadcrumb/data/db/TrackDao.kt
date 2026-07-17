package io.github.valeronm.breadcrumb.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {

    @Insert
    suspend fun insertTrack(track: Track): Long

    @Insert
    suspend fun insertPoints(points: List<TrackPoint>)

    @Query("UPDATE tracks SET endedAt = :endedAt WHERE id = :trackId")
    suspend fun closeTrack(trackId: Long, endedAt: Long)

    /**
     * Write a track's aggregates ([io.github.valeronm.breadcrumb.data.TrackStats]) onto its row —
     * when it's finished, merged, imported or repaired, never per fix (see the observed queries
     * below: `tracks` is the table they read, so a per-fix write here would wake them all).
     */
    @Query(
        """
        UPDATE tracks SET distanceMeters = :distanceMeters, pointCount = :pointCount,
               ignoredCount = :ignoredCount, startLat = :startLat, startLon = :startLon,
               endLat = :endLat, endLon = :endLon
        WHERE id = :trackId
        """
    )
    suspend fun updateStats(
        trackId: Long,
        distanceMeters: Double,
        pointCount: Int,
        ignoredCount: Int,
        startLat: Double?,
        startLon: Double?,
        endLat: Double?,
        endLon: Double?,
    )

    @Query("UPDATE tracks SET activityType = :activityType WHERE id = :trackId")
    suspend fun setActivityType(trackId: Long, activityType: String)

    /** Soft-delete a keep-threshold-filtered track: finalise it and mark it discarded. */
    @Query(
        "UPDATE tracks SET endedAt = :endedAt, discardedAt = :discardedAt, discardReason = :reason " +
            "WHERE id = :trackId"
    )
    suspend fun discardTrack(trackId: Long, endedAt: Long, discardedAt: Long, reason: String)

    /** Bring a discarded track back to the timeline. */
    @Query("UPDATE tracks SET discardedAt = NULL, discardReason = NULL WHERE id = :trackId")
    suspend fun restoreTrack(trackId: Long)

    /** Hard-delete soft-deleted tracks discarded before [cutoff] (points cascade). Returns the count. */
    @Query("DELETE FROM tracks WHERE discardedAt IS NOT NULL AND discardedAt < :cutoff")
    suspend fun purgeDiscardedBefore(cutoff: Long): Int

    /** Hard-delete every soft-deleted track now — the Recently deleted screen's "clear all". */
    @Query("DELETE FROM tracks WHERE discardedAt IS NOT NULL")
    suspend fun purgeAllDiscarded(): Int

    // --- Track merge (close a short same-activity stay into a new track) ------------------------

    /** Copy every point of [srcId] onto [newId] (the merged track keeps its own copy). */
    @Query(
        """
        INSERT INTO track_points
            (trackId, latitude, longitude, altitude, accuracy, speed, bearing, timestamp,
             verticalAccuracy, speedAccuracy, bearingAccuracy, satellitesInFix, cn0,
             ignored, ignoreReason, segmentStart)
        SELECT :newId, latitude, longitude, altitude, accuracy, speed, bearing, timestamp,
               verticalAccuracy, speedAccuracy, bearingAccuracy, satellitesInFix, cn0,
               ignored, ignoreReason, segmentStart
        FROM track_points WHERE trackId = :srcId
        """
    )
    suspend fun copyPointsInto(newId: Long, srcId: Long)

    /** The merged track's first point at/after [timestamp] — marked as the segment break at the join. */
    @Query("SELECT id FROM track_points WHERE trackId = :trackId AND timestamp >= :timestamp ORDER BY timestamp ASC, id ASC LIMIT 1")
    suspend fun firstPointAtOrAfter(trackId: Long, timestamp: Long): Long?

    @Query("UPDATE track_points SET segmentStart = 1 WHERE id = :pointId")
    suspend fun markSegmentStart(pointId: Long)

    @Query("UPDATE tracks SET discardedAt = :discardedAt, discardReason = :reason WHERE id = :trackId")
    suspend fun setDiscarded(trackId: Long, discardedAt: Long, reason: String)

    /**
     * Hard-delete one track (points cascade). For rows with nothing to review — undoing a merge
     * drops the track the merge created, and a finish with too few points to render skips Recently
     * deleted. A user delete is the soft one ([setDiscarded]).
     */
    @Query("DELETE FROM tracks WHERE id = :trackId")
    suspend fun purgeTrack(trackId: Long)

    /** Usable (non-ignored) points, for rendering and export. */
    @Query("SELECT * FROM track_points WHERE trackId = :trackId AND ignored = 0 ORDER BY timestamp ASC, id ASC")
    suspend fun pointsFor(trackId: Long): List<TrackPoint>

    /** The first [limit] usable points — enough to check for a stray leading point. */
    @Query("SELECT * FROM track_points WHERE trackId = :trackId AND ignored = 0 ORDER BY timestamp ASC, id ASC LIMIT :limit")
    suspend fun firstPointsFor(trackId: Long, limit: Int): List<TrackPoint>

    /** Every point of a track, ignored ones included — the input to a [TrackStats] recompute. */
    @Query("SELECT * FROM track_points WHERE trackId = :trackId ORDER BY timestamp ASC, id ASC")
    suspend fun allPointsFor(trackId: Long): List<TrackPoint>

    /** Usable points inserted after [afterId] — the live preview's incremental reload. */
    @Query("SELECT * FROM track_points WHERE trackId = :trackId AND ignored = 0 AND id > :afterId ORDER BY timestamp ASC, id ASC")
    suspend fun pointsAfter(trackId: Long, afterId: Long): List<TrackPoint>

    /** Only the ignored "bad fix" points, for marking them on the map. */
    @Query("SELECT * FROM track_points WHERE trackId = :trackId AND ignored = 1 ORDER BY timestamp ASC, id ASC")
    suspend fun ignoredPointsFor(trackId: Long): List<TrackPoint>

    /** Flag one point as an ignored bad fix, with the reason. */
    @Query("UPDATE track_points SET ignored = 1, ignoreReason = :reason WHERE id = :pointId")
    suspend fun setIgnored(pointId: Long, reason: String)

    /**
     * Duplicate check for GPX import: a track with the exact same time span already exists.
     * Deliberately does NOT filter `discardedAt` — a soft-deleted track still blocks re-importing
     * the same span (it was judged not worth keeping; an import shouldn't resurrect it).
     */
    @Query("SELECT COUNT(*) FROM tracks WHERE startedAt = :startedAt AND endedAt = :endedAt")
    suspend fun countTracksSpanning(startedAt: Long, endedAt: Long): Int

    @Query("SELECT * FROM tracks WHERE endedAt IS NULL")
    suspend fun openTracks(): List<Track>

    @Query("SELECT MAX(timestamp) FROM track_points WHERE trackId = :trackId")
    suspend fun lastPointTime(trackId: Long): Long?

    @Query("SELECT * FROM tracks WHERE id = :trackId")
    suspend fun track(trackId: Long): Track?

    @Query("SELECT id FROM tracks WHERE discardedAt IS NULL ORDER BY startedAt DESC")
    suspend fun allTrackIds(): List<Long>

    // --- Observed queries -----------------------------------------------------------------------
    // These read `tracks` and nothing else, deliberately: Room invalidates per table, so a query
    // that touched `track_points` would re-run on every fix of a live recording — a scan of the
    // whole point history, once a second, to produce a result that cannot have changed (an open
    // track has no endedAt, so it isn't in any of them). The aggregates they need live on the
    // track row instead, written when the track is finished. See [Track] and [TrackStats].

    @Query(
        """
        SELECT id, activityType, startedAt, endedAt, distanceMeters, pointCount, ignoredCount
        FROM tracks
        WHERE endedAt IS NOT NULL AND discardedAt IS NULL
        ORDER BY startedAt DESC
        """
    )
    fun observeSummaries(): Flow<List<TrackSummary>>

    /** The inverse of [observeSummaries]: soft-deleted tracks (user delete, keep-threshold
     *  filter, merge originals) for the Recently deleted screen. */
    @Query(
        """
        SELECT id, activityType, startedAt, endedAt, distanceMeters, pointCount, ignoredCount,
               discardedAt, discardReason
        FROM tracks
        WHERE discardedAt IS NOT NULL
        ORDER BY startedAt DESC
        """
    )
    fun observeDiscardedSummaries(): Flow<List<DiscardedSummary>>

    /** Finished tracks with first/last good-point coordinates, oldest first — the stay deriver's input. */
    @Query(
        """
        SELECT id, activityType, startedAt, endedAt, startLat, startLon, endLat, endLon
        FROM tracks
        WHERE endedAt IS NOT NULL AND discardedAt IS NULL
        ORDER BY startedAt ASC
        """
    )
    fun observeEndpoints(): Flow<List<TrackEndpoints>>
}
