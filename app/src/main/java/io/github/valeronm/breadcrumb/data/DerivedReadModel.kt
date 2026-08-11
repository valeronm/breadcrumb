package io.github.valeronm.breadcrumb.data

import io.github.valeronm.breadcrumb.data.db.ClusterMember
import io.github.valeronm.breadcrumb.data.db.DerivedCluster
import io.github.valeronm.breadcrumb.data.db.DerivedInterval
import io.github.valeronm.breadcrumb.data.db.LivenessEvent
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.PlaceClusterer
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.toLiveness
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
     * Rows in, a derivation out.
     *
     * **Cluster order is a contract, not a convenience.** [PlaceResolver] resolves a cluster to a
     * place *positionally* — `seedIndex` indexes [StoredDerivation.places] — so named clusters come
     * first in the order those are given, and unnamed ones follow. A stay's stored cluster is a row id,
     * translated to that position here; no row id escapes this file.
     *
     * The trailing stay is appended rather than read: it closes at [nowMs] or at [activeStartedAt],
     * so no row could hold it ([StayDeriver.tail]).
     *
     * **What that stay cannot say is that the reader has moved on.** `tail` decides between a stay
     * and a gap from bounds alone; separating them needs the recording track's first fix beside the
     * last track's end, and only [activeStartedAt] reaches here. So while a track that began
     * somewhere else is recording, the timeline shows the previous place held until that track
     * started — corrected, not merely refreshed, the moment it finishes and the pair is derived from
     * two stored endpoints. Closing that would mean carrying the live fix this far and clustering it
     * on the read path, which is the walk persisting the derivation removed; the reading is wrong
     * for the length of one live track and fixes itself, which is why it is written down instead.
     */
    fun derivationOf(
        stored: StoredDerivation,
        liveness: List<LivenessEvent>,
        nowMs: Long,
        activeStartedAt: Long?,
    ): StayDeriver.Derivation {
        val placeOrder = stored.places.withIndex().associate { (index, place) -> place.id to index }
        // Resolved once per row and used for both the order and the seed index, so the two cannot
        // disagree about which clusters are named.
        val positionOf = { row: DerivedCluster -> row.placeId?.let(placeOrder::get) }
        val (named, unnamed) = stored.clusters.partition { positionOf(it) != null }
        val ordered = named.sortedBy(positionOf) + unnamed.sortedBy { it.id }
        val indexOfRow = ordered.withIndex().associate { (index, row) -> row.id to index }

        val membersByCluster = stored.members.groupBy { it.clusterId }
        val clusters = ordered.map { row ->
            val locations = membersByCluster[row.id].orEmpty().map { Coordinate(it.lat, it.lon) }
            val anchor = Coordinate(row.anchorLat, row.anchorLon)
            PlaceClusterer.Cluster(
                anchor = anchor,
                // Asked of the clusterer rather than restated over the stored sums, so a cluster
                // read back reports itself where a freshly derived one would.
                centroid = PlaceClusterer.centroidOf(row.sumLat, row.sumLon, row.memberCount, anchor),
                memberIndices = emptyList(),
                members = locations,
                radiusM = row.radiusM,
                seedIndex = positionOf(row),
            )
        }

        val out = stored.intervals.mapNotNull { it.toInterval(indexOfRow) }.toMutableList()
        tailAnchor(stored.members, indexOfRow)?.let { anchor ->
            StayDeriver.tail(anchor, liveness.mapNotNull { it.toLiveness() }, nowMs, activeStartedAt)
                ?.let { out += it }
        }
        return StayDeriver.Derivation(out, clusters)
    }

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
     * forward-compatible case, and the only expected one — or a stay whose cluster is missing,
     * which is a broken reference and says so in the log rather than quietly shortening the
     * timeline.
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
                        provenance = provenanceOf(provenance),
                        afterTrackId = afterTrackId,
                        clusterId = cluster,
                    )
                }
            }
            DerivedInterval.TYPE_GAP -> StayDeriver.Gap(
                start = start,
                end = endedAt,
                reason = reasonOf(reason),
                afterTrackId = afterTrackId,
                fromClusterId = fromClusterId?.let(indexOfRow::get),
                toClusterId = toClusterId?.let(indexOfRow::get),
                from = coordinateOf(fromLat, fromLon),
                to = coordinateOf(toLat, toLon),
            )
            else -> null
        }

    /**
     * A stored code this build doesn't know reads as the *unattested* value of its vocabulary, and
     * says so: both of these decide how much the app claims to know, and claiming less than the
     * writer meant is the harmless direction.
     */
    private fun provenanceOf(code: String?): StayDeriver.Provenance = when (code) {
        DerivedInterval.PROVENANCE_OBSERVED -> StayDeriver.Provenance.OBSERVED
        DerivedInterval.PROVENANCE_INFERRED -> StayDeriver.Provenance.INFERRED
        else -> {
            DebugLog.w(TAG, "unreadable stay provenance '$code'; read as inferred")
            StayDeriver.Provenance.INFERRED
        }
    }

    private fun reasonOf(code: String?): StayDeriver.GapReason = when (code) {
        DerivedInterval.REASON_MOVED_UNRECORDED -> StayDeriver.GapReason.MOVED_UNRECORDED
        DerivedInterval.REASON_UNKNOWN_ENDPOINT -> StayDeriver.GapReason.UNKNOWN_ENDPOINT
        else -> {
            DebugLog.w(TAG, "unreadable gap reason '$code'; read as unknown endpoint")
            StayDeriver.GapReason.UNKNOWN_ENDPOINT
        }
    }

    private fun coordinateOf(lat: Double?, lon: Double?): Coordinate? =
        if (lat != null && lon != null) Coordinate(lat, lon) else null
}
