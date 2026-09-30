package io.github.valeronm.breadcrumb.data

import android.content.Context
import androidx.room.withTransaction
import io.github.valeronm.breadcrumb.data.db.AppDatabase
import io.github.valeronm.breadcrumb.data.db.IDS_PER_STATEMENT
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.data.db.PlaceEdit
import io.github.valeronm.breadcrumb.data.db.PlaceIdentity
import io.github.valeronm.breadcrumb.data.db.edit
import io.github.valeronm.breadcrumb.domain.PlaceCategory
import io.github.valeronm.breadcrumb.domain.PlaceOrigin
import io.github.valeronm.breadcrumb.domain.placeOrigin
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
    private val identities = db.placeIdentityDao()
    private val tracks = db.trackDao()
    private val derivation = DerivationStore(context, db)

    fun observePlaces(): Flow<List<Place>> = dao.observeAll()

    suspend fun allPlaces(): List<Place> = dao.allPlaces()

    /** Every identity [provider] gives a place. */
    suspend fun identitiesOf(provider: String): List<PlaceIdentity> = identities.byProvider(provider)

    suspend fun allIdentities(): List<PlaceIdentity> = identities.all()

    /** Backup restore and the imports: insert each place under a fresh id with its identities,
     *  answered in [places]' order. Seeded by the caller's own pass, which has a whole history to
     *  derive besides. */
    suspend fun restorePlaces(places: List<Pair<Place, List<PlaceIdentity>>>): List<Long> = db.withTransaction {
        val ids = dao.insertAll(places.map { it.first.copy(id = 0) })
        identities.upsert(ids.zip(places).flatMap { (id, place) -> place.second.map { it.copy(placeId = id) } })
        ids
    }

    /** Inserts [place] and answers with the id Room gave it. Takes the whole row, as [createAndName] and
     *  [restore] do, so a caller that has to *show* what it wrote shows the row that was written. */
    suspend fun create(place: Place): Long = seeding { dao.insert(written(place)) }

    /**
     * Several places as one write: [created] inserted, and [named] — existing rows given a name —
     * rewritten as [save] would. A create re-derives the history, so a caller with more than one to
     * make — a trip whose two ends were both picked by name — hands them over together and pays for
     * one derivation and one invalidation rather than one each.
     */
    suspend fun createAndName(created: List<Place>, named: List<Place>) = seeding {
        dao.insertAll(created.map(::written))
        for (row in named) dao.update(written(row).edit())
    }

    /** Everything the editor commits about an existing place, as one row write — see [PlaceDao.update],
     *  whose [PlaceEdit] is what "everything the editor commits" means. Takes the row for [create]'s
     *  reason: a caller showing what it wrote must be showing the same value. */
    suspend fun save(place: Place) = seeding {
        dao.update(written(place).edit())
    }

    /** A place the app writes on the user's behalf is theirs from then on, whatever made the row. */
    private fun written(place: Place): Place {
        require(place.placeOrigin == PlaceOrigin.MANUAL) { "a place the user writes is ${PlaceOrigin.MANUAL.code}" }
        return place
    }

    /**
     * Tag what the place is for; null untags. Clustering reads only the pin and radius, so this is
     * the one write here that cannot move a visit between places — and the only one that does not
     * go through [seeding]. That a category is not a seed is a fact about the derivation, stated
     * here by the plumbing rather than proven per tap by a reconcile that could only find nothing.
     */
    suspend fun setCategory(id: Long, category: PlaceCategory?) = dao.setCategory(id, category?.code)

    /** What a [delete] takes with the row: its identities, and the tracks whose start or end was
     *  stated to it, which the foreign keys clear. */
    class Removal(
        val place: Place,
        val identities: List<PlaceIdentity>,
        val startsOf: List<Long>,
        val endsOf: List<Long>,
    )

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
     * Folds [absorbed] into [keep]: the trip ends stated to each absorbed row are stated to [keep], its
     * identities name [keep], and the rows are removed. [keep]'s own row is not written. A row already gone is skipped, and
     * nothing happens if [keep] itself is gone.
     */
    suspend fun merge(keep: Place, absorbed: List<Place>): Merge = seeding {
        if (dao.place(keep.id) == null) return@seeding Merge(emptyList())
        val removals = absorbed
            .filter { it.id != keep.id && dao.place(it.id) != null }
            .map { place ->
                val removal = remove(place, heir = keep.id)
                removal.startsOf.chunked(IDS_PER_STATEMENT).forEach { tracks.stateStarts(it, keep.id) }
                removal.endsOf.chunked(IDS_PER_STATEMENT).forEach { tracks.stateEnds(it, keep.id) }
                removal
            }
        Merge(removals)
    }

    /** Undo a [merge]: every absorbed row, its identities and every end stated to it come back as
     *  they were. */
    suspend fun unmerge(merge: Merge) = seeding { merge.removals.forEach { reinstate(it) } }

    /** [heir] takes the row's identities, which the foreign key would otherwise delete with it. */
    private suspend fun remove(place: Place, heir: Long? = null): Removal {
        val removal = Removal(
            place, identities.of(place.id), tracks.startsStatedTo(place.id), tracks.endsStatedTo(place.id),
        )
        if (heir != null) identities.repoint(place.id, heir)
        dao.delete(place.id)
        return removal
    }

    private suspend fun reinstate(removal: Removal) {
        dao.insert(removal.place)
        identities.upsert(removal.identities)
        removal.startsOf.chunked(IDS_PER_STATEMENT).forEach { tracks.stateStarts(it, removal.place.id) }
        removal.endsOf.chunked(IDS_PER_STATEMENT).forEach { tracks.stateEnds(it, removal.place.id) }
    }

    /**
     * One write to `places`, with the derivation's seeds brought back into agreement with it before
     * the transaction closes — so no reader can see a place whose circle the stored stays were not
     * derived against. A place gaining or losing its name re-derives as well, since
     * [DerivationStore.reconcile] cannot see it.
     */
    private suspend fun <T> seeding(write: suspend () -> T): T = db.withTransaction {
        val named = dao.namedIds().toHashSet()
        val result = write()
        derivation.reconcile(stale = dao.namedIds().toHashSet() != named)
        result
    }
}
