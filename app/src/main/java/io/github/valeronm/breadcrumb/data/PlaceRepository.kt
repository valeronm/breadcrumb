package io.github.valeronm.breadcrumb.data

import android.content.Context
import androidx.room.withTransaction
import io.github.valeronm.breadcrumb.data.db.AppDatabase
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.domain.PlaceCategory
import kotlinx.coroutines.flow.Flow

/**
 * What the user said about a place — its label, its circle and what it is for, and nothing derived.
 * A Place row pins a label to a cluster centroid at naming time and is never moved on rename;
 * which cluster wears it is [DerivationStore]'s to store, and `PlaceResolver` reads that back.
 *
 * **A place's circle is the derivation's seed**, so a write that could move one ends at
 * [DerivationStore.reconcile] and re-derives the history when it did. That is where the cost of
 * naming, re-pinning and deleting a place lives, and where a rename's costing nothing is decided.
 * [setCategory] is the one write that reaches no seed column and so goes straight to the row.
 */
class PlaceRepository(context: Context, private val db: AppDatabase = AppDatabase.get(context)) {

    private val dao = db.placeDao()
    private val derivation = DerivationStore(context, db)

    fun observePlaces(): Flow<List<Place>> = dao.observeAll()

    suspend fun allPlaces(): List<Place> = dao.allPlaces()

    /** Backup restore: re-insert exported places under fresh ids (nothing references place ids).
     *  Seeded by the restore's own pass, which has a whole history to derive besides. */
    suspend fun restorePlaces(places: List<Place>) = dao.insertAll(places.map { it.copy(id = 0) })

    suspend fun create(
        label: String,
        lat: Double,
        lon: Double,
        now: Long,
        radiusM: Double,
    ): Long = seeding {
        dao.insert(Place(label = label, lat = lat, lon = lon, createdAt = now, radiusM = radiusM))
    }

    /**
     * Several places as one write. A create re-derives the history, so a caller with more than one
     * to make — a trip whose two ends were both picked by name — hands them over together and pays
     * for one derivation rather than one each.
     */
    suspend fun createAll(places: List<Place>) = seeding { dao.insertAll(places) }

    /** Everything the editor commits about an existing place, as one row write — see [PlaceDao.update]. */
    suspend fun save(id: Long, label: String, lat: Double, lon: Double, radiusM: Double) = seeding {
        dao.update(id, label, lat, lon, radiusM)
    }

    /**
     * Tag what the place is for; null untags. Clustering reads only the pin and radius, so this is
     * the one write here that cannot move a visit between places — and the only one that does not
     * go through [seeding]. That a category is not a seed is a fact about the derivation, stated
     * here by the plumbing rather than proven per tap by a reconcile that could only find nothing.
     */
    suspend fun setCategory(id: Long, category: PlaceCategory?) = dao.setCategory(id, category?.code)

    suspend fun delete(id: Long) = seeding { dao.delete(id) }

    /**
     * Undo a [delete] by re-inserting the row as it was — same id, pin, radius and creation time,
     * so the stays that clustered to it cluster back exactly as before.
     */
    suspend fun restore(place: Place) = seeding { dao.insert(place) }

    /**
     * One write to `places`, with the derivation's seeds brought back into agreement with it before
     * the transaction closes — so no reader can see a place whose circle the stored stays were not
     * derived against.
     */
    private suspend fun <T> seeding(write: suspend () -> T): T = db.withTransaction {
        val result = write()
        derivation.reconcile()
        result
    }
}
