package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.domain.Coordinate
import kotlin.math.abs

internal const val COLLECTION_HEAD = """{"type":"FeatureCollection","features":["""
internal const val COLLECTION_TAIL = "]}"
internal const val NO_FEATURES = COLLECTION_HEAD + COLLECTION_TAIL

/** A line feature is this, its positions ([appendPositions]), [LINE_FEATURE_MID], its properties and a closing brace. */
internal const val LINE_FEATURE_HEAD = """{"type":"Feature","geometry":{"type":"LineString","coordinates":"""
internal const val LINE_FEATURE_MID = """},"properties":"""

/**
 * [fixes] of [lonLat] ([lonLatOf]) as a GeoJSON array of positions, densified along each leg's great circle where
 * [greatCircle] holds ([greatCirclePositions]).
 */
internal fun StringBuilder.appendPositions(lonLat: DoubleArray, fixes: IntRange, greatCircle: Boolean): StringBuilder {
    if (greatCircle) {
        val arcs = lonLatOf(greatCirclePositions(coordinatesOf(lonLat).subList(fixes.first, fixes.last + 1)))
        return appendPositions(arcs, 0 until arcs.size / 2, greatCircle = false)
    }
    ensureCapacity(length + (fixes.last - fixes.first + 1) * CHARS_PER_POSITION + 2)
    append('[')
    for (fix in fixes) {
        if (fix > fixes.first) append(',')
        append('[').appendDegrees(lonLat[2 * fix]).append(',').appendDegrees(lonLat[2 * fix + 1]).append(']')
    }
    return append(']')
}

/** Each of [positions]' longitude and latitude, interleaved: GeoJSON's order. */
internal fun lonLatOf(positions: List<Coordinate>): DoubleArray =
    DoubleArray(positions.size * 2) { if (it % 2 == 0) positions[it / 2].lon else positions[it / 2].lat }

/** The fixes of [lonLat] ([lonLatOf]), each made as it is read. */
internal fun coordinatesOf(lonLat: DoubleArray): List<Coordinate> = object : AbstractList<Coordinate>() {
    override val size: Int get() = lonLat.size / 2

    override fun get(index: Int) = Coordinate(lonLat[2 * index + 1], lonLat[2 * index])
}

/** [units] of 1/[perWhole], a power of ten, as a decimal with no trailing zeros. */
internal fun StringBuilder.appendDecimal(units: Long, perWhole: Long): StringBuilder {
    if (units < 0) append('-')
    append(abs(units) / perWhole)
    var rest = abs(units) % perWhole
    if (rest == 0L) return this
    append('.')
    var place = perWhole / 10
    while (rest != 0L) {
        append('0' + (rest / place).toInt())
        rest %= place
        place /= 10
    }
    return this
}

/** [degrees] to seven decimals, about a centimeter: as fine as the tiles of the closest band in [CASED_ZOOMS] hold. */
internal fun StringBuilder.appendDegrees(degrees: Double): StringBuilder = appendDecimal(Math.round(degrees * DEGREE_SCALE), DEGREE_SCALE)

private const val DEGREE_SCALE = 10_000_000L

/** The most a position takes as written, `[-123.4567891,-12.3456789],`. */
private const val CHARS_PER_POSITION = 27
