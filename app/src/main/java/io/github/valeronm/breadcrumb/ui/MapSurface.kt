package io.github.valeronm.breadcrumb.ui

import android.content.Context
import android.content.res.Configuration
import android.util.Log
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.valeronm.breadcrumb.BuildConfig
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

/**
 * The map every screen draws on, and nothing drawn on it: the [MapView] and its lifecycle, the
 * basemap flavor, and the camera. Nothing here knows which map is on top of it.
 */

/**
 * Shared host for the map composables: owns the [MapView], loads the Protomaps style once, routes
 * later recompositions to [onUpdate], and runs [onMapReady] before the style loads (one-time map
 * setup like click listeners); callers keep their own last-applied state — this only removes boilerplate.
 */
@Composable
internal fun MapLibreStyledMap(
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
 * The live camera zoom, dev builds only — a marker size ramp and the zoom a pin earns its glyph at
 * are zoom numbers, and they can't be judged against a map that won't say which zoom it is at.
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
 * init flag, plus the latest style-loaded callback. Same role as each caller's `Applied*Inputs`
 * holder: a remembered plain object, not a snapshot state, so writing it never recomposes.
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
internal fun LatLngBounds.containsWithMargin(lat: Double, lon: Double, fraction: Double = 0.8): Boolean =
    with(scaled(fraction)) {
        lat in latitudeSouth..latitudeNorth && lon in longitudeWest..longitudeEast
    }

/**
 * Fits the camera to [positions]: ≥2 → bounds fit with 96px padding, exactly 1 → [singlePointZoom].
 * [headroom] > 1 zooms out beyond the exact fit (half-spans scaled around the center), which is what
 * a caller re-framing a moving position needs: an exact fit lands it on the viewport edge, due to
 * re-frame again on the next fix.
 */
internal fun frameTo(map: MapLibreMap, positions: List<LatLng>, singlePointZoom: Double, headroom: Double = 1.0) {
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

/** Whether the UI is in dark mode — the single switch for basemap flavor and map ink colors. */
internal fun isDarkUi(ctx: Context): Boolean =
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
