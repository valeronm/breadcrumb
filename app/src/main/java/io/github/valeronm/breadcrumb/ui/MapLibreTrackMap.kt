package io.github.valeronm.breadcrumb.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import com.google.gson.JsonObject
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.TrackQuality
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.DwellDetector
import io.github.valeronm.breadcrumb.domain.GreatCircle
import io.github.valeronm.breadcrumb.domain.IgnoreReason
import io.github.valeronm.breadcrumb.domain.PlaceClusterer
import io.github.valeronm.breadcrumb.domain.RoutePlaces
import io.github.valeronm.breadcrumb.domain.TrackSettle
import io.github.valeronm.breadcrumb.domain.pin
import io.github.valeronm.breadcrumb.domain.placeCategory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.utils.ColorUtils
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Renders a track on a Protomaps vector basemap (MapLibre GL Native), dark or light flavor per
 * [isMapDark]. The line is drawn as one feature per run of same-colored fixes ([TrackColoring], the metric
 * picked by [colorMode]); start/end and noisy-fix markers ride a symbol layer; the places at the
 * path's two ends are drawn as pins with their capture areas, labeled where named, and an end stated
 * to a place its circle does not reach is joined to the pin by a dashed connector; the camera fits
 * the track and any connector's pin once on open — the places never move it otherwise, a route being
 * the subject and them the annotation.
 * A color-mode switch rebuilds the line's source, camera untouched, and the
 * source refreshes as the point list grows
 * (the live "current track" preview), re-framing only when the position nears the viewport edge —
 * user pan/zoom survives otherwise.
 */
@Composable
internal fun MapLibreTrackMap(
    points: List<TrackPoint>,
    modifier: Modifier = Modifier,
    noisyPoints: List<TrackPoint> = emptyList(),
    activity: ActivityType? = null,
    colorMode: ColorMode = ColorMode.SPEED,
    showLegend: Boolean = false,
    /** A key to [noisyPoints]' marks, stacked over the color legend. */
    noisyLegend: @Composable () -> Unit = {},
    // Read only while drawing, so a new selection redraws the marker without recomposing the map.
    selectedPoint: (() -> TrackPoint?)? = null,
    // Detected in-track stops, highlighted as place-style capture circles under the line.
    dwells: List<DwellDetector.Dwell> = emptyList(),
    // Stops the recording ran on through at either edge, as stored: one point run per edge,
    // drawn grayed off the end of the track line.
    overruns: List<TrackSettle.Overrun> = emptyList(),
    // The places at the path's two ends (see RoutePlaces) — a pin over the capture area that claimed
    // that end, labeled where the place is named. One entry where a round trip began and ended in the
    // same place.
    endPlaces: List<Place> = emptyList(),
    // A dashed connector from an end to the pin of a place it is stated to but lies outside of.
    endLinks: List<RoutePlaces.Link> = emptyList(),
    // Read once, at style load.
    dashed: Boolean = false,
    // Live preview: the last point is the current position — a droplet rotated to the movement
    // bearing instead of the finished-track end dot.
    directionalEnd: Boolean = false,
    // A coloring the caller already computed for the same inputs (the track-detail screen builds
    // one for its metric graph) — the O(points) pass then runs once, not twice.
    precomputedColoring: TrackColoring? = null,
    // …and the seam walk that coloring was built from ([TrackQuality.Seams]). Null = walk it here
    // (the live preview, which has no graph).
    precomputedSeams: TrackQuality.Seams? = null,
) {
    // Display geometry only: the coloring, seams and markers stay on the stored fixes.
    val greatCircleLegs = GreatCircle.drawsPath(points.size, activity)
    val mapDark = isMapDark()
    // For the pin images an update may have to register — the style-loaded callback is handed a
    // context, an update is not.
    val context = LocalContext.current
    // Keyed on the points alone, unlike the coloring: seam distances don't depend on the metric,
    // so switching it must not re-walk them.
    val seams = remember(precomputedSeams, points) {
        precomputedSeams ?: TrackQuality.seams(points)
    }
    val measures = LocalMeasures.current
    val coloring = remember(precomputedColoring, seams, colorMode, activity, mapDark, measures) {
        precomputedColoring?.inShade(mapDark) ?: trackColoring(seams, colorMode, activity, mapDark, measures)
    }
    val colors = coloring.colors
    val applied = remember { AppliedTrackInputs() }
    val frameWhole = { map: MapLibreMap ->
        frameTo(map, framePositions(points, noisyPoints, greatCircleLegs, endLinks), singlePointZoom = 15.0)
    }

    MapLibreStyledMap(
        modifier = modifier,
        legend = if (showLegend) {
            {
                noisyLegend()
                TrackLegend(coloring.legend)
            }
        } else {
            null
        },
        // The live preview frames itself as the recording grows, and has no frame to return to.
        frame = frameWhole.takeUnless { directionalEnd },
        // A track still loading fits once its points arrive.
        frameKey = points.isEmpty(),
        frameCovers = remember(points, noisyPoints, endLinks) { listOf(points, noisyPoints, endLinks) },
        overMap = { map -> if (selectedPoint != null) SelectionMarker(map, selectedPoint) },
        onStyleLoaded = { ctx, map, style, dark ->
            // The end places are one input drawn at two depths — their areas under the line, their
            // pins over it so it can't cover them.
            addEndPlaceAreas(style, endPlaces, dark)
            style.addSource(GeoJsonSource(END_LINK_SOURCE, endLinkCollection(endLinks)))
            addConnectorLayer(style, dark, END_LINK_LAYER, END_LINK_SOURCE)
            addDwellLayers(style, dwells)
            addTrackLine(style, points, colors, greatCircleLegs, dashed)
            addEdgeStayLayer(style, overruns, dark) // over the line, off its ends
            addMarkers(ctx, style, points, noisyPoints, directionalEnd)
            // Over the recorder's own markers: at an end the two land within a capture radius of
            // each other, and the place states where the journey went.
            addEndPlacePins(ctx, style, endPlaces, dark)
            if (directionalEnd) frameWhole(map)
            // Stamped here as well as drawn: otherwise the first update sees no applied input
            // and rebuilds every source the load just built.
            applied.points = points
            applied.noisy = noisyPoints
            applied.colors = colors
            applied.endPlaces = endPlaces
            applied.endLinks = endLinks
        },
        onUpdate = { map, style ->
            // The line's features carry their colors, so a color switch rebuilds that source —
            // but nothing else: not the markers, which no metric touches, and not the camera,
            // so switching metric keeps the user's pan/zoom. Growing points reach it too, the
            // coloring being remembered off them; showing the noisy fixes doesn't, the line
            // having none of them.
            val moved = applied.points !== points || applied.noisy !== noisyPoints
            if (applied.colors !== colors) {
                applied.colors = colors
                style.getSourceAs<GeoJsonSource>(TRACK_SOURCE)
                    ?.setGeoJson(trackLineFeature(points, colors, greatCircleLegs))
            }
            // Refresh geometry when the track grows (the live "current track" preview).
            if (moved) {
                applied.points = points
                applied.noisy = noisyPoints
                style.getSourceAs<GeoJsonSource>(MARKER_SOURCE)?.setGeoJson(markerCollection(points, noisyPoints, directionalEnd))
                // Live preview: hold the camera (so a pan/zoom survives and the map
                // isn't re-rendered every fix); re-fit only when the current position
                // stops showing well.
                if (directionalEnd) {
                    points.lastOrNull()?.let { last ->
                        if (!showsWell(map, listOf(LatLng(last.latitude, last.longitude)))) {
                            // Headroom so the position lands inside showsWell's margin, not
                            // straight back on its edge (which would re-frame again on
                            // the next fix while moving outward). 1.2 keeps each zoom
                            // step small; the fit padding provides the rest of the slack.
                            frameTo(map, points.map { LatLng(it.latitude, it.longitude) }, singlePointZoom = 15.0, headroom = 1.2)
                        }
                    }
                }
            }
            if (applied.dwells !== dwells) {
                applied.dwells = dwells
                style.getSourceAs<GeoJsonSource>(DWELL_SOURCE)?.setGeoJson(dwellCollection(dwells))
            }
            // Keyed on the overruns alone: they are loaded with the track, so a growing live
            // track that has none (the record screen) never rebuilds this source.
            if (applied.overruns !== overruns) {
                applied.overruns = overruns
                style.getSourceAs<GeoJsonSource>(EDGE_STAY_SOURCE)
                    ?.setGeoJson(edgeStayFeature(overruns))
            }
            updateEndPlaces(context, style, applied, endPlaces, endLinks)
        },
    )
}

/** A part's good fixes, and whether its writer implied their motion. */
internal class TripPart(val points: List<TrackPoint>, val unmeasured: Boolean)

/**
 * Every part of a trip as a line in the [activity]'s color, with the trip's end places drawn as
 * [MapLibreTrackMap] draws a track's. No metric: each part's own page carries it.
 */
@Composable
internal fun MapLibreTripMapOfParts(
    parts: List<TripPart>,
    activity: ActivityType?,
    endPlaces: List<Place>,
    endLinks: List<RoutePlaces.Link>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val applied = remember { AppliedTrackInputs() }
    val ends = remember(parts) {
        listOfNotNull(parts.firstOrNull()?.points?.firstOrNull(), parts.lastOrNull()?.points?.lastOrNull())
    }
    val frameWhole = { map: MapLibreMap ->
        frameTo(
            map,
            parts.flatMap { framePositions(it.points, emptyList(), GreatCircle.drawsPath(it.points.size, activity), emptyList()) } +
                endLinks.map { LatLng(it.to.lat, it.to.lon) },
            singlePointZoom = 15.0,
        )
    }
    MapLibreStyledMap(
        modifier = modifier,
        frame = frameWhole,
        onStyleLoaded = { ctx, _, style, dark ->
            addEndPlaceAreas(style, endPlaces, dark)
            style.addSource(GeoJsonSource(END_LINK_SOURCE, endLinkCollection(endLinks)))
            addConnectorLayer(style, dark, END_LINK_LAYER, END_LINK_SOURCE)
            style.addSource(GeoJsonSource(PART_SOURCE, partCollection(parts, activity)))
            addLineLayersByMeasure(
                style,
                PART_LAYER,
                PART_SOURCE,
                PropertyFactory.lineColor(mapActivityArgb(activity, dark)),
                PropertyFactory.lineWidth(TRACK_LINE_WIDTH),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            )
            addMarkers(ctx, style, ends, emptyList(), directionalEnd = false)
            addEndPlacePins(ctx, style, endPlaces, dark)
            applied.endPlaces = endPlaces
            applied.endLinks = endLinks
        },
        onUpdate = { _, style -> updateEndPlaces(context, style, applied, endPlaces, endLinks) },
    )
}

private fun partCollection(parts: List<TripPart>, activity: ActivityType?): FeatureCollection {
    val unmeasured = JsonObject().apply { addProperty(UNMEASURED_KEY, true) }
    return FeatureCollection.fromFeatures(
        parts.mapNotNull { part ->
            lineFeature(part.points, unmeasured.takeIf { part.unmeasured }, GreatCircle.drawsPath(part.points.size, activity))
        },
    )
}

/** One check for both place sources: a pin and the area it sits in are one place, and a set that had them disagree would be showing a reach nothing claims. */
private fun updateEndPlaces(
    context: Context,
    style: Style,
    applied: AppliedTrackInputs,
    endPlaces: List<Place>,
    endLinks: List<RoutePlaces.Link>,
) {
    if (applied.endPlaces !== endPlaces) {
        applied.endPlaces = endPlaces
        addEndPlacePinImages(context, style, endPlaces)
        style.getSourceAs<GeoJsonSource>(END_PLACE_AREA_SOURCE)
            ?.setGeoJson(captureAreaCollection(PlaceClusterer.seedsOf(endPlaces)))
        style.getSourceAs<GeoJsonSource>(END_PLACE_SOURCE)?.setGeoJson(endPlaceCollection(endPlaces))
    }
    if (applied.endLinks !== endLinks) {
        applied.endLinks = endLinks
        style.getSourceAs<GeoJsonSource>(END_LINK_SOURCE)?.setGeoJson(endLinkCollection(endLinks))
    }
}

/**
 * What each source/layer of the track map was last fed, so unrelated recompositions don't
 * re-serialize the full track geometry into the native map. Identity comparisons, matching the
 * stability of the remembered inputs.
 */
private class AppliedTrackInputs {
    var points: List<TrackPoint>? = null
    var noisy: List<TrackPoint>? = null

    /** The line's colours: a metric switch rebuilds the source, the legs carrying their own. */
    var colors: IntArray? = null
    var dwells: List<DwellDetector.Dwell>? = null
    var overruns: List<TrackSettle.Overrun>? = null

    /** The places at the path's ends — identity, the resolution handing back a fresh list only
     *  when the path or the places table changed. */
    var endPlaces: List<Place>? = null
    var endLinks: List<RoutePlaces.Link>? = null
}

private const val TRACK_SOURCE = "track-src"
private const val TRACK_LAYER = "track-layer"
private const val MARKER_SOURCE = "marker-src"
private const val MARKER_LAYER = "marker-layer"
private const val IMG_START = "marker-start"
private const val IMG_END = "marker-end"
private const val IMG_POINTER = "marker-pointer"
private const val IMG_NOISY = "marker-noisy"
private const val IMG_NOISY_JUMP = "marker-noisy-jump"
private const val IMG_NOISY_GNSS = "marker-noisy-gnss"

/** Feature property naming a leg's own color, read by the line layer. */
private const val COLOR_KEY = "color"

private const val DWELL_SOURCE = "dwell-src"
private const val DWELL_FILL = "dwell-fill"
private const val DWELL_LINE = "dwell-line"

private const val EDGE_STAY_SOURCE = "edge-stay-src"
private const val EDGE_STAY_LAYER = "edge-stay-layer"

private const val END_PLACE_AREA_SOURCE = "end-place-area-src"
private const val END_PLACE_AREA_FILL = "end-place-area-fill"
private const val END_PLACE_AREA_LINE = "end-place-area-line"
private const val END_PLACE_SOURCE = "end-place-src"
private const val END_PLACE_LAYER = "end-place-layer"
private const val END_LINK_SOURCE = "end-link-src"
private const val END_LINK_LAYER = "end-link-layer"
private const val PART_SOURCE = "part-src"
private const val PART_LAYER = "part-layer"

/**
 * The stretch at each track edge the recorder ran on through — fixes already off the path, each run
 * drawn from the good fix it hangs off so the gray meets the colored line. Its own dim color rather
 * than dimming the track underneath: the fixes left the track line, so there is nothing to recolor.
 */
private fun edgeStayFeature(overruns: List<TrackSettle.Overrun>): FeatureCollection =
    FeatureCollection.fromFeatures(overruns.mapNotNull { lineFeature(it.points) })

private fun addEdgeStayLayer(
    style: Style,
    overruns: List<TrackSettle.Overrun>,
    dark: Boolean,
) {
    style.addSource(GeoJsonSource(EDGE_STAY_SOURCE, edgeStayFeature(overruns)))
    style.addLayer(
        LineLayer(EDGE_STAY_LAYER, EDGE_STAY_SOURCE).withProperties(
            // Wider than the 3f track line so no colored fringe survives along the edges.
            PropertyFactory.lineWidth(4f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineColor(offPathArgb(dark)),
        ),
    )
}

/** One place-style capture circle per detected stop, each sized at the corral it was found with. */
private fun dwellCollection(dwells: List<DwellDetector.Dwell>): FeatureCollection =
    FeatureCollection.fromFeatures(dwells.map { circleFeature(it.centroid, it.corralRadiusM) })

private fun addDwellLayers(style: Style, dwells: List<DwellDetector.Dwell>) {
    style.addSource(GeoJsonSource(DWELL_SOURCE, dwellCollection(dwells)))
    addCaptureCircleLayers(style, DWELL_SOURCE, DWELL_FILL, DWELL_LINE)
}

private fun addEndPlaceAreas(style: Style, places: List<Place>, dark: Boolean) {
    style.addSource(
        GeoJsonSource(END_PLACE_AREA_SOURCE, captureAreaCollection(PlaceClusterer.seedsOf(places))),
    )
    addContextCircleLayers(style, dark, END_PLACE_AREA_SOURCE, END_PLACE_AREA_FILL, END_PLACE_AREA_LINE)
}

/**
 * A pin per end place, at full size with its glyph and its name — no zoom gating and no receding.
 * Where a journey began and ended is what a reader wants first from a track, so these are read at
 * every zoom the map can open at; the two markers that *are* the recorder's own give way both ways,
 * drawn as dots and drawn underneath (see [addMarkers]).
 */
private fun endPlaceCollection(places: List<Place>): FeatureCollection =
    FeatureCollection.fromFeatures(
        places.map { place ->
            endpointFeature(
                place.pin,
                placePinImage(place.placeCategory, withGlyph = true),
                place.label,
            )
        },
    )

private fun endLinkCollection(links: List<RoutePlaces.Link>): FeatureCollection =
    FeatureCollection.fromFeatures(
        links.map { Feature.fromGeometry(LineString.fromLngLats(listOf(it.from.toPoint(), it.to.toPoint()))) },
    )

private fun addEndPlacePins(ctx: Context, style: Style, places: List<Place>, dark: Boolean) {
    addEndPlacePinImages(ctx, style, places)
    style.addSource(GeoJsonSource(END_PLACE_SOURCE, endPlaceCollection(places)))
    style.addLayer(labeledSymbolLayer(dark, END_PLACE_LAYER, END_PLACE_SOURCE))
}

/**
 * Just the pins these places name, rather than the catalogue: a track has two ends, so a map that
 * registered every category would pack sixteen bitmaps into its sprite atlas — a megabyte of JNI copy
 * per track opened — to draw at most two of them. Cheap enough to redo whenever the ends change,
 * `addImage` overwriting by id, so nothing has to remember whether it ran.
 */
private fun addEndPlacePinImages(ctx: Context, style: Style, places: List<Place>) {
    for (place in places) {
        val category = place.placeCategory
        style.addImage(placePinImage(category, withGlyph = true), glyphedPinBitmap(ctx, category))
    }
}

/**
 * What the once-per-map fit frames: the track line's positions — the drawn ones, so a great-circle
 * leg's poleward bow stays in frame — or, when there's no drawable line, whatever markers there
 * are (noisy fixes included), so a bad-points-only track doesn't open on a world view. [links]' pins
 * are framed too: one can lie far off the path.
 */
private fun framePositions(
    points: List<TrackPoint>,
    noisyPoints: List<TrackPoint>,
    greatCircleLegs: Boolean,
    links: List<RoutePlaces.Link>,
): List<LatLng> {
    val path = if (points.size >= 2) {
        linePositions(points, greatCircleLegs).map { LatLng(it.latitude(), it.longitude()) }
    } else {
        (points + noisyPoints).map { LatLng(it.latitude, it.longitude) }
    }
    return path + links.map { LatLng(it.to.lat, it.to.lon) }
}

/**
 * The positions a run of fixes is drawn through: the fixes themselves, or with [greatCircleLegs]
 * each leg densified along its great circle ([greatCirclePositions]).
 */
private fun linePositions(points: List<TrackPoint>, greatCircleLegs: Boolean): List<Point> {
    if (!greatCircleLegs || points.size < 2) {
        return points.map { Point.fromLngLat(it.longitude, it.latitude) }
    }
    return greatCirclePositions(points.map { Coordinate(it.latitude, it.longitude) }).map { it.toPoint() }
}

/** The points as one polyline, or null below the two positions a GeoJSON LineString needs. */
private fun lineFeature(
    points: List<TrackPoint>,
    properties: JsonObject? = null,
    greatCircleLegs: Boolean = false,
): Feature? =
    if (points.size < 2) {
        null
    } else {
        Feature.fromGeometry(
            LineString.fromLngLats(linePositions(points, greatCircleLegs)),
            properties,
        )
    }

/**
 * Where the drawn line starts and stops, as inclusive index ranges into [points]. Two things end a
 * run, and they end it differently. A **color change** shares its boundary fix between the run that
 * ends on it and the one that starts from it, so the two meet there and the line stays unbroken. A
 * **segment break** does not: [TrackPoint.segmentStart] says nobody watched the ground between the
 * previous fix and this one, so the run ends before it and the next starts on it, and the leg across
 * is never drawn — the same fact that opens a fresh `<trkseg>` on export and lifts the pen on the
 * metric graph. Distance still counts that leg; being unobserved is not being untravelled.
 *
 * A run needs two fixes to be a line, so nothing comes out where two cuts land together — a lone
 * fix has no leg to draw.
 */
internal fun lineRuns(points: List<TrackPoint>, colors: IntArray): List<IntRange> {
    val runs = ArrayList<IntRange>()
    fun add(first: Int, untilExclusive: Int) {
        if (untilExclusive - first >= 2) runs.add(first..untilExclusive - 1)
    }
    var from = 0
    // colors[i] is the color of the leg *arriving* at i, per TrackColoring.colors — so a run's own
    // color is the one at its last index, and index 0 has no leg to be colored by.
    for (arrival in 1 until points.size) {
        if (points[arrival].segmentStart) {
            add(from, arrival)
            from = arrival
        } else if (arrival >= 2 && colors[arrival] != colors[arrival - 1]) {
            add(from, arrival)
            from = arrival - 1
        }
    }
    add(from, points.size)
    return runs
}

/**
 * The track as one feature per run of same-colored fixes, cut where the recorder wasn't watching
 * ([lineRuns]).
 *
 * Features rather than one polyline under a `line-gradient`, because a gradient is positioned along
 * the line's *length* while every other reading of the metric — the graph beside the map most of
 * all — is positioned along its *time*. A slowdown is long in time and short on the ground, and a
 * stretch slow enough to advance no distance between fixes could hold no gradient stop at all,
 * stops having to strictly increase: the colors either side simply ran through it.
 *
 * What puts such a stretch back on the map is the round cap ([addTrackLine]): a run of near-zero
 * length still draws as a dot of the line's own width, so a stop has a floor of a few pixels rather
 * than the nothing its ground distance earns. That is also why the source disables simplification —
 * a zero-length feature is the first thing it drops.
 *
 * Runs, not a feature per fix: the ramp is banded (`RAMP_STEPS`), so a steady pace is one feature
 * instead of hundreds, and only a color boundary is spent twice — a break boundary is spent once,
 * which is what leaves the gap.
 */
private fun trackLineFeature(
    points: List<TrackPoint>,
    colors: IntArray,
    greatCircleLegs: Boolean = false,
): FeatureCollection {
    if (points.size < 2) return FeatureCollection.fromFeatures(emptyList())
    val features = ArrayList<Feature>()
    // One properties object per *color*, not per run: a banded ramp has a few dozen, a track has
    // hundreds of runs, and a feature only ever reads them.
    val properties = HashMap<Int, JsonObject>()
    for (run in lineRuns(points, colors)) {
        val color = colors[run.last]
        val props = properties.getOrPut(color) {
            JsonObject().apply { addProperty(COLOR_KEY, ColorUtils.colorToRgbaString(color)) }
        }
        lineFeature(points.subList(run.first, run.last + 1), props, greatCircleLegs)?.let(features::add)
    }
    return FeatureCollection.fromFeatures(features)
}

private fun addTrackLine(style: Style, points: List<TrackPoint>, colors: IntArray, greatCircleLegs: Boolean, dashed: Boolean) {
    // A run of a slow stretch spans metres — sub-pixel on any view of a whole track — and a GeoJSON
    // source simplifies per tile, which drops a feature that small outright. The one long polyline
    // this replaced survived it because simplification keeps a long line's shape.
    // Turning simplification off is what lets a stop keep its color at all; see trackLineFeature.
    style.addSource(
        GeoJsonSource(
            TRACK_SOURCE,
            trackLineFeature(points, colors, greatCircleLegs),
            GeoJsonOptions().withTolerance(0f),
        ),
    )
    style.addLayer(
        LineLayer(TRACK_LAYER, TRACK_SOURCE).withProperties(
            PropertyFactory.lineWidth(TRACK_LINE_WIDTH),
            // Round is load-bearing, not cosmetic: it joins runs drawn as separate features, and
            // it gives a near-zero-length run the width of the line to be seen in.
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineColor(Expression.get(COLOR_KEY)),
        ).apply { if (dashed) dashedAsUnmeasured() },
    )
}

// Noisy markers are color-coded by why the fix was rejected; points recorded before reasons
// were tracked (null) fall back to the generic accuracy color. A reason that isn't a rejection never
// reaches this layer.
private fun noisyIcon(p: TrackPoint): String = when (IgnoreReason.fromCode(p.ignoreReason)) {
    IgnoreReason.JUMP -> IMG_NOISY_JUMP
    IgnoreReason.NO_GNSS -> IMG_NOISY_GNSS
    IgnoreReason.ACCURACY, IgnoreReason.EDGE_STAY, IgnoreReason.STATIONARY, null -> IMG_NOISY
}

private fun markerCollection(
    points: List<TrackPoint>,
    noisyPoints: List<TrackPoint>,
    directionalEnd: Boolean,
): FeatureCollection {
    val features = ArrayList<Feature>()
    noisyPoints.forEach { features.add(markerFeature(it, noisyIcon(it))) }
    points.firstOrNull()?.let { features.add(markerFeature(it, IMG_START)) }
    points.lastOrNull()?.let { last ->
        // GPS only reports a bearing while moving, so at a standstill the newest fixes carry none —
        // fall back to the last *known* heading so the pointer doesn't flicker into a dot at stops.
        val bearing = if (directionalEnd) points.lastOrNull { it.bearing != null }?.bearing else null
        if (bearing != null) {
            features.add(markerFeature(last, IMG_POINTER, bearing))
        } else {
            features.add(markerFeature(last, IMG_END))
        }
    }
    return FeatureCollection.fromFeatures(features)
}

private fun addMarkers(
    ctx: Context,
    style: Style,
    points: List<TrackPoint>,
    noisyPoints: List<TrackPoint>,
    directionalEnd: Boolean,
) {
    // Endpoint-dot sized and weighted, not pin-sized, and this layer sits under the end pins: the
    // *place* at each end is what the map says first, and these mark the fix — where recording began
    // and stopped inside it, which is a detail of the recording rather than of the journey. They keep
    // their green and red: the pair reads as start-and-end, which the blue cluster dot they now match
    // in size and weight does not say.
    style.addImage(IMG_START, shadowedBitmap(ctx, R.drawable.ic_marker_start, MarkerShadow.EVIDENCE_TURNING))
    style.addImage(IMG_END, shadowedBitmap(ctx, R.drawable.ic_marker_end, MarkerShadow.EVIDENCE_TURNING))
    style.addImage(IMG_POINTER, shadowedBitmap(ctx, R.drawable.ic_marker_pointer, MarkerShadow.SUBJECT_TURNING))
    style.addImage(IMG_NOISY, shadowedBitmap(ctx, R.drawable.ic_marker_noisy, MarkerShadow.EVIDENCE_TURNING))
    style.addImage(IMG_NOISY_JUMP, shadowedBitmap(ctx, R.drawable.ic_marker_noisy_jump, MarkerShadow.EVIDENCE_TURNING))
    style.addImage(IMG_NOISY_GNSS, shadowedBitmap(ctx, R.drawable.ic_marker_noisy_gnss, MarkerShadow.EVIDENCE_TURNING))
    style.addSource(GeoJsonSource(MARKER_SOURCE, markerCollection(points, noisyPoints, directionalEnd)))
    style.addLayer(iconSymbolLayer(MARKER_LAYER, MARKER_SOURCE))
}

// Not a map source: any change to one makes MapLibre redraw every layer, and the selection moves per
// touch event.
@Composable
private fun SelectionMarker(map: MapLibreMap, selection: () -> TrackPoint?) {
    val context = LocalContext.current
    val pointer = remember(context) {
        shadowedBitmap(context, R.drawable.ic_marker_pointer, MarkerShadow.SUBJECT_TURNING).asImageBitmap()
    }
    val dot = remember(context) {
        shadowedBitmap(context, R.drawable.ic_marker_selected, MarkerShadow.SUBJECT_TURNING).asImageBitmap()
    }
    // Read only in the draw phase, so a camera move redraws the marker without recomposing it.
    val cameraMoves = remember(map) { mutableIntStateOf(0) }
    DisposableEffect(map) {
        val listener = MapLibreMap.OnCameraMoveListener { cameraMoves.intValue++ }
        map.addOnCameraMoveListener(listener)
        onDispose { map.removeOnCameraMoveListener(listener) }
    }
    // Without a layer of its own, every redraw would re-record the map's controls with the marker.
    Canvas(Modifier.fillMaxSize().graphicsLayer()) {
        cameraMoves.intValue
        val point = selection() ?: return@Canvas
        val at = map.projection.toScreenLocation(LatLng(point.latitude, point.longitude))
        val center = Offset(at.x, at.y)
        val bearing = point.bearing
        if (bearing == null) {
            drawImage(dot, center - Offset(dot.width / 2f, dot.height / 2f))
        } else {
            // The bearing is from north, and the map turns north by the camera's bearing.
            rotate(bearing - map.cameraPosition.bearing.toFloat(), pivot = center) {
                drawImage(pointer, center - Offset(pointer.width / 2f, pointer.height / 2f))
            }
        }
    }
}

private const val BEARING_KEY = "bearing"

private fun markerFeature(p: TrackPoint, icon: String, bearing: Float = 0f): Feature =
    Feature.fromGeometry(
        Point.fromLngLat(p.longitude, p.latitude),
        JsonObject().apply {
            addProperty(ICON_KEY, icon)
            addProperty(BEARING_KEY, bearing)
        },
    )

private fun iconSymbolLayer(id: String, source: String): SymbolLayer =
    markerSymbolLayer(id, source).withProperties(
        // Rotate with the map so the droplet keeps pointing along the ground-track bearing.
        PropertyFactory.iconRotate(Expression.get(BEARING_KEY)),
        PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
    )
