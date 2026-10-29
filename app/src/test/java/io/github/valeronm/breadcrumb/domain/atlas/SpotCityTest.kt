package io.github.valeronm.breadcrumb.domain.atlas

import io.github.valeronm.breadcrumb.domain.atlas.TwinTowns.atlas
import io.github.valeronm.breadcrumb.domain.atlas.TwinTowns.lone
import io.github.valeronm.breadcrumb.domain.atlas.TwinTowns.north
import io.github.valeronm.breadcrumb.domain.atlas.TwinTowns.south
import io.github.valeronm.breadcrumb.domain.flatDistance
import org.junit.Assert.assertEquals
import org.junit.Test

class SpotCityTest {

    private fun regionAt(city: TestCity) =
        atlas.naming(city.lat, city.lon, flatDistance)?.let { SpotCity.of(it, atlas) }?.region

    @Test fun `a spot shows its region only where the atlas holds another town of its name in its country`() {
        assertEquals(listOf("North", "South", null), listOf(north, south, lone).map(::regionAt))
    }
}
