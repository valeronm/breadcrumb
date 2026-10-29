package io.github.valeronm.breadcrumb.domain.atlas

import io.github.valeronm.breadcrumb.domain.flatDistance
import org.junit.Assert.assertEquals
import org.junit.Test

class SpotCityTest {

    private val north = testCity("Twin", 0.0, region = 1)
    private val south = testCity("Twin", 90_000.0, region = 2)
    private val lone = testCity("Lone", 180_000.0, region = 1)
    private val atlas = atlasOf(north, south, lone)
        .withNames(CityAtlas.LocalNames.NONE, regionNamesOf(3, "North", "South"))

    private fun regionAt(city: TestCity) =
        atlas.naming(city.lat, city.lon, flatDistance)?.let { SpotCity.of(it, atlas) }?.region

    @Test fun `a spot shows its region only where the atlas holds another town of its name in its country`() {
        assertEquals(listOf("North", "South", null), listOf(north, south, lone).map(::regionAt))
    }
}
