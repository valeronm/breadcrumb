package io.github.valeronm.breadcrumb.domain

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import kotlin.math.roundToInt

/**
 * The atlas's binary format, written out by hand from the reader's side — the packer that
 * produces the shipped asset is a Python script on a developer's machine, so a fixture here is the
 * only thing that checks the format in CI. Shared by every suite that needs an atlas, so the format
 * is duplicated exactly once.
 *
 * Coordinates are invented and sit at the neutral origin the domain suites use ([lonAt]).
 */
internal class TestCity(
    val lat: Double,
    val lon: Double,
    val popThousands: Int,
    val country: String,
    val zone: String,
    val name: String,
)

/** A city [meters] east of the origin — the shape most fixtures want. */
internal fun testCity(
    name: String,
    meters: Double,
    pop: Int = 20,
    country: String = "XX",
    zone: String = "Etc/GMT",
) = TestCity(ORIGIN_LAT, lonAt(meters), pop, country, zone, name)

/** See `tools/pack_cities.py`'s docstring for the field-by-field spec this encodes. */
internal fun atlasOf(vararg rows: TestCity): CityAtlas {
    val sorted = rows.sortedBy { it.lat }
    val zones = sorted.map { it.zone }.distinct().sorted()
    val out = ByteArrayOutputStream()
    out.write("BCTY1".toByteArray(StandardCharsets.US_ASCII))
    out.i32(sorted.size)
    out.u16(zones.size)
    for (zone in zones) {
        val encoded = zone.toByteArray(StandardCharsets.UTF_8)
        out.u8(encoded.size)
        out.write(encoded)
    }
    for (row in sorted) {
        out.i32((row.lat * 1e6).roundToInt())
        out.i32((row.lon * 1e6).roundToInt())
        out.u16(row.popThousands)
        out.write(row.country.toByteArray(StandardCharsets.US_ASCII))
        out.u16(zones.indexOf(row.zone))
        out.u8(row.name.toByteArray(StandardCharsets.UTF_8).size)
    }
    for (row in sorted) out.write(row.name.toByteArray(StandardCharsets.UTF_8))
    return CityAtlas.parse(out.toByteArray())
}

/**
 * A names table for an atlas of [rowCount] rows, [names] keyed by row — the order [atlasOf] sorts
 * them into, which for rows on one latitude is the order they were given in — read back as one for
 * an atlas of [parsedFor] rows.
 */
internal fun localNamesOf(rowCount: Int, names: Map<Int, String>, parsedFor: Int = rowCount): CityAtlas.LocalNames {
    val out = ByteArrayOutputStream()
    out.write("BCTN1".toByteArray(StandardCharsets.US_ASCII))
    out.i32(rowCount)
    out.i32(names.size)
    val sorted = names.toSortedMap()
    for ((row, name) in sorted) {
        out.i32(row)
        out.u8(name.toByteArray(StandardCharsets.UTF_8).size)
    }
    for (name in sorted.values) out.write(name.toByteArray(StandardCharsets.UTF_8))
    return CityAtlas.LocalNames.parse(out.toByteArray(), parsedFor)
}

private fun ByteArrayOutputStream.u8(v: Int) = write(v and 0xFF)

private fun ByteArrayOutputStream.u16(v: Int) {
    u8(v shr 8)
    u8(v)
}

private fun ByteArrayOutputStream.i32(v: Int) {
    u16(v shr 16)
    u16(v)
}
