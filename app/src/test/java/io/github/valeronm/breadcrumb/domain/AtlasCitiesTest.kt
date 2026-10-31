package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.domain.atlas.CityAtlas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AtlasCitiesTest {

    private fun city(name: String) =
        CityAtlas.City(name = name, country = "XX", zone = null, population = 1_000, distanceM = 0.0, row = 0)

    @Test fun `a cluster is answered where its visits landed, not at its pin`() {
        val pin = at(0.0)
        val visits = at(300.0)
        val cluster = PlaceClusterer.cluster(
            listOf(visits, visits),
            distance = flatDistance,
            seeds = listOf(PlaceClusterer.Seed(pin, 400.0, placeId = 1L, named = true)),
        ).single()
        val cities = AtlasCities(mapOf(pin to city("Pin"), cluster.atlasPoint to city("Visits")))

        assertEquals("Visits", cities.ofCluster(cluster)?.name)
    }

    @Test fun `a loose end is answered at itself`() {
        val cities = AtlasCities(mapOf(at(0.0) to city("Here")))

        assertEquals("Here", cities.ofLooseEnd(at(0.0))?.name)
        assertNull(cities.ofLooseEnd(at(10.0)))
    }
}
