package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.data.OnlinePlaceSearch
import io.github.valeronm.breadcrumb.domain.at
import io.github.valeronm.breadcrumb.domain.flatDistance
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaceNameSuggestionsTest {

    private fun hit(name: String, meters: Double, locality: String? = "Town") =
        at(meters).let { OnlinePlaceSearch.Hit(name, locality, it.lat, it.lon) }

    private fun names(hits: List<OnlinePlaceSearch.Hit>, radiusM: Double) =
        nameSuggestions(hits, at(0.0), radiusM, flatDistance).map { it.name }

    @Test fun `a result outside the circle is dropped`() {
        assertEquals(listOf("Near"), names(listOf(hit("Near", 90.0), hit("Far", 110.0)), radiusM = 100.0))
    }

    @Test fun `the edge of the circle is inside it`() {
        assertEquals(listOf("Edge"), names(listOf(hit("Edge", 100.0)), radiusM = 100.0))
    }

    @Test fun `results that would read identically are shown once`() {
        val hits = listOf(hit("Café", 10.0), hit("cafe", 20.0), hit("Café", 30.0, locality = "Other"))
        assertEquals(listOf("Café", "Café"), names(hits, radiusM = 100.0))
    }
}
