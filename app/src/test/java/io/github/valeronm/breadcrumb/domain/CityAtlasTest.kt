package io.github.valeronm.breadcrumb.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/** The lookups over a hand-built atlas — see `TestAtlas.kt` for the format the fixtures encode. */
class CityAtlasTest {

    private fun row(name: String, meters: Double, pop: Int = 20, zone: String = "Etc/GMT") =
        testCity(name, meters, pop, zone = zone)

    @Test fun `the nearest city wins, whichever side of the query it sits`() {
        val atlas = atlasOf(
            row("Westward", -40_000.0),
            row("Eastward", 25_000.0),
            row("Far east", 300_000.0),
        )

        assertEquals("Eastward", atlas.nearest(ORIGIN_LAT, lonAt(0.0), flatDistance)?.name)
        assertEquals("Westward", atlas.nearest(ORIGIN_LAT, lonAt(-30_000.0), flatDistance)?.name)
        assertEquals("Far east", atlas.nearest(ORIGIN_LAT, lonAt(280_000.0), flatDistance)?.name)
    }

    @Test fun `a row far away in latitude is still found when nothing is nearer`() {
        // The search walks outward from the query's latitude; a lone city two degrees north has to
        // survive the pruning bound rather than be cut off by it.
        val atlas = atlasOf(TestCity(ORIGIN_LAT + 2.0, lonAt(0.0), 30, "XX", "Etc/GMT", "Northerly"))

        val hit = atlas.nearest(ORIGIN_LAT, lonAt(0.0), flatDistance)
        assertEquals("Northerly", hit?.name)
        assertEquals(200_000.0, hit?.distanceM ?: 0.0, 1.0)
    }

    @Test fun `every field survives the round trip`() {
        val atlas = atlasOf(
            TestCity(ORIGIN_LAT, lonAt(1_000.0), 24_874, "PT", "Europe/Lisbon", "Sintra"),
            TestCity(ORIGIN_LAT + 1.0, lonAt(0.0), 7, "ES", "Europe/Madrid", "Móra d'Ebre"),
        )

        val hit = atlas.nearest(ORIGIN_LAT + 1.0, lonAt(0.0), flatDistance)!!
        // A non-ASCII name is the case a byte length and a character count disagree about.
        assertEquals("Móra d'Ebre", hit.name)
        assertEquals("ES", hit.country)
        assertEquals("Europe/Madrid", hit.zoneId)
        assertEquals(7_000, hit.population)

        val other = atlas.nearest(ORIGIN_LAT, lonAt(1_000.0), flatDistance)!!
        assertEquals("Sintra", other.name)
        assertEquals("Europe/Lisbon", other.zoneId)
        assertEquals(24_874_000, other.population)
    }

    @Test fun `naming prefers the city a district belongs to`() {
        // The case that made this rule: standing in the middle of a city, the nearest row is one of
        // its own districts, with its own small population and a name nobody would call the trip.
        val atlas = atlasOf(
            TestCity(ORIGIN_LAT, lonAt(400.0), 27, "XX", "Etc/GMT", "Fourth district"),
            TestCity(ORIGIN_LAT, lonAt(2_000.0), 2_100, "XX", "Etc/GMT", "The city"),
        )

        assertEquals("Fourth district", atlas.nearest(ORIGIN_LAT, lonAt(0.0), flatDistance)?.name)
        assertEquals("The city", atlas.naming(ORIGIN_LAT, lonAt(0.0), flatDistance)?.name)
    }

    @Test fun `a neighbour of comparable size does not take the name`() {
        // Inside the window and bigger, but not by enough to be the place this one belongs to: two
        // towns of a size are neighbours, and the one someone stood in wins.
        val atlas = atlasOf(
            TestCity(ORIGIN_LAT, lonAt(500.0), 35, "XX", "Etc/GMT", "Nearer town"),
            TestCity(ORIGIN_LAT, lonAt(4_000.0), 46, "XX", "Etc/GMT", "Bigger neighbour"),
        )

        assertEquals("Nearer town", atlas.naming(ORIGIN_LAT, lonAt(0.0), flatDistance)?.name)
    }

    @Test fun `a town several times larger nearby is still only a neighbour`() {
        // Four times the size and a few kilometres away — the shape of two towns along one coast,
        // not of a district and its city. Naming the larger one renames a stay in the smaller.
        val atlas = atlasOf(
            TestCity(ORIGIN_LAT, lonAt(500.0), 6, "XX", "Etc/GMT", "Smaller town"),
            TestCity(ORIGIN_LAT, lonAt(6_300.0), 23, "XX", "Etc/GMT", "Larger town"),
        )

        assertEquals("Smaller town", atlas.naming(ORIGIN_LAT, lonAt(0.0), flatDistance)?.name)
    }

    @Test fun `naming leaves a separate town its own name`() {
        // A big city beyond the window does not get to swallow the town someone actually stayed in.
        val atlas = atlasOf(
            TestCity(ORIGIN_LAT, lonAt(500.0), 35, "XX", "Etc/GMT", "Seaside town"),
            TestCity(ORIGIN_LAT, lonAt(25_000.0), 2_100, "XX", "Etc/GMT", "The city"),
        )

        assertEquals("Seaside town", atlas.naming(ORIGIN_LAT, lonAt(0.0), flatDistance)?.name)
    }

    @Test fun `a place across a border does not name a coordinate its neighbours disown`() {
        // The nearest row is foreign and the country around it is not: a village near a border would
        // otherwise take a name from the far side, and count a country nobody entered.
        val atlas = atlasOf(
            TestCity(ORIGIN_LAT, lonAt(1_200.0), 2, "ES", "Europe/Madrid", "Across the line"),
            TestCity(ORIGIN_LAT, lonAt(-3_000.0), 3, "PT", "Europe/Lisbon", "This side"),
            TestCity(ORIGIN_LAT, lonAt(-6_000.0), 4, "PT", "Europe/Lisbon", "Also this side"),
            TestCity(ORIGIN_LAT, lonAt(-9_000.0), 5, "PT", "Europe/Lisbon", "Further in"),
        )

        val hit = atlas.naming(ORIGIN_LAT, lonAt(0.0), flatDistance)!!
        assertEquals("This side", hit.name)
        assertEquals("PT", hit.country)
        // The raw nearest is unguarded and still answers with what is actually closest.
        assertEquals("Across the line", atlas.nearest(ORIGIN_LAT, lonAt(0.0), flatDistance)?.name)
    }

    @Test fun `a place with no population figure keeps its name`() {
        // Administrative seats are listed whatever their size, and many carry a zero. Zero times any
        // factor is a bar every neighbour clears, so without a case of its own the village someone
        // stayed in loses its name to the nearest larger town.
        val atlas = atlasOf(
            TestCity(ORIGIN_LAT, lonAt(400.0), 0, "XX", "Etc/GMT", "Seat of nowhere"),
            TestCity(ORIGIN_LAT, lonAt(3_000.0), 40, "XX", "Etc/GMT", "Market town"),
        )

        assertEquals("Seat of nowhere", atlas.naming(ORIGIN_LAT, lonAt(0.0), flatDistance)?.name)
    }

    @Test fun `name search folds accents and ranks prefix hits before larger infix ones`() {
        val atlas = atlasOf(
            row("Lisbon", 0.0, pop = 500),
            row("East Lisbon", 10_000.0, pop = 900),
            row("Lisburn", 20_000.0, pop = 40),
            row("Móra d'Ebre", 30_000.0, pop = 7),
        )

        // Prefix hits first (by population), then infix ones — a bigger "East Lisbon" must not
        // outrank the city someone typed the start of.
        assertEquals(
            listOf("Lisbon", "Lisburn", "East Lisbon"),
            atlas.searchByName("Lis", limit = 10).map { it.name },
        )
        assertEquals(listOf("Lisbon", "Lisburn"), atlas.searchByName("lis", limit = 2).map { it.name })
        // An accented name is reachable from the plain keys, and hands back where it sits.
        val mora = atlas.searchByName("mora", limit = 5).single()
        assertEquals("Móra d'Ebre", mora.name)
        assertEquals(ORIGIN_LAT, mora.lat, 1e-5)
        assertTrue(atlas.searchByName("nowhere at all", limit = 5).isEmpty())
        assertTrue(atlas.searchByName("   ", limit = 5).isEmpty())
        // A single letter would rank tens of thousands of rows nobody asked it to.
        assertTrue(atlas.searchByName("l", limit = 5).isEmpty())
    }

    @Test fun `a match spanning two adjacent names is nobody's hit`() {
        // The index is one concatenated string, so a needle can straddle the seam between names.
        val atlas = atlasOf(
            TestCity(ORIGIN_LAT, lonAt(0.0), 5, "XX", "Etc/GMT", "Abcd"),
            TestCity(ORIGIN_LAT + 1.0, lonAt(0.0), 5, "XX", "Etc/GMT", "Cdef"),
        )

        assertTrue(atlas.searchByName("dc", limit = 5).isEmpty())
        // ...while a needle sitting inside either single name still finds it.
        assertEquals(listOf("Cdef", "Abcd"), atlas.searchByName("cd", limit = 5).map { it.name })
    }

    @Test fun `an empty atlas names nothing rather than failing`() {
        val atlas = atlasOf()

        assertEquals(0, atlas.size)
        assertNull(atlas.nearest(ORIGIN_LAT, lonAt(0.0), flatDistance))
    }

    @Test fun `anything that is not an atlas is rejected at parse`() {
        assertThrows(IllegalArgumentException::class.java) { CityAtlas.parse(ByteArray(0)) }
        assertThrows(IllegalArgumentException::class.java) {
            CityAtlas.parse("not an atlas at all".toByteArray(StandardCharsets.UTF_8))
        }
    }
}
