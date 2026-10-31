package io.github.valeronm.breadcrumb.history

import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.at
import io.github.valeronm.breadcrumb.domain.atlas.CityAtlas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CityCacheTest {

    private val a = at(0.0)
    private val b = at(5_000.0)

    private val lookedUp = mutableListOf<Coordinate>()
    private var unreached = emptySet<Coordinate>()

    private val cache = CityCache { point, language ->
        lookedUp += point
        if (point in unreached) {
            null
        } else {
            CityAtlas.City(name = "$language town", country = "XX", zone = null, population = 1_000, distanceM = 0.0, row = 0)
        }
    }

    private fun citiesAt(vararg points: Coordinate, language: String = "en") = cache.citiesAt(
        StayDeriver.Derivation(emptyList(), emptyList()),
        points.mapIndexed { i, point -> StayDeriver.TrackEnd(i + 1L, 0L, 1L, point, null) },
        language,
    )

    @Test fun `a point that left the history is looked up again when it returns`() {
        citiesAt(a)
        citiesAt(b)
        citiesAt(a)

        assertEquals(listOf(a, b, a), lookedUp)
    }

    @Test fun `a language change looks every point up again`() {
        citiesAt(a, b)
        lookedUp.clear()

        val cities = citiesAt(a, b, language = "de")

        assertEquals(listOf(a, b), lookedUp)
        assertEquals("de town", cities.ofLooseEnd(a)?.name)
    }

    @Test fun `a point the atlas answered null for is not looked up again`() {
        unreached = setOf(b)
        citiesAt(b)
        lookedUp.clear()

        val cities = citiesAt(b)

        assertEquals(emptyList<Coordinate>(), lookedUp)
        assertNull(cities.ofLooseEnd(b))
    }
}
