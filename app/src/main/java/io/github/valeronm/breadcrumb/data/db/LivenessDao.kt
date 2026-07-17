package io.github.valeronm.breadcrumb.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LivenessDao {
    @Insert
    suspend fun insert(event: LivenessEvent): Long

    /** Backup restore: one transaction for the whole list, not one per row. */
    @Insert
    suspend fun insertAll(events: List<LivenessEvent>)

    @Query("SELECT * FROM liveness_events ORDER BY at DESC, id DESC LIMIT 1")
    suspend fun lastEvent(): LivenessEvent?

    @Query("SELECT * FROM liveness_events ORDER BY at ASC, id ASC")
    fun observeAll(): Flow<List<LivenessEvent>>

    @Query("SELECT * FROM liveness_events ORDER BY at ASC, id ASC")
    suspend fun allEvents(): List<LivenessEvent>
}
