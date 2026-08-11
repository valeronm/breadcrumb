package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.domain.StayDeriver.Armed
import io.github.valeronm.breadcrumb.domain.StayDeriver.Gap
import io.github.valeronm.breadcrumb.domain.StayDeriver.Stay
import io.github.valeronm.breadcrumb.domain.StayDeriver.TrackEnd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repairing a stored derivation around a change, without deriving the history again.
 *
 * **The claim under test is agreement**, not plausibility: the rows this leaves must be the rows a
 * full [StayDeriver.derive] would have written for the same history. Where the two are allowed to
 * differ is stated on [StayLedger] and pinned here — a cluster's anchor is its first-ever member,
 * so deleting the track that founded one leaves the anchor behind.
 *
 * Distance is the flat-earth stub the domain suites share: 0.001° ≈ 100 m.
 */
class StayLedgerTest {

    private val home = Coordinate(1.0, 1.0)
    private val office = Coordinate(2.0, 2.0)

    private fun track(id: Long, start: Long, end: Long, from: Coordinate? = home, to: Coordinate? = home) =
        TrackEnd(trackId = id, startedAt = start, endedAt = end, start = from, end = to)

    /** A history's clusters and memberships, as a rebuild would have stored them. */
    private class Stored(val clusters: List<StayLedger.ClusterRow>, val members: List<StayLedger.Membership>)

    /**
     * The stored state a full derivation of [tracks] leaves — cluster rows numbered from 1, and one
     * membership per endpoint. The ledger is then asked to reach the same answer incrementally.
     */
    private fun store(tracks: List<TrackEnd>, named: Set<Int> = emptySet()): Stored {
        val derivation = StayDeriver.derive(
            tracks, listOf(Armed(0)), NOW, StayDeriver.Params(), flatDistance, emptyList(),
        )
        val endpoints = StayDeriver.endpointsOf(tracks)
        val clusters = derivation.clusters.mapIndexed { index, cluster ->
            StayLedger.ClusterRow(
                id = index + 1L,
                seed = PlaceClusterer.Seed(cluster.anchor, cluster.radiusM),
                named = index in named,
                memberCount = cluster.visitCount,
            )
        }
        val members = derivation.clusters.flatMapIndexed { index, cluster ->
            cluster.memberIndices.map { i ->
                StayLedger.Membership(
                    endpoints[i].trackId, endpoints[i].isStart, endpoints[i].at, endpoints[i].atMs,
                    StayLedger.ClusterRef.Stored(index + 1L),
                )
            }
        }
        return Stored(clusters, members)
    }

    /**
     * One interval as something two passes can be compared on: its verdict, its bounds, and **where
     * its clusters are** rather than what they are numbered. An id or an index compares two
     * numberings; an anchor compares two answers.
     */
    private fun describe(
        verdict: StayDeriver.Verdict,
        start: Long,
        end: Long,
        afterTrackId: Long,
        cluster: Coordinate?,
        toCluster: Coordinate?,
    ) = listOf(verdict, start, end, afterTrackId, cluster, toCluster)

    private fun StayLedger.Interval.described(stored: Stored, mutations: StayLedger.Mutations) = describe(
        verdict, start, end, afterTrackId,
        anchorOf(cluster, stored, mutations), anchorOf(toCluster, stored, mutations),
    )

    /** Where a cluster is, whether it was already stored or this pass founded it — so a comparison
     *  covers both, rather than quietly passing on the ones it cannot resolve. */
    private fun anchorOf(
        ref: StayLedger.ClusterRef?,
        stored: Stored,
        mutations: StayLedger.Mutations,
    ): Coordinate? = when (ref) {
        null -> null
        is StayLedger.ClusterRef.Stored -> stored.clusters.first { it.id == ref.id }.seed.anchor
        is StayLedger.ClusterRef.Founded -> mutations.founded[ref.index].anchor
    }

    /** The same description, taken off a full derivation's interval. */
    private fun StayDeriver.Interval.described(clusters: List<PlaceClusterer.Cluster>) = when (this) {
        is Stay -> describe(
            StayDeriver.Verdict.Stayed(provenance), start, end!!, afterTrackId,
            clusters[clusterId].anchor, null,
        )
        is Gap -> describe(
            StayDeriver.Verdict.Moved(reason), start, end, afterTrackId,
            fromClusterId?.let { clusters[it].anchor }, toClusterId?.let { clusters[it].anchor },
        )
    }

    /** What a full derivation of [tracks] says, described for comparison. */
    private fun derived(tracks: List<TrackEnd>): List<List<Any?>> {
        val derivation = StayDeriver.derive(
            tracks, listOf(Armed(0)), NOW, StayDeriver.Params(), flatDistance, emptyList(),
        )
        return derivation.intervals.map { it.described(derivation.clusters) }
    }

    private fun reknit(
        stored: Stored,
        prev: TrackEnd? = null,
        removed: List<TrackEnd> = emptyList(),
        added: List<TrackEnd> = emptyList(),
        next: TrackEnd? = null,
    ) = StayLedger.reknit(
        StayLedger.Seam(prev, removed.map { it.trackId }, added, next),
        StayLedger.Stored(
            clusters = stored.clusters,
            membershipOf = stored.members.groupBy { it.trackId },
            evidence = StayDeriver.summarizeLiveness(listOf(Armed(0)), NOW),
        ),
        distance = flatDistance,
    )

    // --- Appending, which is what a track finishing is -----------------------

    @Test fun `a track finishing leaves the interval a full derivation would have`() {
        val first = track(1, 60 * MIN, 120 * MIN)
        val second = track(2, 240 * MIN, 300 * MIN)
        val stored = store(listOf(first))

        val mutations = reknit(stored, prev = first, added = listOf(second))

        assertEquals(derived(listOf(first, second)), mutations.intervals.map { it.described(stored, mutations) })
    }

    @Test fun `an endpoint within reach joins the cluster already there rather than founding one`() {
        val first = track(1, 60 * MIN, 120 * MIN)
        val stored = store(listOf(first))

        val mutations = reknit(stored, prev = first, added = listOf(track(2, 240 * MIN, 300 * MIN)))

        assertTrue("nothing new was founded", mutations.founded.isEmpty())
        assertEquals(listOf(StayLedger.ClusterRef.Stored(1L)), mutations.memberships.map { it.cluster }.distinct())
        assertEquals(2, mutations.deltas.single().count)
    }

    @Test fun `an endpoint out of reach founds a cluster, which the next endpoint then joins`() {
        val first = track(1, 60 * MIN, 120 * MIN)
        val stored = store(listOf(first))
        // Both ends at the office: the first founds a cluster and the second must land in it, which
        // is what makes an incremental append agree with a chronological pass.
        val away = track(2, 240 * MIN, 300 * MIN, from = office, to = office)

        val mutations = reknit(stored, prev = first, added = listOf(away))

        assertEquals(1, mutations.founded.size)
        assertEquals(office, mutations.founded.single().anchor)
        assertEquals(listOf(StayLedger.ClusterRef.Founded(0)), mutations.memberships.map { it.cluster }.distinct())
    }

    @Test fun `a disagreeing pair leaves a gap carrying both positions`() {
        val first = track(1, 60 * MIN, 120 * MIN)
        val stored = store(listOf(first))
        val away = track(2, 240 * MIN, 300 * MIN, from = office, to = office)

        val gap = reknit(stored, prev = first, added = listOf(away)).intervals.single()

        assertEquals(StayDeriver.Verdict.Moved(StayDeriver.GapReason.MOVED_UNRECORDED), gap.verdict)
        assertEquals(home, gap.from)
        assertEquals(office, gap.to)
    }

    // --- Removing, and the seam that closes over it --------------------------

    @Test fun `deleting a middle track leaves the interval its neighbours now share`() {
        val first = track(1, 60 * MIN, 120 * MIN)
        val middle = track(2, 180 * MIN, 240 * MIN)
        val last = track(3, 300 * MIN, 360 * MIN)
        val stored = store(listOf(first, middle, last))

        val mutations = reknit(stored, prev = first, removed = listOf(middle), next = last)

        assertEquals(derived(listOf(first, last)), mutations.intervals.map { it.described(stored, mutations) })
        assertTrue("the deleted track's own interval goes", 2L in mutations.removed.intervalsAfterTracks)
        assertTrue("and its memberships with it", 2L in mutations.removed.membershipsOfTracks)
    }

    @Test fun `a cluster left holding nothing ends, unless the user named it`() {
        val only = track(1, 60 * MIN, 120 * MIN)
        val unnamed = store(listOf(only))
        val named = store(listOf(only), named = setOf(0))

        assertEquals(listOf(1L), reknit(unnamed, removed = listOf(only)).removed.emptiedClusters)
        assertTrue(
            "a named cluster survives its last visit",
            reknit(named, removed = listOf(only)).removed.emptiedClusters.isEmpty(),
        )
    }

    @Test fun `the removed track's endpoints stop counting toward their cluster`() {
        val first = track(1, 60 * MIN, 120 * MIN)
        val second = track(2, 240 * MIN, 300 * MIN)
        val stored = store(listOf(first, second))

        val delta = reknit(stored, prev = first, removed = listOf(second)).deltas.single()

        assertEquals(-2, delta.count)
        assertEquals(-2 * home.lat, delta.sumLat, 1e-9)
    }

    // --- A merge, which is both at once --------------------------------------

    @Test fun `merging two tracks into one leaves what a derivation of the merged history leaves`() {
        val first = track(1, 60 * MIN, 120 * MIN)
        val second = track(2, 180 * MIN, 240 * MIN)
        val after = track(3, 300 * MIN, 360 * MIN)
        val stored = store(listOf(first, second, after))
        val merged = track(4, 60 * MIN, 240 * MIN)

        val mutations = reknit(
            stored, removed = listOf(first, second), added = listOf(merged), next = after,
        )

        assertEquals(derived(listOf(merged, after)), mutations.intervals.map { it.described(stored, mutations) })
    }

    @Test fun `a rewritten track's old endpoints leave as its new ones arrive`() {
        // What editing a manual trip is: one row removed and added at once, so both halves of the
        // membership arithmetic have to run for the same id.
        val first = track(1, 60 * MIN, 120 * MIN)
        val away = track(2, 240 * MIN, 300 * MIN, from = office, to = office)
        val stored = store(listOf(first, away))
        val rewritten = track(2, 240 * MIN, 300 * MIN)

        val mutations = reknit(stored, prev = first, removed = listOf(away), added = listOf(rewritten))

        assertEquals(
            derived(listOf(first, rewritten)),
            mutations.intervals.map { it.described(stored, mutations) },
        )
        assertEquals("the cluster it used to be in is left empty", listOf(2L), mutations.removed.emptiedClusters)
    }

    @Test fun `no interval is left after the newest track, that one being the open stay`() {
        val first = track(1, 60 * MIN, 120 * MIN)
        val stored = store(listOf(first))

        val mutations = reknit(stored, prev = first, added = listOf(track(2, 240 * MIN, 300 * MIN)))

        assertTrue(mutations.intervals.none { it.afterTrackId == 2L })
    }

    @Test fun `deleting the track that founded a cluster leaves the anchor where it was`() {
        // The one divergence from a fresh derivation, and the reason it is accepted: an anchor is
        // a cluster's identity, so moving it on a delete would repoint the stays that stayed.
        val founding = track(1, 60 * MIN, 120 * MIN)
        val nearby = Coordinate(1.0005, 1.0) // 50 m on — inside the founding anchor's reach
        val later = track(2, 240 * MIN, 300 * MIN, from = nearby, to = nearby)
        val stored = store(listOf(founding, later))

        val mutations = reknit(stored, removed = listOf(founding), next = later)

        assertTrue("the cluster survives its founder", mutations.removed.emptiedClusters.isEmpty())
        assertEquals("and keeps the anchor it was founded at", home, stored.clusters.single().seed.anchor)
        // A fresh derivation of what remains would anchor on the surviving endpoint instead.
        val afresh = StayDeriver.derive(
            listOf(later), listOf(Armed(0)), NOW, StayDeriver.Params(), flatDistance, emptyList(),
        )
        assertEquals(nearby, afresh.clusters.single().anchor)
    }

    @Test fun `a named cluster's reach decides agreement, as it does in a full derivation`() {
        // The shared-pin override — the clause the ledger and the deriver share through Agreement.
        // Two endpoints too far apart to agree on their own, both inside one named place's radius.
        val wide = Coordinate(1.003, 1.0) // 300 m from home
        val first = track(1, 60 * MIN, 120 * MIN, to = home)
        val second = track(2, 240 * MIN, 300 * MIN, from = wide, to = wide)
        val stored = Stored(
            clusters = listOf(
                StayLedger.ClusterRow(1L, PlaceClusterer.Seed(home, 500.0), named = true, memberCount = 2),
            ),
            members = StayDeriver.endpointsOf(listOf(first)).map {
                StayLedger.Membership(it.trackId, it.isStart, it.at, it.atMs, StayLedger.ClusterRef.Stored(1L))
            },
        )

        val interval = reknit(stored, prev = first, added = listOf(second)).intervals.single()

        assertTrue("the pin's reach holds the pair together", interval.verdict is StayDeriver.Verdict.Stayed)
    }

    @Test fun `a stay names the cluster its earlier endpoint is in`() {
        val first = track(1, 60 * MIN, 120 * MIN)
        val stored = store(listOf(first))

        val stay = reknit(stored, prev = first, added = listOf(track(2, 240 * MIN, 300 * MIN)))
            .intervals.single()

        assertTrue(stay.verdict is StayDeriver.Verdict.Stayed)
        assertEquals(StayLedger.ClusterRef.Stored(1L), stay.cluster)
    }

    private companion object {
        const val NOW = 1_000 * MIN
    }
}
