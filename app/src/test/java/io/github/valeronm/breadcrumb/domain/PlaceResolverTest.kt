package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.domain.StayDeriver.Endpoint
import io.github.valeronm.breadcrumb.domain.StayDeriver.Provenance
import io.github.valeronm.breadcrumb.domain.StayDeriver.Stay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cluster → place matching goes through seed identity; results key by afterTrackId. */
class PlaceResolverTest {

    private val PIN_RADIUS = 350.0

    private var nextTrackId = 0L
    private fun stay(location: Endpoint, end: Long? = 2_000L) = Stay(
        start = 1_000L, end = end, location = location,
        provenance = Provenance.OBSERVED, afterTrackId = ++nextTrackId, clusterId = 0,
    )

    private fun place(id: Long, label: String, location: Endpoint) =
        Place(id = id, label = label, lat = location.lat, lon = location.lon, createdAt = 0L, radiusM = PlaceClusterer.DEFAULT_RADIUS_M)

    /** [locations] clustered against [places]' pins — the seeding production does. */
    private fun clusterAt(locations: List<Endpoint>, places: List<Place>) =
        PlaceClusterer.cluster(
            locations, distance = flatDistance,
            seeds = places.map { PlaceClusterer.Seed(Endpoint(it.lat, it.lon), PIN_RADIUS) },
        )

    /**
     * Clusters the stay locations — seeded by the places' pins, as in production — and stamps
     * each stay with its cluster id: the shape [StayDeriver.derive] hands to the resolver.
     */
    private fun withClusters(
        stays: List<Stay>,
        places: List<Place>,
    ): Pair<List<Stay>, List<PlaceClusterer.Cluster>> {
        val clusters = clusterAt(stays.map { it.location }, places)
        val clusterIdByStay = IntArray(stays.size)
        clusters.forEachIndexed { ci, cluster ->
            for (index in cluster.memberIndices) clusterIdByStay[index] = ci
        }
        return stays.mapIndexed { i, s -> s.copy(clusterId = clusterIdByStay[i]) } to clusters
    }

    /** Stay-keyed view over [PlaceResolver.resolveClusters] — the lookup production does per item. */
    private fun resolve(stays: List<Stay>, places: List<Place>): Map<Long, PlaceResolver.ResolvedStay> {
        val (stamped, clusters) = withClusters(stays, places)
        val byCluster = PlaceResolver.resolveClusters(stamped, clusters, places)
        return stamped.associate { it.afterTrackId to byCluster[it.clusterId] }
    }

    @Test fun `a place pin labels every stay it captures`() {
        val stays = listOf(stay(at(0.0)), stay(at(50.0)), stay(at(30.0)))
        val resolved = resolve(stays, listOf(place(7, "Home", at(50.0))))
        stays.forEach { s ->
            val r = resolved.getValue(s.afterTrackId)
            assertEquals("Home", r.label)
            assertEquals(7L, r.placeId)
            assertEquals(3, r.visitCount)
        }
    }

    @Test fun `a distant place leaves the cluster unnamed but counted`() {
        val resolved = resolve(listOf(stay(at(0.0)), stay(at(10.0))), listOf(place(7, "Home", at(500.0))))
        val r = resolved.values.first()
        assertNull(r.label)
        assertNull(r.placeId)
        assertEquals(2, r.visitCount)
    }

    @Test fun `a stay between two pins resolves to the nearest`() {
        val resolved = resolve(
            listOf(stay(at(0.0))),
            listOf(place(1, "Cafe", at(140.0)), place(2, "Home", at(40.0))),
        )
        assertEquals("Home", resolved.values.first().label)
    }

    @Test fun `venue-scale scatter all resolves to the seeding pin`() {
        // Stays spread over 140 m — beyond what an organic 150 m anchor at 0 would hold together
        // reliably — but all within the pin's 350 m seed radius, so they're one named cluster.
        val stays = listOf(stay(at(0.0)), stay(at(140.0)), stay(at(140.0)), stay(at(120.0)))
        val resolved = resolve(stays, listOf(place(3, "Office", at(100.0))))
        assertEquals("Office", resolved.getValue(stays[0].afterTrackId).label)
        assertEquals(4, resolved.getValue(stays[0].afterTrackId).visitCount)
    }

    @Test fun `an ongoing stay resolves like any other`() {
        val ongoing = stay(at(20.0), end = null)
        val resolved = resolve(listOf(stay(at(0.0)), ongoing), listOf(place(7, "Home", at(0.0))))
        assertEquals("Home", resolved.getValue(ongoing.afterTrackId).label)
    }

    @Test fun `results are keyed by afterTrackId per stay`() {
        val a = stay(at(0.0))
        val b = stay(at(500.0))
        val resolved = resolve(listOf(a, b), emptyList())
        assertEquals(2, resolved.size)
        assertEquals(1, resolved.getValue(a.afterTrackId).visitCount)
        assertEquals(at(500.0), resolved.getValue(b.afterTrackId).centroid)
    }

    @Test fun `resolveClusters covers every cluster including stay-less ones`() {
        // One visited cluster near the pin, plus a pass-through cluster no stay belongs to
        // (a gap endpoint could land there) — both must resolve.
        val stays = listOf(stay(at(0.0)))
        val places = listOf(place(7, "Home", at(0.0)))
        val (stamped, _) = withClusters(stays, places)
        val clusters = clusterAt(listOf(at(0.0), at(500.0)), places)
        val resolved = PlaceResolver.resolveClusters(stamped, clusters, places)
        assertEquals(clusters.size, resolved.size)
        assertEquals("Home", resolved[0].label)
        assertEquals(1, resolved[0].visitCount)
        val passThrough = resolved.last()
        assertNull(passThrough.label)
        assertEquals(0, passThrough.visitCount)
        assertEquals(at(500.0), passThrough.centroid)
    }

    // --- summarize -----------------------------------------------------------

    private val NOW = 100_000L
    private fun summarize(stays: List<Stay>, places: List<Place>): List<PlaceResolver.PlaceSummary> {
        val (stamped, clusters) = withClusters(stays, places)
        return PlaceResolver.summarize(stamped, clusters, places, NOW)
    }

    private fun stayAt(location: Endpoint, start: Long, end: Long?) = Stay(
        start = start, end = end, location = location,
        provenance = Provenance.OBSERVED, afterTrackId = ++nextTrackId, clusterId = 0,
    )

    @Test fun `a named summary aggregates count, last seen and total over its stays`() {
        val stays = listOf(
            stayAt(at(0.0), start = 1_000, end = 3_000),   // 2_000
            stayAt(at(40.0), start = 5_000, end = 6_000),  // 1_000
            stayAt(at(20.0), start = 9_000, end = 9_500),  // 500
        )
        val s = summarize(stays, listOf(place(7, "Home", at(0.0)))).single()
        assertEquals("Home", s.place?.label)
        assertEquals(3, s.visitCount)
        assertEquals(9_500L, s.lastSeenMs)
        assertEquals(3_500L, s.totalMs)
    }

    @Test fun `an ongoing stay counts up to now`() {
        val s = summarize(
            listOf(stayAt(at(0.0), start = 40_000, end = null)),
            listOf(place(7, "Home", at(0.0))),
        ).single()
        assertEquals(NOW, s.lastSeenMs)
        assertEquals(NOW - 40_000, s.totalMs)
    }

    @Test fun `a named place with no stays is still listed with zero`() {
        // The lone stay forms its own unnamed cluster; the distant named place is a zero orphan.
        val summaries = summarize(
            listOf(stayAt(at(0.0), 1_000, 2_000)),
            listOf(place(7, "Faraway", at(500.0))),
        )
        val faraway = summaries.single { it.place?.label == "Faraway" }
        assertEquals(0, faraway.visitCount)
        assertNull(faraway.lastSeenMs)
        assertEquals(0L, faraway.totalMs)
    }

    @Test fun `a stay-less unnamed cluster gets a zero-visit summary`() {
        // A pass-through endpoint (e.g. a gap side) forms a cluster no stay belongs to; it must
        // still summarize so the gap card can open its detail page.
        val places = listOf(place(7, "Home", at(600.0)))
        val clusters = clusterAt(listOf(at(0.0), at(1200.0)), places)
        val stayClusterId = clusters.indexOfFirst { 0 in it.memberIndices }
        val stays = listOf(stay(at(0.0)).copy(clusterId = stayClusterId))
        val summaries = PlaceResolver.summarize(stays, clusters, places, NOW)
        val passThrough = summaries.single { !it.isNamed && it.visitCount == 0 }
        assertEquals(at(1200.0), passThrough.pin)
        assertEquals(listOf(at(1200.0)), passThrough.endpoints)
        assertNull(passThrough.lastSeenMs)
        assertEquals(0L, passThrough.totalMs)
    }

    @Test fun `unnamed clusters are listed too`() {
        val summaries = summarize(
            listOf(stayAt(at(0.0), 1_000, 2_000), stayAt(at(500.0), 3_000, 4_000)),
            emptyList(),
        )
        assertEquals(2, summaries.size)
        assertTrue(summaries.all { !it.isNamed && it.visitCount == 1 })
    }

    /**
     * A resolved stay carries the place row, so its label, id and category can't come apart and a
     * new place column needs no field of its own here.
     */
    @Test fun `a resolved stay exposes the matched place's attributes from one row`() {
        val tagged = place(7, "Corner shop", at(0.0)).copy(category = PlaceCategory.GROCERIES.code)
        val r = resolve(listOf(stay(at(0.0))), listOf(tagged)).values.single()!!
        assertEquals(tagged, r.place)
        assertEquals("Corner shop", r.label)
        assertEquals(7L, r.placeId)
        assertEquals(PlaceCategory.GROCERIES, r.category)
    }

    /** Untagged and unknown-code places both resolve with no category, and keep their label. */
    @Test fun `an untagged or unrecognized category resolves to none`() {
        val untagged = place(7, "Corner shop", at(0.0))
        resolve(listOf(stay(at(0.0))), listOf(untagged)).values.single()!!.let {
            assertNull(it.category)
            assertEquals("Corner shop", it.label)
        }
        val future = untagged.copy(category = "laundromat")
        resolve(listOf(stay(at(0.0))), listOf(future)).values.single()!!.let {
            assertNull(it.category)
            assertEquals("Corner shop", it.label)
        }
    }

    /** An unnamed cluster has no place row at all — so no label, id or category. */
    @Test fun `an unnamed cluster resolves with nothing from a place`() {
        val r = resolve(listOf(stay(at(0.0))), emptyList()).values.single()!!
        assertNull(r.place)
        assertNull(r.label)
        assertNull(r.placeId)
        assertNull(r.category)
    }

    @Test fun `named places come first in input order, then unnamed clusters`() {
        val places = listOf(place(1, "Home", at(0.0)), place(2, "Office", at(500.0)))
        val stays = listOf(stayAt(at(500.0), 1_000, 2_000), stayAt(at(900.0), 3_000, 4_000))
        val summaries = summarize(stays, places)
        assertEquals(listOf("Home", "Office"), summaries.take(2).map { it.place?.label })
        assertEquals(0, summaries[0].visitCount)        // Home: orphan
        assertEquals(1, summaries[1].visitCount)        // Office: one matching stay
        assertEquals(1, summaries.count { !it.isNamed }) // the (900) stay is an unnamed cluster
    }

    @Test fun `a named place's endpoint centroid is where its visits landed, not its pin`() {
        // The two positions a summary carries, and they are only interesting when they differ:
        // the anchor is where the pin was dropped, the centroid where the visits actually fell.
        val places = listOf(place(1, "Home", at(0.0)))
        // Both stays fall inside the pin's capture radius, so there is no unnamed cluster beside it.
        val s = summarize(listOf(stayAt(at(100.0), 1_000, 2_000), stayAt(at(300.0), 3_000, 4_000)), places)
            .single()

        assertEquals(at(0.0), s.anchor)
        assertEquals(at(200.0).lon, s.endpointCentroid!!.lon, 1e-9)
    }

    @Test fun `a named place that captured nothing has no endpoint centroid`() {
        // A cluster with no members has no mean, and must not report its pin as one — an empty
        // seed keeps the pin in [PlaceClusterer.Cluster.centroid], which is the answer to avoid.
        val s = summarize(emptyList(), listOf(place(1, "Home", at(0.0)))).single()

        assertTrue(s.endpoints.isEmpty())
        assertNull(s.endpointCentroid)
    }

    @Test fun `re-centering is offered only where it would move the pin, and follows the radius`() {
        // The whole rule the action bar renders, against the circle on screen: a radius wide
        // enough to take the far endpoint has a middle worth moving to, and one that reaches only
        // what is already under the pin does not — same scan, so the radius is what decides.
        val scan = PlaceClusterer.scanCapture(
            listOf(at(0.0), at(600.0)), at(0.0), 1_000.0, emptyList(), flatDistance,
        )
        fun targetAt(radiusM: Double) = PlaceResolver.recenterTarget(at(0.0), scan, radiusM, flatDistance)

        assertEquals(at(300.0).lon, targetAt(1_000.0)!!.lon, 1e-9) // both, so the middle is between
        assertNull(targetAt(100.0))                                // only the one on the pin
    }

    @Test fun `an unnamed cluster is keyed by where it sits and a named place by its row`() {
        // Identity has to survive re-derivation: a name does, a cluster's position is all it has.
        val summaries = summarize(
            listOf(stayAt(at(0.0), 1_000, 2_000), stayAt(at(900.0), 3_000, 4_000)),
            listOf(place(7, "Home", at(0.0))),
        )

        assertEquals("place:7", summaries.single { it.isNamed }.key)
        assertEquals("cluster:%.5f,%.5f".format(at(900.0).lat, at(900.0).lon), summaries.single { !it.isNamed }.key)
    }

    // --- neighborhood: what a capture radius is judged against -------------------------------

    /**
     * A subject at the origin with a named neighbor and an unnamed one inside the shipping context
     * radius, plus one stay beyond it. Laid out against [PlaceResolver.NEIGHBOR_CONTEXT_M] itself,
     * so the reach these tests exercise is the reach production gathers at.
     */
    private fun neighborhoodFixture(): List<PlaceResolver.PlaceSummary> {
        val places = listOf(place(1, "Home", at(0.0)), place(2, "Office", at(400.0)))
        val stays = listOf(
            stayAt(at(0.0), 1_000, 2_000),      // Home
            stayAt(at(400.0), 3_000, 4_000),    // Office
            stayAt(at(800.0), 5_000, 6_000),    // an unnamed cluster, still in reach
            stayAt(at(PlaceResolver.NEIGHBOR_CONTEXT_M + 500), 7_000, 8_000), // past the context radius
        )
        return summarize(stays, places)
    }

    private fun homeNeighborhood(): PlaceResolver.Neighborhood {
        val all = neighborhoodFixture()
        return PlaceResolver.neighborhood(all.first { it.place?.label == "Home" }, all, flatDistance)
    }

    @Test fun `the neighborhood excludes the subject and anything out of reach`() {
        val n = homeNeighborhood()

        assertTrue(n.nearby.none { it === n.subject })
        assertEquals(listOf(at(400.0), at(800.0)), n.nearby.map { it.anchor })
    }

    @Test fun `only a named neighbor is a rival`() {
        // The rule the capture preview rests on; the reasoning is on PlaceResolver.Neighborhood.
        val n = homeNeighborhood()

        assertEquals(2, n.nearby.size)                    // the Office and the unnamed cluster
        assertEquals(listOf(at(400.0)), n.rivals.map { it.anchor }) // the Office pin, not the other
    }

    @Test fun `candidates are the subject's own endpoints plus every neighbor's`() {
        // The subject's own are in there: they are what it already holds, and a preview that left
        // them out would show a radius losing what it never had at stake.
        assertEquals(listOf(at(0.0), at(400.0), at(800.0)), homeNeighborhood().candidates)
    }

    // --- reacquire -----------------------------------------------------------
    //
    // What an open place screen does with a freshly derived list. Every fixture here goes through
    // `summarize`, never a hand-built summary: the whole subject is how keys and pins behave across
    // a re-derivation, so a summary whose key was assigned by the test would prove nothing.

    /** The one unnamed cluster in a history of stays at [locations]. */
    private fun unnamedAt(vararg locations: Endpoint) =
        summarize(locations.map { stay(it) }, emptyList()).single()

    @Test fun `a live key resolves to its own summary`() {
        val summaries = summarize(
            listOf(stay(at(0.0)), stay(at(4_000.0))),
            listOf(place(7, "Home", at(0.0))),
        )
        val home = summaries.first { it.place?.label == "Home" }

        assertTrue(home === PlaceResolver.reacquire(summaries, home.key, previous = null))
    }

    @Test fun `naming a cluster is followed by pin, the key it was opened with being gone`() {
        // The case the fallback exists for. A screen opens on an unnamed cluster, the user names it,
        // and the next derivation has no `cluster:` row at all — a `place:` row stands where it
        // stood. Naming pins the place at the cluster's own mean, so the pin is what carries over.
        val before = unnamedAt(at(0.0), at(20.0))
        val named = summarize(
            listOf(stay(at(0.0)), stay(at(20.0))),
            listOf(place(7, "Home", before.pin)),
        ).single()

        assertTrue("the key really did die", named.key != before.key)
        assertTrue(named === PlaceResolver.reacquire(listOf(named), before.key, previous = before))
    }

    @Test fun `a place created at a hand-placed pin is followed by its id, not its position`() {
        // The editor creates a place at whatever pin the user placed, and that is the one case the
        // positional fallback cannot cover: the row exists, but nothing in the fresh list sits where
        // the cluster did, so the screen would hold the cluster it opened on and go on offering to
        // create the place that already exists.
        val stays = listOf(stay(at(0.0)), stay(at(20.0)))
        val before = summarize(stays, emptyList()).single()
        val handPlaced = at(60.0)
        val after = summarize(stays, listOf(place(7, "Home", handPlaced)))
        val named = after.single { it.place?.label == "Home" }

        assertTrue("the pin really did move", named.pin != before.pin)
        assertNull(
            "position cannot find it, which is why the id is followed instead",
            PlaceResolver.reacquire(after, before.key, previous = before)?.place,
        )
        assertTrue(named === PlaceResolver.reacquire(after, PlaceResolver.keyOf(7), previous = before))
    }

    @Test fun `a summary whose pin also moved keeps what the screen had`() {
        // Both readings fail: the key is dead and no pin matches. Emptying the screen would be the
        // wrong answer — a derivation in flight is not the same as a place that stopped existing.
        val before = unnamedAt(at(0.0))
        val elsewhere = unnamedAt(at(9_000.0))

        val got = PlaceResolver.reacquire(listOf(elsewhere), before.key, previous = before)

        assertTrue(got === before)
    }

    @Test fun `an unmatched key resolves to nothing, never to whatever is first`() {
        // With no previous summary there is nothing to fall back *to*, and the list is not a
        // fallback: resolving to its first row would put the screen on someone else's place while
        // looking like a successful match. This is MainActivity's opening state, key and all.
        val summaries = listOf(unnamedAt(at(0.0)), unnamedAt(at(9_000.0)))

        assertNull(PlaceResolver.reacquire(summaries, key = "place:99", previous = null))
        assertNull(PlaceResolver.reacquire(summaries, key = null, previous = null))
        assertNull(PlaceResolver.reacquire(emptyList(), key = "place:7", previous = null))
    }

    @Test fun `a key that is merely absent still prefers a pin match over the stale summary`() {
        // Deleting a place leaves its cluster unnamed again, so the row's key changes without the
        // place moving. The pin match must win: the *fresh* summary is the one carrying the visit
        // counts, and retaining `previous` would leave the screen on figures that no longer hold.
        val named = summarize(listOf(stay(at(0.0))), listOf(place(7, "Home", at(0.0)))).single()
        val unnamedAgain = unnamedAt(at(0.0))

        val got = PlaceResolver.reacquire(listOf(unnamedAgain), named.key, previous = named)

        assertTrue(got === unnamedAgain)
        assertNull(got?.place)
    }
}
