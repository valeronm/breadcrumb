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
    suspend fun insertPoint(point: TrackPoint): Long

    @Query("UPDATE tracks SET endedAt = :endedAt WHERE id = :trackId")
    suspend fun closeTrack(trackId: Long, endedAt: Long)

    @Query("UPDATE tracks SET distanceMeters = :distance WHERE id = :trackId")
    suspend fun updateDistance(trackId: Long, distance: Double)

    @Query("UPDATE tracks SET activityType = :activityType WHERE id = :trackId")
    suspend fun setActivityType(trackId: Long, activityType: String)

    @Query("DELETE FROM tracks WHERE id = :trackId")
    suspend fun deleteTrack(trackId: Long)

    /** Soft-delete a keep-threshold-filtered track: finalise it and mark it discarded. */
    @Query("UPDATE tracks SET endedAt = :endedAt, discardedAt = :discardedAt WHERE id = :trackId")
    suspend fun discardTrack(trackId: Long, endedAt: Long, discardedAt: Long)

    /** Hard-delete soft-deleted tracks discarded before [cutoff] (points cascade). Returns the count. */
    @Query("DELETE FROM tracks WHERE discardedAt IS NOT NULL AND discardedAt < :cutoff")
    suspend fun purgeDiscardedBefore(cutoff: Long): Int

    // --- Track merge (close a short same-activity stay into a new track) ------------------------

    /** Copy every point of [srcId] onto [newId] (the merged track keeps its own copy). */
    @Query(
        """
        INSERT INTO track_points
            (trackId, latitude, longitude, altitude, accuracy, speed, bearing, timestamp,
             verticalAccuracy, speedAccuracy, bearingAccuracy, satellitesInFix, cn0, provider,
             ignored, ignoreReason, segmentStart)
        SELECT :newId, latitude, longitude, altitude, accuracy, speed, bearing, timestamp,
               verticalAccuracy, speedAccuracy, bearingAccuracy, satellitesInFix, cn0, provider,
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

    @Query("UPDATE tracks SET discardedAt = :discardedAt WHERE id = :trackId")
    suspend fun setDiscarded(trackId: Long, discardedAt: Long)

    /** Usable (non-ignored) points, for rendering and export. */
    @Query("SELECT * FROM track_points WHERE trackId = :trackId AND ignored = 0 ORDER BY timestamp ASC, id ASC")
    suspend fun pointsFor(trackId: Long): List<TrackPoint>

    /** Only the ignored "bad fix" points, for marking them on the map. */
    @Query("SELECT * FROM track_points WHERE trackId = :trackId AND ignored = 1 ORDER BY timestamp ASC, id ASC")
    suspend fun ignoredPointsFor(trackId: Long): List<TrackPoint>

    /** Every point, good and ignored, for reprocessing passes. */
    @Query("SELECT * FROM track_points WHERE trackId = :trackId ORDER BY timestamp ASC, id ASC")
    suspend fun allPointsFor(trackId: Long): List<TrackPoint>

    @Query("UPDATE track_points SET ignoreReason = :reason WHERE id IN (:ids)")
    suspend fun setIgnoreReason(ids: List<Long>, reason: String)

    /** Duplicate check for GPX import: a track with the exact same time span already exists. */
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

    @Query(
        """
        SELECT t.id, t.activityType, t.startedAt, t.endedAt, t.distanceMeters,
               (SELECT COUNT(*) FROM track_points p WHERE p.trackId = t.id AND p.ignored = 0) AS pointCount,
               (SELECT COUNT(*) FROM track_points p WHERE p.trackId = t.id AND p.ignored = 1) AS ignoredCount
        FROM tracks t
        WHERE t.endedAt IS NOT NULL AND t.discardedAt IS NULL
        ORDER BY t.startedAt DESC
        """
    )
    fun observeSummaries(): Flow<List<TrackSummary>>

    /** The inverse of [observeSummaries]: keep-threshold-filtered (soft-deleted) tracks, for the
     *  debug "Discarded tracks" screen used to tune the thresholds against real data. */
    @Query(
        """
        SELECT t.id, t.activityType, t.startedAt, t.endedAt, t.distanceMeters,
               (SELECT COUNT(*) FROM track_points p WHERE p.trackId = t.id AND p.ignored = 0) AS pointCount,
               (SELECT COUNT(*) FROM track_points p WHERE p.trackId = t.id AND p.ignored = 1) AS ignoredCount
        FROM tracks t
        WHERE t.discardedAt IS NOT NULL
        ORDER BY t.startedAt DESC
        """
    )
    fun observeDiscardedSummaries(): Flow<List<TrackSummary>>

    /**
     * Finished tracks with first/last good-point coordinates, oldest first — the stay deriver's
     * input. The subqueries walk the (trackId, timestamp) index, stopping at the first
     * non-ignored row.
     */
    @Query(
        """
        SELECT t.id, t.activityType, t.startedAt, t.endedAt,
               (SELECT p.latitude  FROM track_points p WHERE p.trackId = t.id AND p.ignored = 0
                  ORDER BY p.timestamp ASC,  p.id ASC  LIMIT 1) AS startLat,
               (SELECT p.longitude FROM track_points p WHERE p.trackId = t.id AND p.ignored = 0
                  ORDER BY p.timestamp ASC,  p.id ASC  LIMIT 1) AS startLon,
               (SELECT p.latitude  FROM track_points p WHERE p.trackId = t.id AND p.ignored = 0
                  ORDER BY p.timestamp DESC, p.id DESC LIMIT 1) AS endLat,
               (SELECT p.longitude FROM track_points p WHERE p.trackId = t.id AND p.ignored = 0
                  ORDER BY p.timestamp DESC, p.id DESC LIMIT 1) AS endLon
        FROM tracks t
        WHERE t.endedAt IS NOT NULL AND t.discardedAt IS NULL
        ORDER BY t.startedAt ASC
        """
    )
    fun observeEndpoints(): Flow<List<TrackEndpoints>>
}
