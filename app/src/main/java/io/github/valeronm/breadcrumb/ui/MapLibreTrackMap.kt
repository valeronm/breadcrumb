package io.github.valeronm.breadcrumb.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.appcompat.content.res.AppCompatResources
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.valeronm.breadcrumb.BuildConfig
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.ActivityType
import io.github.valeronm.breadcrumb.data.IgnoreReason
import io.github.valeronm.breadcrumb.data.TrackQuality
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.domain.StayDeriver
import com.google.gson.JsonObject
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
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Renders a track on a Protomaps dark vector basemap via MapLibre GL Native. The line is coloured by
 * [colorMode] via a MapLibre `line-gradient` built from [TrackColoring]'s per-point colours; start/end
 * and noisy-fix markers sit on a symbol layer, and the camera fits the track once on open. Switching
 * the colour mode updates the gradient in place without moving the camera; the source is refreshed
 * when the point list grows (the live "current track" preview), which re-frames only when the
 * current position nears the viewport edge — user pan/zoom survives otherwise.
 */
@Composable
fun MapLibreTrackMap(
    points: List<TrackPoint>,
    noisyPoints: List<TrackPoint> = emptyList(),
    activity: ActivityType? = null,
    colorMode: ColorMode = ColorMode.SPEED,
    showLegend: Boolean = false,
    selectedPoint: TrackPoint? = null,
    // Live preview: the last point is the current position — a droplet rotated to the movement
    // bearing instead of the finished-track end dot.
    directionalEnd: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val coloring = remember(points, colorMode, activity) {
        trackColoring(points, TrackQuality.pointSpeedsKmh(points), colorMode, activity)
    }
    val paint = remember(points, coloring) { buildTrackPaint(points, coloring.colors) }
    val mapView = rememberMapLibreMapView()
    val mapRef = remember(mapView) { arrayOfNulls<MapLibreMap>(1) }
    val inited = remember(mapView) { booleanArrayOf(false) }
    // Frame once per map; later updates (colour switches, live point growth) must not move the
    // camera — the live preview re-frames only when the current position nears the viewport edge.
    val framed = remember(mapView) { booleanArrayOf(false) }
    // What each source/layer was last fed, so unrelated recompositions (e.g. the graph scrubber
    // moving the selection) don't re-serialize the full track geometry into the native map.
    val applied = remember(mapView) { arrayOfNulls<Any?>(4) } // points, noisy, paint, selection

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { mapView },
            update = { view ->
                if (!inited[0]) {
                    inited[0] = true
                    view.getMapAsync { map ->
                        mapRef[0] = map
                        map.setStyle(Style.Builder().fromJson(loadProtomapsDarkStyle(view.context))) { style ->
                            addTrackLine(style, points, paint)
                            addMarkers(view.context, style, points, noisyPoints, directionalEnd)
                            addSelectionLayer(view.context, style, selectedPoint)
                            frameTo(map, points)
                            framed[0] = true
                        }
                    }
                } else {
                    // Recolour on colour-mode change; also refresh geometry when the track grows (the
                    // live "current track" preview). Re-frame only when the points changed (not on a
                    // colour switch), so a colour change keeps the user's pan/zoom.
                    val map = mapRef[0]
                    val style = map?.style
                    if (style != null) {
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
                                        frameTo(map, points, headroom = 1.2)
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
                        if (!framed[0]) {
                            frameTo(map, points)
                            framed[0] = true
                        }
                    }
                }
            },
        )
        if (showLegend) {
            // Bottom-right: MapLibre's logo + attribution live bottom-left.
            TrackLegend(coloring.legend, Modifier.align(Alignment.BottomEnd).padding(12.dp))
        }
    }
}

/** A MapLibre [MapView] whose lifecycle follows the composition's [LifecycleOwner]. */
@Composable
private fun rememberMapLibreMapView(): MapView {
    val ctx = LocalContext.current
    val mapView = remember {
        MapLibre.getInstance(ctx)
        // Texture mode instead of the default SurfaceView: a SurfaceView composites in its own
        // layer and ignores Compose clipping, so it would bleed over rounded card corners. The
        // cards' side padding also keeps the map out of the back-gesture edge strips, so no
        // edge-swipe handling is needed on the view itself.
        val options = MapLibreMapOptions.createFromAttributes(ctx).textureMode(true)
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

private fun trackLineFeature(points: List<TrackPoint>): Feature =
    Feature.fromGeometry(LineString.fromLngLats(points.map { Point.fromLngLat(it.longitude, it.latitude) }))

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

// Noisy markers are colour-coded by why the fix was rejected; points recorded before reasons
// were tracked (null) fall back to the generic accuracy colour.
private fun noisyIcon(p: TrackPoint): String = when (IgnoreReason.fromCode(p.ignoreReason)) {
    IgnoreReason.JUMP -> IMG_NOISY_JUMP
    IgnoreReason.NO_GNSS -> IMG_NOISY_GNSS
    IgnoreReason.ACCURACY, null -> IMG_NOISY
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
    style.addLayer(
        SymbolLayer(MARKER_LAYER, MARKER_SOURCE).withProperties(
            PropertyFactory.iconImage(Expression.get("icon")),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
            // Rotate with the map so the droplet keeps pointing along the ground-track bearing.
            PropertyFactory.iconRotate(Expression.get("bearing")),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
        ),
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
                if (bearing != null) markerFeature(it, IMG_POINTER, bearing)
                else markerFeature(it, IMG_SELECTED)
            },
        ),
    )

private fun addSelectionLayer(ctx: Context, style: Style, selected: TrackPoint?) {
    style.addImage(IMG_SELECTED, drawableBitmap(ctx, R.drawable.ic_marker_selected))
    style.addImage(IMG_POINTER, drawableBitmap(ctx, R.drawable.ic_marker_pointer))
    style.addSource(GeoJsonSource(SELECT_SOURCE, selectionCollection(selected)))
    style.addLayer(
        SymbolLayer(SELECT_LAYER, SELECT_SOURCE).withProperties(
            PropertyFactory.iconImage(Expression.get("icon")),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
            PropertyFactory.iconRotate(Expression.get("bearing")),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
        ),
    )
}

private fun markerFeature(p: TrackPoint, icon: String, bearing: Float = 0f): Feature =
    Feature.fromGeometry(
        Point.fromLngLat(p.longitude, p.latitude),
        JsonObject().apply {
            addProperty("icon", icon)
            addProperty("bearing", bearing)
        },
    )

/** Whether ([lat], [lon]) sits within the central [fraction] of these bounds. */
private fun LatLngBounds.containsWithMargin(lat: Double, lon: Double, fraction: Double = 0.8): Boolean {
    val centerLat = (latitudeNorth + latitudeSouth) / 2
    val centerLon = (longitudeEast + longitudeWest) / 2
    val halfLat = (latitudeNorth - latitudeSouth) / 2 * fraction
    val halfLon = (longitudeEast - longitudeWest) / 2 * fraction
    return lat in (centerLat - halfLat)..(centerLat + halfLat) &&
        lon in (centerLon - halfLon)..(centerLon + halfLon)
}

/**
 * [headroom] > 1 zooms out beyond the exact fit (half-spans scaled around the centre). The live
 * re-fit needs it: a tight fit puts the current position right back at the viewport edge, so the
 * very next fix would trigger another re-frame.
 */
private fun frameTo(map: MapLibreMap, points: List<TrackPoint>, headroom: Double = 1.0) {
    when {
        // moveCamera (not easeCamera): the map should open already framed, with no zoom animation.
        points.size >= 2 -> {
            val b = LatLngBounds.Builder()
            points.forEach { b.include(LatLng(it.latitude, it.longitude)) }
            var bounds = b.build()
            if (headroom > 1.0) {
                val centerLat = (bounds.latitudeNorth + bounds.latitudeSouth) / 2
                val centerLon = (bounds.longitudeEast + bounds.longitudeWest) / 2
                val halfLat = (bounds.latitudeNorth - bounds.latitudeSouth) / 2 * headroom
                val halfLon = (bounds.longitudeEast - bounds.longitudeWest) / 2 * headroom
                bounds = LatLngBounds.from(
                    centerLat + halfLat, centerLon + halfLon,
                    centerLat - halfLat, centerLon - halfLon,
                )
            }
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 96))
        }
        points.size == 1 -> map.cameraPosition = CameraPosition.Builder()
            .target(LatLng(points[0].latitude, points[0].longitude)).zoom(15.0).build()
    }
}

/** The line's paint for the current colour mode: a per-distance gradient, or a solid fallback. */
private sealed interface TrackPaint {
    data class Gradient(val expression: Expression) : TrackPaint
    data class Solid(val color: Int) : TrackPaint
}

/**
 * Builds a MapLibre `line-gradient` from per-point [colors] by placing each point's colour at its
 * cumulative-distance fraction along the line (0..1) — the parity port of osmdroid's per-vertex
 * paint list. Falls back to a solid colour for a track with no length.
 */
private fun buildTrackPaint(points: List<TrackPoint>, colors: IntArray): TrackPaint {
    if (points.size < 2 || colors.isEmpty()) return TrackPaint.Solid(colors.firstOrNull() ?: DEFAULT_LINE)
    val cumulative = DoubleArray(points.size)
    for (i in 1 until points.size) {
        cumulative[i] = cumulative[i - 1] + haversineMeters(points[i - 1], points[i])
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

private fun haversineMeters(a: TrackPoint, b: TrackPoint): Double {
    val r = 6_371_000.0
    val la1 = Math.toRadians(a.latitude)
    val la2 = Math.toRadians(b.latitude)
    val dLa = Math.toRadians(b.latitude - a.latitude)
    val dLo = Math.toRadians(b.longitude - a.longitude)
    val h = sin(dLa / 2) * sin(dLa / 2) + cos(la1) * cos(la2) * sin(dLo / 2) * sin(dLo / 2)
    return 2 * r * asin(sqrt(h))
}

private fun drawableBitmap(ctx: Context, resId: Int): Bitmap {
    val d = AppCompatResources.getDrawable(ctx, resId)!!
    val w = d.intrinsicWidth.coerceAtLeast(1)
    val h = d.intrinsicHeight.coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    d.setBounds(0, 0, w, h)
    d.draw(Canvas(bmp))
    return bmp
}

/** The bundled official Protomaps dark style (assets/protomaps-dark.json) with the hosted-API key injected. */
private fun loadProtomapsDarkStyle(ctx: Context): String =
    ctx.assets.open("protomaps-dark.json").bufferedReader().use { it.readText() }
        .replace("{PROTOMAPS_KEY}", BuildConfig.PROTOMAPS_API_KEY)

// --- Place map ----------------------------------------------------------------------------------

/** A neighbouring cluster shown for context on the place map. */
class NeighborPlace(
    val location: StayDeriver.Endpoint,
    /** Named neighbours render as a labelled pin; null = a plain neighbour endpoint dot. */
    val label: String? = null,
)

/**
 * Renders one place on the dark basemap: the cluster's capture circle (metre-true polygon around
 * [center]) with the pin marker, every track endpoint the cluster captured as small dots, and
 * [neighbors] — surrounding clusters' endpoints (grey dots) and named pins (labelled) — so the
 * radius can be judged against what a wider circle would swallow. The camera fits the circle once
 * on open; the place data is a snapshot, so there is no live update path beyond a full refresh
 * when the inputs change.
 */
@Composable
fun MapLibrePlaceMap(
    center: StayDeriver.Endpoint,
    radiusM: Double,
    endpoints: List<StayDeriver.Endpoint>,
    neighbors: List<NeighborPlace> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val mapView = rememberMapLibreMapView()
    val mapRef = remember(mapView) { arrayOfNulls<MapLibreMap>(1) }
    val inited = remember(mapView) { booleanArrayOf(false) }
    val applied = remember(mapView) { arrayOfNulls<Any?>(2) } // circle (center+radius), markers
    AndroidView(
        modifier = modifier,
        factory = { mapView },
        update = { view ->
            if (!inited[0]) {
                inited[0] = true
                view.getMapAsync { map ->
                    mapRef[0] = map
                    map.setStyle(Style.Builder().fromJson(loadProtomapsDarkStyle(view.context))) { style ->
                        applied[0] = center to radiusM
                        applied[1] = endpoints to neighbors
                        addPlaceLayers(view.context, style, center, radiusM, endpoints, neighbors)
                        framePlace(map, center, radiusM)
                    }
                }
            } else {
                val map = mapRef[0]
                val style = map?.style ?: return@AndroidView
                if (applied[0] != center to radiusM) {
                    applied[0] = center to radiusM
                    style.getSourceAs<GeoJsonSource>(PLACE_CIRCLE_SOURCE)
                        ?.setGeoJson(circleFeature(center, radiusM))
                    framePlace(map, center, radiusM)
                }
                if (applied[1] != endpoints to neighbors) {
                    applied[1] = endpoints to neighbors
                    style.getSourceAs<GeoJsonSource>(PLACE_MARKER_SOURCE)
                        ?.setGeoJson(placeMarkerCollection(center, endpoints, neighbors))
                }
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
) {
    style.addSource(GeoJsonSource(PLACE_CIRCLE_SOURCE, circleFeature(center, radiusM)))
    style.addLayer(
        FillLayer(PLACE_CIRCLE_FILL, PLACE_CIRCLE_SOURCE).withProperties(
            PropertyFactory.fillColor(CIRCLE_FILL),
        ),
    )
    style.addLayer(
        LineLayer(PLACE_CIRCLE_LINE, PLACE_CIRCLE_SOURCE).withProperties(
            PropertyFactory.lineColor(CIRCLE_LINE),
            PropertyFactory.lineWidth(1.5f),
            PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
        ),
    )
    style.addImage(IMG_ENDPOINT, drawableBitmap(ctx, R.drawable.ic_marker_endpoint))
    style.addImage(IMG_NEIGHBOR, drawableBitmap(ctx, R.drawable.ic_marker_neighbor))
    style.addImage(IMG_PLACE, drawableBitmap(ctx, R.drawable.ic_marker_place))
    style.addSource(GeoJsonSource(PLACE_MARKER_SOURCE, placeMarkerCollection(center, endpoints, neighbors)))
    style.addLayer(
        SymbolLayer(PLACE_MARKER_LAYER, PLACE_MARKER_SOURCE).withProperties(
            PropertyFactory.iconImage(Expression.get("icon")),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
            // Named neighbours carry a label under the pin; other features have an empty string.
            PropertyFactory.textField(Expression.get("label")),
            PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
            PropertyFactory.textSize(12f),
            PropertyFactory.textColor("#C8CFC6"),
            PropertyFactory.textHaloColor("#14211A"),
            PropertyFactory.textHaloWidth(1.2f),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textOffset(arrayOf(0f, 0.8f)),
            PropertyFactory.textOptional(true),
        ),
    )
}

/**
 * Neighbour context first (visually underneath), then the place's own endpoint dots, then the
 * pin marker last so it draws on top.
 */
private fun placeMarkerCollection(
    center: StayDeriver.Endpoint,
    endpoints: List<StayDeriver.Endpoint>,
    neighbors: List<NeighborPlace>,
): FeatureCollection {
    val features = ArrayList<Feature>(neighbors.size + endpoints.size + 1)
    neighbors.forEach { n ->
        val icon = if (n.label != null) IMG_PLACE else IMG_NEIGHBOR
        features.add(endpointFeature(n.location, icon, n.label))
    }
    endpoints.forEach { features.add(endpointFeature(it, IMG_ENDPOINT)) }
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

/** A metre-true circle approximated by a 72-gon (fine at place zoom levels). */
private fun circleFeature(center: StayDeriver.Endpoint, radiusM: Double): Feature {
    val ring = (0..72).map { i ->
        val theta = 2 * Math.PI * i / 72
        val (lat, lon) = offsetMeters(center, radiusM * sin(theta), radiusM * cos(theta))
        Point.fromLngLat(lon, lat)
    }
    return Feature.fromGeometry(Polygon.fromLngLats(listOf(ring)))
}

/** ([lat], [lon]) displaced by metres north/east — flat-earth, fine at circle scale. */
private fun offsetMeters(e: StayDeriver.Endpoint, northM: Double, eastM: Double): Pair<Double, Double> {
    val lat = e.lat + northM / 111_320.0
    val lon = e.lon + eastM / (111_320.0 * cos(Math.toRadians(e.lat)))
    return lat to lon
}

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
