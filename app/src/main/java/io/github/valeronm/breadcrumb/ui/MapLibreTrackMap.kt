package io.github.valeronm.breadcrumb.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
