package io.github.valeronm.breadcrumb.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.RectF
import android.util.Log
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.gson.JsonObject
import io.github.valeronm.breadcrumb.BuildConfig
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.TrackQuality
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.DwellDetector
import io.github.valeronm.breadcrumb.domain.EdgeStayIgnore
import io.github.valeronm.breadcrumb.domain.IgnoreReason
import io.github.valeronm.breadcrumb.domain.PlaceCategory
import io.github.valeronm.breadcrumb.domain.PlaceClusterer
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
import org.maplibre.android.offline.OfflineManager
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
 * Renders a track on a Protomaps vector basemap (MapLibre GL Native), dark or light flavor per the app
 * theme. The line is colored by [colorMode] via a `line-gradient` of [TrackColoring]'s per-point colors;
 * start/end and noisy-fix markers ride a symbol layer; the camera fits the track once on open. A
 * color-mode switch recolors in place, camera untouched; the source refreshes as the point list grows
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
    // Live preview: the last point is the current position — a droplet rotated to the movement
    // bearing instead of the finished-track end dot.
    directionalEnd: Boolean = false,
    // A coloring the caller already computed for the same inputs (the track-detail screen builds
    // one for its metric graph) — the O(points) pass then runs once, not twice.
    precomputedColoring: TrackColoring? = null,
    // …and the seam walk that coloring was built from ([TrackQuality.Seams]), which the gradient
    // below needs too. Null = walk it here (the live preview, which has no graph).
    precomputedSeams: TrackQuality.Seams? = null,
) {
    val darkTheme = isSystemInDarkTheme()
    val units = LocalUnits.current
    // Keyed on the points alone, unlike the coloring: seam distances don't depend on the metric,
    // so switching it must not re-walk them.
    val seams = remember(precomputedSeams, points) {
        precomputedSeams ?: TrackQuality.seams(points)
    }
    val coloring = remember(precomputedColoring, seams, colorMode, activity, darkTheme, units) {
        precomputedColoring
            ?: trackColoring(points, TrackQuality.pointSpeedsKmh(seams), colorMode, activity, darkTheme, units)
    }
    val paint = remember(seams, coloring) { buildTrackPaint(coloring.colors, seams) }
    val applied = remember { AppliedTrackInputs() }

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
                applied.framed = true
            },
            onUpdate = { map, style ->
                // Recolor on color-mode change; also refresh geometry when the track grows (the
                // live "current track" preview). Re-frame only when the points changed (not on a
                // color switch), so a color change keeps the user's pan/zoom.
                if (applied.points !== points || applied.noisy !== noisyPoints) {
                    applied.points = points
                    applied.noisy = noisyPoints
                    style.getSourceAs<GeoJsonSource>(TRACK_SOURCE)?.setGeoJson(trackLineFeature(points))
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
                if (applied.paint !== paint) {
                    applied.paint = paint
                    style.getLayerAs<LineLayer>(TRACK_LAYER)?.let { applyPaint(it, paint) }
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
    var paint: TrackPaint? = null
    var selection: TrackPoint? = null
    var dwells: List<DwellDetector.Dwell>? = null
    var overruns: List<EdgeStayIgnore.Overrun>? = null

    /** Frame once per map; later updates must not move the camera (the live preview re-frames
     *  only when the current position nears the viewport edge). */
    var framed = false
}

/**
 * Shared host for the map composables: owns the [MapView], loads the Protomaps style once, routes
 * later recompositions to [onUpdate], and runs [onMapReady] before the style loads (one-time map
 * setup like click listeners); callers keep their own last-applied state — this only removes boilerplate.
 */
@Composable
private fun MapLibreStyledMap(
    modifier: Modifier = Modifier,
    onMapReady: (MapLibreMap) -> Unit = {},
    onStyleLoaded: (Context, MapLibreMap, Style) -> Unit,
    onUpdate: (MapLibreMap, Style) -> Unit,
) {
    val mapView = rememberMapLibreMapView()
    val host = remember(mapView) { MapHost() }
    // The style loads asynchronously; inputs that arrive in the meantime recompose while the
    // style is still null, so their update is skipped. Route the callback through the host so the
    // load applies the *latest* composition's data, not what the first composition captured.
    host.onStyleLoaded = onStyleLoaded
    val zoom = remember { mutableFloatStateOf(0f) }
    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { mapView },
            update = { view ->
                if (!host.inited) {
                    host.inited = true
                    view.getMapAsync { map ->
                        host.map = map
                        val readZoom = { zoom.floatValue = map.cameraPosition.zoom.toFloat() }
                        if (BuildConfig.DEV_TOOLS) map.addOnCameraMoveListener(readZoom)
                        onMapReady(map)
                        map.setStyle(Style.Builder().fromJson(loadProtomapsStyle(view.context))) { style ->
                            host.onStyleLoaded(view.context, map, style)
                            // The opening frame is a moveCamera, which lands before this listener
                            // exists to hear it.
                            if (BuildConfig.DEV_TOOLS) readZoom()
                        }
                    }
                } else {
                    val map = host.map
                    val style = map?.style ?: return@AndroidView
                    onUpdate(map, style)
                }
            },
        )
        if (BuildConfig.DEV_TOOLS) {
            ZoomReadout(zoom, Modifier.align(Alignment.BottomEnd).padding(8.dp))
        }
    }
}

/**
 * The live camera zoom, dev builds only — every threshold on these maps (marker size ramp, when a
 * pin earns its glyph) is a zoom number, and they can't be judged against a map that won't say
 * which zoom it is at.
 *
 * It takes the state rather than its value so the read happens *here*: read a level up and every
 * camera frame would invalidate the map host, whose `update` lambda then re-runs each caller's
 * whole diff mid-pinch — in the `perf` build, which exists to measure exactly that.
 */
@Composable
private fun ZoomReadout(zoom: FloatState, modifier: Modifier = Modifier) {
    LegendSurface(modifier) {
        Text("z %.1f".format(zoom.floatValue), style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * Per-[MapView] mutable state held outside Compose's snapshot system — the map and its one-shot
 * init flag, plus the latest style-loaded callback. Same role as the `Applied*Inputs` holders
 * below: a remembered plain object, not a snapshot state, so writing it never recomposes.
 */
private class MapHost {
    var map: MapLibreMap? = null
    var inited = false
    var onStyleLoaded: (Context, MapLibreMap, Style) -> Unit = { _, _, _ -> }
}

/**
 * How much basemap MapLibre may keep in its ambient tile cache (`files/mbgl-offline.db`). The
 * library default (~a tenth of this) is a few screenfuls: Protomaps vector tiles average ~45 KiB,
 * so it fills at roughly a thousand and LRU-evicts what a session just viewed — reopening a track
 * an hour later re-downloads its tiles, and with map data the app's only network use, that
 * eviction *is* the data bill. This holds several times a day's worth of viewing. Nothing is
 * pinned: a ceiling raise, not an offline region — the cache stays opportunistic, and ground the
 * map has never shown still needs a connection.
 */
private const val AMBIENT_CACHE_BYTES = 250L * 1024 * 1024

/** Set once per process — the ceiling is a property of the shared file source, not of a map. */
@Volatile private var ambientCacheCeilingRaised = false

private fun raiseAmbientCacheCeiling(ctx: Context) {
    if (ambientCacheCeilingRaised) return
    ambientCacheCeilingRaised = true
    OfflineManager.getInstance(ctx.applicationContext)
        .setMaximumAmbientCacheSize(
            AMBIENT_CACHE_BYTES,
            object : OfflineManager.FileSourceCallback {
                override fun onSuccess() = Unit

                // Not fatal: the cache keeps working at whatever ceiling it already had.
                override fun onError(message: String) {
                    Log.w("Breadcrumb", "Could not raise the map tile cache ceiling: $message")
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
        raiseAmbientCacheCeiling(ctx)
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
    style.addImage(IMG_START, shadowedBitmap(ctx, R.drawable.ic_marker_start, MarkerShadow.SUBJECT_TURNING))
    style.addImage(IMG_END, shadowedBitmap(ctx, R.drawable.ic_marker_end, MarkerShadow.SUBJECT_TURNING))
    style.addImage(IMG_POINTER, shadowedBitmap(ctx, R.drawable.ic_marker_pointer, MarkerShadow.SUBJECT_TURNING))
    style.addImage(IMG_NOISY, shadowedBitmap(ctx, R.drawable.ic_marker_noisy, MarkerShadow.EVIDENCE_TURNING))
    style.addImage(IMG_NOISY_JUMP, shadowedBitmap(ctx, R.drawable.ic_marker_noisy_jump, MarkerShadow.EVIDENCE_TURNING))
    style.addImage(IMG_NOISY_GNSS, shadowedBitmap(ctx, R.drawable.ic_marker_noisy_gnss, MarkerShadow.EVIDENCE_TURNING))
    style.addSource(GeoJsonSource(MARKER_SOURCE, markerCollection(points, noisyPoints, directionalEnd)))
    style.addLayer(iconSymbolLayer(MARKER_LAYER, MARKER_SOURCE))
}

/**
 * Shared base of the marker layers: an icon per feature, drawn in source order — the load-bearing
 * part: left to itself a symbol layer stacks point symbols by screen position, so the lower marker
 * covers the rest, and both feeding collections end with the marker that matters most (a place's
 * pin among its dots, a track's start/end among rejected fixes). Overlap and placement are off
 * likewise: these are markers, not labels competing for room.
 */
private fun markerSymbolLayer(id: String, source: String): SymbolLayer =
    SymbolLayer(id, source).withProperties(
        PropertyFactory.symbolZOrder(Property.SYMBOL_Z_ORDER_SOURCE),
        PropertyFactory.iconImage(Expression.get(ICON_KEY)),
        PropertyFactory.iconAllowOverlap(true),
        PropertyFactory.iconIgnorePlacement(true),
        PropertyFactory.iconAnchor(Property.ICON_ANCHOR_CENTER),
    )

/** The track's marker and selection layers: markers that turn to face along the ground track. */
private fun iconSymbolLayer(id: String, source: String): SymbolLayer =
    markerSymbolLayer(id, source).withProperties(
        // Rotate with the map so the droplet keeps pointing along the ground-track bearing.
        PropertyFactory.iconRotate(Expression.get("bearing")),
        PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
    )

/** Labeled pin layer shared by the place and overview maps: a marker plus a label under it. */
private fun labeledSymbolLayer(ctx: Context, id: String, source: String): SymbolLayer {
    val dark = isDarkUi(ctx)
    return markerSymbolLayer(id, source).withProperties(
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
    style.addImage(IMG_SELECTED, shadowedBitmap(ctx, R.drawable.ic_marker_selected, MarkerShadow.SUBJECT_TURNING))
    style.addImage(IMG_POINTER, shadowedBitmap(ctx, R.drawable.ic_marker_pointer, MarkerShadow.SUBJECT_TURNING))
    style.addSource(GeoJsonSource(SELECT_SOURCE, selectionCollection(selected)))
    style.addLayer(iconSymbolLayer(SELECT_LAYER, SELECT_SOURCE))
}

private fun markerFeature(p: TrackPoint, icon: String, bearing: Float = 0f): Feature =
    Feature.fromGeometry(
        Point.fromLngLat(p.longitude, p.latitude),
        JsonObject().apply {
            addProperty(ICON_KEY, icon)
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
 * [headroom] > 1 zooms out beyond the exact fit (half-spans scaled around the center) — the live
 * re-fit needs it: a tight fit leaves the position at the viewport edge, re-framing on the next fix.
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
 * cumulative-distance fraction along the line (0..1). Falls back to a solid color for a track
 * with no length.
 */
private fun buildTrackPaint(colors: IntArray, seams: TrackQuality.Seams): TrackPaint {
    val points = seams.points
    if (points.size < 2 || colors.isEmpty()) return TrackPaint.Solid(colors.firstOrNull() ?: DEFAULT_LINE)
    val cumulative = DoubleArray(points.size)
    for (i in 1 until points.size) {
        cumulative[i] = cumulative[i - 1] + seams.meters[i]
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

/** Whether the UI is in dark mode — the single switch for basemap flavor and map ink colors. */
private fun isDarkUi(ctx: Context): Boolean =
    (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

private val BACKGROUND_COLOR_RE = Regex("\"background-color\":\\s*\"(#[0-9a-fA-F]{6})\"")

/** One flavor's resolved style: the key-injected JSON and its own background color. */
private class StyleFlavor(val asset: String, val json: String, val backgroundColor: Int)

/** The current flavor's resolved style, cached by asset name — read for every map creation. */
private var cachedStyle: StyleFlavor? = null

/**
 * The bundled official Protomaps style for the current theme (assets/protomaps-{dark,light}.json)
 * with the hosted-API key injected. The background color is scanned out on the same cache miss:
 * it is a constant of the flavor, and the document is ~268 KB — far too big to re-scan per map.
 */
private fun styleFlavor(ctx: Context): StyleFlavor {
    val asset = if (isDarkUi(ctx)) "protomaps-dark.json" else "protomaps-light.json"
    cachedStyle?.let { if (it.asset == asset) return it }
    val json = ctx.assets.open(asset).bufferedReader().use { it.readText() }
        .replace("{PROTOMAPS_KEY}", BuildConfig.PROTOMAPS_API_KEY)
    val background = BACKGROUND_COLOR_RE.find(json)
        ?.groupValues?.get(1)?.let(android.graphics.Color::parseColor)
        ?: android.graphics.Color.DKGRAY
    return StyleFlavor(asset, json, background).also { cachedStyle = it }
}

private fun loadProtomapsStyle(ctx: Context): String = styleFlavor(ctx).json

/**
 * The style's own `background` layer color — used as the pre-render placeholder so a style
 * refresh can't desync the load flash from the basemap.
 */
private fun styleBackgroundColor(ctx: Context): Int = styleFlavor(ctx).backgroundColor

// --- Place map ----------------------------------------------------------------------------------

/**
 * One dot on the place map whose fate the *map* decides: [distanceM] (distance from the pin) is
 * compared against the live radius in a layer expression; null means settled — a neighbor keeps it
 * whatever the radius does, drawn gray uncompared. Carrying the distance rather than a resolved
 * icon means a drag changes one layer property, not thousands of features — that per-step rebuild
 * of the collection is what lags.
 */
internal class CaptureDot(val location: StayDeriver.Endpoint, val distanceM: Double?)

/** A neighboring cluster shown for context on the place map. */
class NeighborPlace(
    val location: StayDeriver.Endpoint,
    /** Named neighbors render as a labeled pin; null = a plain neighbor endpoint dot. */
    val label: String? = null,
    /** The neighbor's own category — it wears its colors here, not this place's. */
    val category: PlaceCategory? = null,
)

/**
 * Renders one place on the basemap. With [showInternals] the cluster's capture circle (a meter-true
 * polygon around [center]) is drawn with every captured track endpoint as small dots plus
 * [neighbors] — gray neighbor-endpoint dots and labeled named pins — so the radius can be judged
 * against what a wider circle would swallow; without it only the pin shows. Toggling restyles in
 * place, camera untouched; the camera fits the circle once on open, and the data is a snapshot —
 * an input change is a full refresh.
 */
@Composable
internal fun MapLibrePlaceMap(
    center: StayDeriver.Endpoint,
    radiusM: Double,
    endpoints: List<StayDeriver.Endpoint>,
    category: PlaceCategory?,
    modifier: Modifier = Modifier,
    // False draws the centre as the cluster dot it is on the Places map, glyphless, rather than
    // promoting it to a pin: an unnamed cluster is the same thing on both screens. Untagged is not
    // unnamed — a named place with no category is still a pin, in the neutral.
    centerAsPin: Boolean = true,
    neighbors: List<NeighborPlace> = emptyList(),
    showInternals: Boolean = true,
    // When set, these dots replace [endpoints] and the plain neighbor dots, and the radius decides
    // each one's icon in a layer expression — so dragging costs one property, not a re-upload.
    capture: List<CaptureDot>? = null,
    // Named neighbors' own capture areas, drawn muted under this one. They are what stops a dot
    // joining this place, so seeing them is what makes a gray dot inside your circle make sense.
    rivalAreas: List<PlaceClusterer.Seed> = emptyList(),
) {
    val applied = remember { AppliedPlaceInputs() }
    val untaggedFill = placeDiscTint(null).toArgb()
    val placeContent = {
        PlaceMapContent(
            center = center,
            radiusM = radiusM,
            centerCategory = category,
            centerAsPin = centerAsPin,
            markers = PlaceMarkers(endpoints, neighbors, showInternals, capture),
            rivalAreas = rivalAreas,
        )
    }
    MapLibreStyledMap(
        modifier = modifier,
        onStyleLoaded = { ctx, map, style ->
            applied.circle = center to radiusM
            applied.markers = Triple(endpoints, neighbors, showInternals)
            applied.center = category to centerAsPin
            applied.capture = capture
            applied.rivalAreas = rivalAreas
            applied.internals = showInternals
            addPlaceLayers(ctx, style, placeContent(), untaggedFill)
            framePlace(map, center, radiusM)
        },
        onUpdate = { map, style ->
            if (applied.circle != center to radiusM) {
                applied.circle = center to radiusM
                style.getSourceAs<GeoJsonSource>(PLACE_CIRCLE_SOURCE)
                    ?.setGeoJson(circleFeature(center, radiusM))
                framePlace(map, center, radiusM)
                // The dots do not move; which side of the radius they fall on does.
                style.getLayer(PLACE_MARKER_LAYER)?.setProperties(markerIconProperty(radiusM))
            }
            if (applied.markers != Triple(endpoints, neighbors, showInternals) ||
                applied.capture !== capture ||
                applied.center != category to centerAsPin
            ) {
                applied.markers = Triple(endpoints, neighbors, showInternals)
                applied.capture = capture
                applied.center = category to centerAsPin
                style.getSourceAs<GeoJsonSource>(PLACE_MARKER_SOURCE)
                    ?.setGeoJson(placeMarkerCollection(placeContent()))
            }
            if (applied.rivalAreas !== rivalAreas) {
                applied.rivalAreas = rivalAreas
                style.getSourceAs<GeoJsonSource>(PLACE_RIVAL_SOURCE)
                    ?.setGeoJson(rivalAreaCollection(rivalAreas))
            }
            if (applied.internals != showInternals) {
                applied.internals = showInternals
                val visibility = PropertyFactory.visibility(
                    if (showInternals) Property.VISIBLE else Property.NONE,
                )
                for (id in PLACE_CIRCLE_LAYERS) style.getLayer(id)?.setProperties(visibility)
            }
        },
    )
}

/** What the marker layer draws — four inputs that always travel together. */
private class PlaceMarkers(
    val endpoints: List<StayDeriver.Endpoint>,
    val neighbors: List<NeighborPlace>,
    val showInternals: Boolean,
    val capture: List<CaptureDot>?,
)

/** Everything the place map draws: its own area, the markers, and the areas it competes with. */
private class PlaceMapContent(
    val center: StayDeriver.Endpoint,
    val radiusM: Double,
    val centerCategory: PlaceCategory?,
    val centerAsPin: Boolean,
    val markers: PlaceMarkers,
    val rivalAreas: List<PlaceClusterer.Seed>,
)

/** Last-applied inputs of the place map — value comparisons, the inputs are rebuilt lists. */
private class AppliedPlaceInputs {
    var circle: Pair<StayDeriver.Endpoint, Double>? = null
    var markers: Triple<List<StayDeriver.Endpoint>, List<NeighborPlace>, Boolean>? = null

    /** What the centre marker draws as. Tagging a place while its map is open recolors the pin —
     *  the marker source names an image per category, so a re-tag is a feature rebuild, not a
     *  restyle — and naming one promotes it from a dot. */
    var center: Pair<PlaceCategory?, Boolean>? = null

    /** Identity, not equality: the scan hands back a new list only when the candidates change. */
    var capture: List<CaptureDot>? = null
    var rivalAreas: List<PlaceClusterer.Seed>? = null
    var internals: Boolean? = null
}

/** The place-circle layers, toggled together with the rest of the edit-mode internals. */
private val PLACE_CIRCLE_LAYERS = listOf(
    PLACE_RIVAL_FILL, PLACE_RIVAL_LINE, PLACE_CIRCLE_FILL, PLACE_CIRCLE_LINE,
)

/** Faint enough to read as context rather than as a second thing being edited. */
private const val RIVAL_AREA_OPACITY = 0.35f

private const val PLACE_RIVAL_SOURCE = "place-rival-src"
private const val PLACE_RIVAL_FILL = "place-rival-fill"
private const val PLACE_RIVAL_LINE = "place-rival-line"

private const val PLACE_CIRCLE_SOURCE = "place-circle-src"
private const val PLACE_CIRCLE_FILL = "place-circle-fill"
private const val PLACE_CIRCLE_LINE = "place-circle-line"

/** Feature property names shared by the marker features and the icon expressions above. */
private const val ICON_KEY = "icon"
private const val DISTANCE_KEY = "dm"

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
private const val CIRCLE_FILL = 0x2E5B9BF0
private const val CIRCLE_LINE = 0x995B9BF0.toInt()

private fun addPlaceLayers(ctx: Context, style: Style, content: PlaceMapContent, untaggedFill: Int) {
    val visibility =
        PropertyFactory.visibility(if (content.markers.showInternals) Property.VISIBLE else Property.NONE)
    // Rivals first, so this place's own circle reads on top of them where they overlap.
    style.addSource(GeoJsonSource(PLACE_RIVAL_SOURCE, rivalAreaCollection(content.rivalAreas)))
    addCaptureCircleLayers(
        style, PLACE_RIVAL_SOURCE, PLACE_RIVAL_FILL, PLACE_RIVAL_LINE, visibility,
        PropertyFactory.fillOpacity(RIVAL_AREA_OPACITY),
        PropertyFactory.lineOpacity(RIVAL_AREA_OPACITY),
    )
    style.addSource(GeoJsonSource(PLACE_CIRCLE_SOURCE, circleFeature(content.center, content.radiusM)))
    addCaptureCircleLayers(style, PLACE_CIRCLE_SOURCE, PLACE_CIRCLE_FILL, PLACE_CIRCLE_LINE, visibility)
    style.addImage(IMG_ENDPOINT, shadowedBitmap(ctx, R.drawable.ic_marker_endpoint, MarkerShadow.EVIDENCE))
    style.addImage(IMG_NEIGHBOR, shadowedBitmap(ctx, R.drawable.ic_marker_neighbor, MarkerShadow.EVIDENCE))
    // This map holds one place at the zoom its own radius frames, so its pins are never the
    // small form: full size, glyphed, and no disc variant to carry.
    addPlacePinImages(ctx, style, untaggedFill, PIN_MAX_SCALE, glyphedOnly = true)
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
 * pin marker last so it draws on top. Without [showInternals] only the pin is emitted.
 */
private fun placeMarkerCollection(content: PlaceMapContent): FeatureCollection {
    val (endpoints, neighbors, capture) = content.markers.let {
        Triple(it.endpoints, it.neighbors, it.capture)
    }
    val features = ArrayList<Feature>(neighbors.size + endpoints.size + 1)
    if (content.markers.showInternals) {
        // Named neighbors are pins in both modes; their plain dots are only drawn when the
        // capture form isn't, because there they are already among the candidates.
        neighbors.forEach { n ->
            if (capture != null && n.label == null) return@forEach
            val icon = if (n.label != null) {
                placePinImage(n.category, withGlyph = true)
            } else {
                IMG_NEIGHBOR
            }
            features.add(endpointFeature(n.location, icon, n.label))
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
            endpoints.forEach { features.add(endpointFeature(it, IMG_ENDPOINT)) }
        }
    }
    val centerIcon = if (content.centerAsPin) {
        placePinImage(content.centerCategory, withGlyph = true)
    } else {
        IMG_ENDPOINT
    }
    features.add(endpointFeature(content.center, centerIcon))
    return FeatureCollection.fromFeatures(features)
}

private fun endpointFeature(
    e: StayDeriver.Endpoint,
    icon: String,
    label: String? = null,
    distanceM: Double? = null,
): Feature =
    Feature.fromGeometry(
        Point.fromLngLat(e.lon, e.lat),
        JsonObject().apply {
            addProperty(ICON_KEY, icon)
            addProperty("label", label ?: "")
            distanceM?.let { addProperty(DISTANCE_KEY, it) }
        },
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

/** Every named neighbor's capture area, as its own polygon. Empty is a valid, common answer. */
private fun rivalAreaCollection(rivals: List<PlaceClusterer.Seed>): FeatureCollection =
    FeatureCollection.fromFeatures(rivals.map { circleFeature(it.anchor, it.radiusM) })

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
    /** What the place is for — the pin's color, and its glyph up close. Null reads as untagged,
     *  which is a state of a *named* place; an unnamed cluster is a dot and has no category. */
    val category: PlaceCategory? = null,
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
    val untaggedFill = placeDiscTint(null).toArgb()
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
            addOverviewLayers(ctx, style, places, untaggedFill)
            frameTo(map, places.map { LatLng(it.location.lat, it.location.lon) }, singlePointZoom = 13.0)
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
 * A pin at full size, as a multiple of its base size — the one size a pin is ever *drawn* at, which
 * every other size is a scaling down of. It is the overview ramp's top stop and the place map's only
 * size, so the pin a place opens onto is the same pin the map it was opened from was showing.
 *
 * Raising it lifts the whole ramp, the stops being fractions of the bitmap; the stops below the top
 * are set back down to hold the sizes a wide view had, so the extra is spent where a glyph is read.
 */
private const val PIN_MAX_SCALE = 1.55f

/**
 * The zoom from which a pin carries its category's glyph. Below it the pin is a colored disc — the
 * color survives being scaled down where a 24-unit glyph does not. It is also the ramp's middle
 * stop, and the stop is set high enough that the glyph arrives on a pin already near full size:
 * a glyph appearing on a marker still too small to hold it is what the threshold exists to prevent.
 */
private const val GLYPH_ZOOM = 10f

/**
 * The zoom from which a pin carries its place's name. Wider than this the map answers *where* the
 * places are and how they group, which the colored discs do on their own; a field of names over a
 * region is a wall of text with most of it dropped to collision anyway. Above [GLYPH_ZOOM], so a
 * pin gains its glyph first and its name second — shape, then word.
 */
private const val LABEL_ZOOM = 11f

private fun addOverviewLayers(
    ctx: Context,
    style: Style,
    places: List<OverviewPlace>,
    untaggedFill: Int,
) {
    style.addImage(IMG_ENDPOINT, shadowedBitmap(ctx, R.drawable.ic_marker_endpoint, MarkerShadow.EVIDENCE))
    style.addImage(
        IMG_ENDPOINT_BRIEF,
        shadowedBitmap(ctx, R.drawable.ic_marker_endpoint_brief, MarkerShadow.EVIDENCE),
    )
    addPlacePinImages(ctx, style, untaggedFill, PIN_MAX_SCALE)
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
                    Expression.stop(LABEL_ZOOM, Expression.get("label")),
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
        places.sortedBy { it.label != null }.map { p ->
            val dot = if (p.brief) IMG_ENDPOINT_BRIEF else IMG_ENDPOINT
            Feature.fromGeometry(
                Point.fromLngLat(p.location.lon, p.location.lat),
                JsonObject().apply {
                    val named = p.label != null
                    addProperty(KIND_KEY, if (named) KIND_PIN else KIND_DOT)
                    addProperty(ICON_KEY, if (named) placePinImage(p.category, withGlyph = false) else dot)
                    addProperty(GLYPH_KEY, if (named) placePinImage(p.category, withGlyph = true) else dot)
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
