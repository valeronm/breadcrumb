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

    private val acrossTheLine = listOf(
        box("PT", west = -200_000.0, east = 1_000.0),
        box("ES", west = 1_000.0, east = 200_000.0),
    )

    private fun pt(name: String, meters: Double, pop: Int = 3) = testCity(name, meters, pop, "PT", "Europe/Lisbon")

    private fun es(name: String, meters: Double, pop: Int = 3) = testCity(name, meters, pop, "ES", "Europe/Madrid")

    @Test fun `the nearest city wins, whichever side of the query it sits`() {
        val atlas = atlasOf(
            row("Westward", -40_000.0),
            row("Eastward", 25_000.0),
            row("Far east", 300_000.0),
        )

        assertEquals("Eastward", atlas.naming(ORIGIN_LAT, lonAt(0.0), flatDistance)?.name)
        assertEquals("Westward", atlas.naming(ORIGIN_LAT, lonAt(-30_000.0), flatDistance)?.name)
        assertEquals("Far east", atlas.naming(ORIGIN_LAT, lonAt(280_000.0), flatDistance)?.name)
    }

    @Test fun `a row far away in latitude is still found when nothing is nearer`() {
        // The search walks outward from the query's latitude; a lone city two degrees north has to
        // survive the pruning bound rather than be cut off by it.
        val atlas = atlasOf(TestCity(ORIGIN_LAT + 2.0, lonAt(0.0), 30, "XX", "Etc/GMT", "Northerly"))

        val hit = atlas.naming(ORIGIN_LAT, lonAt(0.0), flatDistance)
        assertEquals("Northerly", hit?.name)
        assertEquals(200_000.0, hit?.distanceM ?: 0.0, 1.0)
    }

    @Test fun `every field survives the round trip`() {
        val atlas = atlasOf(
            TestCity(ORIGIN_LAT, lonAt(1_000.0), 24_874, "PT", "Europe/Lisbon", "Sintra"),
            TestCity(ORIGIN_LAT + 1.0, lonAt(0.0), 7, "ES", "Europe/Madrid", "Móra d'Ebre"),
        )

        val hit = atlas.naming(ORIGIN_LAT + 1.0, lonAt(0.0), flatDistance)!!
        // A non-ASCII name is the case a byte length and a character count disagree about.
        assertEquals("Móra d'Ebre", hit.name)
        assertEquals("ES", hit.country)
        assertEquals("Europe/Madrid", hit.zoneId)
        assertEquals(7_000, hit.population)

        val other = atlas.naming(ORIGIN_LAT, lonAt(1_000.0), flatDistance)!!
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

    @Test fun `a place across a border does not name a coordinate on this side of it`() {
        val atlas = atlasOf(
            es("Across the line", 1_200.0, pop = 2),
            pt("This side", -3_000.0),
            outlines = acrossTheLine,
        )

        val hit = atlas.naming(ORIGIN_LAT, lonAt(0.0), flatDistance)!!
        assertEquals("This side", hit.name)
        assertEquals("PT", hit.country)
    }

    @Test fun `a town outnumbered by places across the border keeps its own country`() {
        val atlas = atlasOf(
            pt("Hilltown", 0.0, pop = 0),
            pt("Market town", -17_500.0, pop = 7),
            es("Across one", 15_000.0),
            es("Across two", 15_200.0, pop = 1),
            es("Across three", 20_000.0, pop = 0),
            outlines = acrossTheLine,
        )

        val hit = atlas.naming(ORIGIN_LAT, lonAt(200.0), flatDistance)!!
        assertEquals("Hilltown", hit.name)
        assertEquals("PT", hit.country)
        assertEquals("Europe/Lisbon", hit.zoneId)
    }

    @Test fun `the nearest place in the spot's country names it however far, before a closer foreign one`() {
        val atlas = atlasOf(
            pt("Far inland", -40_000.0),
            es("Just across", 10_000.0),
            outlines = acrossTheLine,
        )

        assertEquals("Far inland", atlas.naming(ORIGIN_LAT, lonAt(0.0), flatDistance)?.name)
    }

    @Test fun `a spot in no outline takes the country of an outline nearer than any place`() {
        val atlas = atlasOf(
            es("Across the bay", 2_000.0),
            pt("Harbor town", -5_000.0),
            outlines = listOf(
                box("PT", west = -200_000.0, east = -1_000.0),
                box("ES", west = 1_500.0, east = 200_000.0),
            ),
        )

        assertEquals("Harbor town", atlas.naming(ORIGIN_LAT, lonAt(0.0), flatDistance)?.name)
    }

    @Test fun `a spot in no outline takes the country of a place nearer than any outline`() {
        val atlas = atlasOf(
            testCity("Island village", 300.0, 1, "GR", "Europe/Athens"),
            testCity("Mainland town", 8_000.0, 40, "TR", "Europe/Istanbul"),
            outlines = listOf(box("TR", west = 5_000.0, east = 200_000.0)),
        )

        val hit = atlas.naming(ORIGIN_LAT, lonAt(0.0), flatDistance)!!
        assertEquals("Island village", hit.name)
        assertEquals("GR", hit.country)
    }

    @Test fun `a spot in a country with no places is named by the nearest place of any`() {
        val atlas = atlasOf(
            testCity("Nearest anywhere", 20_000.0, 2),
            outlines = listOf(box("AQ", west = -10_000.0, east = 10_000.0)),
        )

        val hit = atlas.naming(ORIGIN_LAT, lonAt(0.0), flatDistance)!!
        assertEquals("Nearest anywhere", hit.name)
        assertEquals("XX", hit.country)
    }

    @Test fun `a district takes its city's name only from the same country`() {
        val atlas = atlasOf(
            pt("Border district", -200.0, pop = 5),
            es("Big city across", 2_500.0, pop = 400),
            outlines = acrossTheLine,
        )

        assertEquals("Border district", atlas.naming(ORIGIN_LAT, lonAt(0.0), flatDistance)?.name)
    }

    @Test fun `of two cities a district could belong to, equally populous, the nearer takes the name`() {
        val atlas = atlasOf(
            testCity("Small district", 200.0, pop = 5),
            TestCity(latAt(3_000.0), lonAt(0.0), 40, "XX", "Etc/GMT", "Farther city"),
            TestCity(latAt(-2_000.0), lonAt(0.0), 40, "XX", "Etc/GMT", "Nearer city"),
        )

        assertEquals("Nearer city", atlas.naming(ORIGIN_LAT, lonAt(0.0), flatDistance)?.name)
    }

    @Test fun `a bigger town across a regional line does not take the name`() {
        val atlas = atlasOf(
            testCity("Harbor town", 700.0, pop = 2, region = 9),
            testCity("Capital", 1_250.0, pop = 6, region = 60),
            testCity("Big town", 4_350.0, pop = 24, region = 4),
        )

        assertEquals("Harbor town", atlas.naming(ORIGIN_LAT, lonAt(0.0), flatDistance)?.name)
    }

    @Test fun `a district takes its city's name within their region`() {
        val atlas = atlasOf(
            testCity("Fourth district", 400.0, pop = 27, region = 3),
            testCity("Neighbor city", 1_500.0, pop = 3_000, region = 5),
            testCity("The city", 2_000.0, pop = 2_100, region = 3),
        )

        assertEquals("The city", atlas.naming(ORIGIN_LAT, lonAt(0.0), flatDistance)?.name)
    }

    @Test fun `a row with no region code is in every region`() {
        val uncodedCity = atlasOf(
            testCity("Coded district", 400.0, pop = 27, region = 3),
            testCity("Uncoded city", 2_000.0, pop = 2_100),
        )
        val uncodedDistrict = atlasOf(
            testCity("Uncoded district", 400.0, pop = 27),
            testCity("Coded city", 2_000.0, pop = 2_100, region = 3),
        )

        assertEquals("Uncoded city", uncodedCity.naming(ORIGIN_LAT, lonAt(0.0), flatDistance)?.name)
        assertEquals("Coded city", uncodedDistrict.naming(ORIGIN_LAT, lonAt(0.0), flatDistance)?.name)
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

    @Test fun `a place is named in the language that has a name for it, and by its own elsewhere`() {
        val base = atlasOf(row("Lisbon", 0.0), row("Porto", 300_000.0))
        val atlas = base.withNames(localNamesOf(2, mapOf(0 to "Lisboa")))

        assertEquals("Lisboa", atlas.naming(ORIGIN_LAT, lonAt(0.0), flatDistance)?.name)
        assertEquals("Porto", atlas.naming(ORIGIN_LAT, lonAt(300_000.0), flatDistance)?.name)
        assertEquals("Lisbon", base.naming(ORIGIN_LAT, lonAt(0.0), flatDistance)?.name)
    }

    @Test fun `name search finds a place by either name and reports the language's`() {
        val atlas = atlasOf(row("Lisbon", 0.0, pop = 10), row("Olisboa", 300_000.0, pop = 500))
            .withNames(localNamesOf(2, mapOf(0 to "Lisboa")))

        assertEquals(listOf("Lisboa"), atlas.searchByName("lisbon", limit = 5).map { it.name })
        assertEquals(listOf("Lisboa", "Olisboa"), atlas.searchByName("lisboa", limit = 5).map { it.name })
    }

    @Test fun `a place carries its region's name, and none where it has no region or the region no name`() {
        val atlas = atlasOf(
            testCity("Coded", 0.0, region = 2),
            testCity("Uncoded", 100_000.0),
            testCity("Unnamed region", 200_000.0, region = 1),
        ).withNames(CityAtlas.LocalNames.NONE, regionNamesOf(3, null, "Coast"))

        assertEquals("Coast", atlas.naming(ORIGIN_LAT, lonAt(0.0), flatDistance)?.region)
        assertNull(atlas.naming(ORIGIN_LAT, lonAt(100_000.0), flatDistance)?.region)
        assertNull(atlas.naming(ORIGIN_LAT, lonAt(200_000.0), flatDistance)?.region)
    }

    @Test fun `a region table for another atlas is rejected at parse`() {
        assertThrows(IllegalArgumentException::class.java) {
            regionNamesOf(2, "Coast", parsedFor = 3)
        }
    }

    @Test fun `a match spanning a place's two names is no hit`() {
        val atlas = atlasOf(row("Abcd", 0.0)).withNames(localNamesOf(1, mapOf(0 to "Efgh")))

        assertTrue(atlas.searchByName("de", limit = 5).isEmpty())
    }

    @Test fun `a names table for another atlas is rejected at parse`() {
        assertThrows(IllegalArgumentException::class.java) {
            localNamesOf(2, mapOf(0 to "Lisboa"), parsedFor = 3)
        }
    }

    @Test fun `a row has a namesake only where another in its country goes by its name`() {
        val atlas = atlasOf(
            testCity("Twin", 0.0),
            testCity("Twin", 90_000.0),
            testCity("Lone", 180_000.0),
            testCity("Lone", 270_000.0, country = "YY"),
        )

        assertEquals(listOf(true, true, false, false), (0 until atlas.size).map(atlas::hasNamesake))
    }

    @Test fun `a namesake is judged by the names the atlas's language gives`() {
        val atlas = atlasOf(testCity("Abcd", 0.0), testCity("Efgh", 90_000.0))
            .withNames(localNamesOf(2, mapOf(1 to "Abcd")))

        assertEquals(listOf(true, true), (0 until atlas.size).map(atlas::hasNamesake))
    }

    @Test fun `a table cut off inside its header is rejected at parse`() {
        fun cut(magic: String) = (magic + "\u0000").toByteArray(StandardCharsets.US_ASCII)

        assertThrows(IllegalArgumentException::class.java) { CityAtlas.LocalNames.parse(cut("BCTN1"), 0) }
        assertThrows(IllegalArgumentException::class.java) { CityAtlas.RegionNames.parse(cut("BCRG1"), 0) }
        assertThrows(IllegalArgumentException::class.java) { CityAtlas.parse(cut("BCTY2"), bordersBytes(emptyList())) }
    }

    @Test fun `an empty atlas names nothing rather than failing`() {
        val atlas = atlasOf()

        assertEquals(0, atlas.size)
        assertNull(atlas.naming(ORIGIN_LAT, lonAt(0.0), flatDistance))
    }

    @Test fun `anything that is not an atlas is rejected at parse`() {
        val noOutlines = bordersBytes(emptyList())
        assertThrows(IllegalArgumentException::class.java) { CityAtlas.parse(ByteArray(0), noOutlines) }
        assertThrows(IllegalArgumentException::class.java) {
            CityAtlas.parse("not an atlas at all".toByteArray(StandardCharsets.UTF_8), noOutlines)
        }
    }
}
