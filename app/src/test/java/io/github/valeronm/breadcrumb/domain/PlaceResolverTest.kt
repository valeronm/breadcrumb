package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.DistanceFn
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.domain.StayDeriver.Endpoint
import io.github.valeronm.breadcrumb.domain.StayDeriver.Provenance
import io.github.valeronm.breadcrumb.domain.StayDeriver.Stay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        provenance = Provenance.OBSERVED, afterTrackId = ++nextTrackId,
    )

    private fun place(id: Long, label: String, location: Endpoint) =
        Place(id = id, label = label, lat = location.lat, lon = location.lon, createdAt = 0L)

    private fun resolve(stays: List<Stay>, places: List<Place>) =
        PlaceResolver.resolve(stays, places, distance = flatDistance)

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
}
