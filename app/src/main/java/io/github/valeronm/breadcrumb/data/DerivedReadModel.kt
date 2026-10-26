package io.github.valeronm.breadcrumb.data

import io.github.valeronm.breadcrumb.data.db.ClusterMember
import io.github.valeronm.breadcrumb.data.db.DerivedCluster
import io.github.valeronm.breadcrumb.data.db.DerivedInterval
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.PlaceClusterer
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.util.DebugLog

private const val TAG = "Breadcrumb"

/** One consistent reading of everything the derivation is made of, [places] included — see
 *  [DerivationStore.observeStored], which says why they are one reading. */
class StoredDerivation(
    val clusters: List<DerivedCluster>,
    val members: List<ClusterMember>,
    val intervals: List<DerivedInterval>,
    val places: List<Place>,
)

/**
 * Stored rows read back as the shape the screens consume — the same [StayDeriver.Derivation] a full
 * derivation produces, so nothing downstream can tell which one it was handed. That equivalence is
 * the point: place resolution, the day slicing, the totals and the journeys are unchanged by
 * persistence, and `DerivedReadModelTest` holds the two answers against each other.
 *
 * The exact inverse of the mapping [DerivationStore] writes with, which is why the two sit in one
 * package: a code added to one is unreadable until it is added to the other.
 */
internal object DerivedReadModel {

    /**
     * The stored rows as the shapes the screens consume — **everything a derivation holds that a row
     * can hold**, which is all of it bar the stay still running.
     *
     * Separate from that trailing stay because the two go stale on different terms: this is a
     * function of the rows alone, so it is worth keeping while they are, and the tail moves with the
     * clock. [DerivationStore.read] is what pairs them.
     *
     * **Cluster order is a contract, not a convenience.** [PlaceResolver] resolves a cluster to a
     * place *positionally* — `seedIndex` indexes [StoredDerivation.places] — so seeded clusters come
     * first in the order those are given, and organic ones follow. A stay's stored cluster is a row id,
     * translated to that position here; no row id escapes this file.
     *
     * **The walk is over the whole history and is meant to be.** A screen that draws every day of a
     * history pays for every day of it.
     */
    fun mappedRows(stored: StoredDerivation): MappedRows {
        val placeOrder = stored.places.withIndex().associate { (index, place) -> place.id to index }
        // Resolved once per row and used for both the order and the seed index, so the two cannot
        // disagree about which clusters are seeded.
        val positionOf = { row: DerivedCluster -> row.placeId?.let(placeOrder::get) }
        val (seeded, organic) = stored.clusters.partition { positionOf(it) != null }
        val ordered = seeded.sortedBy(positionOf) + organic.sortedBy { it.id }
        val indexOfRow = ordered.withIndex().associate { (index, row) -> row.id to index }

        val membersByCluster = stored.members.groupBy { it.clusterId }
        val clusters = ordered.map { row ->
            val locations = membersByCluster[row.id].orEmpty().map { Coordinate(it.lat, it.lon) }
            PlaceClusterer.Cluster(
                anchor = Coordinate(row.anchorLat, row.anchorLon),
                placing = PlaceClusterer.Sums(row.sumLat, row.sumLon, row.memberCount),
                memberIndices = emptyList(),
                members = locations,
                radiusM = row.radiusM,
                seedIndex = positionOf(row),
            )
        }

        return MappedRows(
            clusters = clusters,
            intervals = stored.intervals.mapNotNull { it.toInterval(indexOfRow) },
            tailAnchor = tailAnchor(stored.members, indexOfRow),
        )
    }

    /**
     * A reading of the stored rows, and the anchor the stay still running would hang off. Everything
     * here is a function of those rows and of nothing else — no clock, no recording track — which is
     * what lets a caller keep one while the rows it was mapped from are unchanged.
     */
    class MappedRows(
        val clusters: List<PlaceClusterer.Cluster>,
        val intervals: List<StayDeriver.Interval>,
        val tailAnchor: StayDeriver.TailAnchor?,
    )

    /**
     * What the trailing stay hangs off: the newest kept track's end endpoint, whose `atMs` is that
     * track's end bound. Found among the member rows already read rather than by its own query —
     * one row out of a table held in full is not worth a second observer, and a second one could
     * disagree with the first about which track is newest. Taken off the back of the list, which
     * `DerivedDao.membersOnce` orders by `atMs`: scanning for the maximum would copy half the
     * member table on every reading to answer for one row.
     */
    private fun tailAnchor(
        members: List<ClusterMember>,
        indexOfRow: Map<Long, Int>,
    ): StayDeriver.TailAnchor? {
        val last = members.lastOrNull { !it.isStart } ?: return null
        val cluster = indexOfRow[last.clusterId] ?: return null
        return StayDeriver.TailAnchor(last.trackId, last.atMs, cluster)
    }

    /**
     * Null where this build cannot read the row: a `type` its vocabulary predates — the
     * forward-compatible case, and the only expected one — or a broken row, logged rather than
     * quietly shortening the timeline: a stay whose cluster is missing, or a gap side holding a
     * position without a cluster or a cluster without a position.
     */
    private fun DerivedInterval.toInterval(indexOfRow: Map<Long, Int>): StayDeriver.Interval? =
        when (type) {
            DerivedInterval.TYPE_STAY -> {
                val cluster = clusterId?.let(indexOfRow::get)
                if (cluster == null) {
                    DebugLog.w(TAG, "stay after track $afterTrackId names no readable cluster; dropped")
                    null
                } else {
                    StayDeriver.Stay(
                        start = start,
                        end = endedAt,
                        afterTrackId = afterTrackId,
                        clusterId = cluster,
                    )
                }
            }
            DerivedInterval.TYPE_GAP -> {
                val from = storedSide(fromLat, fromLon, fromClusterId, indexOfRow)
                val to = storedSide(toLat, toLon, toClusterId, indexOfRow)
                if (from == null || to == null) {
                    DebugLog.w(TAG, "gap after track $afterTrackId has a side whose position and cluster disagree; dropped")
                    null
                } else {
                    StayDeriver.Gap(start, endedAt, afterTrackId, from = from.side, to = to.side)
                }
            }
            else -> null
        }

    private class StoredSide(val side: StayDeriver.Gap.Side?)

    private val ABSENT = StoredSide(null)

    /** Null where the row holds one of a side's position and cluster without the other. */
    private fun storedSide(lat: Double?, lon: Double?, clusterId: Long?, indexOfRow: Map<Long, Int>): StoredSide? {
        val at = coordinateOf(lat, lon)
        val cluster = clusterId?.let(indexOfRow::get)
        return when {
            at == null && cluster == null -> ABSENT
            at != null && cluster != null -> StoredSide(StayDeriver.Gap.Side(at, cluster))
            else -> null
        }
    }

    private fun coordinateOf(lat: Double?, lon: Double?): Coordinate? =
        if (lat != null && lon != null) Coordinate(lat, lon) else null
}
