package io.github.valeronm.breadcrumb.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PlaceIdentityDao {
    /** An identity already held by another place moves to the one given. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(identities: List<PlaceIdentity>)

    @Query("SELECT * FROM place_identities WHERE placeId = :placeId")
    suspend fun of(placeId: Long): List<PlaceIdentity>

    @Query("SELECT * FROM place_identities WHERE provider = :provider")
    suspend fun byProvider(provider: String): List<PlaceIdentity>

    @Query("SELECT * FROM place_identities")
    suspend fun all(): List<PlaceIdentity>

    @Query("UPDATE place_identities SET placeId = :to WHERE placeId = :from")
    suspend fun repoint(from: Long, to: Long)
}
