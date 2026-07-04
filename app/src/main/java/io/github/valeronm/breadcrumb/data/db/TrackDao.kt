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

    @Query("DELETE FROM tracks WHERE id = :trackId")
    suspend fun deleteTrack(trackId: Long)

    @Query("SELECT COUNT(*) FROM track_points WHERE trackId = :trackId AND ignored = 0")
    suspend fun pointCount(trackId: Long): Int

    /** Usable (non-ignored) points, for rendering and export. */
    @Query("SELECT * FROM track_points WHERE trackId = :trackId AND ignored = 0 ORDER BY timestamp ASC, id ASC")
    suspend fun pointsFor(trackId: Long): List<TrackPoint>

    /** Only the ignored "bad fix" points, for marking them on the map. */
    @Query("SELECT * FROM track_points WHERE trackId = :trackId AND ignored = 1 ORDER BY timestamp ASC, id ASC")
    suspend fun ignoredPointsFor(trackId: Long): List<TrackPoint>

    @Query("SELECT * FROM tracks WHERE endedAt IS NULL")
    suspend fun openTracks(): List<Track>

    @Query("SELECT MAX(timestamp) FROM track_points WHERE trackId = :trackId")
    suspend fun lastPointTime(trackId: Long): Long?

    @Query("SELECT * FROM tracks WHERE id = :trackId")
    suspend fun track(trackId: Long): Track?

    @Query("SELECT id FROM tracks ORDER BY startedAt DESC")
    suspend fun allTrackIds(): List<Long>

    @Query(
        """
        SELECT t.id, t.activityType, t.startedAt, t.endedAt, t.distanceMeters,
               (SELECT COUNT(*) FROM track_points p WHERE p.trackId = t.id AND p.ignored = 0) AS pointCount,
               (SELECT COUNT(*) FROM track_points p WHERE p.trackId = t.id AND p.ignored = 1) AS ignoredCount
        FROM tracks t
        ORDER BY t.startedAt DESC
        """
    )
    fun observeSummaries(): Flow<List<TrackSummary>>
}
