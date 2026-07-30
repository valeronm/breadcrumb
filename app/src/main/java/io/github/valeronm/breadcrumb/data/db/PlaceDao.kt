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

    /**
     * Everything the place editor commits, as **one** write — deliberately not a setter per field.
     * Each write invalidates `places` and the derivation every screen reads runs again off it, and
     * because a pin and a radius are both
     * [io.github.valeronm.breadcrumb.domain.PlaceClusterer.Seed] fields, two of those invalidations
     * are two full re-clusterings of the whole history for one Done tap.
     */
    @Query("UPDATE places SET label = :label, lat = :lat, lon = :lon, radiusM = :radiusM WHERE id = :id")
    suspend fun update(id: Long, label: String, lat: Double, lon: Double, radiusM: Double)

    /** `PlaceCategory.code`, or null to untag. */
    @Query("UPDATE places SET category = :code WHERE id = :id")
    suspend fun setCategory(id: Long, code: String?)

    @Query("DELETE FROM places WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM places ORDER BY createdAt ASC, id ASC")
    fun observeAll(): Flow<List<Place>>

    @Query("SELECT * FROM places ORDER BY createdAt ASC, id ASC")
    suspend fun allPlaces(): List<Place>
}
