package io.github.valeronm.breadcrumb.domain

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import kotlin.math.roundToLong

/** Rings of (lat, lon) pairs. */
internal class TestOutline(val country: String, val rings: List<List<Pair<Double, Double>>>)

/** An outline spanning [west]..[east] meters east of the origin and [south]..[north] north of it. */
internal fun box(
    country: String,
    west: Double,
    east: Double,
    south: Double = -50_000.0,
    north: Double = 50_000.0,
    holes: List<List<Pair<Double, Double>>> = emptyList(),
) = TestOutline(country, listOf(rectangle(west, east, south, north)) + holes)

/** The ring of a rectangle placed as in [box]. */
internal fun rectangle(west: Double, east: Double, south: Double, north: Double) = listOf(
    latAt(south) to lonAt(west),
    latAt(south) to lonAt(east),
    latAt(north) to lonAt(east),
    latAt(north) to lonAt(west),
)

internal fun bordersOf(outlines: List<TestOutline>, rowCount: Int = 0, parsedFor: Int = rowCount): CountryBorders =
    CountryBorders.parse(bordersBytes(outlines, rowCount), parsedFor)

/** `tools/pack_borders.py`'s format encoded by hand, the only check of that format CI runs. */
internal fun bordersBytes(outlines: List<TestOutline>, rowCount: Int = 0): ByteArray {
    val out = ByteArrayOutputStream()
    out.write("BCBR1".toByteArray(StandardCharsets.US_ASCII))
    out.i32(rowCount)
    out.i32(outlines.size)
    for (outline in outlines) {
        out.write(outline.country.toByteArray(StandardCharsets.US_ASCII))
        out.varint(outline.rings.size.toLong())
        for (ring in outline.rings) {
            out.varint(ring.size.toLong())
            var lat0 = 0L
            var lon0 = 0L
            for ((lat, lon) in ring) {
                val qLat = (lat * 1e5).roundToLong()
                val qLon = (lon * 1e5).roundToLong()
                out.zigzag(qLat - lat0)
                out.zigzag(qLon - lon0)
                lat0 = qLat
                lon0 = qLon
            }
        }
    }
    return out.toByteArray()
}

private fun ByteArrayOutputStream.varint(value: Long) {
    var rest = value
    while (rest >= 0x80) {
        u8((rest and 0x7F).toInt() or 0x80)
        rest = rest ushr 7
    }
    u8(rest.toInt())
}

private fun ByteArrayOutputStream.zigzag(value: Long) = varint((value shl 1) xor (value shr 63))
