package io.github.valeronm.breadcrumb.data

import android.content.Context
import androidx.room.withTransaction
import io.github.valeronm.breadcrumb.data.db.AppDatabase
import io.github.valeronm.breadcrumb.data.db.ClusterMember
import io.github.valeronm.breadcrumb.data.db.DerivedCluster
import io.github.valeronm.breadcrumb.data.db.DerivedInterval
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.PlaceClusterer
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.toLiveness
import io.github.valeronm.breadcrumb.domain.toTrackEnd
import io.github.valeronm.breadcrumb.util.DebugLog

private const val TAG = "Breadcrumb"

/**
 * What a stored cluster is to the clustering: an anchor with a reach. **The one projection that
 * decides cluster identity**, so it is written once — a second hand-copy would let a caller seed a
 * derivation differently from the one that produced the rows it compares against.
 */
internal fun DerivedCluster.toSeed() =
    PlaceClusterer.Seed(Coordinate(anchorLat, anchorLon), radiusM)

/**
 * Writes the stay/place derivation into the database, so that reading the timeline is a query
 * rather than a walk over every track in the history.
 *
 * [rebuild] is O(history), which is what a *whole-derivation* answer costs: it is the pass behind
 * the things that change what every row says — a place named or deleted, an import, a history-wide
 * sweep — and the repair when the stored rows are doubted.
 *
 * [db] is a seam, as on [TrackRepository]: production passes nothing and gets the app's singleton.
 */
class DerivationStore(context: Context, private val db: AppDatabase = AppDatabase.get(context)) {

    private val derived = db.derivedDao()
    private val tracks = db.trackDao()
    private val liveness = db.livenessDao()
    private val places = db.placeDao()

    /**
     * Re-derive the whole history and replace the stored rows with it, in one transaction.
     *
     * **Named clusters are preserved, not recreated.** They are the seeds the derivation runs
     * against, so they must exist before it starts; and their ids are what a stay's place *is*, so
     * re-inserting them would silently repoint every stay in the history at a new row. Everything
     * else is output, and is written afresh.
     *
     * No open stay is stored ([StayDeriver.derive] with `emitTail = false`): it closes at the clock,
     * so a row holding it would be wrong by the time anything read it.
     *
     * One transaction for the lot, and deliberately not chunked the way the point sweeps are: a
     * half-replaced derivation is not a slower answer but a wrong one, mixing intervals derived
     * against two different sets of clusters. It holds the writer for its duration, so a recording
     * track's fixes queue behind it — a second or so, against the sweeps that precede it here
     * holding it far longer.
     */
    suspend fun rebuild(nowMs: Long = System.currentTimeMillis()) {
        try {
            db.withTransaction {
                val trackEnds = tracks.endpointsOnce().map { it.toTrackEnd() }
                SweepStatus.start(trackEnds.size)
                val seeds = derived.namedClusters()
                val derivation = StayDeriver.derive(
                    tracks = trackEnds,
                    liveness = liveness.allEvents().mapNotNull { it.toLiveness() },
                    nowMs = nowMs,
                    activeTrack = null,
                    distance = AndroidDistance,
                    placePins = seeds.map { it.toSeed() },
                    emitTail = false,
                )
                SweepStatus.advance(trackEnds.size)

                derived.deleteAllIntervals()
                derived.deleteAllMembers()
                derived.deleteUnnamedClusters()

                // Seeded clusters come back in seed order, so a cluster's index into the derivation
                // resolves to the row it was seeded from; the rest are new rows.
                val clusterRowIds = derivation.clusters.map { cluster ->
                    val sumLat = cluster.members.sumOf { it.lat }
                    val sumLon = cluster.members.sumOf { it.lon }
                    val seedIndex = cluster.seedIndex
                    if (seedIndex == null) {
                        derived.insertCluster(
                            DerivedCluster(
                                anchorLat = cluster.anchor.lat,
                                anchorLon = cluster.anchor.lon,
                                radiusM = cluster.radiusM,
                                sumLat = sumLat,
                                sumLon = sumLon,
                                memberCount = cluster.visitCount,
                            ),
                        )
                    } else {
                        val id = seeds[seedIndex].id
                        derived.setClusterMembership(id, sumLat, sumLon, cluster.visitCount)
                        id
                    }
                }

                // Indexed rather than searched: `memberIndices` index into this list by contract,
                // and with no recording track passed above there is no index past its end.
                val endpoints = StayDeriver.endpointsOf(trackEnds)
                derived.insertMembers(
                    derivation.clusters.flatMapIndexed { index, cluster ->
                        cluster.memberIndices.map { endpoints[it].toRow(clusterRowIds[index]) }
                    },
                )
                derived.insertIntervals(derivation.intervals.map { it.toRow(clusterRowIds) })
                DebugLog.i(
                    TAG,
                    "derivation rebuild (logic v$LOGIC_VERSION) over ${trackEnds.size} tracks: " +
                        "${derivation.clusters.size} clusters, ${derivation.intervals.size} intervals",
                )
            }
        } finally {
            SweepStatus.finish()
        }
    }

    /**
     * Give every place a cluster of its own, from the pin and reach it was created with.
     *
     * This is what carries user curation into these tables: a place's influence on the derivation is
     * its pin and its reach, and here that becomes a seed row the derivation can preserve, rather
     * than a projection of the places table taken afresh each time. Places that already have one are
     * skipped, which makes it safe to re-run — as a pass that crashes between the work and its flag
     * will be.
     */
    suspend fun linkPlacesToClusters() {
        db.withTransaction {
            val alreadyLinked = derived.namedClusters().mapTo(HashSet()) { it.placeId }
            for (place in places.allPlaces()) {
                if (place.id in alreadyLinked) continue
                derived.insertCluster(
                    DerivedCluster(
                        placeId = place.id,
                        anchorLat = place.lat,
                        anchorLon = place.lon,
                        radiusM = place.radiusM,
                        sumLat = 0.0,
                        sumLon = 0.0,
                        memberCount = 0,
                    ),
                )
            }
        }
    }

    private fun StayDeriver.EndpointRef.toRow(clusterRowId: Long) = ClusterMember(
        clusterId = clusterRowId,
        trackId = trackId,
        isStart = isStart,
        lat = at.lat,
        lon = at.lon,
        atMs = atMs,
    )

    private fun StayDeriver.Interval.toRow(clusterRowIds: List<Long>): DerivedInterval = when (this) {
        is StayDeriver.Stay -> DerivedInterval(
            type = DerivedInterval.TYPE_STAY,
            start = start,
            // Only the open stay has no end, and this derivation does not emit one.
            endedAt = checkNotNull(end) { "a stored stay must be closed" },
            afterTrackId = afterTrackId,
            provenance = when (provenance) {
                StayDeriver.Provenance.OBSERVED -> DerivedInterval.PROVENANCE_OBSERVED
                StayDeriver.Provenance.INFERRED -> DerivedInterval.PROVENANCE_INFERRED
            },
            clusterId = clusterRowIds[clusterId],
        )
        is StayDeriver.Gap -> DerivedInterval(
            type = DerivedInterval.TYPE_GAP,
            start = start,
            endedAt = end,
            afterTrackId = afterTrackId,
            reason = when (reason) {
                StayDeriver.GapReason.MOVED_UNRECORDED -> DerivedInterval.REASON_MOVED_UNRECORDED
                StayDeriver.GapReason.UNKNOWN_ENDPOINT -> DerivedInterval.REASON_UNKNOWN_ENDPOINT
            },
            fromClusterId = fromClusterId?.let { clusterRowIds[it] },
            toClusterId = toClusterId?.let { clusterRowIds[it] },
            fromLat = from?.lat,
            fromLon = from?.lon,
            toLat = to?.lat,
            toLon = to?.lon,
        )
    }

    companion object {
        /**
         * What the stored rows were derived by. Raising it re-derives every install's history on its
         * next launch, which is the lever for a rule change here or in the deriver — and the repair
         * for rows nobody trusts.
         */
        const val LOGIC_VERSION = 1
    }
}
