package io.github.valeronm.breadcrumb.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaceDao {
    @Insert
    suspend fun insert(place: Place): Long

    /** Backup restore: one transaction for the whole list, not one per row. */
    @Insert
    suspend fun insertAll(places: List<Place>)

    @Query("UPDATE places SET label = :label WHERE id = :id")
    suspend fun rename(id: Long, label: String)

    @Query("UPDATE places SET radiusM = :radiusM WHERE id = :id")
    suspend fun setRadius(id: Long, radiusM: Double)

    @Query("UPDATE places SET lat = :lat, lon = :lon WHERE id = :id")
    suspend fun setPin(id: Long, lat: Double, lon: Double)

    @Query("DELETE FROM places WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM places ORDER BY createdAt ASC, id ASC")
    fun observeAll(): Flow<List<Place>>

    @Query("SELECT * FROM places ORDER BY createdAt ASC, id ASC")
    suspend fun allPlaces(): List<Place>
}
