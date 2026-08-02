package io.github.valeronm.breadcrumb.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import kotlin.math.roundToInt

/**
 * The atlas is read from an asset `tools/pack_cities.py` writes, so these fixtures encode the format
 * a second time, by hand, from the reader's side. That duplication is the point: a format with one
 * implementation is a format nothing checks, and the packer runs on a developer's machine while this
 * runs in CI.
 *
 * Coordinates here are invented and sit near the neutral origin the other suites use.
 */
class CityAtlasTest {

    private class Row(
        val lat: Double,
        val lon: Double,
        val popThousands: Int,
        val country: String,
        val zone: String,
        val name: String,
    )

    /** The packer's format, written out again — see its docstring for the field-by-field spec. */
    private fun atlasOf(vararg rows: Row): CityAtlas {
        val sorted = rows.sortedBy { it.lat }
        val zones = sorted.map { it.zone }.distinct().sorted()
        val out = ByteArrayOutputStream()
        fun u8(v: Int) = out.write(v and 0xFF)
        fun u16(v: Int) {
            u8(v shr 8)
            u8(v)
        }
        fun i32(v: Int) {
            u16(v shr 16)
            u16(v)
        }

        out.write("BCTY1".toByteArray(StandardCharsets.US_ASCII))
        i32(sorted.size)
        u16(zones.size)
        for (zone in zones) {
            val encoded = zone.toByteArray(StandardCharsets.UTF_8)
            u8(encoded.size)
            out.write(encoded)
        }
        for (row in sorted) {
            i32((row.lat * 1e6).roundToInt())
            i32((row.lon * 1e6).roundToInt())
            u16(row.popThousands)
            out.write(row.country.toByteArray(StandardCharsets.US_ASCII))
            u16(zones.indexOf(row.zone))
            u8(row.name.toByteArray(StandardCharsets.UTF_8).size)
        }
        for (row in sorted) out.write(row.name.toByteArray(StandardCharsets.UTF_8))
        return CityAtlas.parse(out.toByteArray())
    }

    private fun row(name: String, meters: Double, pop: Int = 20, zone: String = "Etc/GMT") =
        Row(ORIGIN_LAT, lonAt(meters), pop, "XX", zone, name)

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
        val atlas = atlasOf(Row(ORIGIN_LAT + 2.0, lonAt(0.0), 30, "XX", "Etc/GMT", "Northerly"))

        val hit = atlas.nearest(ORIGIN_LAT, lonAt(0.0), flatDistance)
        assertEquals("Northerly", hit?.name)
        assertEquals(200_000.0, hit?.distanceM ?: 0.0, 1.0)
    }

    @Test fun `every field survives the round trip`() {
        val atlas = atlasOf(
            Row(ORIGIN_LAT, lonAt(1_000.0), 24_874, "PT", "Europe/Lisbon", "Sintra"),
            Row(ORIGIN_LAT + 1.0, lonAt(0.0), 7, "ES", "Europe/Madrid", "Móra d'Ebre"),
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
            Row(ORIGIN_LAT, lonAt(400.0), 27, "XX", "Etc/GMT", "Fourth district"),
            Row(ORIGIN_LAT, lonAt(2_000.0), 2_100, "XX", "Etc/GMT", "The city"),
        )

        assertEquals("Fourth district", atlas.nearest(ORIGIN_LAT, lonAt(0.0), flatDistance)?.name)
        assertEquals("The city", atlas.naming(ORIGIN_LAT, lonAt(0.0), flatDistance)?.name)
    }

    @Test fun `a neighbour of comparable size does not take the name`() {
        // Inside the window and bigger, but not by enough to be the place this one belongs to: two
        // towns of a size are neighbours, and the one someone stood in wins.
        val atlas = atlasOf(
            Row(ORIGIN_LAT, lonAt(500.0), 35, "XX", "Etc/GMT", "Nearer town"),
            Row(ORIGIN_LAT, lonAt(4_000.0), 46, "XX", "Etc/GMT", "Bigger neighbour"),
        )

        assertEquals("Nearer town", atlas.naming(ORIGIN_LAT, lonAt(0.0), flatDistance)?.name)
    }

    @Test fun `a town several times larger nearby is still only a neighbour`() {
        // Four times the size and a few kilometres away — the shape of two towns along one coast,
        // not of a district and its city. Naming the larger one renames a stay in the smaller.
        val atlas = atlasOf(
            Row(ORIGIN_LAT, lonAt(500.0), 6, "XX", "Etc/GMT", "Smaller town"),
            Row(ORIGIN_LAT, lonAt(6_300.0), 23, "XX", "Etc/GMT", "Larger town"),
        )

        assertEquals("Smaller town", atlas.naming(ORIGIN_LAT, lonAt(0.0), flatDistance)?.name)
    }

    @Test fun `naming leaves a separate town its own name`() {
        // A big city beyond the window does not get to swallow the town someone actually stayed in.
        val atlas = atlasOf(
            Row(ORIGIN_LAT, lonAt(500.0), 35, "XX", "Etc/GMT", "Seaside town"),
            Row(ORIGIN_LAT, lonAt(25_000.0), 2_100, "XX", "Etc/GMT", "The city"),
        )

        assertEquals("Seaside town", atlas.naming(ORIGIN_LAT, lonAt(0.0), flatDistance)?.name)
    }

    @Test fun `a place across a border does not name a coordinate its neighbours disown`() {
        // The nearest row is foreign and the country around it is not: a village near a border would
        // otherwise take a name from the far side, and count a country nobody entered.
        val atlas = atlasOf(
            Row(ORIGIN_LAT, lonAt(1_200.0), 2, "ES", "Europe/Madrid", "Across the line"),
            Row(ORIGIN_LAT, lonAt(-3_000.0), 3, "PT", "Europe/Lisbon", "This side"),
            Row(ORIGIN_LAT, lonAt(-6_000.0), 4, "PT", "Europe/Lisbon", "Also this side"),
            Row(ORIGIN_LAT, lonAt(-9_000.0), 5, "PT", "Europe/Lisbon", "Further in"),
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
            Row(ORIGIN_LAT, lonAt(400.0), 0, "XX", "Etc/GMT", "Seat of nowhere"),
            Row(ORIGIN_LAT, lonAt(3_000.0), 40, "XX", "Etc/GMT", "Market town"),
        )

        assertEquals("Seat of nowhere", atlas.naming(ORIGIN_LAT, lonAt(0.0), flatDistance)?.name)
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
