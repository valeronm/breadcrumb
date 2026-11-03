package io.github.valeronm.breadcrumb.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.utils.ColorUtils
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.floor

/**
 * Builds the cased [lines] a zoom band at a time off the main thread, and hands each band to the sources
 * [addCasedLines] made on [style] for [idPrefix] as soon as it is built. Nothing keeps a band once MapLibre holds it, so
 * a new [style] builds them all again. A change of [keys] cancels the build, and [lines] runs on the build's thread.
 *
 * Composed in [MapLibreStyledMap]'s `styled` slot, which ends the build with the map: a style is cleared when its map
 * is destroyed, and a cleared style throws on any source read.
 */
@Composable
internal fun CasedLineBuild(style: Style, idPrefix: String, vararg keys: Any?, lines: () -> List<CasedLine>) {
    LaunchedEffect(style, *keys) {
        val drawn = withContext(bandBuilder) { lines() }
        CASED_ZOOMS.forEachIndexed { band, zoom ->
            val json = withContext(bandBuilder) { casedLineJson(drawn, zoom) }
            style.getSourceAs<GeoJsonSource>(casedSource(idPrefix, band))?.setGeoJson(json)
        }
    }
}

/** A band's text runs to tens of megabytes, so builds take turns rather than holding two at once. */
private val bandBuilder = Dispatchers.Default.limitedParallelism(1)

/**
 * Draws a source per zoom band, `<idPrefix>-src-<band>`, empty until [CasedLineBuild] fills it, each under the solid
 * and dashed layers [addLineLayersByMeasure] makes for `<idPrefix>-layer-<band>`.
 *
 * MapLibre lays out a source's tiles only while one of its layers is in range and visible, so a band
 * whose layers came into range at its own zooms would show nothing until its tiles were built. Each
 * band's layers are in range [HEAD_START] beyond its zooms on either side, drawn there at
 * [HEAD_START_OPACITY], which is not seen but counts as drawn.
 */
internal fun addCasedLines(style: Style, idPrefix: String) {
    CASED_ZOOMS.forEachIndexed { band, zoom ->
        val next = CASED_ZOOMS.getOrNull(band + 1)
        val drawnFrom = (zoom - HEAD_START).coerceAtLeast(0f)
        // A GeoJSON source simplifies the tiles it cuts below its max zoom, which drops a run as short as a stop's
        // outright, and cuts the tiles at its max zoom whole, scaling them up for any closer view. At the band's
        // lowest drawn zoom, every tile the band draws is whole, and the tiles cut on the way down to it stay small.
        style.addSource(GeoJsonSource(casedSource(idPrefix, band), NO_FEATURES, GeoJsonOptions().withMaxZoom(floor(drawnFrom).toInt())))
        // The solid layer's round cap joins stretches drawn as separate features, and gives a near-zero-length
        // run the width of the line to be seen in.
        addLineLayersByMeasure(
            style,
            casedLayer(idPrefix, band),
            casedSource(idPrefix, band),
            PropertyFactory.lineWidth(Expression.get(WIDTH_KEY)),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineColor(Expression.get(COLOR_KEY)),
            PropertyFactory.lineOpacity(
                Expression.step(
                    Expression.zoom(),
                    Expression.literal(HEAD_START_OPACITY),
                    *listOfNotNull(Expression.stop(zoom, 1f), next?.let { Expression.stop(it, HEAD_START_OPACITY) }).toTypedArray(),
                ),
            ),
        )
        zoomLineLayersByMeasure(style, casedLayer(idPrefix, band), drawnFrom, next?.plus(HEAD_START))
    }
}

/**
 * [cased] as a zoom band draws them ([bandLine]), one feature per stroke ([casedStrokes]) in drawing order, which a line
 * layer with no sort key keeps; a track's stretches are cut where the color changes and where the recorder wasn't
 * watching ([lineRuns]).
 *
 * Features rather than one polyline under a `line-gradient`, because a gradient is positioned along
 * the line's *length* while every other reading of the metric — the graph beside the map most of
 * all — is positioned along its *time*. A slowdown is long in time and short on the ground, and a
 * stretch slow enough to advance no distance between fixes could hold no gradient stop at all,
 * stops having to strictly increase: the colors either side simply ran through it.
 *
 * What puts such a stretch back on the map is the round cap ([addCasedLines]): a run of near-zero
 * length still draws as a dot of the line's own width, so a stop has a floor of a few pixels rather
 * than the nothing its ground distance earns. That is also why a band draws only tiles its source cut
 * unsimplified ([addCasedLines]) — a zero-length feature is the first thing simplification drops.
 *
 * Runs, not a feature per fix: the ramp is banded (`RAMP_STEPS`), so a steady pace is one stretch
 * instead of hundreds of legs, and only a color boundary is spent twice — a break boundary is spent
 * once, which is what leaves the gap.
 */
private suspend fun casedLineJson(cased: List<CasedLine>, zoom: Int): String {
    val lines = cased.map { line ->
        coroutineContext.ensureActive()
        bandLine(line, zoom)
    }
    val strokes = casedStrokes(lines, zoom)
    // A stretch's pens share its coordinates, each dropped once its last pen is written, and a pen in one color its
    // properties.
    val stretches = (strokes.maxOfOrNull { it.stretch } ?: -1) + 1
    val coordinates = arrayOfNulls<String>(stretches)
    val unwritten = IntArray(stretches)
    val properties = HashMap<Long, String>()
    val strokeProperties = arrayOfNulls<String>(strokes.size)
    var length = COLLECTION_HEAD.length + COLLECTION_TAIL.length
    strokes.forEachIndexed { k, stroke ->
        coroutineContext.ensureActive()
        val geometry = coordinates[stroke.stretch] ?: coordinatesJson(lines[stroke.line], stroke.fixes).also { coordinates[stroke.stretch] = it }
        unwritten[stroke.stretch]++
        val written = properties.getOrPut(propertiesKey(stroke)) { propertiesJson(stroke) }
        strokeProperties[k] = written
        length += FEATURE_HEAD.length + geometry.length + FEATURE_MID.length + written.length + 2
    }
    // A builder grows by doubling, holding its old and its doubled array at once.
    val json = StringBuilder(length).append(COLLECTION_HEAD)
    strokes.forEachIndexed { k, stroke ->
        if (k > 0) json.append(',')
        json.append(FEATURE_HEAD).append(coordinates[stroke.stretch]).append(FEATURE_MID).append(strokeProperties[k]).append('}')
        if (--unwritten[stroke.stretch] == 0) coordinates[stroke.stretch] = null
    }
    return json.append(COLLECTION_TAIL).toString()
}

/** The pen, color and dash [propertiesJson] writes for [stroke], as one number. */
private fun propertiesKey(stroke: Stroke): Long =
    (stroke.argb.toLong() and 0xFFFFFFFFL) or (stroke.pen.ordinal.toLong() shl Int.SIZE_BITS) or (if (stroke.dashed) 1L shl 40 else 0L)

/** [fixes] of [line] as GeoJSON positions. */
private fun coordinatesJson(line: CasedLine, fixes: IntRange): String {
    val drawn = line.positions.subList(fixes.first, fixes.last + 1)
    val json = StringBuilder("[")
    drawnLine(drawn, line.greatCircleLegs).forEachIndexed { i, p ->
        if (i > 0) json.append(',')
        json.append('[').appendDegrees(p.lon).append(',').appendDegrees(p.lat).append(']')
    }
    return json.append(']').toString()
}

/** What the line layers read off [stroke]. */
private fun propertiesJson(stroke: Stroke): String = buildString {
    append("""{"$COLOR_KEY":"${ColorUtils.colorToRgbaString(stroke.argb)}","$WIDTH_KEY":${stroke.pen.widthDp}""")
    if (stroke.dashed) append(""","$UNMEASURED_KEY":true""")
    append('}')
}

/** [degrees] to seven decimals, about a centimeter: as fine as the closest band's tiles hold. */
internal fun StringBuilder.appendDegrees(degrees: Double): StringBuilder {
    val scaled = Math.round(degrees * DEGREE_SCALE)
    if (scaled < 0) append('-')
    val fraction = abs(scaled) % DEGREE_SCALE
    append(abs(scaled) / DEGREE_SCALE).append('.')
    var place = DEGREE_SCALE / 10
    while (place > fraction && place > 1) {
        append('0')
        place /= 10
    }
    return append(fraction)
}

private fun casedSource(idPrefix: String, band: Int) = "$idPrefix-src-$band"

private fun casedLayer(idPrefix: String, band: Int) = "$idPrefix-layer-$band"

/**
 * The lowest zoom of each band a cased line is built for, each drawn up to the next: a line cut for
 * a closer view draws its small folds and loops as rings at a farther one ([casedStrokes], [cutLoops]). A band's
 * positions are thinned for its lowest zoom ([bandLine]), so the wider a band, the straighter the line at its top.
 */
private val CASED_ZOOMS = intArrayOf(0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20)

/** Feature properties of a stroke, read by the line layers. */
private const val COLOR_KEY = "color"
private const val WIDTH_KEY = "width"

private const val COLLECTION_HEAD = """{"type":"FeatureCollection","features":["""
private const val COLLECTION_TAIL = "]}"
private const val FEATURE_HEAD = """{"type":"Feature","geometry":{"type":"LineString","coordinates":"""
private const val FEATURE_MID = """},"properties":"""
private const val NO_FEATURES = COLLECTION_HEAD + COLLECTION_TAIL
private const val DEGREE_SCALE = 10_000_000L

/** How far before a band shows, zooming in or out, its layers come into range, so its tiles are laid out by then. */
private const val HEAD_START = 0.5f

/** Above zero, so MapLibre lays the band out, and below what a pixel can show. */
private const val HEAD_START_OPACITY = 0.002f
