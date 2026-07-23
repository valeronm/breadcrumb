package io.github.valeronm.breadcrumb.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.createBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.gson.JsonObject
import io.github.valeronm.breadcrumb.BuildConfig
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.ActivityType
import io.github.valeronm.breadcrumb.data.AndroidDistance
import io.github.valeronm.breadcrumb.data.IgnoreReason
import io.github.valeronm.breadcrumb.data.TrackQuality
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.domain.DwellDetector
import io.github.valeronm.breadcrumb.domain.EdgeStayIgnore
import io.github.valeronm.breadcrumb.domain.StayDeriver
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.PropertyValue
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders a track on a Protomaps vector basemap via MapLibre GL Native, in the dark or light flavor
 * the app theme calls for. The line is colored by
 * [colorMode] via a MapLibre `line-gradient` built from [TrackColoring]'s per-point colors; start/end
 * and noisy-fix markers sit on a symbol layer, and the camera fits the track once on open. Switching
 * the color mode updates the gradient in place without moving the camera; the source is refreshed
 * when the point list grows (the live "current track" preview), which re-frames only when the
 * current position nears the viewport edge — user pan/zoom survives otherwise.
 */
@Composable
fun MapLibreTrackMap(
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
    // Live preview: the last point is the current position — a droplet rotated to the movement
    // bearing instead of the finished-track end dot.
    directionalEnd: Boolean = false,
) {
    val darkTheme = isSystemInDarkTheme()
    val units = LocalUnits.current
    val coloring = remember(points, colorMode, activity, darkTheme, units) {
        trackColoring(points, TrackQuality.pointSpeedsKmh(points), colorMode, activity, darkTheme, units)
    }
    val paint = remember(points, coloring) { buildTrackPaint(points, coloring.colors) }
    // Frame once per map; later updates (color switches, live point growth) must not move the
    // camera — the live preview re-frames only when the current position nears the viewport edge.
    val framed = remember { booleanArrayOf(false) }
    // What each source/layer was last fed, so unrelated recompositions (e.g. the graph scrubber
    // moving the selection) don't re-serialize the full track geometry into the native map.
    val applied = remember { arrayOfNulls<Any?>(6) } // points, noisy, paint, selection, dwells, edges

    Box(modifier) {
        MapLibreStyledMap(
            modifier = Modifier.fillMaxSize(),
            onStyleLoaded = { ctx, map, style ->
                addDwellLayers(style, dwells) // first, so the circles render under the line
                addTrackLine(style, points, paint)
                addEdgeStayLayer(style, overruns, darkTheme) // over the line, off its ends
                addMarkers(ctx, style, points, noisyPoints, directionalEnd)
                addSelectionLayer(ctx, style, selectedPoint)
                frameTo(map, framePositions(points, noisyPoints), singlePointZoom = 15.0)
                framed[0] = true
            },
            onUpdate = { map, style ->
                // Recolor on color-mode change; also refresh geometry when the track grows (the
                // live "current track" preview). Re-frame only when the points changed (not on a
                // color switch), so a color change keeps the user's pan/zoom.
                if (applied[0] !== points || applied[1] !== noisyPoints) {
                    applied[0] = points
                    applied[1] = noisyPoints
                    style.getSourceAs<GeoJsonSource>(TRACK_SOURCE)?.setGeoJson(trackLineFeature(points))
                    style.getSourceAs<GeoJsonSource>(MARKER_SOURCE)?.setGeoJson(markerCollection(points, noisyPoints, directionalEnd))
                    // Live preview: hold the camera (so a pan/zoom survives and the map
                    // isn't re-rendered every fix); re-fit only when the current position
                    // drifts out of the middle 80% of the viewport.
                    if (framed[0] && directionalEnd) {
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
                if (applied[2] !== paint) {
                    applied[2] = paint
                    style.getLayerAs<LineLayer>(TRACK_LAYER)?.let { applyPaint(it, paint) }
                }
                if (applied[3] !== selectedPoint) {
                    applied[3] = selectedPoint
                    style.getSourceAs<GeoJsonSource>(SELECT_SOURCE)?.setGeoJson(selectionCollection(selectedPoint))
                }
                if (applied[4] !== dwells) {
                    applied[4] = dwells
                    style.getSourceAs<GeoJsonSource>(DWELL_SOURCE)?.setGeoJson(dwellCollection(dwells))
                }
                // Keyed on the overruns alone: they are loaded with the track, so a growing live
                // track that has none (the record screen) never rebuilds this source.
                if (applied[5] !== overruns) {
                    applied[5] = overruns
                    style.getSourceAs<GeoJsonSource>(EDGE_STAY_SOURCE)
                        ?.setGeoJson(edgeStayFeature(overruns))
                }
                if (!framed[0]) {
                    frameTo(map, framePositions(points, noisyPoints), singlePointZoom = 15.0)
                    framed[0] = true
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
 * Shared host for all the map composables: owns the [MapView], loads the Protomaps dark style once,
 * then routes subsequent recompositions to [onUpdate]. [onMapReady] runs before the style loads —
 * for one-time map-level setup like click listeners. Callers keep their own "what was last applied"
 * state; this only removes the init/style boilerplate.
 */
@Composable
private fun MapLibreStyledMap(
    modifier: Modifier = Modifier,
    onMapReady: (MapLibreMap) -> Unit = {},
    onStyleLoaded: (Context, MapLibreMap, Style) -> Unit,
    onUpdate: (MapLibreMap, Style) -> Unit,
) {
    val mapView = rememberMapLibreMapView()
    val mapRef = remember(mapView) { arrayOfNulls<MapLibreMap>(1) }
    val inited = remember(mapView) { booleanArrayOf(false) }
    // The style loads asynchronously; inputs that arrive in the meantime recompose while the
    // style is still null, so their update is skipped. Route the callback through a ref so the
    // load applies the *latest* composition's data, not what the first composition captured.
    val styleLoadedRef = remember(mapView) { arrayOf(onStyleLoaded) }
    styleLoadedRef[0] = onStyleLoaded
    AndroidView(
        modifier = modifier,
        factory = { mapView },
        update = { view ->
            if (!inited[0]) {
                inited[0] = true
                view.getMapAsync { map ->
                    mapRef[0] = map
                    onMapReady(map)
                    map.setStyle(Style.Builder().fromJson(loadProtomapsStyle(view.context))) { style ->
                        styleLoadedRef[0](view.context, map, style)
                    }
                }
            } else {
                val map = mapRef[0]
                val style = map?.style ?: return@AndroidView
                onUpdate(map, style)
            }
        },
    )
}

/** A MapLibre [MapView] whose lifecycle follows the composition's [LocalLifecycleOwner]. */
@Composable
private fun rememberMapLibreMapView(): MapView {
    val ctx = LocalContext.current
    val mapView = remember {
        MapLibre.getInstance(ctx)
        // Texture mode instead of the default SurfaceView: a SurfaceView composites in its own
        // layer and ignores Compose clipping, so it would bleed over rounded card corners. The
        // cards' side padding also keeps the map out of the back-gesture edge strips, so no
        // edge-swipe handling is needed on the view itself.
        val options = MapLibreMapOptions.createFromAttributes(ctx)
            .textureMode(true)
            // Shown until the first rendered frame; defaults to white, which flashes hard
            // against a dark UI.
            .foregroundLoadColor(styleBackgroundColor(ctx))
        MapView(ctx, options).apply {
            onCreate(null)
            onStart()
            onResume()
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onStop()
            mapView.onDestroy()
        }
    }
    return mapView
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
private const val DEFAULT_LINE = 0xFF5B9BF0.toInt()

private const val DWELL_SOURCE = "dwell-src"
private const val DWELL_FILL = "dwell-fill"
private const val DWELL_LINE = "dwell-line"

private const val EDGE_STAY_SOURCE = "edge-stay-src"
private const val EDGE_STAY_LAYER = "edge-stay-layer"

// Grays the colored line underneath rather than adding a color of its own: dark theme needs a
// darker gray than the track to read as receding, light theme a lighter one.
private const val EDGE_STAY_DIM_DARK = 0xD9424242.toInt()
private const val EDGE_STAY_DIM_LIGHT = 0xD9BDBDBD.toInt()

/**
 * The stretch at each track edge the recorder ran on through — the fixes already taken off the
 * path, each run drawn from the good fix it hangs off so the gray meets the colored line. Drawn
 * in its own dim color rather than by dimming the track underneath: those fixes are not part of
 * the track line at all anymore, so there is nothing under this to recolor.
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

/** One place-style capture circle per detected stop, sized at the detector's corral radius. */
private fun dwellCollection(dwells: List<DwellDetector.Dwell>): FeatureCollection {
    val radiusM = DwellDetector.Params().corralRadiusM
    return FeatureCollection.fromFeatures(dwells.map { circleFeature(it.centroid, radiusM) })
}

private fun addDwellLayers(style: Style, dwells: List<DwellDetector.Dwell>) {
    style.addSource(GeoJsonSource(DWELL_SOURCE, dwellCollection(dwells)))
    addCaptureCircleLayers(style, DWELL_SOURCE, DWELL_FILL, DWELL_LINE)
}

/**
 * The capture-circle look — translucent fill + dashed outline — shared by the place detail's
 * capture circle and the track map's dwell circles, so they read as the same species.
 */
private fun addCaptureCircleLayers(
    style: Style,
    sourceId: String,
    fillLayerId: String,
    lineLayerId: String,
    vararg extraProps: PropertyValue<*>,
) {
    style.addLayer(
        FillLayer(fillLayerId, sourceId).withProperties(
            PropertyFactory.fillColor(CIRCLE_FILL),
            *extraProps,
        ),
    )
    style.addLayer(
        LineLayer(lineLayerId, sourceId).withProperties(
            PropertyFactory.lineColor(CIRCLE_LINE),
            PropertyFactory.lineWidth(1.5f),
            PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
            *extraProps,
        ),
    )
}

/**
 * What the once-per-map fit frames: the track line's points — or, when there's no drawable line,
 * whatever markers there are (noisy fixes included), so a bad-points-only track doesn't open on a
 * world view.
 */
private fun framePositions(points: List<TrackPoint>, noisyPoints: List<TrackPoint>): List<LatLng> =
    (if (points.size >= 2) points else points + noisyPoints).map { LatLng(it.latitude, it.longitude) }

/** The points as one polyline, or null below the two positions a GeoJSON LineString needs. */
private fun lineFeature(points: List<TrackPoint>): Feature? =
    if (points.size < 2) {
        null
    } else {
        Feature.fromGeometry(
            LineString.fromLngLats(points.map { Point.fromLngLat(it.longitude, it.latitude) }),
        )
    }

private fun trackLineFeature(points: List<TrackPoint>): FeatureCollection =
    FeatureCollection.fromFeatures(listOfNotNull(lineFeature(points)))

private fun addTrackLine(style: Style, points: List<TrackPoint>, paint: TrackPaint) {
    // lineMetrics is required for line-gradient (line-progress is measured along the rendered line).
    style.addSource(GeoJsonSource(TRACK_SOURCE, trackLineFeature(points), GeoJsonOptions().withLineMetrics(true)))
    val layer = LineLayer(TRACK_LAYER, TRACK_SOURCE).withProperties(
        PropertyFactory.lineWidth(3f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
    )
    style.addLayer(layer)
    applyPaint(layer, paint)
}

private fun applyPaint(layer: LineLayer, paint: TrackPaint) {
    when (paint) {
        is TrackPaint.Gradient -> layer.setProperties(PropertyFactory.lineGradient(paint.expression))
        is TrackPaint.Solid -> layer.setProperties(PropertyFactory.lineColor(paint.color))
    }
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
    style.addImage(IMG_START, drawableBitmap(ctx, R.drawable.ic_marker_start))
    style.addImage(IMG_END, drawableBitmap(ctx, R.drawable.ic_marker_end))
    style.addImage(IMG_POINTER, drawableBitmap(ctx, R.drawable.ic_marker_pointer))
    style.addImage(IMG_NOISY, drawableBitmap(ctx, R.drawable.ic_marker_noisy))
    style.addImage(IMG_NOISY_JUMP, drawableBitmap(ctx, R.drawable.ic_marker_noisy_jump))
    style.addImage(IMG_NOISY_GNSS, drawableBitmap(ctx, R.drawable.ic_marker_noisy_gnss))
    style.addSource(GeoJsonSource(MARKER_SOURCE, markerCollection(points, noisyPoints, directionalEnd)))
    style.addLayer(iconSymbolLayer(MARKER_LAYER, MARKER_SOURCE))
}

/** Icon-marker layer shared by the track's marker and selection layers. */
private fun iconSymbolLayer(id: String, source: String): SymbolLayer =
    SymbolLayer(id, source).withProperties(
        PropertyFactory.iconImage(Expression.get("icon")),
        PropertyFactory.iconAllowOverlap(true),
        PropertyFactory.iconIgnorePlacement(true),
        PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
        // Rotate with the map so the droplet keeps pointing along the ground-track bearing.
        PropertyFactory.iconRotate(Expression.get("bearing")),
        PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
    )

/** Labeled pin layer shared by the place and overview maps: icon plus a label under it. */
private fun labeledSymbolLayer(ctx: Context, id: String, source: String): SymbolLayer {
    val dark = isDarkUi(ctx)
    return SymbolLayer(id, source).withProperties(
        PropertyFactory.iconImage(Expression.get("icon")),
        PropertyFactory.iconAllowOverlap(true),
        PropertyFactory.iconIgnorePlacement(true),
        PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
        // Named features carry a label under the pin; other features have an empty string.
        PropertyFactory.textField(Expression.get("label")),
        PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
        PropertyFactory.textSize(12f),
        PropertyFactory.textColor(if (dark) "#C8CFC6" else "#38423B"),
        PropertyFactory.textHaloColor(if (dark) "#14211A" else "#F0F2EE"),
        PropertyFactory.textHaloWidth(1.2f),
        PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
        PropertyFactory.textOffset(arrayOf(0f, 0.8f)),
        PropertyFactory.textOptional(true),
    )
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
    style.addImage(IMG_SELECTED, drawableBitmap(ctx, R.drawable.ic_marker_selected))
    style.addImage(IMG_POINTER, drawableBitmap(ctx, R.drawable.ic_marker_pointer))
    style.addSource(GeoJsonSource(SELECT_SOURCE, selectionCollection(selected)))
    style.addLayer(iconSymbolLayer(SELECT_LAYER, SELECT_SOURCE))
}

private fun markerFeature(p: TrackPoint, icon: String, bearing: Float = 0f): Feature =
    Feature.fromGeometry(
        Point.fromLngLat(p.longitude, p.latitude),
        JsonObject().apply {
            addProperty("icon", icon)
            addProperty("bearing", bearing)
        },
    )

/** These bounds with their half-spans scaled by [factor] around the center. */
private fun LatLngBounds.scaled(factor: Double): LatLngBounds {
    val centerLat = (latitudeNorth + latitudeSouth) / 2
    val centerLon = (longitudeEast + longitudeWest) / 2
    val halfLat = (latitudeNorth - latitudeSouth) / 2 * factor
    val halfLon = (longitudeEast - longitudeWest) / 2 * factor
    return LatLngBounds.from(
        centerLat + halfLat, centerLon + halfLon,
        centerLat - halfLat, centerLon - halfLon,
    )
}

/** Whether ([lat], [lon]) sits within the central [fraction] of these bounds. */
private fun LatLngBounds.containsWithMargin(lat: Double, lon: Double, fraction: Double = 0.8): Boolean =
    with(scaled(fraction)) {
        lat in latitudeSouth..latitudeNorth && lon in longitudeWest..longitudeEast
    }

/**
 * Fits the camera to [positions]: ≥2 → bounds fit with 96px padding, exactly 1 → [singlePointZoom].
 * [headroom] > 1 zooms out beyond the exact fit (half-spans scaled around the center). The live
 * re-fit needs it: a tight fit puts the current position right back at the viewport edge, so the
 * very next fix would trigger another re-frame.
 */
private fun frameTo(map: MapLibreMap, positions: List<LatLng>, singlePointZoom: Double, headroom: Double = 1.0) {
    when {
        // moveCamera (not easeCamera): the map should open already framed, with no zoom animation.
        positions.size >= 2 -> {
            val b = LatLngBounds.Builder()
            positions.forEach { b.include(it) }
            var bounds = b.build()
            if (headroom > 1.0) bounds = bounds.scaled(headroom)
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 96))
        }
        positions.size == 1 -> map.cameraPosition = CameraPosition.Builder()
            .target(positions[0]).zoom(singlePointZoom).build()
    }
}

/** The line's paint for the current color mode: a per-distance gradient, or a solid fallback. */
private sealed interface TrackPaint {
    data class Gradient(val expression: Expression) : TrackPaint
    data class Solid(val color: Int) : TrackPaint
}

/**
 * Builds a MapLibre `line-gradient` from per-point [colors] by placing each point's color at its
 * cumulative-distance fraction along the line (0..1) — the parity port of osmdroid's per-vertex
 * paint list. Falls back to a solid color for a track with no length.
 */
private fun buildTrackPaint(points: List<TrackPoint>, colors: IntArray): TrackPaint {
    if (points.size < 2 || colors.isEmpty()) return TrackPaint.Solid(colors.firstOrNull() ?: DEFAULT_LINE)
    val cumulative = DoubleArray(points.size)
    for (i in 1 until points.size) {
        cumulative[i] = cumulative[i - 1] + AndroidDistance.meters(
            points[i - 1].latitude, points[i - 1].longitude, points[i].latitude, points[i].longitude,
        )
    }
    val total = cumulative.last()
    if (total <= 0.0) return TrackPaint.Solid(colors.first())
    val stops = ArrayList<Expression.Stop>(points.size)
    var lastFraction = -1.0
    for (i in points.indices) {
        val fraction = when (i) {
            0 -> 0.0
            points.size - 1 -> 1.0
            else -> cumulative[i] / total
        }
        // line-gradient stops must strictly increase; skip zero-length steps (duplicate positions).
        if (fraction > lastFraction) {
            stops.add(Expression.stop(fraction, Expression.color(colors[i])))
            lastFraction = fraction
        }
    }
    if (stops.size < 2) return TrackPaint.Solid(colors.first())
    return TrackPaint.Gradient(
        Expression.interpolate(Expression.linear(), Expression.lineProgress(), *stops.toTypedArray()),
    )
}

private fun drawableBitmap(ctx: Context, resId: Int): Bitmap {
    val d = AppCompatResources.getDrawable(ctx, resId)!!
    val w = d.intrinsicWidth.coerceAtLeast(1)
    val h = d.intrinsicHeight.coerceAtLeast(1)
    val bmp = createBitmap(w, h)
    d.setBounds(0, 0, w, h)
    d.draw(Canvas(bmp))
    return bmp
}

/** Whether the UI is in dark mode — the single switch for basemap flavor and map ink colors. */
private fun isDarkUi(ctx: Context): Boolean =
    (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

/** The current flavor's style JSON, cached (asset name → JSON) — read for every map creation. */
private var cachedStyleJson: Pair<String, String>? = null

/**
 * The bundled official Protomaps style for the current theme (assets/protomaps-{dark,light}.json)
 * with the hosted-API key injected.
 */
private fun loadProtomapsStyle(ctx: Context): String {
    val asset = if (isDarkUi(ctx)) "protomaps-dark.json" else "protomaps-light.json"
    cachedStyleJson?.let { (name, json) -> if (name == asset) return json }
    val json = ctx.assets.open(asset).bufferedReader().use { it.readText() }
        .replace("{PROTOMAPS_KEY}", BuildConfig.PROTOMAPS_API_KEY)
    cachedStyleJson = asset to json
    return json
}

/**
 * The style's own `background` layer color — used as the pre-render placeholder so a style
 * refresh can't desync the load flash from the basemap.
 */
private fun styleBackgroundColor(ctx: Context): Int =
    Regex("\"background-color\":\\s*\"(#[0-9a-fA-F]{6})\"").find(loadProtomapsStyle(ctx))
        ?.groupValues?.get(1)?.let(android.graphics.Color::parseColor)
        ?: android.graphics.Color.DKGRAY

// --- Place map ----------------------------------------------------------------------------------

/** A neighboring cluster shown for context on the place map. */
class NeighborPlace(
    val location: StayDeriver.Endpoint,
    /** Named neighbors render as a labeled pin; null = a plain neighbor endpoint dot. */
    val label: String? = null,
)

/**
 * Renders one place on the dark basemap. With [showInternals] the cluster's capture circle
 * (meter-true polygon around [center]) is drawn with every captured track endpoint as small dots
 * plus [neighbors] — surrounding clusters' endpoints (gray dots) and named pins (labeled) — so
 * the radius can be judged against what a wider circle would swallow; without it only the pin
 * marker shows. Toggling the flag restyles in place without moving the camera. The camera fits
 * the circle once on open; the place data is a snapshot, so there is no live update path beyond
 * a full refresh when the inputs change.
 */
@Composable
fun MapLibrePlaceMap(
    center: StayDeriver.Endpoint,
    radiusM: Double,
    endpoints: List<StayDeriver.Endpoint>,
    modifier: Modifier = Modifier,
    neighbors: List<NeighborPlace> = emptyList(),
    showInternals: Boolean = true,
) {
    val applied = remember { arrayOfNulls<Any?>(3) } // circle (center+radius), markers, internals
    MapLibreStyledMap(
        modifier = modifier,
        onStyleLoaded = { ctx, map, style ->
            applied[0] = center to radiusM
            applied[1] = Triple(endpoints, neighbors, showInternals)
            applied[2] = showInternals
            addPlaceLayers(ctx, style, center, radiusM, endpoints, neighbors, showInternals)
            framePlace(map, center, radiusM)
        },
        onUpdate = { map, style ->
            if (applied[0] != center to radiusM) {
                applied[0] = center to radiusM
                style.getSourceAs<GeoJsonSource>(PLACE_CIRCLE_SOURCE)
                    ?.setGeoJson(circleFeature(center, radiusM))
                framePlace(map, center, radiusM)
            }
            if (applied[1] != Triple(endpoints, neighbors, showInternals)) {
                applied[1] = Triple(endpoints, neighbors, showInternals)
                style.getSourceAs<GeoJsonSource>(PLACE_MARKER_SOURCE)
                    ?.setGeoJson(placeMarkerCollection(center, endpoints, neighbors, showInternals))
            }
            if (applied[2] != showInternals) {
                applied[2] = showInternals
                val visibility = PropertyFactory.visibility(
                    if (showInternals) Property.VISIBLE else Property.NONE,
                )
                style.getLayer(PLACE_CIRCLE_FILL)?.setProperties(visibility)
                style.getLayer(PLACE_CIRCLE_LINE)?.setProperties(visibility)
            }
        },
    )
}

private const val PLACE_CIRCLE_SOURCE = "place-circle-src"
private const val PLACE_CIRCLE_FILL = "place-circle-fill"
private const val PLACE_CIRCLE_LINE = "place-circle-line"
private const val PLACE_MARKER_SOURCE = "place-marker-src"
private const val PLACE_MARKER_LAYER = "place-marker-layer"
private const val IMG_ENDPOINT = "marker-endpoint"
private const val IMG_ENDPOINT_BRIEF = "marker-endpoint-brief"
private const val IMG_NEIGHBOR = "marker-neighbor"
private const val IMG_PLACE = "marker-place"
private const val CIRCLE_FILL = 0x2E5B9BF0
private const val CIRCLE_LINE = 0x995B9BF0.toInt()

private fun addPlaceLayers(
    ctx: Context,
    style: Style,
    center: StayDeriver.Endpoint,
    radiusM: Double,
    endpoints: List<StayDeriver.Endpoint>,
    neighbors: List<NeighborPlace>,
    showInternals: Boolean,
) {
    val visibility = PropertyFactory.visibility(if (showInternals) Property.VISIBLE else Property.NONE)
    style.addSource(GeoJsonSource(PLACE_CIRCLE_SOURCE, circleFeature(center, radiusM)))
    addCaptureCircleLayers(style, PLACE_CIRCLE_SOURCE, PLACE_CIRCLE_FILL, PLACE_CIRCLE_LINE, visibility)
    style.addImage(IMG_ENDPOINT, drawableBitmap(ctx, R.drawable.ic_marker_endpoint))
    style.addImage(IMG_NEIGHBOR, drawableBitmap(ctx, R.drawable.ic_marker_neighbor))
    style.addImage(IMG_PLACE, drawableBitmap(ctx, R.drawable.ic_marker_place))
    style.addSource(
        GeoJsonSource(PLACE_MARKER_SOURCE, placeMarkerCollection(center, endpoints, neighbors, showInternals)),
    )
    style.addLayer(labeledSymbolLayer(ctx, PLACE_MARKER_LAYER, PLACE_MARKER_SOURCE))
}

/**
 * Neighbor context first (visually underneath), then the place's own endpoint dots, then the
 * pin marker last so it draws on top. Without [showInternals] only the pin is emitted.
 */
private fun placeMarkerCollection(
    center: StayDeriver.Endpoint,
    endpoints: List<StayDeriver.Endpoint>,
    neighbors: List<NeighborPlace>,
    showInternals: Boolean,
): FeatureCollection {
    val features = ArrayList<Feature>(neighbors.size + endpoints.size + 1)
    if (showInternals) {
        neighbors.forEach { n ->
            val icon = if (n.label != null) IMG_PLACE else IMG_NEIGHBOR
            features.add(endpointFeature(n.location, icon, n.label))
        }
        endpoints.forEach { features.add(endpointFeature(it, IMG_ENDPOINT)) }
    }
    features.add(endpointFeature(center, IMG_PLACE))
    return FeatureCollection.fromFeatures(features)
}

private fun endpointFeature(e: StayDeriver.Endpoint, icon: String, label: String? = null): Feature =
    Feature.fromGeometry(
        Point.fromLngLat(e.lon, e.lat),
        JsonObject().apply {
            addProperty("icon", icon)
            addProperty("label", label ?: "")
        },
    )

/** A meter-true circle approximated by a 72-gon (fine at place zoom levels). */
private fun circleFeature(center: StayDeriver.Endpoint, radiusM: Double): Feature {
    val ring = (0..72).map { i ->
        val theta = 2 * Math.PI * i / 72
        val (lat, lon) = offsetMeters(center, radiusM * sin(theta), radiusM * cos(theta))
        Point.fromLngLat(lon, lat)
    }
    return Feature.fromGeometry(Polygon.fromLngLats(listOf(ring)))
}

/** [e] displaced by meters north/east into a (lat, lon) pair — flat-earth, fine at circle scale. */
private fun offsetMeters(e: StayDeriver.Endpoint, northM: Double, eastM: Double): Pair<Double, Double> {
    val lat = e.lat + northM / 111_320.0
    val lon = e.lon + eastM / (111_320.0 * cos(Math.toRadians(e.lat)))
    return lat to lon
}

// --- All-places overview map ---------------------------------------------------------------

/** One place on the overview map; tapping its marker reports [key] back. */
class OverviewPlace(
    val location: StayDeriver.Endpoint,
    /** Named places render as labeled pins; null = an unnamed cluster dot. */
    val label: String?,
    /** The place-detail key reported on tap. */
    val key: String,
    /** The place's only stay is a merge-eligible short stop (a likely split-track artifact,
     *  not a real visit) — unnamed dots render orange instead of blue. */
    val brief: Boolean = false,
)

/**
 * Every place on one map: labeled amber pins for named places, small dots for unnamed clusters,
 * framed to fit them all once on open. Tapping a marker reports its key via [onOpen].
 */
@Composable
fun MapLibrePlacesMap(
    places: List<OverviewPlace>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val applied = remember { arrayOfNulls<Any?>(1) }
    // The click listener is registered once; route through a ref so it never goes stale.
    val onOpenRef = remember { arrayOf(onOpen) }
    onOpenRef[0] = onOpen
    MapLibreStyledMap(
        modifier = modifier,
        onMapReady = { map ->
            map.addOnMapClickListener { latLng ->
                val screen = map.projection.toScreenLocation(latLng)
                val touch = RectF(screen.x - 36, screen.y - 36, screen.x + 36, screen.y + 36)
                val key = map.queryRenderedFeatures(touch, OVERVIEW_LAYER)
                    .firstOrNull()?.getStringProperty("key")
                if (key != null) onOpenRef[0](key)
                key != null
            }
        },
        onStyleLoaded = { ctx, map, style ->
            applied[0] = places
            addOverviewLayers(ctx, style, places)
            frameTo(map, places.map { LatLng(it.location.lat, it.location.lon) }, singlePointZoom = 13.0)
        },
        onUpdate = { _, style ->
            if (applied[0] !== places) {
                applied[0] = places
                style.getSourceAs<GeoJsonSource>(OVERVIEW_SOURCE)
                    ?.setGeoJson(overviewCollection(places))
            }
        },
    )
}

private const val OVERVIEW_SOURCE = "places-overview-src"
private const val OVERVIEW_LAYER = "places-overview-layer"

private fun addOverviewLayers(ctx: Context, style: Style, places: List<OverviewPlace>) {
    style.addImage(IMG_ENDPOINT, drawableBitmap(ctx, R.drawable.ic_marker_endpoint))
    style.addImage(IMG_ENDPOINT_BRIEF, drawableBitmap(ctx, R.drawable.ic_marker_endpoint_brief))
    style.addImage(IMG_PLACE, drawableBitmap(ctx, R.drawable.ic_marker_place))
    style.addSource(GeoJsonSource(OVERVIEW_SOURCE, overviewCollection(places)))
    style.addLayer(labeledSymbolLayer(ctx, OVERVIEW_LAYER, OVERVIEW_SOURCE))
}

private fun overviewCollection(places: List<OverviewPlace>): FeatureCollection =
    FeatureCollection.fromFeatures(
        // Unnamed dots first so named pins draw (and hit-test) on top.
        places.sortedBy { it.label != null }.map { p ->
            Feature.fromGeometry(
                Point.fromLngLat(p.location.lon, p.location.lat),
                JsonObject().apply {
                    addProperty(
                        "icon",
                        when {
                            p.label != null -> IMG_PLACE
                            p.brief -> IMG_ENDPOINT_BRIEF
                            else -> IMG_ENDPOINT
                        },
                    )
                    addProperty("label", p.label ?: "")
                    addProperty("key", p.key)
                },
            )
        },
    )

private fun framePlace(map: MapLibreMap, center: StayDeriver.Endpoint, radiusM: Double) {
    val (north, _) = offsetMeters(center, radiusM, 0.0)
    val (south, _) = offsetMeters(center, -radiusM, 0.0)
    val (_, east) = offsetMeters(center, 0.0, radiusM)
    val (_, west) = offsetMeters(center, 0.0, -radiusM)
    val bounds = LatLngBounds.Builder()
        .include(LatLng(north, east))
        .include(LatLng(south, west))
        .build()
    map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 64))
}
