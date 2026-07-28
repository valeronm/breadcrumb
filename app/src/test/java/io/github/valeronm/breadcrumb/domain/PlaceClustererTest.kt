package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.domain.StayDeriver.Endpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Anchor-based greedy leader clustering. The flat-earth stub maps 0.001° ≈ 100 m, so tests
 * place points by "degrees" and reason in meters (same convention as StayDeriverTest).
 */
class PlaceClustererTest {

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

    // --- Seeded clustering ------------------------------------------------------

    private fun seed(meters: Double, radius: Double = 350.0) = PlaceClusterer.Seed(at(meters), radius)

    private fun clusterSeeded(seeds: List<PlaceClusterer.Seed>, vararg locations: Endpoint) =
        PlaceClusterer.cluster(locations.toList(), distance = flatDistance, seeds = seeds)

    @Test fun `a seed captures locations at its own radius, beyond the organic one`() {
        // 300 m from the pin — past the 150 m organic radius but within the 350 m seed radius.
        val c = clusterSeeded(listOf(seed(0.0)), at(300.0)).single()
        assertEquals(0, c.seedIndex)
        assertEquals(at(0.0), c.anchor)
        assertEquals(listOf(0), c.memberIndices)
    }

    @Test fun `a memberless seed is still listed, centerd on its pin`() {
        val clusters = clusterSeeded(listOf(seed(0.0)), at(1000.0))
        assertEquals(2, clusters.size)
        assertEquals(0, clusters[0].seedIndex)
        assertEquals(0, clusters[0].visitCount)
        assertEquals(at(0.0), clusters[0].centroid)
        assertNull(clusters[1].seedIndex)
    }

    @Test fun `organic anchors only form beyond every seed radius`() {
        // 300 joins the seed; 400 is past 350 → founds an organic cluster that 450 then joins.
        val clusters = clusterSeeded(listOf(seed(0.0)), at(300.0), at(400.0), at(450.0))
        assertEquals(2, clusters.size)
        assertEquals(listOf(0), clusters[0].memberIndices)
        assertEquals(listOf(1, 2), clusters[1].memberIndices)
        assertNull(clusters[1].seedIndex)
    }

    @Test fun `a location nearer an organic anchor is not pulled into a seed`() {
        // The organic anchor forms at 400 (outside the seed); 300 is within both radii but
        // nearer the organic anchor (100 m vs 300 m) — nearest qualifying anchor wins.
        val clusters = clusterSeeded(listOf(seed(0.0)), at(400.0), at(300.0))
        assertTrue(clusters[0].memberIndices.isEmpty())
        assertEquals(listOf(0, 1), clusters[1].memberIndices)
    }

    // --- Reach-box pruning ------------------------------------------------------
    //
    // The scan rejects most anchors on their coordinates ([ReachBound]) instead of on a distance
    // call. That is only sound if it never rejects an anchor the distance would have accepted, so
    // these run against an unpruned reference scan — under a real-Earth distance rather than the
    // flat stub above, since the bound's whole risk is spherical (a degree of longitude shortens
    // toward the pole, and the box is probed at one point).

    /** Spherical distance, so the pruning cases meet the geometry the bound is exposed to. */
    private val sphere = DistanceFn { aLat, aLon, bLat, bLon ->
        val r = 6_371_008.8
        val dLat = Math.toRadians(bLat - aLat)
        val dLon = Math.toRadians(bLon - aLon)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2) * sin(dLon / 2)
        2 * r * asin(min(1.0, sqrt(h)))
    }

    /** Metres per degree of latitude on [sphere]. */
    private val degreeM = 2 * Math.PI * 6_371_008.8 / 360.0

    /** The clustering as an unpruned scan of every anchor would assign it. */
    private fun referenceAssignments(
        locations: List<Endpoint>,
        radiusM: Double,
        distance: DistanceFn,
        seeds: List<PlaceClusterer.Seed>,
    ): List<List<Int>> {
        val anchors = seeds.map { it.anchor }.toMutableList()
        val radii = seeds.map { it.radiusM }.toMutableList()
        val members = MutableList(seeds.size) { mutableListOf<Int>() }
        locations.forEachIndexed { index, location ->
            var nearest = -1
            var nearestD = Double.MAX_VALUE
            for (ci in anchors.indices) {
                val d = distance.meters(anchors[ci].lat, anchors[ci].lon, location.lat, location.lon)
                if (d <= radii[ci] && d < nearestD) {
                    nearest = ci
                    nearestD = d
                }
            }
            if (nearest >= 0) {
                members[nearest] += index
            } else {
                anchors += location
                radii += radiusM
                members += mutableListOf(index)
            }
        }
        return members
    }

    /** A generated history around [lat]: 400 endpoints over a few hundred distinct locations,
     *  spread ~40 km east-west and [latSpread] × that north-south (0.02 = a linear city). */
    private fun generatedHistory(lat: Double, latSpread: Double): Pair<List<Endpoint>, List<PlaceClusterer.Seed>> {
        val rnd = java.util.Random(20260725L)
        val lonScale = 1.0 / cos(Math.toRadians(lat))
        val locations = List(60) {
            Endpoint(
                lat = lat + (rnd.nextDouble() - 0.5) * 0.36 * latSpread,
                lon = (rnd.nextDouble() - 0.5) * 0.36 * lonScale,
            )
        }
        // Revisits with GPS scatter, so anchors and near-misses of every radius occur.
        val endpoints = List(400) {
            val base = locations[rnd.nextInt(locations.size)]
            Endpoint(
                lat = base.lat + (rnd.nextDouble() - 0.5) * 0.004,
                lon = base.lon + (rnd.nextDouble() - 0.5) * 0.004 * lonScale,
            )
        }
        // Pins at the widest radius the UI offers, where the box has the most to admit.
        val seeds = endpoints.take(6).map { PlaceClusterer.Seed(it, 500.0) }
        return endpoints to seeds
    }

    @Test fun `pruning assigns exactly as an unpruned scan does, at every latitude`() {
        for (lat in listOf(0.0, 1.0, 23.5, 45.0, 60.0, 84.0, -45.0)) {
            for (latSpread in listOf(1.0, 0.02)) {
                val (endpoints, seeds) = generatedHistory(lat, latSpread)
                val pruned = PlaceClusterer.cluster(endpoints, 150.0, sphere, seeds).map { it.memberIndices }
                assertEquals(
                    "lat=$lat latSpread=$latSpread",
                    referenceAssignments(endpoints, 150.0, sphere, seeds),
                    pruned,
                )
            }
        }
    }

    @Test fun `an anchor at the very edge of its radius is still reached`() {
        // A pin's own radius, approached from the compass points the box is weakest on: due east
        // (the longitude bound), due north (the latitude bound), and the diagonal between them.
        for (lat in listOf(0.0, 45.0, 84.0)) {
            val radius = 500.0
            val pin = Endpoint(lat, 0.0)
            val inside = 0.9999 * radius
            val east = Endpoint(lat, inside / (degreeM * cos(Math.toRadians(lat))))
            val north = Endpoint(lat + inside / degreeM, 0.0)
            val diagonal = Endpoint(
                lat + inside / degreeM / sqrt(2.0),
                inside / (degreeM * cos(Math.toRadians(lat))) / sqrt(2.0),
            )
            val clusters = PlaceClusterer.cluster(
                listOf(east, north, diagonal),
                radiusM = 150.0,
                distance = sphere,
                seeds = listOf(PlaceClusterer.Seed(pin, radius)),
            )
            assertEquals("lat=$lat", listOf(0, 1, 2), clusters[0].memberIndices)
        }
    }

    // --- wouldCapture: what a radius being dragged would take, without re-deriving -------------

    private fun wouldCapture(
        radiusM: Double,
        rivals: List<PlaceClusterer.Seed> = emptyList(),
        vararg candidates: Endpoint,
    ) = PlaceClusterer.wouldCapture(
        candidates.toList(), at(0.0), radiusM, rivals, flatDistance,
    )

    @Test fun `takes the candidates inside the radius and no others`() {
        val taken = wouldCapture(150.0, candidates = arrayOf(at(0.0), at(100.0), at(300.0)))
        assertEquals(listOf(at(0.0), at(100.0)), taken)
    }

    @Test fun `a wider radius takes more — the slider's whole point`() {
        val candidates = arrayOf(at(0.0), at(100.0), at(300.0))
        assertEquals(2, wouldCapture(150.0, candidates = candidates).size)
        assertEquals(3, wouldCapture(350.0, candidates = candidates).size)
    }

    @Test fun `a nearer rival that also covers it keeps the candidate`() {
        // Inside our 400 m, but the rival sits 50 m away and covers it — cluster() would give it
        // to the rival, so a preview must not claim it.
        val rival = PlaceClusterer.Seed(at(300.0), 150.0)
        assertTrue(wouldCapture(400.0, listOf(rival), at(250.0)).isEmpty())
    }

    @Test fun `a farther rival does not block it`() {
        val rival = PlaceClusterer.Seed(at(500.0), 300.0)
        assertEquals(listOf(at(200.0)), wouldCapture(400.0, listOf(rival), at(200.0)))
    }

    @Test fun `a rival too tight to cover it does not block it`() {
        // Nearer in absolute terms, but its own radius does not reach the candidate, so it never
        // qualifies as an anchor for it.
        val rival = PlaceClusterer.Seed(at(210.0), 5.0)
        assertEquals(listOf(at(200.0)), wouldCapture(400.0, listOf(rival), at(200.0)))
    }

    @Test fun `the scan partitions every candidate exactly once`() {
        // winnable and conceded are what the screen draws; between them they must account for the
        // whole input, or dots vanish off the map.
        val candidates = listOf(at(0.0), at(120.0), at(250.0), at(900.0))
        val scan = PlaceClusterer.scanCapture(candidates, at(0.0), 500.0, emptyList(), flatDistance)

        assertEquals(candidates.size, scan.winnable.size + scan.conceded.size)
        assertEquals(
            candidates.toSet(),
            (scan.winnable.map { it.location } + scan.conceded).toSet(),
        )
    }

    @Test fun `a candidate beyond the widest radius is conceded, not carried`() {
        // The pruning the scan's cost depends on: at 900 m it can never be reached by a slider
        // that stops at 500, so it is settled up front rather than compared on every step.
        val scan = PlaceClusterer
            .scanCapture(listOf(at(900.0)), at(0.0), 500.0, emptyList(), flatDistance)

        assertTrue(scan.winnable.isEmpty())
        assertEquals(listOf(at(900.0)), scan.conceded)
    }

    @Test fun `the count follows the radius across the prepared distances`() {
        // What the edit screen's subtitle reads while the slider moves. One scan, then a count per
        // step — so it has to agree with the dots at every radius, boundary included.
        val candidates = listOf(at(0.0), at(120.0), at(120.0), at(250.0), at(900.0))
        val scan = PlaceClusterer.scanCapture(candidates, at(0.0), 500.0, emptyList(), flatDistance)

        // The one exact distance the fixture has — the endpoint on the pin — pins inclusivity;
        // the rest are compared either side of themselves, since a projected meter offset does
        // not come back through the distance stub as the round number it was built from.
        assertEquals(1, scan.countWithin(0.0))
        assertEquals(1, scan.countWithin(119.5))
        assertEquals(3, scan.countWithin(120.5))      // duplicates both count
        assertEquals(4, scan.countWithin(500.0))
        assertEquals(4, scan.countWithin(5_000.0))    // the 900 m one was conceded up front
    }

    @Test fun `the count leaves out what a nearer rival keeps`() {
        // Conceded candidates are never counted, however wide the radius — the subtitle has to
        // mean "this place's", the same as the dots that stay gray.
        val rival = PlaceClusterer.Seed(at(210.0), 100.0)
        val scan = PlaceClusterer
            .scanCapture(listOf(at(0.0), at(200.0)), at(0.0), 500.0, listOf(rival), flatDistance)

        assertEquals(1, scan.countWithin(500.0))
    }

    @Test fun `a widened seed takes ground an organic cluster used to hold`() {
        // The premise the preview rests on: an organic anchor only exists where no seed reached,
        // so it is not a rival a radius has to beat — it simply stops forming.
        val locations = listOf(at(0.0), at(300.0), at(320.0))
        fun seededWith(radiusM: Double) = PlaceClusterer
            .cluster(locations, distance = flatDistance, seeds = listOf(PlaceClusterer.Seed(at(0.0), radiusM)))
            .first { it.seedIndex == 0 }

        assertEquals(1, seededWith(100.0).visitCount)
        assertEquals(3, seededWith(400.0).visitCount)
    }

    @Test fun `the preview sees that widening too, given only named rivals`() {
        // Passing the organic anchor at 300 m in as a rival would concede both far endpoints to
        // it — it sits on top of one of them — and the preview would show a widened radius taking
        // nothing, which is exactly the bug this pins.
        val locations = listOf(at(0.0), at(300.0), at(320.0))
        val seed = PlaceClusterer.Seed(at(0.0), 400.0)

        assertEquals(
            PlaceClusterer.cluster(locations, distance = flatDistance, seeds = listOf(seed))
                .first { it.seedIndex == 0 }
                .members,
            PlaceClusterer.wouldCapture(locations, seed.anchor, seed.radiusM, emptyList(), flatDistance),
        )
    }

    @Test fun `agrees with what cluster actually assigns`() {
        // The mirror is only worth having while it matches the rule it mirrors: run the same
        // scene through cluster() and compare the seed's real members against the preview.
        val subject = PlaceClusterer.Seed(at(0.0), 400.0)
        val rival = PlaceClusterer.Seed(at(300.0), 150.0)
        val candidates = listOf(at(50.0), at(200.0), at(250.0), at(900.0))

        val real = PlaceClusterer
            .cluster(candidates, distance = flatDistance, seeds = listOf(subject, rival))
            .first { it.seedIndex == 0 }
            .members

        assertEquals(
            real,
            PlaceClusterer.wouldCapture(
                candidates, subject.anchor, subject.radiusM, listOf(rival), flatDistance,
            ),
        )
    }
}
