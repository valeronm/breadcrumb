package io.github.valeronm.breadcrumb.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.utils.ColorUtils
import kotlin.coroutines.coroutineContext
import kotlin.math.floor

/**
 * Builds the cased [lines] off the main thread a zoom band at a time, each once the camera's [zoom] brings it into
 * range ([bandsInRange]), and hands it to the sources [addCasedLines] made on [style] for [idPrefix]. A band stays
 * once built. A change of [keys] rebuilds the band on screen and the bands in range, and empties the rest, each rebuilt
 * when the camera next reaches it. Nothing keeps a band once MapLibre holds it, so a new [style] builds them again.
 * [lines] runs on the build's thread.
 *
 * A band shows once [view] has drawn it ([BandHandover]), and until then the bands already shown draw its zooms
 * ([bandSpans]).
 *
 * Composed in [MapLibreStyledMap]'s `styled` slot, which ends the build with the map: a style is cleared when its map
 * is destroyed, and a cleared style throws on any source read.
 */
@Composable
internal fun CasedLineBuild(
    view: MapView,
    style: Style,
    zoom: FloatState,
    idPrefix: String,
    vararg keys: Any?,
    lines: () -> List<CasedLine>,
) {
    val bands = remember(style) { ShownBands(style, idPrefix) }
    DisposableEffect(view, bands) {
        val loaded = MapView.OnSourceChangedListener(bands::sourceChanged)
        val drawn = MapView.OnDidFinishRenderingFrameListener { whole, _, _ -> bands.frameDrawn(whole, zoom.floatValue) }
        view.addOnSourceChangedListener(loaded)
        view.addOnDidFinishRenderingFrameListener(drawn)
        onDispose {
            view.removeOnSourceChangedListener(loaded)
            view.removeOnDidFinishRenderingFrameListener(drawn)
        }
    }
    LaunchedEffect(bands, *keys) {
        val onScreen = bands.at(zoom.floatValue)
        val kept = bandsInRange(zoom.floatValue) + listOfNotNull(onScreen)
        for (band in CASED_ZOOMS.indices) {
            if (band !in kept) bands.empty(band)
        }
        val drawn = withContext(bandBuilder) { lines() }
        val built = BooleanArray(CASED_ZOOMS.size)
        val build: suspend (Int) -> Unit = { band ->
            bands.hand(band, withContext(bandBuilder) { casedLineJson(drawn, CASED_ZOOMS[band]) })
            built[band] = true
        }
        onScreen?.let { build(it) }
        snapshotFlow { bandsInRange(zoom.floatValue) }.collect { wanted ->
            for (band in wanted.filterNot { built[it] }) build(band)
        }
    }
}

/** A band's text runs to tens of megabytes, so builds take turns rather than holding two at once. */
private val bandBuilder = Dispatchers.Default.limitedParallelism(1)

/** The cased line's bands on [style], each drawn at the zooms [bandSpans] gives it once [BandHandover] has it ready. */
private class ShownBands(private val style: Style, private val idPrefix: String) {
    private val handover = BandHandover()
    private val sources = CASED_ZOOMS.indices.map { casedSource(idPrefix, it) }
    private var shown: List<BandSpan?> = List(CASED_ZOOMS.size) { null }

    fun at(zoom: Float): Int? = shown.indexOfFirst { it?.covers(zoom) == true }.takeIf { it >= 0 }

    fun hand(band: Int, json: String) = update(band, json, handover.handing(band))

    fun empty(band: Int) {
        if (!handover.isEmpty(band)) update(band, NO_FEATURES, handover.emptied(band))
    }

    fun sourceChanged(id: String) {
        sources.indexOf(id).takeIf { it >= 0 }?.let(handover::sourceChanged)
    }

    fun frameDrawn(whole: Boolean, zoom: Float) {
        if (handover.frameDrawn(whole, zoom)) show()
    }

    private fun update(band: Int, json: String, readyChanged: Boolean) {
        style.getSourceAs<GeoJsonSource>(sources[band])?.setGeoJson(json)
        if (readyChanged) show()
    }

    private fun show() {
        val spans = bandSpans(handover.ready)
        spans.forEachIndexed { band, span ->
            if (span != shown[band]) spanLayers(style, idPrefix, band, span)
        }
        shown = spans
    }
}

/**
 * Draws a source per zoom band, `<idPrefix>-src-<band>`, empty until [CasedLineBuild] fills it, each under the solid
 * and dashed layers [addLineLayersByMeasure] makes for `<idPrefix>-layer-<band>`.
 */
internal fun addCasedLines(style: Style, idPrefix: String) {
    for (band in CASED_ZOOMS.indices) {
        // A GeoJSON source simplifies the tiles it cuts below its max zoom, which drops a run as short as a stop's
        // outright, and cuts the tiles at its max zoom whole, scaling them up for any closer view. From where the
        // band's own zooms come into range, every tile it draws is whole, and the tiles cut on the way down stay small.
        val maxZoom = floor(ownSpan(band).headStarted().from).toInt()
        style.addSource(GeoJsonSource(casedSource(idPrefix, band), NO_FEATURES, GeoJsonOptions().withMaxZoom(maxZoom)))
        // The solid layer's round cap joins stretches drawn as separate features, and gives a near-zero-length
        // run the width of the line to be seen in.
        addLineLayersByMeasure(
            style,
            casedLayer(idPrefix, band),
            casedSource(idPrefix, band),
            PropertyFactory.lineWidth(Expression.get(WIDTH_KEY)),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineColor(Expression.get(COLOR_KEY)),
        )
        spanLayers(style, idPrefix, band, span = null)
    }
}

/**
 * Draws [band]'s layers at [span], and at no zoom when that is null.
 *
 * MapLibre lays out a source's tiles only while one of its layers is in range and visible, so a band whose layer came
 * into range where it shows would show nothing until its tiles were built. A layer is in range [HEAD_START] beyond its
 * span on either side, drawn there at [HEAD_START_OPACITY], which is not seen but counts as drawn; a band with no span
 * is in range where [bandsInRange] builds it, at that opacity throughout.
 */
private fun spanLayers(style: Style, idPrefix: String, band: Int, span: BandSpan?) {
    val range = (span ?: ownSpan(band)).headStarted()
    zoomLineLayersByMeasure(style, casedLayer(idPrefix, band), range.from, range.until)
    val opacity = span?.let(::spanOpacity) ?: Expression.literal(HEAD_START_OPACITY)
    setLineLayersByMeasure(style, casedLayer(idPrefix, band), PropertyFactory.lineOpacity(opacity))
}

private fun spanOpacity(span: BandSpan): Expression = Expression.step(
    Expression.zoom(),
    Expression.literal(HEAD_START_OPACITY),
    // MapLibre parses no step without a stop, so a span from zoom 0 keeps this one too.
    Expression.stop(span.from, 1f),
    *listOfNotNull(span.until?.let { Expression.stop(it, HEAD_START_OPACITY) }).toTypedArray(),
)

/** The bands whose layers are in range at [zoom] once every band is ready ([bandSpans]), the one drawn there first. */
internal fun bandsInRange(zoom: Float): List<Int> {
    val drawn = CASED_ZOOMS.indices.first { ownSpan(it).covers(zoom) }
    return listOf(drawn) + CASED_ZOOMS.indices.filter { band -> band != drawn && reaches(band, zoom) }
}

/** The zooms a ready band is drawn at: from [from] up to [until], or every zoom past [from] when that is null. */
internal data class BandSpan(val from: Float, val until: Float?) {
    fun covers(zoom: Float): Boolean = zoom >= from && (until == null || zoom < until)

    /** This span and [HEAD_START] beyond it on either side. */
    fun headStarted(): BandSpan = BandSpan((from - HEAD_START).coerceAtLeast(0f), until?.plus(HEAD_START))
}

/** [band]'s own zooms, the span [bandSpans] gives it once every band is ready. */
private fun ownSpan(band: Int) = BandSpan(CASED_ZOOMS[band].toFloat(), CASED_ZOOMS.getOrNull(band + 1)?.toFloat())

private fun reaches(band: Int, zoom: Float): Boolean = ownSpan(band).headStarted().covers(zoom)

/**
 * The zooms each band in [ready] is drawn at, null for a band not ready: from its own zoom up to the next ready band's,
 * and from zoom 0 for the farthest. A farther band drawn closer than its own zooms draws a straighter line, where a
 * closer one drawn farther draws its small loops as rings ([CASED_ZOOMS]).
 */
internal fun bandSpans(ready: BooleanArray): List<BandSpan?> {
    val spans = arrayOfNulls<BandSpan>(ready.size)
    val drawn = ready.indices.filter { ready[it] }
    drawn.forEachIndexed { k, band ->
        spans[band] = BandSpan(if (k == 0) 0f else ownSpan(band).from, drawn.getOrNull(k + 1)?.let { ownSpan(it).from })
    }
    return spans.asList()
}

/**
 * Which bands MapLibre draws a line for ([ready]), as their data is handed to it. With no band ready, a band handed
 * over is ready at once. Otherwise it is ready once MapLibre has drawn it: its source has reported the data, then a
 * frame drawn with tiles missing is followed by a whole one, each with the camera within the band's zooms.
 * MapLibre parses a source's data off the main thread and lays out its tiles after, so a whole frame drawn sooner can
 * leave the band empty.
 */
internal class BandHandover {
    private val stage = Array(CASED_ZOOMS.size) { Stage.EMPTY }

    val ready: BooleanArray get() = BooleanArray(stage.size) { stage[it] == Stage.READY }

    fun isEmpty(band: Int): Boolean = stage[band] == Stage.EMPTY

    /** Called before [band]'s data is handed to MapLibre; true if that made it ready. */
    fun handing(band: Int): Boolean {
        if (stage[band] == Stage.READY) return false
        stage[band] = if (Stage.READY in stage) Stage.HANDED else Stage.READY
        return stage[band] == Stage.READY
    }

    /** True if [band] was ready. */
    fun emptied(band: Int): Boolean = (stage[band] == Stage.READY).also { stage[band] = Stage.EMPTY }

    fun sourceChanged(band: Int) {
        if (stage[band] == Stage.HANDED) stage[band] = Stage.LOADED
    }

    /** True if that made a band ready. */
    fun frameDrawn(whole: Boolean, zoom: Float): Boolean {
        var readied = false
        for (band in stage.indices) {
            val next = when (stage[band]) {
                Stage.LOADED -> Stage.PARTLY_DRAWN.takeIf { !whole }
                Stage.PARTLY_DRAWN -> Stage.READY.takeIf { whole }
                else -> null
            }
            if (next == null || !reaches(band, zoom)) continue
            stage[band] = next
            readied = readied || next == Stage.READY
        }
        return readied
    }

    private enum class Stage { EMPTY, HANDED, LOADED, PARTLY_DRAWN, READY }
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
    // A stretch's pens share its coordinates, and a pen in one color its properties.
    val coordinates = arrayOfNulls<String>((strokes.maxOfOrNull { it.stretch } ?: -1) + 1)
    val properties = HashMap<Long, String>()
    val strokeProperties = arrayOfNulls<String>(strokes.size)
    var length = COLLECTION_HEAD.length + COLLECTION_TAIL.length
    strokes.forEachIndexed { k, stroke ->
        coroutineContext.ensureActive()
        val geometry = coordinates[stroke.stretch] ?: coordinatesJson(lines[stroke.line], stroke.fixes).also { coordinates[stroke.stretch] = it }
        val written = properties.getOrPut(propertiesKey(stroke)) { propertiesJson(stroke) }
        strokeProperties[k] = written
        length += LINE_FEATURE_HEAD.length + geometry.length + LINE_FEATURE_MID.length + written.length + 2
    }
    // A builder grows by doubling, holding its old and its doubled array at once.
    val json = StringBuilder(length).append(COLLECTION_HEAD)
    strokes.forEachIndexed { k, stroke ->
        if (k > 0) json.append(',')
        json.append(LINE_FEATURE_HEAD).append(coordinates[stroke.stretch]).append(LINE_FEATURE_MID).append(strokeProperties[k]).append('}')
    }
    // Released before toString copies the builder, so they aren't held beside both copies of the text.
    coordinates.fill(null)
    return json.append(COLLECTION_TAIL).toString()
}

/** The pen, color and dash [propertiesJson] writes for [stroke], as one number. */
private fun propertiesKey(stroke: Stroke): Long =
    (stroke.argb.toLong() and 0xFFFFFFFFL) or (stroke.pen.ordinal.toLong() shl Int.SIZE_BITS) or (if (stroke.dashed) 1L shl 40 else 0L)

private fun coordinatesJson(line: CasedLine, fixes: IntRange): String =
    StringBuilder().appendPositions(line.lonLat, fixes, line.greatCircleLegs).toString()

/** What the line layers read off [stroke]. */
private fun propertiesJson(stroke: Stroke): String = buildString {
    append("""{"$COLOR_KEY":"${ColorUtils.colorToRgbaString(stroke.argb)}","$WIDTH_KEY":${stroke.pen.widthDp}""")
    if (stroke.dashed) append(""","$UNMEASURED_KEY":true""")
    append('}')
}

private fun casedSource(idPrefix: String, band: Int) = "$idPrefix-src-$band"

private fun casedLayer(idPrefix: String, band: Int) = "$idPrefix-layer-$band"

/**
 * The lowest zoom of each band a cased line is built for, each drawn up to the next once both are ready ([bandSpans]):
 * a line cut for a closer view draws its small folds and loops as rings at a farther one ([casedStrokes], [cutLoops]). A band's
 * positions are thinned for its lowest zoom ([bandLine]), so the wider a band, the straighter the line at its top.
 */
internal val CASED_ZOOMS = intArrayOf(0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20)

/** Feature properties of a stroke, read by the line layers. */
private const val COLOR_KEY = "color"
private const val WIDTH_KEY = "width"

/** How far before a band shows, zooming in or out, its layers come into range, so its tiles are laid out by then. */
private const val HEAD_START = 0.5f

/** Above zero, so MapLibre lays the band out, and below what a pixel can show. */
private const val HEAD_START_OPACITY = 0.002f
