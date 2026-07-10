package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.DistanceFn
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.domain.StayDeriver.Endpoint
import io.github.valeronm.breadcrumb.domain.StayDeriver.Provenance
import io.github.valeronm.breadcrumb.domain.StayDeriver.Stay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cluster → place matching goes through the stable anchor; results key by afterTrackId. */
class PlaceResolverTest {

    private val flatDistance = DistanceFn { aLat, aLon, bLat, bLon ->
        maxOf(Math.abs(aLat - bLat), Math.abs(aLon - bLon)) * 100_000.0
    }

    private fun at(meters: Double) = Endpoint(1.0, 1.0 + meters / 100_000.0)

    private var nextTrackId = 0L
    private fun stay(location: Endpoint, end: Long? = 2_000L) = Stay(
        start = 1_000L, end = end, location = location,
        provenance = Provenance.OBSERVED, afterTrackId = ++nextTrackId, clusterId = 0,
    )

    private fun place(id: Long, label: String, location: Endpoint) =
        Place(id = id, label = label, lat = location.lat, lon = location.lon, createdAt = 0L)

    /**
     * Clusters the stay locations and stamps each stay with its cluster id — the shape
     * [StayDeriver.derive] hands to the resolver in production.
     */
    private fun withClusters(stays: List<Stay>): Pair<List<Stay>, List<PlaceClusterer.Cluster>> {
        val locations = stays.map { it.location }
        val clusters = PlaceClusterer.cluster(locations, distance = flatDistance)
        val clusterIdByStay = IntArray(stays.size)
        clusters.forEachIndexed { ci, cluster ->
            for (index in cluster.memberIndices) clusterIdByStay[index] = ci
        }
        return stays.mapIndexed { i, s -> s.copy(clusterId = clusterIdByStay[i]) } to clusters
    }

    private fun resolve(stays: List<Stay>, places: List<Place>): Map<Long, PlaceResolver.ResolvedStay> {
        val (stamped, clusters) = withClusters(stays)
        return PlaceResolver.resolve(stamped, clusters, places, distance = flatDistance)
    }

    @Test fun `a place near the cluster anchor labels every member stay`() {
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
        val resolved = resolve(listOf(stay(at(0.0)), stay(at(10.0))), listOf(place(7, "Home", at(300.0))))
        val r = resolved.values.first()
        assertNull(r.label)
        assertNull(r.placeId)
        assertEquals(2, r.visitCount)
    }

    @Test fun `two places within radius resolve to the nearest`() {
        val resolved = resolve(
            listOf(stay(at(0.0))),
            listOf(place(1, "Cafe", at(140.0)), place(2, "Home", at(40.0))),
        )
        assertEquals("Home", resolved.values.first().label)
    }

    @Test fun `a drifted centroid pin still matches via the anchor`() {
        // Anchor at 0; later stays drag the centroid east; the place was pinned at a centroid
        // 100 m from the anchor — still within radius of the anchor, so the label holds.
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

    // --- summarize -----------------------------------------------------------

    private val NOW = 100_000L
    private fun summarize(stays: List<Stay>, places: List<Place>): List<PlaceResolver.PlaceSummary> {
        val (stamped, clusters) = withClusters(stays)
        return PlaceResolver.summarize(stamped, clusters, places, NOW, distance = flatDistance)
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
        val s = summarize(listOf(stayAt(at(0.0), start = 40_000, end = null)),
            listOf(place(7, "Home", at(0.0)))).single()
        assertEquals(NOW, s.lastSeenMs)
        assertEquals(NOW - 40_000, s.totalMs)
    }

    @Test fun `a named place with no stays is still listed with zero`() {
        // The lone stay forms its own unnamed cluster; the distant named place is a zero orphan.
        val summaries = summarize(listOf(stayAt(at(0.0), 1_000, 2_000)),
            listOf(place(7, "Faraway", at(500.0))))
        val faraway = summaries.single { it.place?.label == "Faraway" }
        assertEquals(0, faraway.visitCount)
        assertNull(faraway.lastSeenMs)
        assertEquals(0L, faraway.totalMs)
    }

    @Test fun `unnamed clusters are listed too`() {
        val summaries = summarize(
            listOf(stayAt(at(0.0), 1_000, 2_000), stayAt(at(500.0), 3_000, 4_000)),
            emptyList(),
        )
        assertEquals(2, summaries.size)
        assertTrue(summaries.all { !it.isNamed && it.visitCount == 1 })
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
}
