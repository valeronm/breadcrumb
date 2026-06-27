package com.valeronm.activitytracker.data.db

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

    @Query("SELECT COUNT(*) FROM track_points WHERE trackId = :trackId")
    suspend fun pointCount(trackId: Long): Int

    @Query("SELECT * FROM track_points WHERE trackId = :trackId ORDER BY timestamp ASC")
    suspend fun pointsFor(trackId: Long): List<TrackPoint>

    @Query("SELECT * FROM tracks WHERE endedAt IS NULL")
    suspend fun openTracks(): List<Track>

    @Query("SELECT MAX(timestamp) FROM track_points WHERE trackId = :trackId")
    suspend fun lastPointTime(trackId: Long): Long?

    @Query("SELECT * FROM tracks WHERE id = :trackId")
    suspend fun track(trackId: Long): Track?

    @Query(
        """
        SELECT t.id, t.activityType, t.startedAt, t.endedAt, t.distanceMeters,
               (SELECT COUNT(*) FROM track_points p WHERE p.trackId = t.id) AS pointCount
        FROM tracks t
        ORDER BY t.startedAt DESC
        """
    )
    fun observeSummaries(): Flow<List<TrackSummary>>
}
