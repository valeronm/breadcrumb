package io.github.valeronm.breadcrumb.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.view.WindowManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.widget.Toast
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.alpha
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.BuildConfig
import io.github.valeronm.breadcrumb.data.AndroidDistance
import io.github.valeronm.breadcrumb.data.Settings as AppSettings
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.TimelineItem
import io.github.valeronm.breadcrumb.location.LocationRecordingService
import io.github.valeronm.breadcrumb.ui.theme.AppTheme
import io.github.valeronm.breadcrumb.util.UnitChoice
import io.github.valeronm.breadcrumb.util.UnitSystem
import io.github.valeronm.breadcrumb.util.isGranted
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import java.util.Locale

class MainActivity : ComponentActivity() {

    /** GPX uris handed to us via share/open-with, waiting for the UI to import them. */
    private val pendingGpxImport = mutableStateOf<List<Uri>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        consumeGpxIntent(intent)
        setContent {
            AppTheme {
                var unitChoice by remember {
                    mutableStateOf(UnitChoice.fromName(AppSettings.unitChoice(this)))
                }
                CompositionLocalProvider(
                    LocalUnits provides unitChoice.resolve(Locale.getDefault().country),
                ) {
                    MainScreen(pendingGpxImport, unitChoice) {
                        unitChoice = it
                        AppSettings.setUnitChoice(this, it.name)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeGpxIntent(intent)
    }

    private fun consumeGpxIntent(intent: Intent?) {
        val uris: List<Uri> = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java),
            )
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    .orEmpty()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        if (uris.isNotEmpty()) pendingGpxImport.value = uris
    }
}


// --- Permission helpers ------------------------------------------------------

private fun foregroundPermissions(): List<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Manifest.permission.ACTIVITY_RECOGNITION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}

private fun Context.foregroundGranted(): Boolean = foregroundPermissions().all { isGranted(it) }

private fun Context.backgroundGranted(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        isGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

private fun Context.isBatteryOptimizationIgnored(): Boolean {
    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(packageName)
}

@Suppress("BatteryLife")
private fun Context.requestIgnoreBatteryOptimization() {
    runCatching {
        startActivity(
            Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }.onFailure {
        // Some OEMs don't expose the direct dialog; fall back to the settings list.
        runCatching {
            startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}

/** The resolved display-unit system; all distance/speed rendering below reads this. */
internal val LocalUnits = staticCompositionLocalOf { UnitSystem.METRIC }

private enum class HomeTab { RECORD, TRACKS, PLACES }

/** A full-screen page shown over the tabs, animated with predictive back. */
private sealed interface Overlay {
    data class TrackDetail(val id: Long) : Overlay
    data object Settings : Overlay
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    pendingGpxImport: MutableState<List<Uri>?>,
    unitChoice: UnitChoice,
    onUnitChoice: (UnitChoice) -> Unit,
) {
    val context = LocalContext.current
    val viewModel: TrackListViewModel = viewModel()
    val timeline by viewModel.timeline.collectAsStateWithLifecycle()

    // GPX files shared/opened into the app import as soon as the UI is up.
    LaunchedEffect(pendingGpxImport.value) {
        val uris = pendingGpxImport.value ?: return@LaunchedEffect
        pendingGpxImport.value = null
        viewModel.importGpx(uris) { result ->
            Toast.makeText(context, gpxImportMessage(result), Toast.LENGTH_LONG).show()
        }
    }

    // Keep-screen-on while charging: live charger state + persisted preference; the window flag
    // holds the screen only while this activity is in the foreground (no wakelock, no permission).
    val charging = rememberChargingState()
    var keepScreenOn by remember { mutableStateOf(AppSettings.keepScreenOnCharging(context)) }
    val window = (context as? ComponentActivity)?.window
    LaunchedEffect(charging, keepScreenOn, window) {
        if (window == null) return@LaunchedEffect
        if (charging && keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Permission state, refreshed whenever the activity resumes (e.g. back from Settings).
    var foregroundOk by remember { mutableStateOf(context.foregroundGranted()) }
    var backgroundOk by remember { mutableStateOf(context.backgroundGranted()) }
    var autoOn by remember { mutableStateOf(AppSettings.isAutoRecord(context)) }
    var batteryOk by remember { mutableStateOf(context.isBatteryOptimizationIgnored()) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    var selectedTab by remember { mutableStateOf(HomeTab.RECORD) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                foregroundOk = context.foregroundGranted()
                backgroundOk = context.backgroundGranted()
                autoOn = AppSettings.isAutoRecord(context)
                batteryOk = context.isBatteryOptimizationIgnored()
                // Doze can hold the pause wake for minutes; opening the app closes a track whose
                // resume window has already passed, so the timeline isn't stale on arrival.
                LocationRecordingService.instance?.finalizeExpiredPause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val requestForeground = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        foregroundOk = context.foregroundGranted()
    }
    val requestBackground = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        backgroundOk = context.backgroundGranted()
    }
    // Reconcile persisted "armed" state with the actual service: if auto-recording is on but
    // the service isn't running (e.g. after a reinstall or being killed), restart it so the UI
    // doesn't get stuck on "Starting…".
    LaunchedEffect(foregroundOk, backgroundOk) {
        if (autoOn && foregroundOk && backgroundOk && !LocationRecordingService.isRunning) {
            LocationRecordingService.start(context)
        }
    }

    // Full-screen pages stack above the tabs as overlay layers, each animated in on open and
    // scaled/shifted by the predictive back gesture (Android 14+), previewing what's underneath.

    // The detail (map) screen or Settings — previews the tabs underneath. Its back handler yields
    // while a layer is stacked above it.
    var settingsPage by remember { mutableStateOf<SettingsPage?>(null) }
    // A deleted track's full detail, stacked above the Recently deleted list.
    var discardedTrackId by remember { mutableStateOf<Long?>(null) }
    // Place detail is opened from the Places list or a timeline stay — back lands wherever it was
    // opened from. Keyed by PlaceSummary.rowKey() so the screen tracks the live summary while the
    // derivation re-runs underneath (rename, radius change).
    var placeDetailKey by remember { mutableStateOf<String?>(null) }
    // The last summary the key resolved to: keeps the screen stable between re-derivations and
    // re-finds a just-named cluster by centroid (naming moves its key from cluster: to place:).
    var placeDetailSnapshot by remember { mutableStateOf<PlaceResolver.PlaceSummary?>(null) }
    // A visit tapped on the place detail: the Timeline scrolls to this stay when it next composes.
    var timelineVisitTarget by remember { mutableStateOf<StayDeriver.Stay?>(null) }

    val overlayLayer = rememberOverlayLayer(
        content = overlay,
        backEnabled = overlay != null && settingsPage == null && placeDetailKey == null,
        onDismiss = { overlay = null },
    )
    // Settings sub-pages stack above the hub — the predictive-back preview under them shows
    // the hub (where back actually lands), not the tabs.
    val settingsPageLayer = rememberOverlayLayer(
        content = settingsPage,
        backEnabled = settingsPage != null && discardedTrackId == null,
        onDismiss = { settingsPage = null },
    )
    // Deleted-track detail: back returns to the Recently deleted list, previewing it under the gesture.
    val discardedLayer = rememberOverlayLayer(
        content = discardedTrackId,
        onDismiss = { discardedTrackId = null },
    )
    val placeLayer = rememberOverlayLayer(
        content = placeDetailKey,
        onDismiss = { placeDetailKey = null },
        onClosed = { placeDetailSnapshot = null },
    )

    // Undo snackbars for the swipe actions on the Timeline and Places lists. Owned here, not in the
    // tabs: a tab switch would take the tab's composition (and its coroutine scope) with it, killing
    // a snackbar mid-timer and the undo with it.
    val snackbarHostState = remember { SnackbarHostState() }
    val undo = rememberUndoSnackbar(snackbarHostState)

    Box(modifier = Modifier.fillMaxSize()) {
        // The tabbed UI stays composed underneath so it can be previewed during the back gesture.
        Scaffold(
            modifier = Modifier.underlayBlur(overlayLayer, placeLayer),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    colors = canvasTopBarColors(),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when (selectedTab) {
                                    HomeTab.RECORD -> "Breadcrumb"
                                    HomeTab.TRACKS -> "Timeline"
                                    HomeTab.PLACES -> "Places"
                                },
                            )
                            if (BuildConfig.DEBUG) {
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                ) {
                                    Text(
                                        "debug",
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { overlay = Overlay.Settings }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    },
                )
            },
            bottomBar = {
                // One container step below the canvas: the default surfaceContainer became the
                // light theme's canvas tone, which made the bar invisible against it.
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    NavigationBarItem(
                        selected = selectedTab == HomeTab.RECORD,
                        onClick = { selectedTab = HomeTab.RECORD },
                        icon = { Icon(Icons.Filled.MyLocation, contentDescription = null) },
                        label = { Text("Record") },
                    )
                    NavigationBarItem(
                        selected = selectedTab == HomeTab.TRACKS,
                        onClick = { selectedTab = HomeTab.TRACKS },
                        icon = { Icon(Icons.Filled.Route, contentDescription = null) },
                        label = { Text("Timeline") },
                    )
                    NavigationBarItem(
                        selected = selectedTab == HomeTab.PLACES,
                        onClick = { selectedTab = HomeTab.PLACES },
                        icon = { Icon(Icons.Filled.Place, contentDescription = null) },
                        label = { Text("Places") },
                    )
                }
            },
        ) { inner ->
            Box(modifier = Modifier.fillMaxSize().padding(inner)) {
                when (selectedTab) {
                    HomeTab.RECORD -> RecordTab(
                        foregroundOk = foregroundOk,
                        backgroundOk = backgroundOk,
                        autoOn = autoOn,
                        batteryOk = batteryOk,
                        charging = charging,
                        keepScreenOn = keepScreenOn,
                        onToggleKeepScreenOn = { enabled ->
                            keepScreenOn = enabled
                            AppSettings.setKeepScreenOnCharging(context, enabled)
                        },
                        viewModel = viewModel,
                        onGrantForeground = {
                            requestForeground.launch(foregroundPermissions().toTypedArray())
                        },
                        onGrantBackground = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                // Android 11+ only grants this from the app's settings page.
                                context.startActivity(
                                    Intent(
                                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", context.packageName, null),
                                    ),
                                )
                            } else {
                                requestBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                            }
                        },
                        onToggleAuto = { enabled ->
                            autoOn = enabled
                            if (enabled) LocationRecordingService.start(context)
                            else LocationRecordingService.stop(context)
                        },
                        onRequestBattery = { context.requestIgnoreBatteryOptimization() },
                    )

                    HomeTab.TRACKS -> TracksTab(
                        items = timeline,
                        viewModel = viewModel,
                        undo = undo,
                        visitTarget = timelineVisitTarget,
                        onVisitTargetShown = { timelineVisitTarget = null },
                        onOpen = { overlay = Overlay.TrackDetail(it) },
                        onOpenPlace = { placeDetailKey = it },
                        onReplay = { track ->
                            TrackReplayer.start(context, track.id)
                            selectedTab = HomeTab.RECORD
                        },
                    )

                    HomeTab.PLACES -> PlacesTab(
                        viewModel = viewModel,
                        undo = undo,
                        onOpenPlace = { placeDetailKey = it },
                    )
                }
            }
        }

        // Overlay (track detail or settings): animates in on open and scales/shifts with the
        // predictive-back gesture, previewing the tabs underneath.
        val rendered = overlayLayer.rendered
        if (rendered != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .overlayTransform(overlayLayer)
                    .underlayBlur(settingsPageLayer),
            ) {
                when (rendered) {
                    is Overlay.TrackDetail -> TrackMapScreen(
                        trackId = rendered.id,
                        summary = timeline.firstNotNullOfOrNull {
                            (it as? TimelineItem.TrackItem)?.summary?.takeIf { s -> s.id == rendered.id }
                        },
                        viewModel = viewModel,
                        onBack = { overlay = null },
                    )

                    Overlay.Settings -> SettingsScreen(
                        viewModel = viewModel,
                        unitChoice = unitChoice,
                        onUnitChoice = onUnitChoice,
                        onBack = { overlay = null },
                        onOpenPage = { settingsPage = it },
                    )

                }
            }
        }

        // Place detail: stacked above whatever opened it (the Places overlay or the Tracks tab),
        // with the same open/close and predictive-back treatment.
        if (placeLayer.rendered != null) {
            // Includes zero-visit pass-through clusters (summarize emits every cluster), so gap
            // sides open even when their cluster never earned a stay — and their endpoints show
            // as neighbour context on adjacent places' maps.
            val placeSummaries by viewModel.places.collectAsStateWithLifecycle()
            val summary = remember(placeSummaries, placeDetailKey, placeDetailSnapshot) {
                placeSummaries.firstOrNull { it.rowKey() == placeDetailKey }
                    ?: placeDetailSnapshot?.let { snap -> placeSummaries.firstOrNull { it.centroid == snap.centroid } }
                    ?: placeDetailSnapshot
            }
            LaunchedEffect(summary) {
                val s = summary ?: return@LaunchedEffect
                placeDetailSnapshot = s
                if (placeDetailKey != null && placeDetailKey != s.rowKey()) placeDetailKey = s.rowKey()
            }
            summary?.let { detail ->
                // Surrounding clusters for radius context: their endpoints as grey dots, named
                // neighbours as labelled pins.
                val neighbors = remember(placeSummaries, detail) {
                    placeSummaries
                        .filter { other ->
                            other.rowKey() != detail.rowKey() && AndroidDistance.metres(
                                other.anchor.lat, other.anchor.lon,
                                detail.anchor.lat, detail.anchor.lon,
                            ) <= NEIGHBOR_CONTEXT_M
                        }
                        .flatMap { other ->
                            other.endpoints.map { NeighborPlace(it) } +
                                listOfNotNull(other.place?.let { NeighborPlace(other.anchor, it.label) })
                        }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .overlayTransform(placeLayer),
                ) {
                    PlaceDetailScreen(
                        summary = detail,
                        neighbors = neighbors,
                        viewModel = viewModel,
                        onBack = { placeDetailKey = null },
                        onOpenVisit = { stay ->
                            timelineVisitTarget = stay
                            placeDetailKey = null
                            overlay = null
                            selectedTab = HomeTab.TRACKS
                        },
                    )
                }
            }
        }

        // Settings sub-pages: a second overlay layer above the hub, same open/close and
        // predictive-back treatment — the gesture previews the hub underneath, where back lands.
        settingsPageLayer.rendered?.let { page ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .overlayTransform(settingsPageLayer)
                    .underlayBlur(discardedLayer),
            ) {
                val closePage = { settingsPage = null }
                when (page) {
                    SettingsPage.Sampling -> SamplingSettingsScreen(onBack = closePage)
                    SettingsPage.PointQuality -> PointQualitySettingsScreen(onBack = closePage)
                    SettingsPage.AutoPause -> AutoPauseSettingsScreen(onBack = closePage)
                    SettingsPage.GpsSearch -> GpsSearchSettingsScreen(onBack = closePage)
                    SettingsPage.TrackFiltering -> TrackFilteringSettingsScreen(onBack = closePage)
                    SettingsPage.RecentlyDeleted -> DiscardedTracksScreen(
                        viewModel = viewModel,
                        onBack = closePage,
                        onOpenTrack = { discardedTrackId = it },
                    )
                    SettingsPage.Logs -> LogsScreen(onBack = closePage)
                }
            }
        }

        // A deleted track's full detail: stacked above the Recently deleted list — back (and the
        // predictive-back preview) returns to the list, not the tabs.
        discardedLayer.rendered?.let { trackId ->
            // Collected here, not at MainScreen level: the aggregate query only stays live
            // while this rarely-open layer exists.
            val discardedTracks by viewModel.discardedTracks.collectAsStateWithLifecycle()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .overlayTransform(discardedLayer),
            ) {
                TrackMapScreen(
                    trackId = trackId,
                    summary = discardedTracks.firstOrNull { it.id == trackId }?.toTrackSummary(),
                    viewModel = viewModel,
                    onBack = { discardedTrackId = null },
                )
            }
        }
    }
}

/**
 * Animation state for one stacked overlay layer: open/close presence plus the predictive-back
 * gesture. [rendered] holds the layer's content from open until the close animation finishes —
 * keep the layer composed (with that content) while it's non-null, so the page doesn't blank
 * or flip while receding.
 */
private class OverlayLayerState<T : Any> {
    val presence = Animatable(0f)      // 0 = underneath shown, 1 = layer fully shown
    val backProgress = Animatable(0f)  // predictive back gesture progress, 0..1
    val backOffsetY = Animatable(0f)   // finger's vertical travel (px) since the gesture started
    var backEdgeSign by mutableFloatStateOf(1f)
    var rendered by mutableStateOf<T?>(null)

    /** Blur radius (dp) for content underneath: full while covered, sharpening with the gesture. */
    val backdropBlurDp: Float
        get() = presence.value * (1f - 0.7f * easeOutBack(backProgress.value)) * 12f
}


// Ease-out on the gesture progress: like the system's cross-activity animation, most of the
// reveal happens right at gesture start, then the surface tracks the finger gently.
private fun easeOutBack(back: Float): Float = 1f - (1f - back) * (1f - back)

/**
 * One stacked overlay layer: animates in while [content] is non-null, out when it goes null, and
 * wires the predictive back gesture ([backEnabled] gates it — a layer yields to one stacked above).
 * [onDismiss] fires when the gesture commits; [onClosed] after the close animation finishes.
 */
@Composable
private fun <T : Any> rememberOverlayLayer(
    content: T?,
    backEnabled: Boolean = content != null,
    onDismiss: () -> Unit,
    onClosed: () -> Unit = {},
): OverlayLayerState<T> {
    val state = remember { OverlayLayerState<T>() }
    // Snapshot the content while present; held stable through the close animation.
    if (content != null) state.rendered = content
    LaunchedEffect(content != null) {
        if (content != null) {
            state.backProgress.snapTo(0f)
            state.backOffsetY.snapTo(0f)
            state.presence.animateTo(1f, tween(300))
        } else if (state.rendered != null) {
            state.presence.animateTo(0f, tween(300))
            state.rendered = null
            state.backProgress.snapTo(0f)
            state.backOffsetY.snapTo(0f)
            onClosed()
        }
    }
    PredictiveBackHandler(enabled = backEnabled) { events ->
        var startTouchY = Float.NaN
        try {
            events.collect { event ->
                if (startTouchY.isNaN()) startTouchY = event.touchY
                state.backEdgeSign = if (event.swipeEdge == BackEventCompat.EDGE_LEFT) 1f else -1f
                state.backOffsetY.snapTo(event.touchY - startTouchY)
                state.backProgress.snapTo(event.progress)
            }
            onDismiss() // gesture committed -> dismiss
        } catch (cancelled: CancellationException) {
            // Gesture cancelled -> spring back to place.
            coroutineScope {
                launch { state.backProgress.animateTo(0f, tween(200)) }
                launch { state.backOffsetY.animateTo(0f, tween(200)) }
            }
        }
    }
    return state
}

/**
 * The overlay open/close + predictive-back transform: slide/scale in, recede toward the edge.
 * The animated values are read inside the graphicsLayer block (like [underlayBlur]) so animation
 * frames re-run only this draw-time block, not the composition that applied the modifier.
 */
private fun Modifier.overlayTransform(layer: OverlayLayerState<*>): Modifier =
    graphicsLayer {
        val enter = layer.presence.value
        val back = layer.backProgress.value
        val edgeSign = layer.backEdgeSign
        val backOffsetY = layer.backOffsetY.value
        val eased = easeOutBack(back)
        val scale = (0.92f + 0.08f * enter) * (1f - 0.10f * eased)
        scaleX = scale
        scaleY = scale
        translationX = (1f - enter) * size.width * 0.25f + edgeSign * eased * 48.dp.toPx()
        // The receding card follows the finger vertically at a damped rate (another system-
        // animation trait), fading in with the gesture so a near-full-screen card stays put.
        translationY = eased * (backOffsetY / 3f).coerceIn(-96.dp.toPx(), 96.dp.toPx())
        // Opaque through the back gesture (M3 predictive-back spec); only open/close fades.
        alpha = enter
        transformOrigin = TransformOrigin(if (edgeSign > 0f) 1f else 0f, 0.5f)
        shape = RoundedCornerShape(eased * 48f)
        clip = back > 0f
    }

/**
 * Blurs this content while any of [layers] sits above it — the system blurs the background
 * activity the same way during predictive back. Strongest when fully covered, sharpening as the
 * gesture reveals it. No-op below Android 12 (no RenderEffect there).
 */
private fun Modifier.underlayBlur(vararg layers: OverlayLayerState<*>): Modifier {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return this
    return graphicsLayer {
        val radius = layers.maxOf { it.backdropBlurDp }.dp.toPx()
        renderEffect = if (radius > 0.5f) BlurEffect(radius, radius, TileMode.Clamp) else null
        clip = renderEffect != null
    }
}

internal fun gpxImportMessage(result: TrackListViewModel.GpxImportSummary): String = buildList {
    add("Imported ${result.imported} tracks")
    if (result.duplicates > 0) add("${result.duplicates} duplicates skipped")
    if (result.failed > 0) add("${result.failed} failed")
}.joinToString(" · ")

/** Live charger state from the sticky ACTION_BATTERY_CHANGED broadcast (reacts to plug/unplug). */
@Composable
private fun rememberChargingState(): Boolean {
    val context = LocalContext.current
    var charging by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                // Plugged, not "actively charging": adaptive charging / a full battery report
                // STATUS_NOT_CHARGING while on the charger, and that's still the car-mount case.
                charging = (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            }
        }
        // Sticky broadcast: registration delivers the current state immediately.
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { context.unregisterReceiver(receiver) }
    }
    return charging
}
