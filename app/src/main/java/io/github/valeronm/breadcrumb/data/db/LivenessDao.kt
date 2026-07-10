package io.github.valeronm.breadcrumb.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LivenessDao {
    @Insert
    suspend fun insert(event: LivenessEvent): Long

    @Query("SELECT * FROM liveness_events ORDER BY at DESC, id DESC LIMIT 1")
    suspend fun lastEvent(): LivenessEvent?

    @Query("SELECT * FROM liveness_events ORDER BY at ASC, id ASC")
    fun observeAll(): Flow<List<LivenessEvent>>
}
