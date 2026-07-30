package io.github.valeronm.breadcrumb.data

import android.content.Context
import io.github.valeronm.breadcrumb.data.db.AppDatabase
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.domain.PlaceCategory
import kotlinx.coroutines.flow.Flow

/**
 * What the user said about a place — label and category, the only persisted layer of the places
 * feature. Stays, clusters and visit counts derive on read; a Place row pins a label to a cluster
 * centroid at naming time (never moved on rename — matching goes through the cluster anchor, see
 * PlaceResolver).
 */
class PlaceRepository(context: Context, db: AppDatabase = AppDatabase.get(context)) {

    private val dao = db.placeDao()

    fun observePlaces(): Flow<List<Place>> = dao.observeAll()

    suspend fun allPlaces(): List<Place> = dao.allPlaces()

    /** Backup restore: re-insert exported places under fresh ids (nothing references place ids). */
    suspend fun restorePlaces(places: List<Place>) = dao.insertAll(places.map { it.copy(id = 0) })

    suspend fun create(
        label: String,
        lat: Double,
        lon: Double,
        now: Long,
        radiusM: Double,
    ): Long = dao.insert(Place(label = label, lat = lat, lon = lon, createdAt = now, radiusM = radiusM))

    /** Everything the editor commits about an existing place, as one row write — see [PlaceDao.update]. */
    suspend fun save(id: Long, label: String, lat: Double, lon: Double, radiusM: Double) =
        dao.update(id, label, lat, lon, radiusM)

    /**
     * Tag what the place is for; null untags. Clustering reads only the pin and radius, so unlike a
     * re-pin this can't move a visit between places, but it's still a write to `places`, the table
     * the shared derivation observes — so the timeline re-derives off it exactly as off a rename.
     * Callers should skip the write when nothing changed.
     */
    suspend fun setCategory(id: Long, category: PlaceCategory?) = dao.setCategory(id, category?.code)

    suspend fun delete(id: Long) = dao.delete(id)

    /**
     * Undo a [delete] by re-inserting the row as it was — same id, pin, radius and creation time,
     * so the stays that clustered to it cluster back exactly as before.
     */
    suspend fun restore(place: Place) {
        dao.insert(place)
    }
}
