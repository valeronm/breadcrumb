package io.github.valeronm.breadcrumb.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.view.MotionEvent
import android.view.WindowInsets
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
import com.google.gson.JsonObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
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
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Renders a track on a Protomaps dark vector basemap via MapLibre GL Native. The line is coloured by
 * [colorMode] via a MapLibre `line-gradient` built from [TrackColoring]'s per-point colours; start/end
 * and noisy-fix markers sit on a symbol layer, and the camera fits the track once on open. Switching
 * the colour mode updates the gradient in place without moving the camera; the source is refreshed
 * when the point list grows (the live "current track" preview).
 */
@Composable
fun MapLibreTrackMap(
    points: List<TrackPoint>,
    noisyPoints: List<TrackPoint> = emptyList(),
    activity: ActivityType? = null,
    colorMode: ColorMode = ColorMode.SPEED,
    showLegend: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val coloring = remember(points, colorMode, activity) {
        trackColoring(points, TrackQuality.pointSpeedsKmh(points), colorMode, activity)
    }
    val paint = remember(points, coloring) { buildTrackPaint(points, coloring.colors) }
    val mapView = rememberMapLibreMapView()
    val mapRef = remember(mapView) { arrayOfNulls<MapLibreMap>(1) }
    val inited = remember(mapView) { booleanArrayOf(false) }
    // Frame once per track; later paint updates (colour-mode switches) must not move the camera.
    val framed = remember(points) { booleanArrayOf(false) }

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
                            addMarkers(view.context, style, points, noisyPoints)
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
                        style.getSourceAs<GeoJsonSource>(TRACK_SOURCE)?.setGeoJson(trackLineFeature(points))
                        style.getSourceAs<GeoJsonSource>(MARKER_SOURCE)?.setGeoJson(markerCollection(points, noisyPoints))
                        style.getLayerAs<LineLayer>(TRACK_LAYER)?.let { applyPaint(it, paint) }
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
private fun rememberMapLibreMapView(): EdgeAwareMapLibreMapView {
    val ctx = LocalContext.current
    val mapView = remember {
        MapLibre.getInstance(ctx)
        EdgeAwareMapLibreMapView(ctx).apply {
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

/**
 * A MapLibre [MapView] that declines touch gestures beginning within the system back-gesture edge
 * strips, so an edge-swipe triggers predictive back instead of panning the map.
 */
private class EdgeAwareMapLibreMapView(context: Context) : MapView(context) {
    private var ignoreGesture = false

    private fun edgeInsetPx(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            rootWindowInsets?.getInsets(WindowInsets.Type.systemGestures())?.let {
                val inset = maxOf(it.left, it.right)
                if (inset > 0) return inset
            }
        }
        return (24 * resources.displayMetrics.density).toInt()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val edge = edgeInsetPx()
            ignoreGesture = event.x <= edge || event.x >= width - edge
        }
        if (ignoreGesture) return false
        return super.dispatchTouchEvent(event)
    }
}

private const val TRACK_SOURCE = "track-src"
private const val TRACK_LAYER = "track-layer"
private const val MARKER_SOURCE = "marker-src"
private const val MARKER_LAYER = "marker-layer"
private const val IMG_START = "marker-start"
private const val IMG_END = "marker-end"
private const val IMG_NOISY = "marker-noisy"
private const val IMG_NOISY_JUMP = "marker-noisy-jump"
private const val IMG_NOISY_GNSS = "marker-noisy-gnss"
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

private fun markerCollection(points: List<TrackPoint>, noisyPoints: List<TrackPoint>): FeatureCollection {
    val features = ArrayList<Feature>()
    noisyPoints.forEach { features.add(markerFeature(it, noisyIcon(it))) }
    points.firstOrNull()?.let { features.add(markerFeature(it, IMG_START)) }
    points.lastOrNull()?.let { features.add(markerFeature(it, IMG_END)) }
    return FeatureCollection.fromFeatures(features)
}

private fun addMarkers(
    ctx: Context, style: Style, points: List<TrackPoint>, noisyPoints: List<TrackPoint>,
) {
    style.addImage(IMG_START, drawableBitmap(ctx, R.drawable.ic_marker_start))
    style.addImage(IMG_END, drawableBitmap(ctx, R.drawable.ic_marker_end))
    style.addImage(IMG_NOISY, drawableBitmap(ctx, R.drawable.ic_marker_noisy))
    style.addImage(IMG_NOISY_JUMP, drawableBitmap(ctx, R.drawable.ic_marker_noisy_jump))
    style.addImage(IMG_NOISY_GNSS, drawableBitmap(ctx, R.drawable.ic_marker_noisy_gnss))
    style.addSource(GeoJsonSource(MARKER_SOURCE, markerCollection(points, noisyPoints)))
    style.addLayer(
        SymbolLayer(MARKER_LAYER, MARKER_SOURCE).withProperties(
            PropertyFactory.iconImage(Expression.get("icon")),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
        ),
    )
}

private fun markerFeature(p: TrackPoint, icon: String): Feature =
    Feature.fromGeometry(
        Point.fromLngLat(p.longitude, p.latitude),
        JsonObject().apply { addProperty("icon", icon) },
    )

private fun frameTo(map: MapLibreMap, points: List<TrackPoint>) {
    when {
        // moveCamera (not easeCamera): the map should open already framed, with no zoom animation.
        points.size >= 2 -> {
            val b = LatLngBounds.Builder()
            points.forEach { b.include(LatLng(it.latitude, it.longitude)) }
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 96))
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
