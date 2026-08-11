package io.github.valeronm.breadcrumb.data

import androidx.test.core.app.ApplicationProvider
import io.github.valeronm.breadcrumb.data.db.AppDatabase
import io.github.valeronm.breadcrumb.data.db.ClusterMember
import io.github.valeronm.breadcrumb.data.db.DerivedCluster
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.PlaceClusterer
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.toTrackEnd
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * **What it means for the stored derivation to be right**, in the two strengths it can be right in —
 * shared by every data-layer suite that writes those rows, so there is one answer to the question
 * rather than one per test file.
 *
 * The moment a derivation is persisted there are two implementations of one rule: the pure pass in
 * [StayDeriver], and whatever wrote the rows. [assertMatchesFreshDerive] is the claim that they
 * agree — not that the rows look plausible, but that they are, column for column, what deriving the
 * whole history afresh produces.
 *
 * [assertInternallyConsistent] is the weaker claim, for the histories where exact agreement is not
 * owed. A repair keeps a cluster's anchor where its first-ever member put it, so deleting the track
 * that founded one leaves an anchor a fresh pass would have placed elsewhere (`StayLedger` states
 * this and `StayLedgerTest` pins it). Everything else still holds: the assignments are legal, the
 * arithmetic over them is right, and the intervals are what the rule says *given* those
 * assignments — and a `rebuild()` afterwards must bring the exact claim back.
 */
internal object DerivedConsistency {

    /**
     * The rows as the screens get them — one transactional reading of every table the derivation is
     * made of, through the query the app itself observes, so the guard cannot pass against a
     * snapshot the app never sees.
     */
    private suspend fun read(db: AppDatabase): StoredDerivation = store(db).observeStored().first()

    private fun store(db: AppDatabase) =
        DerivationStore(ApplicationProvider.getApplicationContext(), db)

    /** Those same rows read back as a derivation — through the app's own entry point, so the side
     *  every comparison is made against is the reading the screens take and not one only a test
     *  could assemble. */
    private suspend fun readBack(db: AppDatabase, stored: StoredDerivation, nowMs: Long) =
        store(db).read(stored, nowMs, activeStartedAt = null)

    /** The named rows in seed order — the pins the app's own derivation was seeded from, taken from
     *  the snapshot rather than re-queried so both readings are of one moment. */
    private fun StoredDerivation.seeds() = clusters.filter { it.placeId != null }

    /**
     * **Mode A**: the stored rows are exactly what a fresh derivation produces.
     *
     * Clusters are matched into a bijection first — seeded ones by identity (a seed's index is its
     * row), organic ones by their anchor, which is a coordinate a derivation either lands on or does
     * not. Everything else is then compared through that map, so a mismatch reports as the field it
     * is rather than as an off-by-one in two unrelated numberings.
     */
    suspend fun assertMatchesFreshDerive(db: AppDatabase, nowMs: Long) {
        val stored = read(db)
        val seeds = stored.seeds()
        val tracks = db.trackDao().endpointsOnce().map { it.toTrackEnd() }
        val fresh = StayDeriver.derive(
            tracks = tracks,
            distance = AndroidDistance,
            // The named rows are *inputs* to the reference pass, not outputs compared against it:
            // they are the pins the app's own derivation was seeded from, so a reference seeded any
            // other way would be answering a different question.
            placePins = seeds.map { it.toSeed() },
        )
        val endpoints = StayDeriver.endpointsOf(tracks)
        val membersOf = stored.members.groupBy { it.clusterId }
        val rowOf = bijection(fresh.clusters, stored.clusters, seeds)

        fresh.clusters.forEachIndexed { index, cluster ->
            val row = stored.clusters.first { it.id == rowOf.getValue(index) }
            val where = "cluster at ${cluster.anchor}"
            assertEquals("$where: radius", cluster.radiusM, row.radiusM, 1e-9)
            // The sums are a column, not a reading of the member rows below — so this is what says
            // the repair's running arithmetic still describes the endpoints it was arithmetic over.
            assertEquals("$where: member count", cluster.visitCount, row.memberCount)
            assertEquals("$where: sumLat", cluster.members.sumOf { it.lat }, row.sumLat, 1e-9)
            assertEquals("$where: sumLon", cluster.members.sumOf { it.lon }, row.sumLon, 1e-9)
            // Endpoint identity, not just position: two tracks can begin at the same coordinate, and
            // a membership stored against the wrong one of them would compare equal by place alone.
            assertEquals(
                "$where: members",
                cluster.memberIndices.map { endpoints[it].identity() }.toSet(),
                membersOf[row.id].orEmpty().map { it.identity() }.toSet(),
            )
        }

        // Intervals through [DerivedReadModel] rather than off the rows: that is the pair the app
        // runs — write then read — and it compares domain values, so the check does not have to
        // assume a stored code still spells its enum's name, which is the one thing the two are
        // deliberately free to disagree about.
        val read = readBack(db, stored, nowMs)
        // The reference derives with no trailing stay, so the stored rows are the read's prefix;
        // that the tail follows them is `DerivedReadModelTest`'s to say.
        assertEquals("interval count", fresh.intervals.size, stored.intervals.size)
        assertEquals(
            "intervals",
            fresh.intervals.map { it.described { index -> fresh.clusters[index].anchor } },
            read.intervals.take(stored.intervals.size)
                .map { it.described { index -> read.clusters[index].anchor } },
        )
    }

    /**
     * Which stored row each cluster of the fresh derivation is, asserted to be a bijection — a
     * derivation and a set of rows that disagree about *which places exist* cannot be compared field
     * by field at all, so this is the failure that has to be reported first.
     */
    private fun bijection(
        clusters: List<PlaceClusterer.Cluster>,
        stored: List<DerivedCluster>,
        seeds: List<DerivedCluster>,
    ): Map<Int, Long> {
        val organicByAnchor = stored.filter { it.placeId == null }.groupBy { it.anchor() }
        val rowOf = clusters.indices.associateWith { index ->
            val cluster = clusters[index]
            val seedIndex = cluster.seedIndex
            if (seedIndex != null) {
                seeds[seedIndex].id
            } else {
                val candidates = organicByAnchor[cluster.anchor].orEmpty()
                assertEquals("one stored cluster anchored at ${cluster.anchor}", 1, candidates.size)
                candidates.single().id
            }
        }
        assertEquals("no two derived clusters are the same row", rowOf.size, rowOf.values.toSet().size)
        assertEquals(
            "the stored clusters are exactly the derived ones",
            stored.map { it.id }.toSet(),
            rowOf.values.toSet(),
        )
        return rowOf
    }

    /**
     * **Mode B**: the stored rows hold together on their own terms, whatever a fresh pass would say
     * about where the anchors sit.
     *
     * Each check answers a way the rows could be wrong that Mode A would have caught by comparison:
     * an endpoint filed under a cluster that does not reach it, sums that no longer describe the
     * members they are sums of, a cluster left holding nothing, or an interval the rule would not
     * produce given these very assignments.
     */
    suspend fun assertInternallyConsistent(db: AppDatabase, nowMs: Long) {
        val stored = read(db)
        val byId = stored.clusters.associateBy { it.id }
        val membersOf = stored.members.groupBy { it.clusterId }

        for (member in stored.members) {
            val cluster = checkNotNull(byId[member.clusterId]) { "member of a cluster that is gone" }
            val away = AndroidDistance.meters(member.lat, member.lon, cluster.anchorLat, cluster.anchorLon)
            assertTrue("an endpoint sits ${away}m from a cluster reaching ${cluster.radiusM}m", away <= cluster.radiusM)
        }

        for (cluster in stored.clusters) {
            val members = membersOf[cluster.id].orEmpty()
            val where = "cluster ${cluster.id}"
            assertEquals("$where: member count", members.size, cluster.memberCount)
            assertEquals("$where: sumLat", members.sumOf { it.lat }, cluster.sumLat, 1e-9)
            assertEquals("$where: sumLon", members.sumOf { it.lon }, cluster.sumLon, 1e-9)
            if (members.isEmpty()) {
                // A named cluster is the user's and survives its last visit; an unnamed one *is* its
                // members, so an empty one is a row nothing would ever have created.
                assertTrue("$where holds nothing and no place named it", cluster.placeId != null)
            }
        }

        val expected = intervalsGivenStoredAssignments(db, stored)
        val read = readBack(db, stored, nowMs)
        assertEquals("interval count", expected.size, stored.intervals.size)
        assertEquals(
            "the rule's own verdict on each adjacent pair, granted these assignments",
            expected.map { it.described { index -> stored.clusters[index].anchor() } },
            read.intervals.take(stored.intervals.size)
                .map { it.described { index -> read.clusters[index].anchor } },
        )
    }

    /**
     * What the rule says the intervals are, taking the stored *assignments* as given — the question
     * Mode B asks that Mode A cannot: not "are the endpoints in the right clusters" but "granted
     * these clusters, are these the intervals". [StayDeriver.verdictBetween] is the same rule both
     * writing passes reach, so nothing here re-decides what a stay is; the clusters are named by
     * position in `stored.clusters`, which is what the caller resolves anchors against.
     */
    private suspend fun intervalsGivenStoredAssignments(
        db: AppDatabase,
        stored: StoredDerivation,
    ): List<StayDeriver.Interval> {
        val agreement = StayDeriver.Agreement(
            StayDeriver.Params(),
            AndroidDistance,
            stored.seeds().map { it.toSeed() },
        )
        val positionOf = stored.clusters.withIndex().associate { (index, row) -> row.id to index }
        val clusterOfEndpoint = stored.members.associate { (it.trackId to it.isStart) to positionOf.getValue(it.clusterId) }
        val tracks = db.trackDao().endpointsOnce().map { it.toTrackEnd() }
        return tracks.zipWithNext().mapNotNull { (before, after) ->
            val from = clusterOfEndpoint[before.trackId to false]
            val to = clusterOfEndpoint[after.trackId to true]
            val verdict = StayDeriver.verdictBetween(
                before, after, sameCluster = from != null && from == to, agreement,
            )
            when (verdict) {
                StayDeriver.Verdict.None -> null
                StayDeriver.Verdict.Stayed -> StayDeriver.Stay(
                    start = before.endedAt,
                    end = after.startedAt,
                    afterTrackId = before.trackId,
                    clusterId = checkNotNull(from) { "an agreeing pair has both ends" },
                )
                is StayDeriver.Verdict.Moved -> StayDeriver.Gap(
                    start = before.endedAt,
                    end = after.startedAt,
                    reason = verdict.reason,
                    afterTrackId = before.trackId,
                    fromClusterId = from,
                    toClusterId = to,
                    from = before.end,
                    to = after.start,
                )
            }
        }
    }

    /**
     * One interval as any two of these passes can be compared on: everything it says, with the
     * clusters it names given as **where they are** rather than as what they are numbered.
     *
     * A cluster index is a position in one pass's own list, and only the named ones have an order
     * anything depends on (`PlaceResolver` reads those positionally; the rest are free). A rebuild
     * numbers them as it derives them, a repair by the row ids its founding happened to take — so
     * comparing indices would compare two numberings and fail on a difference that means nothing.
     */
    private fun StayDeriver.Interval.described(anchorOf: (Int) -> Coordinate): List<Any?> {
        fun anchor(index: Int?) = index?.let(anchorOf)
        return when (this) {
            is StayDeriver.Stay ->
                listOf("stay", start, end, afterTrackId, anchor(clusterId), null, null, null)
            is StayDeriver.Gap ->
                listOf("gap", start, end, reason, afterTrackId, anchor(fromClusterId), anchor(toClusterId), from, to)
        }
    }

    private fun DerivedCluster.anchor() = toSeed().anchor

    private fun ClusterMember.identity() = listOf(trackId, isStart, lat, lon, atMs)

    private fun StayDeriver.EndpointRef.identity() = listOf(trackId, isStart, at.lat, at.lon, atMs)
}
