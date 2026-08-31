package io.github.valeronm.breadcrumb.data

import android.content.Context
import androidx.room.withTransaction
import io.github.valeronm.breadcrumb.data.db.AppDatabase
import io.github.valeronm.breadcrumb.data.db.ClusterMember
import io.github.valeronm.breadcrumb.data.db.DerivedCluster
import io.github.valeronm.breadcrumb.data.db.DerivedInterval
import io.github.valeronm.breadcrumb.data.db.IDS_PER_STATEMENT
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.PlaceClusterer
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.StayLedger
import io.github.valeronm.breadcrumb.domain.toTrackEnd
import io.github.valeronm.breadcrumb.util.DebugLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.runningFold

private const val TAG = "Breadcrumb"

/**
 * What a stored cluster is to the clustering: an anchor with a reach. **The one projection that
 * decides cluster identity**, so it is written once — a second hand-copy would let a caller seed a
 * derivation differently from the one that produced the rows it compares against.
 */
internal fun DerivedCluster.toSeed() =
    PlaceClusterer.Seed(Coordinate(anchorLat, anchorLon), radiusM)

/**
 * The stay/place derivation's storage, read and written — so that showing the timeline is a query
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
    private val places = db.placeDao()

    /**
     * The derivation as **one reading of every table it is made of**, re-emitted whenever any of
     * them is written.
     *
     * One flow rather than a query each, for two reasons that point the same way. A write rewrites
     * several of them in a single transaction, and an observer each would turn that into a
     * re-emission each, every one re-running every reader downstream. And they are only meaningful
     * together — an interval names a cluster, a cluster is placed by its members, and **a cluster is
     * named by a place**, whose row read apart from them is a named place no cluster points at, which
     * is a place with no visits. So they are read inside a transaction, which is what makes a set of
     * rows a snapshot rather than several timings.
     *
     * **A write that reached no derived table re-reads none of them**, which is what a rename and a
     * re-categorization are: the invalidation says which tables were written, and the derived rows —
     * one per endpoint and one per interval in the whole history — are carried over instead of read
     * again. Carried over on two conditions, not one: the reuse is only sound while the rows still
     * describe the places just read, and the emission naming a table is evidence about the moment the
     * tracker last refreshed rather than about this read. So the seeds are held against the fresh
     * rows here too — cheap, being over rows already in hand — and a disagreement reads everything.
     */
    fun observeStored(): Flow<StoredDerivation> =
        db.invalidationTracker
            .createFlow(TABLE_PLACES, *DERIVED_TABLES)
            .runningFold(null as StoredDerivation?) { previous, invalidated ->
                db.withTransaction {
                    val rows = places.allPlaces()
                    val carried = previous
                        ?.takeIf { DERIVED_TABLES.none(invalidated::contains) }
                        ?.takeIf { seedsAgree(it.clusters, rows) }
                    StoredDerivation(
                        clusters = carried?.clusters ?: derived.clustersOnce(),
                        members = carried?.members ?: derived.membersOnce(),
                        intervals = carried?.intervals ?: derived.intervalsOnce(),
                        places = rows,
                    )
                }
            }
            .filterNotNull()

    /**
     * Whether [clusters] still says exactly what [rows] do about seeds — the correspondence
     * [reconcile] establishes, asked of rows already in hand rather than of the database. A place
     * with no seed cluster, or one whose circle has moved out from under it, is precisely the reading
     * [observeStored] exists to prevent.
     */
    private fun seedsAgree(clusters: List<DerivedCluster>, rows: List<Place>): Boolean {
        val seeded = clusters.mapNotNull { row -> row.placeId?.let { it to row } }.toMap()
        return seeded.size == rows.size && rows.all { seeds(seeded[it.id], it) }
    }

    /** Whether [cluster] is the seed cluster [place] should have — what [alignSeeds] establishes
     *  one place at a time and [seedsAgree] asks of a whole reading. */
    private fun seeds(cluster: DerivedCluster?, place: Place) =
        cluster != null && cluster.toSeed() == PlaceClusterer.seedOf(place)

    /**
     * Re-derive the whole history and replace the stored rows with it, in one transaction.
     *
     * **Named clusters are preserved, not recreated.** They are the seeds the derivation runs
     * against, so they must exist before it starts; and their ids are what a stay's place *is*, so
     * re-inserting them would silently repoint every stay in the history at a new row. Everything
     * else is output, and is written afresh.
     *
     * No open stay is stored — [StayDeriver.derive] emits none, it closing at the clock, so a row
     * holding it would be wrong by the time anything read it.
     *
     * One transaction for the lot, and deliberately not chunked the way the point sweeps are: a
     * half-replaced derivation is not a slower answer but a wrong one, mixing intervals derived
     * against two different sets of clusters. It holds the writer for its duration, so a recording
     * track's fixes queue behind it — a second or so, against the sweeps that precede it here
     * holding it far longer.
     */
    suspend fun rebuild() {
        try {
            db.withTransaction {
                val trackEnds = tracks.endpointsOnce().map { it.toTrackEnd() }
                SweepStatus.start(trackEnds.size)
                val seeds = derived.namedClusters()
                val derivation = StayDeriver.derive(
                    tracks = trackEnds,
                    distance = AndroidDistance,
                    placePins = seeds.map { it.toSeed() },
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
                        derived.insertCluster(seedRow(cluster.seed, sumLat, sumLon, cluster.visitCount))
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
                        cluster.memberIndices.map {
                            endpoints[it].asMembership(clusterRowIds[index]).toRow()
                        }
                    },
                )
                derived.insertIntervals(
                    derivation.intervals.map { it.asLedgerInterval(clusterRowIds).toRow() },
                )
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
     * Bring the derivation back into agreement with what it is derived from, re-deriving the history
     * when it has to — the one entry point for every caller that has changed a *seed*, and the only
     * way [rebuild] is reached outside a repair.
     *
     * The seed half is the whole of how user curation reaches these tables. A place's influence on
     * the derivation is its pin and its reach ([PlaceClusterer.seedOf], the one projection that says
     * so), so a place with no cluster gets one, a place whose circle moved moves its cluster's, and a
     * cluster whose place is gone goes with it — the rebuild then re-derives that ground organically.
     * A rename or a re-categorization moves no seed: nothing is written and no history is re-derived.
     *
     * [stale] is for a caller that knows the rows are wrong for a reason of its own — a sweep having
     * rewritten the endpoints under them, a restore having just landed a history, this build's rules
     * having outrun what they were derived by. Taking it here rather than leaving each caller to pair
     * a check with a rebuild is what keeps a seed move from being noticed and then dropped, and it
     * costs those callers nothing: one pass covers both reasons.
     *
     * The seed pass is idempotent and cheap enough to run on every launch and every place write: two
     * small queries against tables measured in hundreds of rows.
     */
    suspend fun reconcile(stale: Boolean = false) {
        db.withTransaction {
            // Seeds first whatever the reason, since a rebuild derives against them: a `stale`
            // caller that rebuilt before this pass would derive from the seeds it is replacing.
            val seedsMoved = alignSeeds()
            if (seedsMoved || stale) rebuild()
        }
    }

    /** Whether reconciling the seeds moved one. Separate only so [reconcile] can read as the rule
     *  it enforces; nothing else may call it, a true answer being an obligation to re-derive. */
    private suspend fun alignSeeds(): Boolean {
        val seeded = derived.namedClusters().associateBy { checkNotNull(it.placeId) }
        val rows = places.allPlaces()
        val kept = rows.mapTo(HashSet()) { it.id }
        var changed = false
        val orphaned = seeded.filterKeys { it !in kept }.map { (_, cluster) -> cluster.id }
        if (orphaned.isNotEmpty()) {
            // Chunked because nothing bounds it: every named place deleted since the last pass is here.
            orphaned.chunked(IDS_PER_STATEMENT).forEach { derived.deleteClusters(it) }
            changed = true
        }
        for (place in rows) {
            val cluster = seeded[place.id]
            if (seeds(cluster, place)) continue
            val seed = PlaceClusterer.seedOf(place)
            if (cluster == null) {
                derived.insertCluster(seedRow(seed, placeId = place.id))
            } else {
                derived.setClusterSeed(cluster.id, seed.anchor.lat, seed.anchor.lon, seed.radiusM)
            }
            changed = true
        }
        return changed
    }

    /** A cluster row at an anchor and reach — the inverse of [toSeed]. Empty of members by default,
     *  which is what a pin nothing has visited and a cluster nothing has joined yet both are. */
    private fun seedRow(
        seed: PlaceClusterer.Seed,
        sumLat: Double = 0.0,
        sumLon: Double = 0.0,
        memberCount: Int = 0,
        placeId: Long? = null,
    ) = DerivedCluster(
        placeId = placeId,
        anchorLat = seed.anchor.lat,
        anchorLon = seed.anchor.lon,
        radiusM = seed.radiusM,
        sumLat = sumLat,
        sumLon = sumLon,
        memberCount = memberCount,
    )

    /**
     * Repair the stored rows around the tracks a change touched, instead of deriving the history
     * again — the seam is worked out here and judged by [StayLedger], which owns the rule.
     *
     * [changedTrackIds] is whatever the caller wrote to: finished, deleted, restored, merged, split
     * or rewritten. Which of them are still on the timeline decides what arrives, and which of them
     * have stored endpoints decides what leaves, so a caller lists what it touched rather than
     * classifying it — a rewritten track is in both sets and needs no saying so.
     *
     * Runs inside the caller's transaction (Room's are re-entrant), which is the point: the track
     * rows and the derivation over them commit together or not at all.
     */
    suspend fun reknit(changedTrackIds: Collection<Long>) {
        val ids = changedTrackIds.distinct()
        if (ids.isEmpty()) return
        db.withTransaction {
            val added = tracks.endpointsFor(ids).map { it.toTrackEnd() }
            val leaving = derived.membersForTracks(ids)
            // Neither on the timeline nor in the derivation: a track discarded at birth by the keep
            // thresholds, whose finish nothing here ever saw.
            if (added.isEmpty() && leaving.isEmpty()) return@withTransaction
            // Both lists are in time order, and no kept track overlaps another, so the first and
            // last of them bound the stretch the change can have altered.
            val from = minOf(
                added.firstOrNull()?.startedAt ?: Long.MAX_VALUE,
                leaving.firstOrNull()?.atMs ?: Long.MAX_VALUE,
            )
            val to = maxOf(
                added.lastOrNull()?.endedAt ?: Long.MIN_VALUE,
                leaving.lastOrNull()?.atMs ?: Long.MIN_VALUE,
            )
            val prev = tracks.keptTrackBefore(from, ids)?.toTrackEnd()
            val next = tracks.keptTrackAfter(to, ids)?.toTrackEnd()
            val neighbours = derived.membersForTracks(listOfNotNull(prev?.trackId, next?.trackId))
            // Every touched id, not the subset with stored endpoints: the ledger reads a removal only
            // through the memberships it finds, so one with none takes nothing away, and classifying
            // them here would be a rule with a second author.
            apply(
                StayLedger.reknit(
                    seam = StayLedger.Seam(prev, ids, added, next),
                    stored = StayLedger.Stored(
                        clusters = derived.clustersOnce().map { it.toClusterRow() },
                        membershipOf = (leaving + neighbours).map { it.toMembership() }
                            .groupBy { it.trackId },
                    ),
                    distance = AndroidDistance,
                ),
            )
        }
    }

    /**
     * Write what a repair decided. The order is the whole of it: what goes, goes first, so a founded
     * cluster cannot collide with a membership about to be deleted; the founded rows are inserted
     * empty and filled by the same deltas that move every other cluster's; and the clusters left
     * holding nothing are dropped after those deltas, which is what makes them nothing.
     */
    private suspend fun apply(mutations: StayLedger.Mutations) {
        derived.deleteIntervalsAfter(mutations.removed.intervalsAfterTracks)
        derived.deleteMembersOf(mutations.removed.membershipsOfTracks)
        val founded = mutations.founded.map { derived.insertCluster(seedRow(it)) }
        derived.insertMembers(mutations.memberships.map { it.toRow(founded) })
        for (delta in mutations.deltas) {
            derived.shiftClusterMembership(
                delta.cluster.rowId(founded), delta.sumLat, delta.sumLon, delta.count,
            )
        }
        derived.deleteClusters(mutations.removed.emptiedClusters)
        derived.insertIntervals(mutations.intervals.map { it.toRow(founded) })
    }

    private fun DerivedCluster.toClusterRow() = StayLedger.ClusterRow(
        id = id,
        seed = toSeed(),
        named = placeId != null,
        memberCount = memberCount,
    )

    private fun ClusterMember.toMembership() = StayLedger.Membership(
        trackId = trackId,
        isStart = isStart,
        at = Coordinate(lat, lon),
        atMs = atMs,
        cluster = StayLedger.ClusterRef.Stored(clusterId),
    )

    /** A whole derivation's endpoint in the shape a repair produces, the cluster it fell into being
     *  a row the rebuild has already inserted. */
    private fun StayDeriver.EndpointRef.asMembership(clusterRowId: Long) = StayLedger.Membership(
        trackId = trackId,
        isStart = isStart,
        at = at,
        atMs = atMs,
        cluster = StayLedger.ClusterRef.Stored(clusterRowId),
    )

    /** **The one writer of a membership row**, as [toRow] is of an interval's and for the reason
     *  given there — both passes state an endpoint's cluster as a [StayLedger.ClusterRef] and
     *  resolve it here. */
    private fun StayLedger.Membership.toRow(founded: List<Long> = emptyList()) = ClusterMember(
        clusterId = cluster.rowId(founded),
        trackId = trackId,
        isStart = isStart,
        lat = at.lat,
        lon = at.lon,
        atMs = atMs,
    )

    /**
     * A reading of [stored] as the derivation the screens consume — **the one entry point for it**,
     * so that mapping the rows and appending the trailing stay is one act with one author rather
     * than a sequence every caller repeats.
     *
     * It is a method and not part of [observeStored] because its other inputs move with no write
     * behind them: the clock, whether something is recording, and [disarmedSince] — when the
     * recorder was last turned off ([Settings.disarmedSinceMs]), which is the instant the trailing
     * stay closes at.
     *
     * **The mapped rows are kept while the rows are.** [observeStored] carries its lists over
     * unchanged when a write reached no derived table, so identity is enough to know the mapping
     * still holds — and every other input to this (a recording starting, a disarm, the clock)
     * re-emits without touching them. Mapping the history again for those would be the larger half
     * of what a reading costs, paid for something that cannot have changed it.
     */
    internal fun read(
        stored: StoredDerivation,
        nowMs: Long,
        activeStartedAt: Long?,
        disarmedSince: Long? = null,
    ): StayDeriver.Derivation {
        val rows = mapped?.takeIf { it.matches(stored) }?.rows
            ?: DerivedReadModel.mappedRows(stored).also { mapped = Mapped(stored, it) }
        val out = rows.intervals.toMutableList()
        rows.tailAnchor?.let { anchor ->
            StayDeriver.tail(
                anchor = anchor,
                disarmedSince = disarmedSince?.coerceAtMost(nowMs),
                nowMs = nowMs,
                activeStartedAt = activeStartedAt,
            )?.let { out += it }
        }
        return StayDeriver.Derivation(out, rows.clusters)
    }

    /**
     * Last reading's mapping, against the row lists it was made from. Written only from [read], which
     * one flow's coroutine drives; nothing else may touch it. Held by identity rather than by value —
     * the question is whether these are the same lists, not whether they say the same thing, and
     * comparing ~20,000 rows for equality would cost what the mapping costs.
     */
    private class Mapped(private val of: StoredDerivation, val rows: DerivedReadModel.MappedRows) {
        fun matches(stored: StoredDerivation) =
            of.clusters === stored.clusters &&
                of.members === stored.members &&
                of.intervals === stored.intervals &&
                of.places === stored.places
    }

    private var mapped: Mapped? = null

    /** Which row a repair's reference names — [founded] holds the ids its new clusters were given,
     *  in the order it declared them. A rebuild founds nothing and passes none. */
    private fun StayLedger.ClusterRef.rowId(founded: List<Long>): Long = when (this) {
        is StayLedger.ClusterRef.Stored -> id
        is StayLedger.ClusterRef.Founded -> founded[index]
    }

    /**
     * **The one writer of an interval row**, reached by both passes: a rebuild states its clusters
     * as positions in its own list and converts them here, a repair as row ids and rows it is about
     * to insert. Two of these would be two spellings of the same vocabulary, which is the failure
     * [DerivedReadModel] is the other half of.
     */
    private fun StayLedger.IntervalRow.toRow(founded: List<Long> = emptyList()): DerivedInterval = when (verdict) {
        StayDeriver.Verdict.Stayed -> DerivedInterval(
            type = DerivedInterval.TYPE_STAY,
            start = start,
            endedAt = end,
            afterTrackId = afterTrackId,
            clusterId = checkNotNull(cluster) { "an agreeing pair has both ends" }.rowId(founded),
        )
        is StayDeriver.Verdict.Moved -> DerivedInterval(
            type = DerivedInterval.TYPE_GAP,
            start = start,
            endedAt = end,
            afterTrackId = afterTrackId,
            reason = when (verdict.reason) {
                StayDeriver.GapReason.MOVED_UNRECORDED -> DerivedInterval.REASON_MOVED_UNRECORDED
                StayDeriver.GapReason.UNKNOWN_ENDPOINT -> DerivedInterval.REASON_UNKNOWN_ENDPOINT
            },
            fromClusterId = cluster?.rowId(founded),
            toClusterId = toCluster?.rowId(founded),
            fromLat = from?.lat,
            fromLon = from?.lon,
            toLat = to?.lat,
            toLon = to?.lon,
        )
    }

    /**
     * A whole derivation's interval in the shape a repair produces — the clusters it names by
     * position translated to the rows they were stored as, which is all the two representations
     * differ by.
     */
    private fun StayDeriver.Interval.asLedgerInterval(clusterRowIds: List<Long>): StayLedger.IntervalRow {
        fun ref(clusterId: Int) = StayLedger.ClusterRef.Stored(clusterRowIds[clusterId])
        return when (this) {
            is StayDeriver.Stay -> StayLedger.IntervalRow(
                verdict = StayDeriver.Verdict.Stayed,
                start = start,
                // Only the open stay has no end, and this derivation does not emit one.
                end = checkNotNull(end) { "a stored stay must be closed" },
                afterTrackId = afterTrackId,
                ends = StayLedger.Ends(ref(clusterId), null, null, null),
            )
            is StayDeriver.Gap -> StayLedger.IntervalRow(
                verdict = StayDeriver.Verdict.Moved(reason),
                start = start,
                end = end,
                afterTrackId = afterTrackId,
                ends = StayLedger.Ends(fromClusterId?.let(::ref), toClusterId?.let(::ref), from, to),
            )
        }
    }

    companion object {
        /**
         * What the stored rows were derived by. Raising it re-derives every install's history on its
         * next launch, which is the lever for a rule change here or in the deriver — and the repair
         * for rows nobody trusts.
         */
        const val LOGIC_VERSION = 2

        /** The tables this class writes — the ones [observeStored] carries over when a write
         *  reached none of them. Named apart from `places`, which is the writing the user does. */
        private val DERIVED_TABLES =
            arrayOf("derived_clusters", "cluster_members", "derived_intervals")

        private const val TABLE_PLACES = "places"
    }
}
