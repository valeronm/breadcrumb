package io.github.valeronm.breadcrumb.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins the shape [OnlinePlaceSearch.parse] expects of a Photon response, with no network near. */
class OnlinePlaceSearchTest {

    @Test fun `parses features into hits, longitude first as GeoJSON orders it`() {
        val json = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","geometry":{"type":"Point","coordinates":[-2.05,1.05]},
               "properties":{"name":"Hotel Origin","city":"Origintown","country":"Testland",
                             "osm_key":"tourism","osm_value":"hotel","extent":[1,2,3,4]}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[-2.6,1.6]},
               "properties":{"name":"Bare Name"}}
            ]}
        """.trimIndent()

        val hits = OnlinePlaceSearch.parse(json.reader())

        assertEquals(2, hits.size)
        val hotel = hits[0]
        assertEquals("Hotel Origin", hotel.name)
        assertEquals("Origintown, Testland", hotel.locality)
        assertEquals(1.05, hotel.lat, 1e-9)
        assertEquals(-2.05, hotel.lon, 1e-9)
        assertNull("no city and no country is no locality line", hits[1].locality)
    }

    @Test fun `a nameless feature is dropped rather than pinned`() {
        // Photon also returns bare addresses; a nameless result offers nothing a long press on the
        // map doesn't.
        val json = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","geometry":{"type":"Point","coordinates":[-2.5,1.5]},
               "properties":{"city":"Nameless","country":"Testland"}}
            ]}
        """.trimIndent()

        assertEquals(0, OnlinePlaceSearch.parse(json.reader()).size)
    }
}
