package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.DistanceFn
import io.github.valeronm.breadcrumb.domain.StayDeriver.Endpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anchor-based greedy leader clustering. The flat-earth stub maps 0.001° ≈ 100 m, so tests
 * place points by "degrees" and reason in metres (same convention as StayDeriverTest).
 */
class PlaceClustererTest {

    private val flatDistance = DistanceFn { aLat, aLon, bLat, bLon ->
        maxOf(Math.abs(aLat - bLat), Math.abs(aLon - bLon)) * 100_000.0
    }

    /** A point [meters] east of lat/lon origin (1.0, 1.0). */
    private fun at(meters: Double) = Endpoint(1.0, 1.0 + meters / 100_000.0)

    private fun cluster(vararg locations: Endpoint) =
        PlaceClusterer.cluster(locations.toList(), distance = flatDistance)

    @Test fun `empty input yields no clusters`() {
        assertTrue(cluster().isEmpty())
    }

    @Test fun `a single stay is a singleton cluster anchored at itself`() {
        val c = cluster(at(0.0)).single()
        assertEquals(1, c.visitCount)
        assertEquals(at(0.0), c.anchor)
        assertEquals(at(0.0), c.centroid)
    }

    @Test fun `two nearby stays form one cluster with the mean centroid`() {
        val c = cluster(at(0.0), at(50.0)).single()
        assertEquals(2, c.visitCount)
        assertEquals(at(0.0), c.anchor)
        assertEquals(at(25.0).lon, c.centroid.lon, 1e-9)
    }

    @Test fun `two distant stays form two clusters`() {
        val clusters = cluster(at(0.0), at(300.0))
        assertEquals(2, clusters.size)
        assertTrue(clusters.all { it.visitCount == 1 })
    }

    @Test fun `a stay between two clusters joins the nearest anchor`() {
        // Anchors at 0 and 250; a stay at 140 is within 150 of both — nearest (140 vs 110) is B.
        val clusters = cluster(at(0.0), at(250.0), at(140.0))
        assertEquals(2, clusters.size)
        assertEquals(listOf(1, 2), clusters[1].memberIndices)
    }

    @Test fun `clusters never chain past the anchor radius`() {
        // B (140) joins A's cluster; C (280) is within 150 of B but not of anchor A → new cluster.
        val clusters = cluster(at(0.0), at(140.0), at(280.0))
        assertEquals(2, clusters.size)
        assertEquals(listOf(0, 1), clusters[0].memberIndices)
        assertEquals(listOf(2), clusters[1].memberIndices)
    }

    @Test fun `appending stays never reassigns earlier ones`() {
        val history = listOf(at(0.0), at(300.0), at(50.0), at(340.0), at(120.0))
        val more = history + listOf(at(30.0), at(600.0), at(310.0))
        val before = assignments(PlaceClusterer.cluster(history, distance = flatDistance))
        val after = assignments(PlaceClusterer.cluster(more, distance = flatDistance))
        history.indices.forEach { i ->
            assertEquals("stay $i moved cluster", before[i], after[i])
        }
    }

    @Test fun `interleaved visits count per place`() {
        val home = at(0.0)
        val office = at(500.0)
        val clusters = cluster(home, office, home, home, office, home, office, home)
        assertEquals(2, clusters.size)
        assertEquals(5, clusters[0].visitCount)
        assertEquals(3, clusters[1].visitCount)
    }

    /** index → anchor of its cluster (cluster identity that's comparable across runs). */
    private fun assignments(clusters: List<PlaceClusterer.Cluster>): Map<Int, Endpoint> =
        clusters.flatMap { c -> c.memberIndices.map { it to c.anchor } }.toMap()
}
