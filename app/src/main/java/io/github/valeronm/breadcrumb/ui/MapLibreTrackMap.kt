package io.github.valeronm.breadcrumb.ui

import android.content.Context
import android.graphics.RectF
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.gson.JsonObject
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.TrackQuality
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.DwellDetector
import io.github.valeronm.breadcrumb.domain.EdgeStayIgnore
import io.github.valeronm.breadcrumb.domain.IgnoreReason
import io.github.valeronm.breadcrumb.domain.PlaceCategory
import io.github.valeronm.breadcrumb.domain.PlaceClusterer
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.placeCategory
import org.maplibre.android.geometry.LatLng
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
 * Renders a track on a Protomaps vector basemap (MapLibre GL Native), dark or light flavor per the app
 * theme. The line is drawn as one feature per run of same-colored fixes ([TrackColoring], the metric
 * picked by [colorMode]); start/end and noisy-fix markers ride a symbol layer; the named places at
 * the path's two ends are drawn as labeled pins with their capture areas; the camera fits the
 * track once on open — the places never move it, a route being the subject and them the annotation.
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
    selectedPoint: TrackPoint? = null,
    // Detected in-track stops, highlighted as place-style capture circles under the line.
    dwells: List<DwellDetector.Dwell> = emptyList(),
    // Stops the recording ran on through at either edge, as stored: one point run per edge,
    // drawn grayed off the end of the track line.
    overruns: List<EdgeStayIgnore.Overrun> = emptyList(),
    // The named places at the path's two ends (see RoutePlaces) — a labeled pin over the capture
    // area that claimed that end. One entry where a round trip began and ended in the same place.
    endPlaces: List<Place> = emptyList(),
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
    val darkTheme = isSystemInDarkTheme()
    val units = LocalUnits.current
    // For the pin images an update may have to register — the style-loaded callback is handed a
    // context, an update is not.
    val context = LocalContext.current
    // Keyed on the points alone, unlike the coloring: seam distances don't depend on the metric,
    // so switching it must not re-walk them.
    val seams = remember(precomputedSeams, points) {
        precomputedSeams ?: TrackQuality.seams(points)
    }
    val coloring = remember(precomputedColoring, seams, colorMode, activity, darkTheme, units) {
        precomputedColoring
            ?: trackColoring(points, TrackQuality.pointSpeedsKmh(seams), colorMode, activity, darkTheme, units)
    }
    val colors = coloring.colors
    val applied = remember { AppliedTrackInputs() }

    Box(modifier) {
        MapLibreStyledMap(
            modifier = Modifier.fillMaxSize(),
            onStyleLoaded = { ctx, map, style ->
                // The end places are one input drawn at two depths — their areas under the line, their
                // pins over it so it can't cover them.
                addEndPlaceAreas(style, endPlaces)
                addDwellLayers(style, dwells)
                addTrackLine(style, points, colors)
                addEdgeStayLayer(style, overruns, darkTheme) // over the line, off its ends
                addMarkers(ctx, style, points, noisyPoints, directionalEnd)
                // Over the recorder's own markers: at an end the two land within a capture radius of
                // each other, and the place is the one that says where the journey went. The
                // scrubber's selection alone draws over it — that one is under the user's thumb.
                addEndPlacePins(ctx, style, endPlaces)
                addSelectionLayer(ctx, style, selectedPoint)
                frameTo(map, framePositions(points, noisyPoints), singlePointZoom = 15.0)
                // Stamped here as well as drawn: otherwise the first update sees no applied input
                // and rebuilds every source the load just built.
                applied.points = points
                applied.noisy = noisyPoints
                applied.colors = colors
                applied.endPlaces = endPlaces
                applied.framed = true
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
                    style.getSourceAs<GeoJsonSource>(TRACK_SOURCE)?.setGeoJson(trackLineFeature(points, colors))
                }
                // Refresh geometry when the track grows (the live "current track" preview).
                if (moved) {
                    applied.points = points
                    applied.noisy = noisyPoints
                    style.getSourceAs<GeoJsonSource>(MARKER_SOURCE)?.setGeoJson(markerCollection(points, noisyPoints, directionalEnd))
                    // Live preview: hold the camera (so a pan/zoom survives and the map
                    // isn't re-rendered every fix); re-fit only when the current position
                    // drifts out of the middle 80% of the viewport.
                    if (applied.framed && directionalEnd) {
                        points.lastOrNull()?.let { last ->
                            if (!map.projection.visibleRegion.latLngBounds
                                    .containsWithMargin(last.latitude, last.longitude)
                            ) {
                                // Headroom so the position lands inside the 80% zone, not
                                // straight back on its edge (which would re-frame again on
                                // the next fix while moving outward). 1.2 keeps each zoom
                                // step small; the fit padding provides the rest of the slack.
                                frameTo(map, points.map { LatLng(it.latitude, it.longitude) }, singlePointZoom = 15.0, headroom = 1.2)
                            }
                        }
                    }
                }
                if (applied.selection !== selectedPoint) {
                    applied.selection = selectedPoint
                    style.getSourceAs<GeoJsonSource>(SELECT_SOURCE)?.setGeoJson(selectionCollection(selectedPoint))
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
                // One check for both sources: a pin and the area it sits in are one place, and a set
                // that had them disagree would be showing a reach nothing claims.
                if (applied.endPlaces !== endPlaces) {
                    applied.endPlaces = endPlaces
                    addEndPlacePinImages(context, style, endPlaces)
                    style.getSourceAs<GeoJsonSource>(END_PLACE_AREA_SOURCE)
                        ?.setGeoJson(captureAreaCollection(PlaceClusterer.seedsOf(endPlaces)))
                    style.getSourceAs<GeoJsonSource>(END_PLACE_SOURCE)
                        ?.setGeoJson(endPlaceCollection(endPlaces))
                }
                if (!applied.framed) {
                    frameTo(map, framePositions(points, noisyPoints), singlePointZoom = 15.0)
                    applied.framed = true
                }
            },
        )
        if (showLegend) {
            // Bottom-right: MapLibre's logo + attribution live bottom-left.
            TrackLegend(coloring.legend, Modifier.align(Alignment.BottomEnd).padding(12.dp))
        }
    }
}

/**
 * What each source/layer of the track map was last fed, so unrelated recompositions (e.g. the
 * graph scrubber moving the selection) don't re-serialize the full track geometry into the
 * native map. Identity comparisons, matching the stability of the remembered inputs.
 */
private class AppliedTrackInputs {
    var points: List<TrackPoint>? = null
    var noisy: List<TrackPoint>? = null

    /** The line's colours: a metric switch rebuilds the source, the legs carrying their own. */
    var colors: IntArray? = null
    var selection: TrackPoint? = null
    var dwells: List<DwellDetector.Dwell>? = null
    var overruns: List<EdgeStayIgnore.Overrun>? = null

    /** The named places at the path's ends — identity, the resolution handing back a fresh list only
     *  when the path or the places table changed. */
    var endPlaces: List<Place>? = null

    /** Frame once per map; later updates must not move the camera (the live preview re-frames
     *  only when the current position nears the viewport edge). */
    var framed = false
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
private const val SELECT_SOURCE = "select-src"
private const val SELECT_LAYER = "select-layer"
private const val IMG_SELECTED = "marker-selected"

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

// Grays the colored line underneath rather than adding a color of its own: dark theme needs a
// darker gray than the track to read as receding, light theme a lighter one.
private const val EDGE_STAY_DIM_DARK = 0xD9424242.toInt()
private const val EDGE_STAY_DIM_LIGHT = 0xD9BDBDBD.toInt()

/**
 * The stretch at each track edge the recorder ran on through — fixes already off the path, each run
 * drawn from the good fix it hangs off so the gray meets the colored line. Its own dim color rather
 * than dimming the track underneath: the fixes left the track line, so there is nothing to recolor.
 */
private fun edgeStayFeature(overruns: List<EdgeStayIgnore.Overrun>): FeatureCollection =
    FeatureCollection.fromFeatures(overruns.mapNotNull { lineFeature(it.points) })

private fun addEdgeStayLayer(
    style: Style,
    overruns: List<EdgeStayIgnore.Overrun>,
    darkTheme: Boolean,
) {
    style.addSource(GeoJsonSource(EDGE_STAY_SOURCE, edgeStayFeature(overruns)))
    style.addLayer(
        LineLayer(EDGE_STAY_LAYER, EDGE_STAY_SOURCE).withProperties(
            // Wider than the 3f track line so no colored fringe survives along the edges.
            PropertyFactory.lineWidth(4f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineColor(if (darkTheme) EDGE_STAY_DIM_DARK else EDGE_STAY_DIM_LIGHT),
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

private fun addEndPlaceAreas(style: Style, places: List<Place>) {
    style.addSource(
        GeoJsonSource(END_PLACE_AREA_SOURCE, captureAreaCollection(PlaceClusterer.seedsOf(places))),
    )
    addContextCircleLayers(style, END_PLACE_AREA_SOURCE, END_PLACE_AREA_FILL, END_PLACE_AREA_LINE)
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
                StayDeriver.Endpoint(place.lat, place.lon),
                placePinImage(place.placeCategory, withGlyph = true),
                place.label,
            )
        },
    )

private fun addEndPlacePins(ctx: Context, style: Style, places: List<Place>) {
    addEndPlacePinImages(ctx, style, places)
    style.addSource(GeoJsonSource(END_PLACE_SOURCE, endPlaceCollection(places)))
    style.addLayer(labeledSymbolLayer(ctx, END_PLACE_LAYER, END_PLACE_SOURCE))
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
 * What the once-per-map fit frames: the track line's points — or, when there's no drawable line,
 * whatever markers there are (noisy fixes included), so a bad-points-only track doesn't open on a
 * world view.
 */
private fun framePositions(points: List<TrackPoint>, noisyPoints: List<TrackPoint>): List<LatLng> =
    (if (points.size >= 2) points else points + noisyPoints).map { LatLng(it.latitude, it.longitude) }

/** The points as one polyline, or null below the two positions a GeoJSON LineString needs. */
private fun lineFeature(points: List<TrackPoint>, properties: JsonObject? = null): Feature? =
    if (points.size < 2) {
        null
    } else {
        Feature.fromGeometry(
            LineString.fromLngLats(points.map { Point.fromLngLat(it.longitude, it.latitude) }),
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
private fun trackLineFeature(points: List<TrackPoint>, colors: IntArray): FeatureCollection {
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
        lineFeature(points.subList(run.first, run.last + 1), props)?.let(features::add)
    }
    return FeatureCollection.fromFeatures(features)
}

private fun addTrackLine(style: Style, points: List<TrackPoint>, colors: IntArray) {
    // A run of a slow stretch spans metres — sub-pixel on any view of a whole track — and a GeoJSON
    // source simplifies per tile, which drops a feature that small outright. The one long polyline
    // this replaced survived it because simplification keeps a long line's shape.
    // Turning simplification off is what lets a stop keep its color at all; see trackLineFeature.
    style.addSource(
        GeoJsonSource(TRACK_SOURCE, trackLineFeature(points, colors), GeoJsonOptions().withTolerance(0f)),
    )
    style.addLayer(
        LineLayer(TRACK_LAYER, TRACK_SOURCE).withProperties(
            PropertyFactory.lineWidth(3f),
            // Round is load-bearing, not cosmetic: it joins runs drawn as separate features, and
            // it gives a near-zero-length run the width of the line to be seen in.
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineColor(Expression.get(COLOR_KEY)),
        ),
    )
}

// Noisy markers are color-coded by why the fix was rejected; points recorded before reasons
// were tracked (null) fall back to the generic accuracy color. EDGE_STAY fixes are not rejects
// and never reach this layer — they are drawn as the grayed overrun line.
private fun noisyIcon(p: TrackPoint): String = when (IgnoreReason.fromCode(p.ignoreReason)) {
    IgnoreReason.JUMP -> IMG_NOISY_JUMP
    IgnoreReason.NO_GNSS -> IMG_NOISY_GNSS
    IgnoreReason.ACCURACY, IgnoreReason.EDGE_STAY, null -> IMG_NOISY
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

/**
 * The graph-scrubber selection: its own source/layer so updates don't rebuild the marker set.
 * A droplet pointing along the fix's bearing where one was recorded, else the plain dot.
 */
private fun selectionCollection(p: TrackPoint?): FeatureCollection =
    FeatureCollection.fromFeatures(
        listOfNotNull(
            p?.let {
                val bearing = it.bearing
                if (bearing != null) {
                    markerFeature(it, IMG_POINTER, bearing)
                } else {
                    markerFeature(it, IMG_SELECTED)
                }
            },
        ),
    )

private fun addSelectionLayer(ctx: Context, style: Style, selected: TrackPoint?) {
    style.addImage(IMG_SELECTED, shadowedBitmap(ctx, R.drawable.ic_marker_selected, MarkerShadow.SUBJECT_TURNING))
    style.addImage(IMG_POINTER, shadowedBitmap(ctx, R.drawable.ic_marker_pointer, MarkerShadow.SUBJECT_TURNING))
    style.addSource(GeoJsonSource(SELECT_SOURCE, selectionCollection(selected)))
    style.addLayer(iconSymbolLayer(SELECT_LAYER, SELECT_SOURCE))
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

/** The track's marker and selection layers: markers that turn to face along the ground track. */
private fun iconSymbolLayer(id: String, source: String): SymbolLayer =
    markerSymbolLayer(id, source).withProperties(
        // Rotate with the map so the droplet keeps pointing along the ground-track bearing.
        PropertyFactory.iconRotate(Expression.get(BEARING_KEY)),
        PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
    )

// --- Place map ----------------------------------------------------------------------------------

/**
 * One dot on the place map whose fate the *map* decides: [distanceM] (distance from the pin) is
 * compared against the live radius in a layer expression; null means settled — a neighbor keeps it
 * whatever the radius does, drawn gray uncompared. Carrying the distance rather than a resolved
 * icon means a drag changes one layer property, not thousands of features — that per-step rebuild
 * of the collection is what lags.
 */
internal class CaptureDot(val location: StayDeriver.Endpoint, val distanceM: Double?)

/**
 * A place as a map draws it, wherever it appears: what it is called and what it is for, at a spot.
 * Being named is what makes one a *pin* — an unnamed cluster stays a dot — and the name and the
 * category always come from the same place row, which is what the [Place] constructor is for.
 */
internal data class PlaceMarker(
    val location: StayDeriver.Endpoint,
    val label: String? = null,
    val category: PlaceCategory? = null,
) {
    constructor(location: StayDeriver.Endpoint, place: Place?) :
        this(location, place?.label, place?.placeCategory)
}

/**
 * What a place is drawn as: named makes it a pin in its own category's colors — [withGlyph] where
 * the marker is big enough to hold one — and unnamed leaves it the [unnamed] dot the surface gives
 * a cluster. Every map answers this the same way; only the fallback differs — and it need not be a
 * dot: the place map's own centre passes an untagged pin, being about to become one.
 */
private fun markerIcon(
    marker: PlaceMarker,
    withGlyph: Boolean,
    unnamed: String,
    muted: Boolean = false,
): String = if (marker.label != null) placePinImage(marker.category, withGlyph, muted) else unnamed

/**
 * Renders one place on the basemap: the cluster's capture circle (a meter-true polygon around
 * [center]) with every captured track endpoint as small dots, plus [neighbors] — gray
 * neighbor-endpoint dots and labeled named pins — so the radius can be judged against what a wider
 * circle would swallow. The camera fits the circle once on open, and the data is a snapshot: an
 * input change is a full refresh.
 */
@Composable
internal fun MapLibrePlaceMap(
    center: PlaceMarker,
    radiusM: Double,
    endpoints: List<StayDeriver.Endpoint>,
    modifier: Modifier = Modifier,
    neighbors: List<PlaceMarker> = emptyList(),
    // When set, these dots replace [endpoints] and the plain neighbor dots, and the radius decides
    // each one's icon in a layer expression — so dragging costs one property, not a re-upload.
    capture: List<CaptureDot>? = null,
    // Named neighbors' own capture areas, drawn muted under this one. They are what stops a dot
    // joining this place, so seeing them is what makes a gray dot inside your circle make sense.
    rivalAreas: List<PlaceClusterer.Seed> = emptyList(),
    /** A long press on the map, in map coordinates — how the center is placed by hand. */
    onLongPress: (StayDeriver.Endpoint) -> Unit,
) {
    val applied = remember { AppliedPlaceInputs() }
    // The listener is attached once, to a map that outlives every recomposition, so it must read the
    // *current* callback rather than the one the first composition passed — that one would move the
    // pin while offering an Undo back to wherever the pin was when the map was built.
    val longPress by rememberUpdatedState(onLongPress)
    val placeContent = {
        PlaceMapContent(
            center = center,
            radiusM = radiusM,
            markers = PlaceMarkers(endpoints, neighbors, capture),
            rivalAreas = rivalAreas,
        )
    }
    MapLibreStyledMap(
        modifier = modifier,
        onMapReady = { map ->
            map.addOnMapLongClickListener { at ->
                longPress(StayDeriver.Endpoint(at.latitude, at.longitude))
                true
            }
        },
        onStyleLoaded = { ctx, map, style ->
            applied.circleCenter = center.location
            applied.circleRadiusM = radiusM
            applied.markers = endpoints to neighbors
            applied.center = center
            applied.capture = capture
            applied.rivalAreas = rivalAreas
            addPlaceLayers(ctx, style, placeContent())
            framePlace(map, center.location, radiusM)
        },
        onUpdate = { map, style ->
            if (applied.circleCenter != center.location || applied.circleRadiusM != radiusM) {
                applied.circleCenter = center.location
                style.getSourceAs<GeoJsonSource>(PLACE_CIRCLE_SOURCE)
                    ?.setGeoJson(circleFeature(center.location, radiusM))
            }
            if (applied.circleRadiusM != radiusM) {
                applied.circleRadiusM = radiusM
                // A radius that changed re-fits the camera — the circle is the subject and it just
                // grew or shrank. A *moved* pin deliberately does not: the move was aimed at a point
                // the user was looking at, so re-fitting would slide it out from under them and
                // re-zoom to boot, which is exactly the precision the placement was after.
                framePlace(map, center.location, radiusM)
                // The dots do not move; which side of the radius they fall on does.
                style.getLayer(PLACE_MARKER_LAYER)?.setProperties(markerIconProperty(radiusM))
            }
            if (applied.markers != endpoints to neighbors ||
                applied.capture !== capture ||
                applied.center != center
            ) {
                applied.markers = endpoints to neighbors
                applied.capture = capture
                applied.center = center
                style.getSourceAs<GeoJsonSource>(PLACE_MARKER_SOURCE)
                    ?.setGeoJson(placeMarkerCollection(placeContent()))
            }
            if (applied.rivalAreas !== rivalAreas) {
                applied.rivalAreas = rivalAreas
                style.getSourceAs<GeoJsonSource>(PLACE_RIVAL_SOURCE)
                    ?.setGeoJson(captureAreaCollection(rivalAreas))
            }
        },
    )
}

/** What the marker layer draws — three inputs that always travel together. */
private class PlaceMarkers(
    val endpoints: List<StayDeriver.Endpoint>,
    val neighbors: List<PlaceMarker>,
    val capture: List<CaptureDot>?,
)

/** Everything the place map draws: its own area, the markers, and the areas it competes with. */
private class PlaceMapContent(
    val center: PlaceMarker,
    val radiusM: Double,
    val markers: PlaceMarkers,
    val rivalAreas: List<PlaceClusterer.Seed>,
)

/** Last-applied inputs of the place map — value comparisons, the inputs are rebuilt lists. */
private class AppliedPlaceInputs {
    /** Where the circle is drawn and how wide, tracked apart because the two have different
     *  consequences: either redraws the ring, but only a *resize* re-fits the camera and re-decides
     *  which side of the radius each dot falls on. */
    var circleCenter: StayDeriver.Endpoint? = null
    var circleRadiusM: Double? = null
    var markers: Pair<List<StayDeriver.Endpoint>, List<PlaceMarker>>? = null

    /** Naming or tagging a place while its map is open redraws its marker — the source names an
     *  image per category, so both are a feature rebuild rather than a restyle. Its position is
     *  watched twice over, here and in [circleCenter]: the two answer different questions about a
     *  moved pin (which features to rebuild, where to point the camera) and both must be asked. */
    var center: PlaceMarker? = null

    /** Identity, not equality: the scan hands back a new list only when the candidates change. */
    var capture: List<CaptureDot>? = null
    var rivalAreas: List<PlaceClusterer.Seed>? = null
}

private const val PLACE_RIVAL_SOURCE = "place-rival-src"
private const val PLACE_RIVAL_FILL = "place-rival-fill"
private const val PLACE_RIVAL_LINE = "place-rival-line"

private const val PLACE_CIRCLE_SOURCE = "place-circle-src"
private const val PLACE_CIRCLE_FILL = "place-circle-fill"
private const val PLACE_CIRCLE_LINE = "place-circle-line"

/** The image a feature takes once it is drawn big enough to carry a glyph — see [GLYPH_ZOOM]. */
private const val GLYPH_KEY = "glyph"

/** What a feature *is*, kept apart from which image draws it: a pin's image id names its category. */
private const val KIND_KEY = "kind"
private const val KIND_PIN = "pin"
private const val KIND_DOT = "dot"

private const val PLACE_MARKER_SOURCE = "place-marker-src"
private const val PLACE_MARKER_LAYER = "place-marker-layer"
private const val IMG_ENDPOINT = "marker-endpoint"
private const val IMG_ENDPOINT_BRIEF = "marker-endpoint-brief"
private const val IMG_NEIGHBOR = "marker-neighbor"

private fun addPlaceLayers(ctx: Context, style: Style, content: PlaceMapContent) {
    // Rivals first, so this place's own circle reads on top of them where they overlap.
    style.addSource(GeoJsonSource(PLACE_RIVAL_SOURCE, captureAreaCollection(content.rivalAreas)))
    addContextCircleLayers(style, PLACE_RIVAL_SOURCE, PLACE_RIVAL_FILL, PLACE_RIVAL_LINE)
    style.addSource(
        GeoJsonSource(PLACE_CIRCLE_SOURCE, circleFeature(content.center.location, content.radiusM)),
    )
    addCaptureCircleLayers(style, PLACE_CIRCLE_SOURCE, PLACE_CIRCLE_FILL, PLACE_CIRCLE_LINE)
    style.addImage(IMG_ENDPOINT, shadowedBitmap(ctx, R.drawable.ic_marker_endpoint, MarkerShadow.EVIDENCE))
    style.addImage(IMG_NEIGHBOR, shadowedBitmap(ctx, R.drawable.ic_marker_neighbor, MarkerShadow.EVIDENCE))
    // This map holds one place at the zoom its own radius frames, so its pins are never the
    // small form: full size, glyphed, and no disc variant to carry.
    addPlacePinImages(ctx, style, PinSet.PlacesAndNeighbors)
    style.addSource(
        GeoJsonSource(
            PLACE_MARKER_SOURCE,
            placeMarkerCollection(content),
        ),
    )
    style.addLayer(
        labeledSymbolLayer(ctx, PLACE_MARKER_LAYER, PLACE_MARKER_SOURCE)
            .withProperties(markerIconProperty(content.radiusM)),
    )
}

/**
 * Neighbor context first (visually underneath), then the place's own endpoint dots, then the
 * pin marker last so it draws on top.
 */
private fun placeMarkerCollection(content: PlaceMapContent): FeatureCollection {
    val markers = content.markers
    val capture = markers.capture
    val features = ArrayList<Feature>(markers.neighbors.size + markers.endpoints.size + 1)
    // Named neighbors are pins in both modes; their plain dots are only drawn when the
    // capture form isn't, because there they are already among the candidates.
    markers.neighbors.forEach { n ->
        if (capture != null && n.label == null) return@forEach
        features.add(neighborFeature(n))
    }
    if (capture != null) {
        capture.forEach { dot ->
            features.add(
                // A settled dot is written as the gray icon; one still in play carries the
                // distance instead and lets the layer decide.
                if (dot.distanceM == null) {
                    endpointFeature(dot.location, IMG_NEIGHBOR)
                } else {
                    endpointFeature(dot.location, IMG_NEIGHBOR, distanceM = dot.distanceM)
                },
            )
        }
    } else {
        markers.endpoints.forEach { features.add(endpointFeature(it, IMG_ENDPOINT)) }
    }
    // A pin whether or not the row exists yet — this map shows what saving would produce, and saving
    // produces a pin. Said through the rule's own `unnamed` seam rather than around it: an untagged
    // pin is simply the fallback *this* surface gives, where a dot would be the same marker as the
    // endpoints it sits among in another hue, and these are told apart by size and shape first.
    // Carries no label: its name is the screen's title, not something to repeat on it.
    val center = content.center
    val centerIcon = markerIcon(center, withGlyph = true, unnamed = placePinImage(null, withGlyph = true))
    features.add(endpointFeature(center.location, centerIcon))
    return FeatureCollection.fromFeatures(features)
}

/**
 * A neighbouring place, receding on both counts a raster symbol can: a desaturated bitmap and less
 * ink. One function because the two are one decision — set on the feature but not the image (or the
 * reverse) and the pin half-recedes, with nothing to catch it.
 */
private fun neighborFeature(n: PlaceMarker): Feature = endpointFeature(
    n.location,
    markerIcon(n, withGlyph = true, unnamed = IMG_NEIGHBOR, muted = true),
    n.label,
    muted = true,
)

/**
 * The marker layer's icon property: only features carrying a distance (capture dots) resolve against
 * the radius; the rest keep their written icon. Keyed on the property being *present*, not a sentinel
 * icon name — that shares the image ids' value space (an image id "auto" would break it). A radius
 * change costs only this rebuild; correct with or without capture dots, so installed unconditionally.
 */
private fun markerIconProperty(radiusM: Double) = PropertyFactory.iconImage(
    Expression.switchCase(
        Expression.has(DISTANCE_KEY),
        Expression.switchCase(
            Expression.lte(Expression.get(DISTANCE_KEY), Expression.literal(radiusM)),
            Expression.literal(IMG_ENDPOINT),
            Expression.literal(IMG_NEIGHBOR),
        ),
        Expression.get(ICON_KEY),
    ),
)

// --- All-places overview map ---------------------------------------------------------------

/** One place on the overview map: what to draw, plus what only this map needs of it. */
internal class OverviewPlace(
    val marker: PlaceMarker,
    /** The place-detail key reported on tap. */
    val key: String,
    /** The place's only stay is a merge-eligible short stop (a likely split-track artifact,
     *  not a real visit) — unnamed dots render orange instead of blue. */
    val brief: Boolean = false,
)

/**
 * Every place on one map: labeled pins for named places, small dots for unnamed clusters, sized
 * down the zoom range (see [overviewIconSize]) and framed to fit them all once on open. A pin is
 * colored by its category's group and shows the category's glyph at [GLYPH_ZOOM]. Tapping a marker
 * reports its key via [onOpen].
 */
@Composable
internal fun MapLibrePlacesMap(
    places: List<OverviewPlace>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val applied = remember { AppliedOverviewInputs() }
    applied.onOpen = onOpen
    MapLibreStyledMap(
        modifier = modifier,
        onMapReady = { map ->
            map.addOnMapClickListener { latLng ->
                val screen = map.projection.toScreenLocation(latLng)
                val touch = RectF(screen.x - 36, screen.y - 36, screen.x + 36, screen.y + 36)
                val key = map.queryRenderedFeatures(touch, OVERVIEW_LAYER)
                    .firstOrNull()?.getStringProperty("key")
                if (key != null) applied.onOpen(key)
                key != null
            }
        },
        onStyleLoaded = { ctx, map, style ->
            applied.places = places
            addOverviewLayers(ctx, style, places)
            frameTo(map, places.map { LatLng(it.marker.location.lat, it.marker.location.lon) }, singlePointZoom = 13.0)
        },
        onUpdate = { _, style ->
            if (applied.places !== places) {
                applied.places = places
                style.getSourceAs<GeoJsonSource>(OVERVIEW_SOURCE)
                    ?.setGeoJson(overviewCollection(places))
            }
        },
    )
}

/** Last-applied input of the all-places overview map. */
private class AppliedOverviewInputs {
    var places: List<OverviewPlace>? = null

    /** The click listener is registered once, so it reads the handler from here to never go stale. */
    var onOpen: (String) -> Unit = {}
}

private const val OVERVIEW_SOURCE = "places-overview-src"
private const val OVERVIEW_LAYER = "places-overview-layer"

/**
 * The zoom from which an overview pin carries its category's glyph. Below it the pin is a colored
 * disc — the color survives being scaled down where a 24-unit glyph does not, and drawing the glyph
 * all the way out reads as a field of smudges rather than as symbols. It is also the ramp's middle
 * stop, so the glyph arrives on a pin already near full size: a glyph appearing on a marker still too
 * small to hold it is what the threshold exists to prevent.
 *
 * A threshold at all is a property of a *field* of places, framed to fit however far apart they are.
 * The track map's two end pins carry their glyph and name at every zoom: two markers cannot smudge
 * into each other, and a pin small enough to need this rule would be a pin whose name is the only
 * thing saying where the journey went.
 *
 * **Whole numbers only.** A camera expression on a *layout* property — which `icon-image`,
 * `text-field` and `icon-size` all are — is evaluated at integer zooms alone, so a fractional
 * threshold silently takes effect at the integer above it.
 */
private const val GLYPH_ZOOM = 9f

/**
 * The zoom from which an overview pin carries its place's name. Wider than this the map answers
 * *where* the places are and how they group, which the colored discs do on their own; a field of
 * names over a region is a wall of text with most of it dropped to collision anyway. Above
 * [GLYPH_ZOOM], so a pin gains its glyph first and its name second — shape, then word.
 */
private const val LABEL_ZOOM = 11f

private fun addOverviewLayers(ctx: Context, style: Style, places: List<OverviewPlace>) {
    style.addImage(IMG_ENDPOINT, shadowedBitmap(ctx, R.drawable.ic_marker_endpoint, MarkerShadow.EVIDENCE))
    style.addImage(
        IMG_ENDPOINT_BRIEF,
        shadowedBitmap(ctx, R.drawable.ic_marker_endpoint_brief, MarkerShadow.EVIDENCE),
    )
    addPlacePinImages(ctx, style)
    style.addSource(GeoJsonSource(OVERVIEW_SOURCE, overviewCollection(places)))
    style.addLayer(
        labeledSymbolLayer(ctx, OVERVIEW_LAYER, OVERVIEW_SOURCE).withProperties(
            // Every feature names both of its variants; zoom picks which one is drawn. A dot names
            // its own icon twice — it has no glyph to gain, and a missing image draws nothing.
            PropertyFactory.iconImage(
                Expression.step(
                    Expression.zoom(),
                    Expression.get(ICON_KEY),
                    Expression.stop(GLYPH_ZOOM, Expression.get(GLYPH_KEY)),
                ),
            ),
            PropertyFactory.iconSize(overviewIconSize()),
            // Emptied rather than faded out: an invisible label still takes part in collision and
            // would push its neighbours' names around from behind.
            PropertyFactory.textField(
                Expression.step(
                    Expression.zoom(),
                    Expression.literal(""),
                    Expression.stop(LABEL_ZOOM, Expression.get(LABEL_KEY)),
                ),
            ),
        ),
    )
}

/**
 * Marker size down the overview map's zoom range: this map is framed to fit every place at once,
 * so it opens as wide as the history is spread and pans down to a single street — one fixed size
 * cannot serve both. The pin shrinks the harder of the two, because a wide view is asking where the
 * places *are*, not which one is which; the dots shrink gently so they stay visible as a density
 * without ever catching the pin up.
 *
 * Zoom must be the interpolation's own input — it may not sit nested inside a per-feature
 * expression — so the ramp is over zoom with each stop choosing per feature, never the reverse.
 *
 * The ramp steps rather than glides: `icon-size` is a layout property, so this is sampled at whole
 * zooms and the sizes between the stops are the ones those samples land on.
 */
private fun overviewIconSize(): Expression =
    Expression.interpolate(
        Expression.linear(),
        Expression.zoom(),
        // Interpolation clamps outside its stops, so the smallest size holds all the way out to a
        // world view without a stop of its own down there.
        Expression.stop(8f, sizeByMarker(pin = 0.4f, dot = 0.65f)),
        Expression.stop(GLYPH_ZOOM, sizeByMarker(pin = 0.76f, dot = 0.8f)),
        Expression.stop(12f, sizeByMarker(pin = 1f, dot = 1f)),
    )

/**
 * Keyed on the feature's kind rather than its icon id: a pin's id says which *category* it is, and
 * matching sixteen of those to learn it is a pin would put the palette in the size ramp.
 */
private fun sizeByMarker(pin: Float, dot: Float): Expression =
    Expression.switchCase(
        Expression.eq(Expression.get(KIND_KEY), Expression.literal(KIND_PIN)),
        Expression.literal(pin),
        Expression.literal(dot),
    )

private fun overviewCollection(places: List<OverviewPlace>): FeatureCollection =
    FeatureCollection.fromFeatures(
        // Unnamed dots first so named pins draw (and hit-test) on top.
        places.sortedBy { it.marker.label != null }.map { p ->
            val dot = if (p.brief) IMG_ENDPOINT_BRIEF else IMG_ENDPOINT
            Feature.fromGeometry(
                Point.fromLngLat(p.marker.location.lon, p.marker.location.lat),
                JsonObject().apply {
                    addProperty(KIND_KEY, if (p.marker.label != null) KIND_PIN else KIND_DOT)
                    addProperty(ICON_KEY, markerIcon(p.marker, withGlyph = false, unnamed = dot))
                    addProperty(GLYPH_KEY, markerIcon(p.marker, withGlyph = true, unnamed = dot))
                    addProperty(LABEL_KEY, p.marker.label ?: "")
                    addProperty("key", p.key)
                },
            )
        },
    )
