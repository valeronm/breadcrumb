package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.atlas.AtlasCity
import io.github.valeronm.breadcrumb.domain.atlas.CityAtlas
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class CityScreenTest {

    @Test fun `a city holds only the places of its own row, not those of a same-named town`() {
        val here = place(visits = 5, row = 3)
        val sameName = place(visits = 5, row = 9)

        assertEquals(listOf(here), placesIn(listOf(here, sameName), AtlasCity.of(twin(row = 3))))
    }

    @Test fun `a city leaves out an unnamed place with no visit`() {
        val visited = place(visits = 1, row = 3)
        val passedThrough = place(visits = 0, row = 3)

        assertEquals(listOf(visited), placesIn(listOf(visited, passedThrough), AtlasCity.of(twin(row = 3))))
    }

    private companion object {
        fun twin(row: Int) = CityAtlas.City("Twin", "XX", ZoneId.of("Etc/GMT"), 20_000, 0.0, row)

        fun place(visits: Int, row: Int) = PlaceResolver.PlaceSummary(
            place = null,
            visitCount = visits,
            lastSeenMs = null,
            totalMs = 0,
            anchor = Coordinate(lat = 1.0, lon = -2.0),
            radiusM = 100.0,
            endpoints = emptyList(),
            endpointCentroid = null,
            locality = twin(row),
        )
    }
}
