package io.github.valeronm.breadcrumb.data

import android.content.Context
import androidx.room.withTransaction
import io.github.valeronm.breadcrumb.data.db.AppDatabase
import io.github.valeronm.breadcrumb.data.db.IDS_PER_STATEMENT
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
    private val tracks = db.trackDao()
    private val derivation = DerivationStore(context, db)

    fun observePlaces(): Flow<List<Place>> = dao.observeAll()

    suspend fun allPlaces(): List<Place> = dao.allPlaces()

    /** Backup restore: re-insert exported places under fresh ids, answered in [places]' order.
     *  Seeded by the restore's own pass, which has a whole history to derive besides. */
    suspend fun restorePlaces(places: List<Place>): List<Long> = dao.insertAll(places.map { it.copy(id = 0) })

    /** Inserts [place] and answers with the id Room gave it. Takes the whole row, as [createAndName] and
     *  [restore] do, so a caller that has to *show* what it wrote shows the row that was written. */
    suspend fun create(place: Place): Long = seeding { dao.insert(place) }

    /**
     * Several places as one write: [created] inserted, and [named] — existing rows given a name —
     * rewritten as [save] would. A create re-derives the history, so a caller with more than one to
     * make — a trip whose two ends were both picked by name — hands them over together and pays for
     * one derivation and one invalidation rather than one each.
     */
    suspend fun createAndName(created: List<Place>, named: List<Place>) = seeding {
        dao.insertAll(created)
        for (row in named) dao.update(row.id, row.label, row.lat, row.lon, row.radiusM)
    }

    /** Everything the editor commits about an existing place, as one row write — see [PlaceDao.update],
     *  whose column list is what "everything the editor commits" means. Takes the row for [create]'s
     *  reason: a caller showing what it wrote must be showing the same value. */
    suspend fun save(place: Place) = seeding {
        dao.update(place.id, place.label, place.lat, place.lon, place.radiusM)
    }

    /**
     * Tag what the place is for; null untags. Clustering reads only the pin and radius, so this is
     * the one write here that cannot move a visit between places — and the only one that does not
     * go through [seeding]. That a category is not a seed is a fact about the derivation, stated
     * here by the plumbing rather than proven per tap by a reconcile that could only find nothing.
     */
    suspend fun setCategory(id: Long, category: PlaceCategory?) = dao.setCategory(id, category?.code)

    /** What a [delete] takes with the row: the tracks whose start or end was stated to it, which the
     *  foreign key clears. */
    class Removal(val place: Place, val startsOf: List<Long>, val endsOf: List<Long>)

    suspend fun delete(place: Place): Removal = seeding { remove(place) }

    /**
     * Undo a [delete] by re-inserting the row as it was — same id, pin, radius and creation time —
     * and stating its ends to it again, so the stays that clustered to it cluster back exactly as
     * before.
     */
    suspend fun restore(removal: Removal) = seeding { reinstate(removal) }

    /** The rows a [merge] removed, each with the trip ends that were stated to it. */
    class Merge(val removals: List<Removal>)

    /**
     * Folds [absorbed] into [keep]: the trip ends stated to each absorbed row are stated to [keep],
     * and the rows are removed. [keep]'s own row is not written. A row already gone is skipped, and
     * nothing happens if [keep] itself is gone.
     */
    suspend fun merge(keep: Place, absorbed: List<Place>): Merge = seeding {
        if (dao.place(keep.id) == null) return@seeding Merge(emptyList())
        val removals = absorbed
            .filter { it.id != keep.id && dao.place(it.id) != null }
            .map { place ->
                val removal = remove(place)
                removal.startsOf.chunked(IDS_PER_STATEMENT).forEach { tracks.stateStarts(it, keep.id) }
                removal.endsOf.chunked(IDS_PER_STATEMENT).forEach { tracks.stateEnds(it, keep.id) }
                removal
            }
        Merge(removals)
    }

    /** Undo a [merge]: every absorbed row and every end stated to it come back as they were. */
    suspend fun unmerge(merge: Merge) = seeding { merge.removals.forEach { reinstate(it) } }

    private suspend fun remove(place: Place): Removal {
        val removal = Removal(place, tracks.startsStatedTo(place.id), tracks.endsStatedTo(place.id))
        dao.delete(place.id)
        return removal
    }

    private suspend fun reinstate(removal: Removal) {
        dao.insert(removal.place)
        removal.startsOf.chunked(IDS_PER_STATEMENT).forEach { tracks.stateStarts(it, removal.place.id) }
        removal.endsOf.chunked(IDS_PER_STATEMENT).forEach { tracks.stateEnds(it, removal.place.id) }
    }

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
