package io.github.valeronm.breadcrumb.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaceDao {
    @Insert
    suspend fun insert(place: Place): Long

    /** Backup restore: one transaction for the whole list, not one per row. */
    @Insert
    suspend fun insertAll(places: List<Place>): List<Long>

    /**
     * Everything the place editor commits, as **one** write — deliberately not a setter per field.
     * Each write invalidates `places` and the derivation every screen reads runs again off it, and
     * because a pin and a radius are both
     * [io.github.valeronm.breadcrumb.domain.PlaceClusterer.Seed] fields, two of those invalidations
     * are two full re-clusterings of the whole history for one Done tap.
     */
    @Update(entity = Place::class)
    suspend fun update(edit: PlaceEdit)

    /** `PlaceCategory.code`, or null to untag. */
    @Query("UPDATE places SET category = :code WHERE id = :id")
    suspend fun setCategory(id: Long, code: String?)

    @Query("DELETE FROM places WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM places WHERE id = :id")
    suspend fun place(id: Long): Place?

    @Query("SELECT * FROM places ORDER BY createdAt ASC, id ASC")
    fun observeAll(): Flow<List<Place>>

    @Query("SELECT * FROM places ORDER BY createdAt ASC, id ASC")
    suspend fun allPlaces(): List<Place>

    @Query("SELECT id FROM places WHERE label IS NOT NULL")
    suspend fun namedIds(): List<Long>
}

/** The columns of `places` the editor commits — [PlaceDao.update]'s whole write; `category` and
 *  `createdAt` are not among them. */
data class PlaceEdit(
    val id: Long,
    val label: String?,
    val lat: Double,
    val lon: Double,
    val radiusM: Double,
    val source: String?,
)

fun Place.edit() = PlaceEdit(id, label, lat, lon, radiusM, source)
