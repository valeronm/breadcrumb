package io.github.valeronm.breadcrumb.data

import android.content.Context
import io.github.valeronm.breadcrumb.data.db.AppDatabase
import io.github.valeronm.breadcrumb.data.db.Place
import kotlinx.coroutines.flow.Flow

/**
 * User-assigned place labels — the only persisted layer of the places feature. Stays, clusters
 * and visit counts derive on read; a Place row pins a label to a cluster centroid at naming time
 * (the pin is never moved on rename — matching goes through the cluster anchor, see PlaceResolver).
 */
class PlaceRepository(context: Context, db: AppDatabase = AppDatabase.get(context)) {

    private val dao = db.placeDao()

    fun observePlaces(): Flow<List<Place>> = dao.observeAll()

    suspend fun allPlaces(): List<Place> = dao.allPlaces()

    /** Backup restore: re-insert exported places under fresh ids (nothing references place ids). */
    suspend fun restorePlaces(places: List<Place>) = dao.insertAll(places.map { it.copy(id = 0) })

    suspend fun create(label: String, lat: Double, lon: Double, now: Long): Long =
        dao.insert(Place(label = label, lat = lat, lon = lon, createdAt = now))

    suspend fun rename(id: Long, label: String) = dao.rename(id, label)

    suspend fun setRadius(id: Long, radiusM: Double) = dao.setRadius(id, radiusM)

    /** Explicit pin move (the re-center action) — the only path that ever changes a pin. */
    suspend fun setPin(id: Long, lat: Double, lon: Double) = dao.setPin(id, lat, lon)

    suspend fun delete(id: Long) = dao.delete(id)

    /**
     * Undo a [delete] by re-inserting the row as it was — same id, pin, radius and creation time,
     * so the stays that clustered to it cluster back exactly as before.
     */
    suspend fun restore(place: Place) {
        dao.insert(place)
    }
}
