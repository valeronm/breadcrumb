package io.github.valeronm.breadcrumb.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Route
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.valeronm.breadcrumb.BuildConfig
import io.github.valeronm.breadcrumb.data.AndroidDistance
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.TimelineItem
import io.github.valeronm.breadcrumb.location.LocationRecordingService
import io.github.valeronm.breadcrumb.ui.theme.AppTheme
import io.github.valeronm.breadcrumb.util.UnitChoice
import io.github.valeronm.breadcrumb.util.UnitSystem
import io.github.valeronm.breadcrumb.util.backgroundGranted
import io.github.valeronm.breadcrumb.util.foregroundGranted
import io.github.valeronm.breadcrumb.util.foregroundPermissions
import io.github.valeronm.breadcrumb.util.isBatteryOptimizationIgnored
import io.github.valeronm.breadcrumb.util.requestIgnoreBatteryOptimization
import kotlinx.coroutines.launch
import io.github.valeronm.breadcrumb.data.Settings as AppSettings

class MainActivity : ComponentActivity() {

    /** GPX URIs handed to us via share/open-with, waiting for the UI to import them. */
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
                // The configuration locale, not Locale.getDefault(): composition observes it, so a
                // mid-process language switch re-resolves the Automatic units choice.
                val locale = LocalConfiguration.current.locales[0]
                CompositionLocalProvider(
                    LocalUnits provides unitChoice.resolve(locale.country),
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
        viewModel.importExport.importGpx(uris) { result ->
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
    // the service isn't running (e.g. after a reinstallation or being killed), restart it so the UI
    // doesn't get stuck on "Starting…".
    LaunchedEffect(foregroundOk, backgroundOk) {
        val armedAndPermitted = autoOn && foregroundOk && backgroundOk
        if (armedAndPermitted && !LocationRecordingService.isRunning) {
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
                            if (enabled) {
                                LocationRecordingService.start(context)
                            } else {
                                LocationRecordingService.stop(context)
                            }
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

        // The stacked full-screen layers, bottom to top; each animates in on open and scales/
        // shifts with the predictive-back gesture, previewing the layer underneath.
        MainOverlay(
            layer = overlayLayer,
            underlay = settingsPageLayer,
            timeline = timeline,
            viewModel = viewModel,
            unitChoice = unitChoice,
            onUnitChoice = onUnitChoice,
            onClose = { overlay = null },
            onOpenPage = { settingsPage = it },
        )

        PlaceDetailOverlay(
            layer = placeLayer,
            viewModel = viewModel,
            detailKey = placeDetailKey,
            snapshot = placeDetailSnapshot,
            onResolved = { s ->
                placeDetailSnapshot = s
                if (placeDetailKey != null && placeDetailKey != s.rowKey()) placeDetailKey = s.rowKey()
            },
            onClose = { placeDetailKey = null },
            onOpenVisit = { stay ->
                timelineVisitTarget = stay
                placeDetailKey = null
                overlay = null
                selectedTab = HomeTab.TRACKS
            },
        )

        SettingsPagesOverlay(
            layer = settingsPageLayer,
            underlay = discardedLayer,
            viewModel = viewModel,
            onClose = { settingsPage = null },
            onOpenTrack = { discardedTrackId = it },
        )

        DiscardedTrackOverlay(
            layer = discardedLayer,
            viewModel = viewModel,
            onClose = { discardedTrackId = null },
        )
    }
}

/**
 * A deleted track's full detail: stacked above the Recently deleted list — back (and the
 * predictive-back preview) returns to the list, not the tabs.
 */
@Composable
private fun DiscardedTrackOverlay(
    layer: OverlayLayerState<Long>,
    viewModel: TrackListViewModel,
    onClose: () -> Unit,
) {
    val trackId = layer.rendered ?: return
    // Collected here, not at MainScreen level: the aggregate query only stays live
    // while this rarely-open layer exists.
    val discardedTracks by viewModel.discardedTracks.collectAsStateWithLifecycle()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .overlayTransform(layer),
    ) {
        TrackMapScreen(
            trackId = trackId,
            summary = discardedTracks.firstOrNull { it.id == trackId }?.toTrackSummary(),
            viewModel = viewModel,
            onBack = onClose,
        )
    }
}

/** Track detail or the Settings hub — the first overlay layer, previewing the tabs underneath. */
@Composable
private fun MainOverlay(
    layer: OverlayLayerState<Overlay>,
    underlay: OverlayLayerState<SettingsPage>,
    timeline: List<TimelineItem>,
    viewModel: TrackListViewModel,
    unitChoice: UnitChoice,
    onUnitChoice: (UnitChoice) -> Unit,
    onClose: () -> Unit,
    onOpenPage: (SettingsPage) -> Unit,
) {
    val rendered = layer.rendered ?: return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .overlayTransform(layer)
            .underlayBlur(underlay),
    ) {
        when (rendered) {
            is Overlay.TrackDetail -> TrackMapScreen(
                trackId = rendered.id,
                summary = timeline.firstNotNullOfOrNull {
                    (it as? TimelineItem.TrackItem)?.summary?.takeIf { s -> s.id == rendered.id }
                },
                viewModel = viewModel,
                onBack = onClose,
            )

            Overlay.Settings -> SettingsScreen(
                viewModel = viewModel,
                unitChoice = unitChoice,
                onUnitChoice = onUnitChoice,
                onBack = onClose,
                onOpenPage = onOpenPage,
            )
        }
    }
}

/**
 * Place detail: stacked above whatever opened it (the Places overlay or the Tracks tab). The live
 * summary is re-found by [detailKey] each derivation; [onResolved] reports what it resolved to so
 * the caller can keep its snapshot (and key) tracking a renamed cluster.
 */
@Composable
private fun PlaceDetailOverlay(
    layer: OverlayLayerState<String>,
    viewModel: TrackListViewModel,
    detailKey: String?,
    snapshot: PlaceResolver.PlaceSummary?,
    onResolved: (PlaceResolver.PlaceSummary) -> Unit,
    onClose: () -> Unit,
    onOpenVisit: (StayDeriver.Stay) -> Unit,
) {
    if (layer.rendered == null) return
    // Includes zero-visit pass-through clusters (summarize emits every cluster), so gap
    // sides open even when their cluster never earned a stay — and their endpoints show
    // as neighbor context on adjacent places' maps.
    val placeSummaries by viewModel.places.collectAsStateWithLifecycle()
    val summary = remember(placeSummaries, detailKey, snapshot) {
        placeSummaries.firstOrNull { it.rowKey() == detailKey }
            ?: snapshot?.let { snap -> placeSummaries.firstOrNull { it.centroid == snap.centroid } }
            ?: snapshot
    }
    LaunchedEffect(summary) {
        summary?.let(onResolved)
    }
    summary?.let { detail ->
        // Surrounding clusters for radius context: their endpoints as gray dots, named
        // neighbors as labeled pins.
        val neighbors = remember(placeSummaries, detail) {
            placeSummaries
                .filter { other ->
                    other.rowKey() != detail.rowKey() &&
                        AndroidDistance.meters(
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
                .overlayTransform(layer),
        ) {
            PlaceDetailScreen(
                summary = detail,
                neighbors = neighbors,
                viewModel = viewModel,
                onBack = onClose,
                onOpenVisit = onOpenVisit,
            )
        }
    }
}

/**
 * Settings sub-pages: a second overlay layer above the hub — the gesture previews the hub
 * underneath, where back lands.
 */
@Composable
private fun SettingsPagesOverlay(
    layer: OverlayLayerState<SettingsPage>,
    underlay: OverlayLayerState<Long>,
    viewModel: TrackListViewModel,
    onClose: () -> Unit,
    onOpenTrack: (Long) -> Unit,
) {
    val page = layer.rendered ?: return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .overlayTransform(layer)
            .underlayBlur(underlay),
    ) {
        when (page) {
            SettingsPage.Sampling -> SamplingSettingsScreen(onBack = onClose)
            SettingsPage.PointQuality -> PointQualitySettingsScreen(onBack = onClose)
            SettingsPage.AutoPause -> AutoPauseSettingsScreen(onBack = onClose)
            SettingsPage.GpsSearch -> GpsSearchSettingsScreen(onBack = onClose)
            SettingsPage.TrackFiltering -> TrackFilteringSettingsScreen(onBack = onClose)
            SettingsPage.RecentlyDeleted -> DiscardedTracksScreen(
                viewModel = viewModel,
                onBack = onClose,
                onOpenTrack = onOpenTrack,
            )
            SettingsPage.Logs -> LogsScreen(onBack = onClose)
        }
    }
}

internal fun gpxImportMessage(result: ImportExportController.GpxImportSummary): String = buildList {
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
