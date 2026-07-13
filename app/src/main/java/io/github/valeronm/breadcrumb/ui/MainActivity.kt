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
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterCenterFocus
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocalTaxi
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.BuildConfig
import io.github.valeronm.breadcrumb.data.ActivityType
import io.github.valeronm.breadcrumb.data.IgnoreReason
import io.github.valeronm.breadcrumb.data.TrackQuality
import io.github.valeronm.breadcrumb.data.AndroidDistance
import io.github.valeronm.breadcrumb.data.Settings as AppSettings
import io.github.valeronm.breadcrumb.data.DISCARDED_RETENTION_DAYS
import io.github.valeronm.breadcrumb.data.db.DiscardedSummary
import io.github.valeronm.breadcrumb.data.db.Track
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.domain.DwellDetector
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.RecordCardState
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.TrackMerge
import io.github.valeronm.breadcrumb.domain.TimelineItem
import io.github.valeronm.breadcrumb.domain.recordCardState
import io.github.valeronm.breadcrumb.domain.recorderCardTitle
import io.github.valeronm.breadcrumb.location.LocationRecordingService
import io.github.valeronm.breadcrumb.location.TrackingStatus
import io.github.valeronm.breadcrumb.ui.theme.AppTheme
import io.github.valeronm.breadcrumb.util.DebugLog
import io.github.valeronm.breadcrumb.util.avgSpeedKmh
import io.github.valeronm.breadcrumb.util.formatKm
import io.github.valeronm.breadcrumb.util.formatKmh
import io.github.valeronm.breadcrumb.util.isGranted
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class MainActivity : ComponentActivity() {

    /** GPX uris handed to us via share/open-with, waiting for the UI to import them. */
    private val pendingGpxImport = mutableStateOf<List<Uri>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        consumeGpxIntent(intent)
        setContent {
            AppTheme {
                MainScreen(pendingGpxImport)
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

private enum class HomeTab { RECORD, TRACKS, PLACES }

/** A full-screen page shown over the tabs, animated with predictive back. */
private sealed interface Overlay {
    data class TrackDetail(val id: Long) : Overlay
    data object Settings : Overlay
}

/** A Settings sub-page stacked above the Settings hub (shares one overlay slot). */
private enum class SettingsPage {
    Sampling, PointQuality, AutoPause, GpsSearch, TrackFiltering, RecentlyDeleted, Logs,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(pendingGpxImport: MutableState<List<Uri>?>) {
    val context = LocalContext.current
    val viewModel: TrackListViewModel = viewModel()
    val timeline by viewModel.timeline.collectAsState()

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

    Box(modifier = Modifier.fillMaxSize()) {
        // The tabbed UI stays composed underneath so it can be previewed during the back gesture.
        Scaffold(
            modifier = Modifier.underlayBlur(overlayLayer, placeLayer),
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
                        onOpen = { overlay = Overlay.TrackDetail(it) },
                        onOpenPlace = { placeDetailKey = it },
                        onReplay = { track ->
                            TrackReplayer.start(context, track.id)
                            selectedTab = HomeTab.RECORD
                        },
                    )

                    HomeTab.PLACES -> PlacesTab(
                        viewModel = viewModel,
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
                        onBack = { overlay = null },
                        onOpenPage = { settingsPage = it },
                    )

                }
            }
        }

        // Place detail: stacked above whatever opened it (the Places overlay or the Tracks tab),
        // with the same open/close and predictive-back treatment.
        if (placeLayer.rendered != null) {
            val placeSummaries by viewModel.places.collectAsState()
            val summary = placeSummaries.firstOrNull { it.rowKey() == placeDetailKey }
                ?: placeDetailSnapshot?.let { snap -> placeSummaries.firstOrNull { it.centroid == snap.centroid } }
                ?: placeDetailSnapshot
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
            val discardedTracks by viewModel.discardedTracks.collectAsState()
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

private fun gpxImportMessage(result: TrackListViewModel.GpxImportSummary): String = buildList {
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

@Composable
private fun RecordTab(
    foregroundOk: Boolean,
    backgroundOk: Boolean,
    autoOn: Boolean,
    batteryOk: Boolean,
    charging: Boolean,
    keepScreenOn: Boolean,
    onToggleKeepScreenOn: (Boolean) -> Unit,
    viewModel: TrackListViewModel,
    onGrantForeground: () -> Unit,
    onGrantBackground: () -> Unit,
    onToggleAuto: (Boolean) -> Unit,
    onRequestBattery: () -> Unit,
) {
    // Collected here, not in MainScreen: the status flow emits per fix, and only this tab reads it.
    val status by TrackingStatus.state.collectAsState()
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        when {
            !foregroundOk -> PermissionCard(
                title = "Location & activity access needed",
                body = "Grant location and physical-activity access so the app can detect " +
                    "whether you're walking, driving or cycling and record GPS.",
                button = "Grant permissions",
                onClick = onGrantForeground,
            )

            !backgroundOk -> PermissionCard(
                title = "Allow background location",
                body = "Set location access to \"Allow all the time\" so tracks keep recording " +
                    "when the screen is off or the app is closed.",
                button = "Allow in the background",
                onClick = onGrantBackground,
            )

            else -> {
                AutoRecordControls(autoOn = autoOn, onToggle = onToggleAuto)
                if (autoOn && !batteryOk) {
                    Spacer(Modifier.height(8.dp))
                    PermissionCard(
                        title = "Keep recording in the background",
                        body = "Allow this app to ignore battery optimization so Android " +
                            "doesn't stop tracking after a while in the background.",
                        button = "Allow unrestricted",
                        onClick = onRequestBattery,
                    )
                }
                // The middle stretches so the keep-screen-on row is anchored at the bottom; while
                // recording (or replaying, debug), the track preview card fills all of it.
                val replay = if (BuildConfig.DEBUG) {
                    TrackReplayer.state.collectAsState().value
                } else {
                    null
                }
                val cardState = recordCardState(
                    armed = autoOn,
                    tracking = status.tracking,
                    recording = status.recording,
                    paused = status.pausedActivity != null,
                    gpsSuspended = status.gpsSuspended,
                    points = status.points,
                    hasOpenTrack = status.activeTrackId != null,
                )
                Spacer(Modifier.height(16.dp))
                val scrollingStats: @Composable ColumnScope.() -> Unit = {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        RecordedStats(viewModel)
                    }
                }
                when {
                    replay != null -> {
                        ReplayBanner(replay) { TrackReplayer.stop() }
                        Spacer(Modifier.height(8.dp))
                        CurrentTrackPreview(
                            status = replay.status,
                            points = replay.points,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    }
                    cardState == RecordCardState.LIVE_MAP -> {
                        LiveTrackPreview(
                            viewModel = viewModel,
                            status = status,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    }
                    cardState == RecordCardState.STATS_ONLY -> scrollingStats()
                    else -> {
                        RecorderStateCard(cardState, status)
                        Spacer(Modifier.height(12.dp))
                        scrollingStats()
                    }
                }
                Spacer(Modifier.height(16.dp))
                KeepScreenOnRow(
                    charging = charging,
                    enabled = keepScreenOn,
                    onToggle = onToggleKeepScreenOn,
                )
            }
        }
    }
}

/**
 * Recorded totals per activity for today / this month / the previous month — fills the Record
 * tab while nothing is recording, in the same grouped-block style as the settings page.
 */
@Composable
private fun RecordedStats(viewModel: TrackListViewModel) {
    val tracks by viewModel.tracks.collectAsState()
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val byDate = remember(tracks) {
        tracks.map { it to it.startedAt.toLocalDate(zone) }
    }
    // Remembered: RecordTab recomposes on every status tick while visible.
    val periods = remember(byDate, today) {
        val prevMonth = YearMonth.from(today).minusMonths(1)
        listOf(
            "Today" to byDate.filter { it.second == today },
            "This month" to byDate.filter { it.second.year == today.year && it.second.month == today.month },
            monthLabel(prevMonth, today) to byDate.filter { YearMonth.from(it.second) == prevMonth },
        )
    }
    GroupedRows(
        *periods.map { (title, entries) ->
            @Composable { PeriodStats(title, entries.map { it.first }) }
        }.toTypedArray(),
    )
}

@Composable
private fun PeriodStats(title: String, tracks: List<TrackSummary>) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    if (tracks.isEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(
            "No tracks yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        val totals = remember(tracks) { dayActivityTotals(tracks) }
        for (total in totals) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TonalIconDisc(
                    icon = activityIcon(total.activity),
                    tint = activityColor(total.activity),
                    contentDescription = total.activity?.label,
                    size = 28.dp,
                    iconSize = 16.dp,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    total.activity?.label ?: "Other",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${formatKm(total.meters)} · ${formatDurationMs(total.durationMs)}",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
    }
}

/** DEBUG: banner shown above the preview while a stored track is being replayed through it. */
@Composable
private fun ReplayBanner(replay: TrackReplayer.Replay, onStop: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Replaying ${replay.trackLabel} at ${replay.speedX}×",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onStop) { Text("Stop") }
    }
}

/** Loads the recorder's in-progress track and renders it via [CurrentTrackPreview]. */
@Composable
private fun LiveTrackPreview(
    viewModel: TrackListViewModel,
    status: TrackingStatus.State,
    modifier: Modifier = Modifier,
) {
    val activeId = status.activeTrackId ?: return
    // Refresh whenever a new point is recorded (points count changes), loading incrementally:
    // the full list once, then only rows newer than the last seen — re-reading the whole track
    // costs O(track length) per fix and grows for the whole recording.
    var points by remember(activeId) { mutableStateOf<List<TrackPoint>>(emptyList()) }
    LaunchedEffect(activeId, status.points) {
        val lastId = points.lastOrNull()?.id
        val fresh = if (lastId == null) viewModel.getPoints(activeId)
        else viewModel.getPointsAfter(activeId, lastId)
        if (fresh.isNotEmpty()) points = points + fresh
    }
    CurrentTrackPreview(status = status, points = points, modifier = modifier)
}

/** The live "current track" card: map preview + ticking stats. Pure — fed by recorder or replay. */
@Composable
private fun CurrentTrackPreview(
    status: TrackingStatus.State,
    points: List<TrackPoint>,
    modifier: Modifier = Modifier,
) {
    val activity = status.activity
    Card(modifier = modifier) {
        Column {
            // The map takes whatever height the card is given beyond the stats block.
            Box(modifier = Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
                if (points.size >= 2) {
                    MapLibreTrackMap(points = points, activity = activity, directionalEnd = true)
                } else {
                    Text(
                        "Waiting for GPS fix…",
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Current track · ${status.activity?.label ?: "Idle"}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                // Live trip stats; the status flow updates per fix, which keeps these ticking.
                val startedAt = status.startedAtMillis
                val durationS = startedAt?.let { (System.currentTimeMillis() - it) / 1000.0 } ?: 0.0
                val avgKmh = avgSpeedKmh(status.distanceMeters, durationS)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatItem("Distance", formatKm(status.distanceMeters))
                    StatItem(
                        "Duration",
                        startedAt?.let { formatDuration(it, System.currentTimeMillis()) } ?: "—",
                    )
                    StatItem("Speed", status.speedMps?.let { formatKmh(it * 3.6) } ?: "—")
                    StatItem("Avg", if (avgKmh > 0) formatKmh(avgKmh) else "—")
                    StatItem("Elevation", status.altitudeM?.let { "%.0f m".format(it) } ?: "—")
                }
            }
        }
    }
}

/** Recorder state while there's no track to draw: starting, idle, paused or waiting for GPS. */
@Composable
private fun RecorderStateCard(state: RecordCardState, status: TrackingStatus.State) {
    val context = LocalContext.current
    // A 1 Hz tick drives the pause countdown and the "last signal" age.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state) {
        while (true) {
            delay(1_000)
            nowMs = System.currentTimeMillis()
        }
    }
    val title = recorderCardTitle(
        state = state,
        nowMs = nowMs,
        activity = status.activity,
        pausedActivity = status.pausedActivity,
        pausedUntilMs = status.pausedUntilMillis,
        lastReadingAtMs = status.lastReadingAtMillis,
        lastFixAccuracyM = status.lastFixAccuracyM,
        lastFixRejectedByAccuracy = status.lastFixRejectedByAccuracy,
        gpsSuspendedSinceMs = status.gpsSuspendedSinceMillis,
        formatClock = { timeFormat.format(Date(it)) },
        formatDuration = ::formatDurationMs,
    )
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/** Settings-style switch with a check/cross icon in the thumb mirroring its state. */
@Composable
private fun IconSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        thumbContent = {
            Icon(
                if (checked) Icons.Filled.Check else Icons.Filled.Close,
                contentDescription = null,
                modifier = Modifier.size(SwitchDefaults.IconSize),
            )
        },
    )
}

/**
 * Keep-screen-on toggle; only actionable on the charger (car-mount use), greyed out on battery.
 * Deliberately card-less and small — a utility, not a peer of the main recording control.
 */
@Composable
private fun KeepScreenOnRow(
    charging: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Keep screen on",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (charging) "While charging, with the app open." else "Available while charging.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconSwitch(
            checked = enabled && charging,
            onCheckedChange = onToggle,
            enabled = charging,
        )
    }
}

/** Master on/off pill for the whole recorder, styled like Android settings' main toggle. */
@Composable
private fun AutoRecordControls(
    autoOn: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Surface(
        onClick = { onToggle(!autoOn) },
        shape = CircleShape,
        color = if (autoOn) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        contentColor = if (autoOn) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Auto recording",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconSwitch(checked = autoOn, onCheckedChange = onToggle)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TracksTab(
    items: List<TimelineItem>,
    viewModel: TrackListViewModel,
    onOpen: (Long) -> Unit,
    onOpenPlace: (String) -> Unit,
    onReplay: (TrackSummary) -> Unit,
) {
    val context = LocalContext.current
    var pendingDelete by remember { mutableStateOf<TrackSummary?>(null) }
    var pendingMerge by remember { mutableStateOf<TrackMerge.Plan?>(null) }

    if (items.none { it is TimelineItem.TrackItem }) {
        EmptyState(
            "No tracks yet. They'll appear here once recording captures some movement.",
            Modifier.fillMaxSize().padding(24.dp),
        )
        return
    }

    val groups = remember(items) { groupTimelineByDay(items) }
    val listState = rememberLazyListState()
    // Day label -> its header's lazy-item index: the fast scroller jumps between these anchors.
    val dayAnchors = remember(groups) {
        buildList {
            var index = 0
            groups.forEach { (label, dayItems) ->
                add(label to index)
                index += dayItems.size + 1
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            // Rows within a day sit tight so the group reads as one visual block.
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            groups.forEach { (label, dayItems) ->
                val dayTracks = dayItems.filterIsInstance<TimelineItem.TrackItem>().map { it.summary }
                stickyHeader(key = "header:$label") {
                    DayHeader(label, dayTracks) {
                        viewModel.shareTracks(dayTracks.map { it.id }) { intent ->
                            if (intent != null) context.startActivity(intent)
                        }
                    }
                }
                itemsIndexed(dayItems, key = { _, item -> item.rowKey() }) { index, item ->
                    val shape = groupedRowShape(index, dayItems.size)
                    when (item) {
                        is TimelineItem.TrackItem -> TrackRow(
                            track = item.summary,
                            shape = shape,
                            onOpen = { onOpen(item.summary.id) },
                            onDeleteRequest = { pendingDelete = item.summary },
                            // DEBUG: long-press replays the track through the Record tab's live view.
                            onReplay = if (BuildConfig.DEBUG) {
                                { onReplay(item.summary) }
                            } else {
                                null
                            },
                        )
                        is TimelineItem.StayItem -> StayRow(
                            item = item,
                            shape = shape,
                            onMerge = item.merge?.let { plan -> { pendingMerge = plan } },
                            onClick = {
                                item.place?.let { onOpenPlace(placeDetailKeyOf(it.placeId, it.centroid)) }
                            },
                        )
                        is TimelineItem.GapItem -> GapRow(item, shape, onOpenPlace)
                    }
                }
            }
        }
        TimelineFastScroller(state = listState, dayAnchors = dayAnchors)
    }

    pendingDelete?.let { track ->
        ConfirmDialog(
            icon = Icons.Filled.Delete,
            title = "Delete this track?",
            text = "The ${ActivityType.labelFor(track.activityType)} track from " +
                "${dateFormat.format(Date(track.startedAt))} will be deleted. For 14 days " +
                "it can be restored from Settings → Data → Recently deleted.",
            confirmLabel = "Delete",
            onConfirm = {
                viewModel.delete(track.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    pendingMerge?.let { plan ->
        ConfirmDialog(
            icon = Icons.AutoMirrored.Filled.CallMerge,
            title = "Merge these tracks?",
            text = "The two tracks either side of this short stop will be joined into one. " +
                "The stop is removed. This can't be undone.",
            confirmLabel = "Merge",
            onConfirm = {
                viewModel.mergeTracks(plan)
                pendingMerge = null
            },
            onDismiss = { pendingMerge = null },
        )
    }
}

/**
 * Haptic CLOCK_TICK when a scrubbed value crosses to a different key, throttled (30 ms) so a fast
 * drag feels like a picker, not a buzz. A plain holder rather than composed state: gesture lambdas
 * capture one composition and go stale. [tickOnFirst] controls whether the first non-null key
 * after construction (or a [reset]) ticks. Shared by the timeline fast scroller and the metric
 * graph scrubber.
 */
private class ThrottledTick(private val view: View, private val tickOnFirst: Boolean) {
    private var last: Any? = null
    private var lastTickAt = 0L

    fun onChange(key: Any?) {
        if (key != null && key != last && (last != null || tickOnFirst)) {
            val now = SystemClock.uptimeMillis()
            if (now - lastTickAt >= 30) {
                lastTickAt = now
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        }
        last = key
    }

    fun reset() {
        last = null
    }
}

/**
 * Fast scroller for the timeline: a finger-sized handle that fades in while the list scrolls and
 * can be grabbed and dragged through the history. The drag snaps to day headers (never into the
 * middle of a day), a bubble names the day under the thumb, and crossing into a different day
 * ticks like the track scrubber.
 */
@Composable
private fun BoxScope.TimelineFastScroller(state: LazyListState, dayAnchors: List<Pair<String, Int>>) {
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    // Tick when the drag crosses into a different day (never on the day under the initial grab).
    val dayTick = remember { ThrottledTick(view, tickOnFirst = false) }
    // Linger after the scroll stops so there's time to reach for the handle before it fades.
    var shown by remember { mutableStateOf(false) }
    val active = dragging || state.isScrollInProgress
    LaunchedEffect(active) {
        if (active) {
            shown = true
        } else {
            delay(1_500)
            shown = false
        }
    }
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(if (shown) 100 else 500),
        label = "fastScrollerAlpha",
    )
    if (alpha == 0f || dayAnchors.isEmpty()) return

    // Where the thumb sits when the finger isn't driving it: the day currently at the top,
    // on the same day-quantized scale the drag uses (so grabbing the handle doesn't jump).
    val listFraction by remember(state, dayAnchors) {
        derivedStateOf {
            val first = state.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: return@derivedStateOf 0f
            val dayIdx = dayAnchors.indexOfLast { it.second <= first }.coerceAtLeast(0)
            if (dayAnchors.size <= 1) 0f else dayIdx.toFloat() / (dayAnchors.size - 1)
        }
    }
    val fraction = if (dragging) dragFraction else listFraction

    BoxWithConstraints(Modifier.matchParentSize()) {
        val density = LocalDensity.current
        val thumbHeight = 56.dp
        val thumbWidth = 32.dp
        val thumbPx = with(density) { thumbHeight.toPx() }
        val trackPx = (constraints.maxHeight - thumbPx).coerceAtLeast(1f)
        val thumbY = (trackPx * fraction).roundToInt()

        fun dayIndexAt(f: Float): Int =
            (f * (dayAnchors.size - 1)).roundToInt().coerceIn(dayAnchors.indices)

        fun applyFraction(f: Float) {
            dragFraction = f.coerceIn(0f, 1f)
            val (day, headerIndex) = dayAnchors[dayIndexAt(dragFraction)]
            dayTick.onChange(day)
            scope.launch { state.scrollToItem(headerIndex) }
        }

        // The handle: a half-circle hugging the edge inside a larger touch box that captures on
        // first touch-down — no slop wait, so grabs aren't eaten by drag detection (which loses
        // slow or slightly diagonal starts). Only the handle area takes input; the rest of the
        // edge scrolls the list as usual.
        val touchPad = 12.dp
        val touchPadPx = with(density) { touchPad.toPx() }
        val currentFraction = rememberUpdatedState(fraction)
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, (thumbY - touchPadPx).roundToInt()) }
                .size(width = thumbWidth + touchPad, height = thumbHeight + touchPad * 2)
                .pointerInput(dayAnchors.size, trackPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        dragging = true
                        dayTick.reset()
                        dragFraction = currentFraction.value
                        // This box moves with the thumb, so map local positions to track space
                        // through the thumb's current offset; anchor the grab point so the
                        // handle doesn't jump under the finger.
                        fun trackY(localY: Float) = localY + trackPx * dragFraction - touchPadPx
                        val grabDelta = (trackPx * dragFraction + thumbPx / 2) - trackY(down.position.y)
                        try {
                            drag(down.id) { change ->
                                change.consume()
                                val centre = trackY(change.position.y) + grabDelta
                                applyFraction((centre - thumbPx / 2) / trackPx)
                            }
                        } finally {
                            dragging = false
                        }
                    }
                },
            contentAlignment = Alignment.CenterEnd,
        ) {
            Surface(
                modifier = Modifier.size(width = thumbWidth, height = thumbHeight).alpha(alpha),
                shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp),
                color = if (dragging) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.secondaryContainer,
                tonalElevation = 3.dp,
                shadowElevation = 3.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.UnfoldMore,
                        contentDescription = "Scroll to a day",
                        tint = if (dragging) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
        if (dragging) {
            val label = dayAnchors[dayIndexAt(fraction)].first
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset {
                        IntOffset(
                            -with(density) { (thumbWidth + 12.dp).roundToPx() },
                            (thumbY + (thumbPx / 2).roundToInt() - with(density) { 16.dp.roundToPx() }),
                        )
                    },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                tonalElevation = 3.dp,
                shadowElevation = 3.dp,
            ) {
                Text(
                    label,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

/**
 * Corner shape for a row in a day group: large outer corners on the group's first/last edge,
 * small inner corners between neighbours — the rows read as one grouped block.
 */
private fun groupedRowShape(index: Int, count: Int): RoundedCornerShape {
    val outer = 12.dp
    val inner = 4.dp
    val top = if (index == 0) outer else inner
    val bottom = if (index == count - 1) outer else inner
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

private class DayActivityTotal(val activity: ActivityType?, val meters: Double, val durationMs: Long)

private fun dayActivityTotals(tracks: List<TrackSummary>): List<DayActivityTotal> =
    tracks.groupBy { ActivityType.ofName(it.activityType) }
        .map { (activity, list) ->
            DayActivityTotal(
                activity = activity,
                meters = list.sumOf { it.distanceMeters },
                durationMs = list.sumOf { (it.endedAt ?: it.startedAt) - it.startedAt },
            )
        }
        .sortedByDescending { it.meters }

@Composable
private fun DayHeader(label: String, dayTracks: List<TrackSummary>, onShare: () -> Unit) {
    val totals = remember(dayTracks) { dayActivityTotals(dayTracks) }
    Column(
        // Opaque background: the header is sticky, rows scroll underneath it.
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            // Share exports the day's tracks as GPX — nothing to offer on a day with only stays.
            if (dayTracks.isNotEmpty()) {
                // Compact: a full 48dp/24dp action on every header outweighs the content rows.
                IconButton(onClick = onShare, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.Share,
                        contentDescription = "Share $label tracks",
                        // Match the top bar's action-icon tint — plain onSurface reads too bright here.
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        // Day totals per recorded activity, in the row style: tinted glyph + distance · duration.
        Row(
            modifier = Modifier.padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (total in totals) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        activityIcon(total.activity),
                        contentDescription = total.activity?.label,
                        tint = activityColor(total.activity),
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${formatKm(total.meters)} · ${formatDurationMs(total.durationMs)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private enum class PlacesView(val label: String) {
    MAP("Map"),
    LAST_VISIT("Recent"),
    MOST_VISITS("Visits"),
    TIME_SPENT("Time"),
}

/** How far around a place the detail map shows neighbouring clusters for radius context. */
private const val NEIGHBOR_CONTEXT_M = 1_200.0

/** Unnamed clusters with fewer visits than this are hidden unless "Rare unnamed stops" is on. */
private const val RARE_UNNAMED_MIN_VISITS = 3

/** The Places tab: sortable list (tap for detail, swipe to delete) or an all-places map. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlacesTab(
    viewModel: TrackListViewModel,
    onOpenPlace: (String) -> Unit,
) {
    val context = LocalContext.current
    val places by viewModel.places.collectAsState()
    var view by remember { mutableStateOf(PlacesView.MAP) }
    var showRareUnnamed by remember { mutableStateOf(AppSettings.placesShowRareUnnamed(context)) }
    var pendingDelete by remember { mutableStateOf<PlaceResolver.PlaceSummary?>(null) }

    val sorted = remember(view, places, showRareUnnamed) {
        val comparator = when (view) {
            PlacesView.MOST_VISITS -> compareByDescending<PlaceResolver.PlaceSummary> { it.visitCount }
            PlacesView.TIME_SPENT -> compareByDescending { it.totalMs }
            else -> compareByDescending { it.lastSeenMs ?: Long.MIN_VALUE }
        }
        places
            .filter { it.isNamed || showRareUnnamed || it.visitCount >= RARE_UNNAMED_MIN_VISITS }
            // Tiebreak: named before unnamed, then by label — stable across recompositions.
            .sortedWith(comparator.thenBy { it.place?.label?.lowercase(Locale.getDefault()) ?: "￿" })
    }

    Column(Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            PlacesView.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = view == option,
                    onClick = { view = option },
                    shape = SegmentedButtonDefaults.itemShape(index, PlacesView.entries.size),
                    // No checkmark: four segments need the width for their labels.
                    icon = {},
                ) { Text(option.label) }
            }
        }
        FilterChip(
            selected = showRareUnnamed,
            onClick = {
                showRareUnnamed = !showRareUnnamed
                AppSettings.setPlacesShowRareUnnamed(context, showRareUnnamed)
            },
            label = { Text("Rare unnamed stops") },
            leadingIcon = if (showRareUnnamed) {
                { Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp)) }
            } else {
                null
            },
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 4.dp),
        )
        if (sorted.isEmpty()) {
            EmptyState(
                "No places yet. Stays and places you name show up here.",
                Modifier.weight(1f).fillMaxWidth().padding(24.dp),
            )
        } else if (view == PlacesView.MAP) {
            // Card padding keeps the texture-mode map off the back-gesture edge strips.
            Card(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
            ) {
                Box(Modifier.fillMaxSize().clipToBounds()) {
                    val mapPlaces = remember(sorted) {
                        sorted.map { OverviewPlace(it.anchor, it.place?.label, it.rowKey()) }
                    }
                    MapLibrePlacesMap(
                        places = mapPlaces,
                        onOpen = onOpenPlace,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(sorted, key = { _, s -> s.rowKey() }) { index, summary ->
                    PlaceRow(
                        summary = summary,
                        shape = groupedRowShape(index, sorted.size),
                        onOpen = { onOpenPlace(summary.rowKey()) },
                        onDeleteRequest = { pendingDelete = summary },
                    )
                }
            }
        }
    }

    pendingDelete?.let { summary ->
        val existing = summary.place
        if (existing == null) {
            pendingDelete = null
            return@let
        }
        ConfirmDialog(
            icon = Icons.Filled.Delete,
            title = "Delete this place?",
            text = "\"${existing.label}\" will be removed. Its stays stay recorded — they " +
                "just go back to being unnamed.",
            confirmLabel = "Delete",
            onConfirm = {
                viewModel.deletePlace(existing.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PlaceRow(
    summary: PlaceResolver.PlaceSummary,
    shape: RoundedCornerShape,
    onOpen: () -> Unit,
    onDeleteRequest: () -> Unit,
) {
    // Only named places can be deleted (there's a label to remove) — unnamed clusters render as a
    // plain card with no swipe.
    if (!summary.isNamed) {
        PlaceRowCard(summary, shape, onOpen)
        return
    }
    SwipeActionRow(
        shape = shape,
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        icon = Icons.Filled.Delete,
        iconDescription = "Delete",
        onAction = onDeleteRequest,
    ) {
        PlaceRowCard(summary, shape, onOpen)
    }
}

/**
 * Full-screen detail for one place: stats header, the cluster on a map (capture circle +
 * endpoints), and — for named places — a live radius slider (the circle previews while dragging;
 * the value persists on release and the derivation re-clusters reactively).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaceDetailScreen(
    summary: PlaceResolver.PlaceSummary,
    neighbors: List<NeighborPlace>,
    viewModel: TrackListViewModel,
    onBack: () -> Unit,
) {
    val place = summary.place
    var showNameDialog by remember { mutableStateOf(false) }
    var showRecenterDialog by remember { mutableStateOf(false) }
    // Edit mode reveals the cluster internals (capture circle, endpoints, neighbours) plus the
    // radius slider and re-centre action; view mode leads with stats, a clean map and visits.
    var editing by remember(place?.id) { mutableStateOf(false) }
    // Local while dragging; summary.radiusM catches up after the persisted value re-derives.
    var radiusM by remember(place?.id) { mutableFloatStateOf(summary.radiusM.toFloat()) }
    // Where the pin would move: the mean of the endpoints the cluster currently captures.
    val endpointCentroid = remember(summary.endpoints) {
        summary.endpoints.takeIf { it.isNotEmpty() }?.let { pts ->
            StayDeriver.Endpoint(pts.sumOf { it.lat } / pts.size, pts.sumOf { it.lon } / pts.size)
        }
    }
    // Back steps out of edit mode before it closes the screen.
    BackHandler(enabled = editing) { editing = false }
    Scaffold(
        topBar = {
            TopAppBar(
                colors = canvasTopBarColors(),
                title = {
                    Column {
                        Text(place?.label ?: "Unnamed place")
                        if (editing) {
                            Text(
                                "${summary.endpoints.size} recorded track endpoints",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = { BackNavIcon { if (editing) editing = false else onBack() } },
                actions = {
                    if (editing) {
                        if (place != null && endpointCentroid != null) {
                            IconButton(onClick = { showRecenterDialog = true }) {
                                Icon(
                                    Icons.Filled.FilterCenterFocus,
                                    contentDescription = "Re-centre pin",
                                )
                            }
                        }
                        IconButton(onClick = { editing = false }) {
                            Icon(Icons.Filled.Check, contentDescription = "Done")
                        }
                    } else if (place != null) {
                        // Unnamed places get no top-bar actions: naming has the header CTA.
                        IconButton(onClick = { showNameDialog = true }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Rename place")
                        }
                        IconButton(onClick = { editing = true }) {
                            Icon(Icons.Filled.Tune, contentDescription = "Adjust area")
                        }
                    }
                },
            )
        },
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!editing) {
                Card(Modifier.fillMaxWidth()) {
                    PlaceStatsHeader(summary)
                    if (place == null) {
                        FilledTonalButton(
                            onClick = { showNameDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 16.dp),
                        ) { Text("Name this place") }
                    }
                }
            } else if (place != null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("Place radius", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${radiusM.roundToInt()} m",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Slider(
                            value = radiusM,
                            valueRange = 50f..500f,
                            onValueChange = { raw ->
                                radiusM = ((raw / 25f).roundToInt() * 25f).coerceIn(50f, 500f)
                            },
                            onValueChangeFinished = {
                                viewModel.setPlaceRadius(place.id, radiusM.toDouble())
                            },
                        )
                    }
                }
            }
            // One card at one call site in both modes, so the MapView survives the mode switch
            // and only restyles (internals on/off) instead of reloading.
            Card(
                if (editing) Modifier.weight(1f).fillMaxWidth()
                else Modifier.height(220.dp).fillMaxWidth(),
            ) {
                Box(Modifier.fillMaxSize().clipToBounds()) {
                    MapLibrePlaceMap(
                        center = summary.anchor,
                        radiusM = if (place != null) radiusM.toDouble() else summary.radiusM,
                        endpoints = summary.endpoints,
                        neighbors = neighbors,
                        showInternals = editing,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (!editing) {
                if (summary.stays.isEmpty()) {
                    EmptyState("No visits yet", Modifier.weight(1f).fillMaxWidth())
                } else {
                    PlaceVisitsList(summary.stays, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }

    if (showRecenterDialog && place != null && endpointCentroid != null) {
        ConfirmDialog(
            icon = Icons.Filled.FilterCenterFocus,
            title = "Re-centre pin?",
            text = "Moves \"${place.label}\" to the middle of where your visits actually landed. " +
                "Visits and stats recalculate around the new spot.",
            confirmLabel = "Move",
            onConfirm = {
                viewModel.setPlacePin(place.id, endpointCentroid.lat, endpointCentroid.lon)
                showRecenterDialog = false
            },
            onDismiss = { showRecenterDialog = false },
        )
    }

    if (showNameDialog) {
        var text by remember(place?.id) { mutableStateOf(place?.label ?: "") }
        val removing = text.isBlank() && place != null
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            icon = { Icon(Icons.Filled.Place, contentDescription = null) },
            title = { Text(if (place == null) "Name this place" else "Rename place") },
            text = {
                Column {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        label = { Text("Place name") },
                        // Place names are proper nouns — capitalize each word.
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    )
                    if (place != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Clear the name to remove this place.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed = text.trim()
                        when {
                            trimmed.isEmpty() && place != null -> viewModel.deletePlace(place.id)
                            place != null -> viewModel.renamePlace(place.id, trimmed)
                            trimmed.isNotEmpty() -> viewModel.createPlace(
                                summary.centroid.lat, summary.centroid.lon, trimmed,
                            )
                        }
                        showNameDialog = false
                    },
                    enabled = text.isNotBlank() || place != null,
                ) { Text(if (removing) "Remove" else "Save") }
            },
            dismissButton = {
                TextButton(onClick = { showNameDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun PlaceStatsHeader(summary: PlaceResolver.PlaceSummary) {
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        HeaderStat(
            "Visits",
            if (summary.visitCount > 0) "${summary.visitCount}" else "—",
            Modifier.weight(1f),
        )
        HeaderStat(
            "Time there",
            if (summary.totalMs > 0) formatDurationMs(summary.totalMs) else "—",
            Modifier.weight(1f),
        )
        HeaderStat(
            "Last visit",
            summary.lastSeenMs?.let { relativeDayCompact(it) } ?: "—",
            Modifier.weight(1f),
        )
    }
}

/** The place's visit history, newest first, grouped under month headers. */
@Composable
private fun PlaceVisitsList(stays: List<StayDeriver.Stay>, modifier: Modifier = Modifier) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val nowMs = remember { System.currentTimeMillis() }
    // Stays arrive newest first, so groupBy preserves month order and in-month order.
    val groups = remember(stays) {
        stays.groupBy { YearMonth.from(it.start.toLocalDate(zone)) }
    }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        groups.forEach { (month, visits) ->
            item(key = "month:$month") {
                Text(
                    monthLabel(month, today),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 6.dp),
                )
            }
            itemsIndexed(visits, key = { _, s -> "visit:${s.afterTrackId}:${s.start}" }) { index, stay ->
                Card(Modifier.fillMaxWidth(), shape = groupedRowShape(index, visits.size)) {
                    VisitRowContent(stay, zone, nowMs)
                }
            }
        }
        // The history ends where it began — a quiet marker instead of a stat-card factoid.
        stays.lastOrNull()?.let { first ->
            item(key = "first-visit") {
                Text(
                    "First visit ${relativeDay(first.start)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun VisitRowContent(stay: StayDeriver.Stay, zone: ZoneId, nowMs: Long) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                visitDayFormat.format(Instant.ofEpochMilli(stay.start).atZone(zone)),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                visitTimeRange(stay, zone),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            formatDurationMs((stay.end ?: nowMs) - stay.start),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** "18:18 – 08:30 +1" — the marker counts midnights crossed; the row title carries the start day. */
private fun visitTimeRange(stay: StayDeriver.Stay, zone: ZoneId): String {
    val start = timeFormat.format(Date(stay.start))
    val end = stay.end ?: return "since $start"
    val nights = ChronoUnit.DAYS.between(
        stay.start.toLocalDate(zone),
        end.toLocalDate(zone),
    )
    val rollover = if (nights > 0) " +$nights" else ""
    return "$start – ${timeFormat.format(Date(end))}$rollover"
}

private val visitDayFormat = DateTimeFormatter.ofPattern("EEE d", Locale.getDefault())
private val monthFormat = DateTimeFormatter.ofPattern("MMMM", Locale.getDefault())
private val monthYearFormat = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())

private fun monthLabel(month: YearMonth, today: LocalDate): String =
    if (month.year == today.year) month.format(monthFormat) else month.format(monthYearFormat)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaceRowCard(
    summary: PlaceResolver.PlaceSummary,
    shape: RoundedCornerShape,
    onClick: () -> Unit,
) {
    val named = summary.isNamed
    Card(modifier = Modifier.fillMaxWidth(), shape = shape, onClick = onClick) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tint = if (named) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
            TonalIconDisc(Icons.Filled.Place, tint, contentDescription = null, discAlpha = 0.16f)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    summary.place?.label ?: "Unnamed place",
                    style = MaterialTheme.typography.titleMedium,
                    color = placeTitleColor(named),
                )
                Text(
                    placeSubtitle(summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Title colour for anything place-like (Places list rows, stay cards, gap sides): named reads
 * at full onSurface, unnamed at the variant. Explicit because the inherited card colour dims
 * to onSurfaceVariant under dynamic colour (contentColorFor matches surfaceVariant first).
 */
@Composable
private fun placeTitleColor(named: Boolean) =
    if (named) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant

/**
 * Stable place identity: the place id for named places, the centroid for ephemeral unnamed
 * clusters. Shared by the Places list keys, the detail overlay, and stay-row tap-through —
 * a stay's [PlaceResolver.ResolvedStay] carries the same placeId/centroid pair.
 */
private fun placeDetailKeyOf(placeId: Long?, centroid: StayDeriver.Endpoint): String =
    placeId?.let { "place:$it" } ?: "cluster:%.5f,%.5f".format(centroid.lat, centroid.lon)

private fun PlaceResolver.PlaceSummary.rowKey(): String = placeDetailKeyOf(place?.id, centroid)

private fun placeSubtitle(summary: PlaceResolver.PlaceSummary): String {
    if (summary.visitCount == 0) return "No visits yet"
    val total = formatDurationMs(summary.totalMs)
    val lastVisit = summary.lastSeenMs?.let { "last visit ${relativeDayCompact(it)}" }
    return listOfNotNull(visitCountLabel(summary.visitCount), lastVisit, total).joinToString(" · ")
}

private fun visitCountLabel(n: Int): String = if (n == 1) "1 visit" else "$n visits"

/** Epoch millis → the local calendar date in [zone]. */
private fun Long.toLocalDate(zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

/** Coarse relative day for "last seen": today / yesterday / N days ago / a date. */
private fun relativeDay(epochMs: Long): String = relativeDay(epochMs, compact = false)

/** [relativeDay] squeezed for the big stat cells, where "5 days ago" or a full date overflows:
 *  "5d ago", "29 Nov", "Nov 2025" — always one line; exact dates live in the visit history. */
private fun relativeDayCompact(epochMs: Long): String = relativeDay(epochMs, compact = true)

private fun relativeDay(epochMs: Long, compact: Boolean): String {
    val zone = ZoneId.systemDefault()
    val then = epochMs.toLocalDate(zone)
    val today = LocalDate.now(zone)
    val days = ChronoUnit.DAYS.between(then, today)
    return when {
        days <= 0 -> "today"
        days == 1L && !compact -> "yesterday"
        days < 7 -> if (compact) "${days}d ago" else "$days days ago"
        // Compact beyond a week — this renders inside stat cells and one-line row subtitles.
        then.year == today.year -> then.format(compactDayFormat)
        else -> then.format(if (compact) monthOfYearFormat else compactDayYearFormat)
    }
}

private val compactDayFormat = DateTimeFormatter.ofPattern("d MMM")
private val compactDayYearFormat = DateTimeFormatter.ofPattern("d MMM yyyy")
private val monthOfYearFormat = DateTimeFormatter.ofPattern("MMM yyyy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    viewModel: TrackListViewModel,
    onBack: () -> Unit,
    onOpenPage: (SettingsPage) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                colors = canvasTopBarColors(),
                title = { Text("Settings") },
                navigationIcon = { BackNavIcon(onBack) },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text("Recording", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            GroupedRows(
                {
                    NavRow(
                        "Sampling",
                        subtitle = "Position source and how densely points are recorded",
                    ) { onOpenPage(SettingsPage.Sampling) }
                },
                {
                    NavRow(
                        "Point quality",
                        subtitle = "Which recorded points count as good",
                    ) { onOpenPage(SettingsPage.PointQuality) }
                },
                {
                    NavRow(
                        "Auto-pause",
                        subtitle = "How long a stop keeps the track open",
                    ) { onOpenPage(SettingsPage.AutoPause) }
                },
                {
                    NavRow(
                        "GPS search",
                        subtitle = "When to stop looking for a position",
                    ) { onOpenPage(SettingsPage.GpsSearch) }
                },
                {
                    NavRow(
                        "Track filtering",
                        subtitle = "Limits below which finished tracks are discarded",
                    ) { onOpenPage(SettingsPage.TrackFiltering) }
                },
            )
            Spacer(Modifier.height(24.dp))
            Text("Data", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            GroupedRows(
                { ImportTracksRow(viewModel) },
                { ExportTracksRow(viewModel) },
                {
                    NavRow(
                        "Recently deleted",
                        subtitle = "Restorable for $DISCARDED_RETENTION_DAYS days",
                    ) { onOpenPage(SettingsPage.RecentlyDeleted) }
                },
            )
            Spacer(Modifier.height(24.dp))
            Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            GroupedRows(
                { NavRow("Logs") { onOpenPage(SettingsPage.Logs) } },
            )
            Spacer(Modifier.height(32.dp))
            Text(
                "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Shared scaffold for one settings group page: title, back, optional top-bar Reset. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSubPage(
    title: String,
    onBack: () -> Unit,
    resetPrefs: List<Pref<*>> = emptyList(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                colors = canvasTopBarColors(),
                title = { Text(title) },
                navigationIcon = { BackNavIcon(onBack) },
                actions = {
                    if (resetPrefs.any { !it.isDefault }) {
                        TextButton(onClick = { resetPrefs.forEach { it.reset() } }) { Text("Reset") }
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            content = content,
        )
    }
}

/** The explanatory line under a settings page's top bar. */
@Composable
private fun SettingsPageDescription(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SamplingSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val intervalSec = rememberPref(AppSettings.DEFAULT_SAMPLING_MIN_INTERVAL_SEC,
        { AppSettings.minIntervalSec(context) }) { AppSettings.setMinIntervalSec(context, it) }
    val distanceM = rememberPref(AppSettings.DEFAULT_SAMPLING_MIN_DISTANCE_M,
        { AppSettings.minDistanceM(context) }) { AppSettings.setMinDistanceM(context, it) }
    val useFusedProvider = rememberPref(AppSettings.DEFAULT_USE_FUSED_PROVIDER,
        { AppSettings.useFusedProvider(context) }) { AppSettings.setUseFusedProvider(context, it) }
    SettingsSubPage("Sampling", onBack, listOf(intervalSec, distanceM, useFusedProvider)) {
        SettingsPageDescription(
            "Where positions come from and how densely points are recorded while moving.",
        )
        GroupedRows(
            {
                SwitchSettingRow(
                    title = "Use fused location",
                    subtitle = "Also estimates position from Wi-Fi and mobile networks, so " +
                        "tracks can continue indoors. Uses more battery.",
                    checked = useFusedProvider.value,
                    onCheckedChange = { useFusedProvider.set(it) },
                )
            },
            {
                SliderSetting("Time between points", intervalSec.value.toFloat(), 1f..30f, 1, { "${it.toInt()} s" }) {
                    intervalSec.set(it.toInt())
                }
            },
            {
                SliderSetting("Distance between points", distanceM.value.toFloat(), 1f..50f, 1, { "${it.toInt()} m" }) {
                    distanceM.set(it.toInt())
                }
            },
        )
    }
}

@Composable
private fun PointQualitySettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val accuracyGateM = rememberPref(AppSettings.DEFAULT_ACCURACY_GATE_M,
        { AppSettings.accuracyGateM(context) }) { AppSettings.setAccuracyGateM(context, it) }
    val requireGnssFix = rememberPref(AppSettings.DEFAULT_REQUIRE_GNSS_FIX,
        { AppSettings.requireGnssFix(context) }) { AppSettings.setRequireGnssFix(context, it) }
    SettingsSubPage("Point quality", onBack, listOf(accuracyGateM, requireGnssFix)) {
        SettingsPageDescription("Which recorded points count as good.")
        GroupedRows(
            {
                SwitchSettingRow(
                    title = "Require satellite fix",
                    subtitle = "Drops guessed positions, like in a tunnel. Walks with fused " +
                        "location keep them, so indoor tracks survive.",
                    checked = requireGnssFix.value,
                    onCheckedChange = { requireGnssFix.set(it) },
                )
            },
            {
                Text(
                    "Points less accurate than this are marked noisy and excluded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                SliderSetting("Max accuracy radius", accuracyGateM.value.toFloat(), 10f..150f, 10, { "${it.toInt()} m" }) {
                    accuracyGateM.set(it.toInt())
                }
            },
        )
    }
}

@Composable
private fun AutoPauseSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val resumeWindowSec = rememberPref(AppSettings.DEFAULT_STITCH_RESUME_WINDOW_SEC,
        { AppSettings.resumeWindowSec(context) }) { AppSettings.setResumeWindowSec(context, it) }
    SettingsSubPage("Auto-pause", onBack, listOf(resumeWindowSec)) {
        SettingsPageDescription(
            "A stop shorter than this keeps the track open — moving again continues the same " +
                "track. When set to Off, every stop ends the track.",
        )
        GroupedRows(
            {
                SliderSetting("Resume window", resumeWindowSec.value.toFloat(), 0f..600f, 60, { durationSettingLabel(it.toInt()) }) {
                    resumeWindowSec.set(it.toInt())
                }
            },
        )
    }
}

@Composable
private fun GpsSearchSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val gpsGiveUpSec = rememberPref(AppSettings.DEFAULT_GPS_GIVE_UP_SEC,
        { AppSettings.gpsGiveUpSec(context) }) { AppSettings.setGpsGiveUpSec(context, it) }
    SettingsSubPage("GPS search", onBack, listOf(gpsGiveUpSec)) {
        SettingsPageDescription(
            "If no good position arrives for this long, GPS pauses and retries when you move " +
                "or another app gets a location. When set to Off, GPS never stops searching.",
        )
        GroupedRows(
            {
                SliderSetting("Give up after", gpsGiveUpSec.value.toFloat(), 0f..600f, 60, { durationSettingLabel(it.toInt()) }) {
                    gpsGiveUpSec.set(it.toInt())
                }
            },
        )
    }
}

@Composable
private fun TrackFilteringSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val minDurationSec = rememberPref(AppSettings.DEFAULT_TRACK_MIN_DURATION_SEC,
        { AppSettings.minTrackDurationSec(context) }) { AppSettings.setMinTrackDurationSec(context, it) }
    val minLengthM = rememberPref(AppSettings.DEFAULT_TRACK_MIN_LENGTH_M,
        { AppSettings.minTrackLengthM(context) }) { AppSettings.setMinTrackLengthM(context, it) }
    val minExtentM = rememberPref(AppSettings.DEFAULT_TRACK_MIN_EXTENT_M,
        { AppSettings.minTrackExtentM(context) }) { AppSettings.setMinTrackExtentM(context, it) }
    SettingsSubPage("Track filtering", onBack, listOf(minDurationSec, minLengthM, minExtentM)) {
        SettingsPageDescription(
            "Tracks under these limits are moved to Recently deleted when they finish.",
        )
        GroupedRows(
            {
                SliderSetting("Min duration", minDurationSec.value.toFloat(), 0f..300f, 30, { durationSettingLabel(it.toInt()) }) {
                    minDurationSec.set(it.toInt())
                }
            },
            {
                SliderSetting("Min length", minLengthM.value.toFloat(), 0f..500f, 50, { lengthSettingLabel(it.toInt()) }) {
                    minLengthM.set(it.toInt())
                }
            },
            {
                Text(
                    "How far the track spread from where it started — filters out " +
                        "standing-still noise.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                SliderSetting("Min extent", minExtentM.value.toFloat(), 0f..500f, 50, { lengthSettingLabel(it.toInt()) }) {
                    minExtentM.set(it.toInt())
                }
            },
        )
    }
}

/** Hub row that opens the GPX picker directly; the subtitle doubles as import progress. */
@Composable
private fun ImportTracksRow(viewModel: TrackListViewModel) {
    val context = LocalContext.current
    // Progress lives in the ViewModel, so it survives leaving Settings mid-import.
    val importProgress by viewModel.importProgress.collectAsState()
    val appContext = context.applicationContext
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        viewModel.importGpx(uris) { result ->
            Toast.makeText(appContext, gpxImportMessage(result), Toast.LENGTH_LONG).show()
        }
    }
    val progress = importProgress
    NavRow(
        "Import tracks",
        subtitle = if (progress == null) {
            "GPX files; points need timestamps"
        } else {
            "Importing file ${(progress.filesDone + 1).coerceAtMost(progress.filesTotal)} " +
                "of ${progress.filesTotal} · ${progress.imported} tracks so far"
        },
    ) {
        if (progress == null) {
            importLauncher.launch(
                arrayOf(
                    "application/gpx+xml", "application/octet-stream",
                    "text/xml", "application/xml",
                ),
            )
        }
    }
}

/** Hub row that opens the folder picker and writes every track out as GPX. */
@Composable
private fun ExportTracksRow(viewModel: TrackListViewModel) {
    val context = LocalContext.current
    var exporting by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        exporting = true
        viewModel.exportAll(uri) { count ->
            exporting = false
            Toast.makeText(context, "Exported $count tracks", Toast.LENGTH_LONG).show()
        }
    }
    NavRow(
        "Export tracks",
        subtitle = if (exporting) "Exporting…" else "Every track as a GPX file, into a folder you pick",
    ) { if (!exporting) exportLauncher.launch(null) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val entries by DebugLog.entries.collectAsState(initial = emptyList())
    Scaffold(
        topBar = {
            TopAppBar(
                colors = canvasTopBarColors(),
                title = { Text("Logs (${entries.size})") },
                navigationIcon = { BackNavIcon(onBack) },
                actions = {
                    IconButton(onClick = {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, DebugLog.dump())
                        }
                        context.startActivity(Intent.createChooser(share, "Share logs"))
                    }) { Icon(Icons.Filled.Share, contentDescription = "Share logs") }
                    IconButton(onClick = { DebugLog.clear() }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Clear logs")
                    }
                },
            )
        },
    ) { inner ->
        if (entries.isEmpty()) {
            EmptyState("No logs yet — arm recording and move around.", Modifier.padding(inner).fillMaxSize())
        } else {
            // Newest first so the latest events are visible without scrolling.
            LazyColumn(modifier = Modifier.padding(inner).fillMaxSize().padding(horizontal = 12.dp)) {
                items(entries.asReversed()) { e ->
                    val color = when (e.level) {
                        'E' -> MaterialTheme.colorScheme.error
                        'W' -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Text(
                        "${DebugLog.formatTime(e.timeMillis)}  ${e.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = color,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * "Recently deleted": every soft-deleted track — deleted by the user, filtered by the keep
 * thresholds, or replaced by a merge — with why it's here and how long until the retention
 * purge removes it for good. Rows restore in place; tapping opens the full track detail.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscardedTracksScreen(
    viewModel: TrackListViewModel,
    onBack: () -> Unit,
    // Tapping a row opens its full detail as a layer above this list; back returns here.
    onOpenTrack: (Long) -> Unit,
) {
    val tracks by viewModel.discardedTracks.collectAsState()
    val nowMs = remember { System.currentTimeMillis() }
    var showClearDialog by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                colors = canvasTopBarColors(),
                title = { Text("Recently deleted" + if (tracks.isEmpty()) "" else " (${tracks.size})") },
                navigationIcon = { BackNavIcon(onBack) },
                actions = {
                    if (tracks.isNotEmpty()) {
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(Icons.Filled.DeleteForever, contentDescription = "Clear all")
                        }
                    }
                },
            )
        },
    ) { inner ->
        if (tracks.isEmpty()) {
            EmptyState(
                "Nothing here. Deleted and filtered-out tracks stay restorable " +
                    "for $DISCARDED_RETENTION_DAYS days.",
                Modifier.padding(inner).fillMaxSize().padding(horizontal = 24.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.padding(inner).fillMaxSize().padding(horizontal = 16.dp)) {
                item {
                    Text(
                        "Tracks stay here for $DISCARDED_RETENTION_DAYS days, " +
                            "then are removed forever.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                items(tracks, key = { it.id }) { t ->
                    val activity = ActivityType.ofName(t.activityType) ?: ActivityType.UNKNOWN
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { onOpenTrack(t.id) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            activityIcon(activity),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            val started = Instant.ofEpochMilli(t.startedAt)
                                .atZone(ZoneId.systemDefault()).format(discardedWhenFormat)
                            Text(
                                "${activity.label} · $started",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                "${t.pointCount} pts · ${formatKm(t.distanceMeters)} · " +
                                    formatDurationMs((t.endedAt ?: t.startedAt) - t.startedAt) +
                                    (if (t.ignoredCount > 0) " · ${t.ignoredCount} noisy" else ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                listOfNotNull(
                                    discardReasonLabel(t.discardReason),
                                    purgeCountdown(t.discardedAt, nowMs),
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { viewModel.restoreTrack(t.id) }) {
                            Icon(
                                Icons.Filled.RestoreFromTrash,
                                contentDescription = "Restore track",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }

    if (showClearDialog) {
        ConfirmDialog(
            icon = Icons.Filled.DeleteForever,
            title = "Clear Recently deleted?",
            text = if (tracks.size == 1) {
                "The one track here will be permanently deleted. This can't be undone."
            } else {
                "All ${tracks.size} tracks here will be permanently deleted. This can't be undone."
            },
            confirmLabel = "Delete all",
            onConfirm = {
                viewModel.purgeAllDiscarded()
                showClearDialog = false
            },
            onDismiss = { showClearDialog = false },
        )
    }
}

private fun discardReasonLabel(reason: String?): String? = when (reason) {
    Track.REASON_DELETED -> "Deleted by you"
    Track.REASON_FILTERED -> "Too short to keep"
    Track.REASON_MERGED -> "Replaced by a merged track"
    else -> null
}

/** "9 days left" until the retention purge; clamps at "removal due". */
private fun purgeCountdown(discardedAt: Long, nowMs: Long): String {
    val daysGone = ((nowMs - discardedAt) / (24 * 60 * 60_000L)).toInt()
    val left = DISCARDED_RETENTION_DAYS - daysGone
    return when {
        left <= 0 -> "removal due"
        left == 1 -> "1 day left"
        else -> "$left days left"
    }
}

private fun DiscardedSummary.toTrackSummary() = TrackSummary(
    id = id, activityType = activityType, startedAt = startedAt, endedAt = endedAt,
    distanceMeters = distanceMeters, pointCount = pointCount, ignoredCount = ignoredCount,
)

/**
 * Android-settings-style group: each row is its own card, large corners on the group's outer
 * edges and small ones between neighbours (same look as the track list's day groups).
 */
@Composable
private fun GroupedRows(vararg rows: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEachIndexed { index, row ->
            Card(modifier = Modifier.fillMaxWidth(), shape = groupedRowShape(index, rows.size)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { row() }
            }
        }
    }
}

/** Settings-style navigation row: label + chevron, opening a stacked screen. */
@Composable
private fun NavRow(
    label: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    // No vertical padding of its own: the GroupedRows card already pads the row.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
    }
}

/**
 * One settings value on this screen: a local mirror of the pref plus its persist and default, so a
 * section's canReset/reset derive from the prefs themselves instead of hand-listing every write.
 */
private class Pref<T>(initial: T, val default: T, private val persist: (T) -> Unit) {
    var value by mutableStateOf(initial)
        private set

    val isDefault: Boolean get() = value == default

    fun set(newValue: T) {
        value = newValue
        persist(newValue)
    }

    fun reset() = set(default)
}

@Composable
private fun <T> rememberPref(default: T, load: () -> T, save: (T) -> Unit): Pref<T> =
    remember { Pref(load(), default, save) }

/** Settings row with a title, explanatory subtitle and an [IconSwitch]. */
@Composable
private fun SwitchSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        IconSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SliderSetting(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Int,
    valueText: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                valueText(value),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = { raw ->
                // Snap to the nearest step so values land on round numbers.
                val snapped = ((raw / step).roundToInt() * step).toFloat()
                    .coerceIn(range.start, range.endInclusive)
                onChange(snapped)
            },
            valueRange = range,
        )
    }
}

private fun durationSettingLabel(sec: Int): String = when {
    sec <= 0 -> "Off"
    sec < 60 -> "$sec s"
    sec % 60 == 0 -> "${sec / 60} min"
    else -> "${sec / 60}m ${sec % 60}s"
}

private fun lengthSettingLabel(m: Int): String = when {
    m <= 0 -> "Off"
    m < 1000 -> "$m m"
    else -> formatKm(m.toDouble())
}

private fun groupTimelineByDay(items: List<TimelineItem>): List<Pair<String, List<TimelineItem>>> {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    // groupBy preserves encounter order, and items arrive newest-first, so days stay descending.
    // Midnight-spanning stays were already sliced per day by the deriver.
    return items
        .groupBy { it.startedAt.toLocalDate(zone) }
        .map { (date, list) -> dayLabel(date, today) to list }
}

private fun isLocalMidnight(epochMs: Long): Boolean =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalTime() == java.time.LocalTime.MIDNIGHT

private fun TimelineItem.rowKey(): String = when (this) {
    is TimelineItem.TrackItem -> "track:${summary.id}"
    is TimelineItem.StayItem -> "stay:${stay.afterTrackId}:${stay.start}"
    is TimelineItem.GapItem -> "gap:${gap.start}"
}

private val dayHeaderFormat = DateTimeFormatter.ofPattern("EEEE, d MMM yyyy", Locale.getDefault())
private val dayHeaderFormatThisYear = DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.getDefault())

private fun dayLabel(date: LocalDate, today: LocalDate): String = when {
    date == today -> "Today"
    date == today.minusDays(1) -> "Yesterday"
    // The current year goes without saying.
    date.year == today.year -> date.format(dayHeaderFormatThisYear)
    else -> date.format(dayHeaderFormat)
}

private val dateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

/** The screens' shared top-bar back arrow. */
@Composable
private fun BackNavIcon(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
    }
}

/** Centered placeholder for a list with nothing to show. */
@Composable
private fun EmptyState(message: String, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The list rows' category token: a glyph on a soft tonal disc of the same colour (M3 "tonal"). */
@Composable
private fun TonalIconDisc(
    icon: ImageVector,
    tint: Color,
    contentDescription: String?,
    size: Dp = 36.dp,
    iconSize: Dp = 20.dp,
    discAlpha: Float = 0.22f,
) {
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(tint.copy(alpha = discAlpha)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

/** Confirm-style dialog: icon, message, a confirm action and a Cancel button. */
@Composable
private fun ConfirmDialog(
    icon: ImageVector,
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(icon, contentDescription = null) },
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Row with a swipe-left action revealed behind it. The end-to-start dismissal itself is always
 * vetoed (so the row springs back on its own) and [onAction] takes over — a confirm dialog or the
 * merge. Resetting from a targetValue observer instead loses to an in-progress drag and leaves the
 * row stuck showing the background after Cancel. [confirmSettle] is what other state changes
 * (settling back) return from confirmValueChange.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeActionRow(
    shape: RoundedCornerShape,
    containerColor: Color,
    contentColor: Color,
    icon: ImageVector,
    iconDescription: String,
    onAction: () -> Unit,
    confirmSettle: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onAction()
                false
            } else {
                confirmSettle
            }
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(containerColor)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(icon, contentDescription = iconDescription, tint = contentColor)
            }
        },
    ) { content() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackRow(
    track: TrackSummary,
    shape: RoundedCornerShape,
    onOpen: () -> Unit,
    onDeleteRequest: () -> Unit,
    onReplay: (() -> Unit)? = null,
) {
    // Swipe right-to-left to request deletion — the actual delete happens via the confirm dialog.
    SwipeActionRow(
        shape = shape,
        containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        icon = Icons.Filled.Delete,
        iconDescription = "Delete",
        onAction = onDeleteRequest,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onOpen, onLongClick = onReplay),
            shape = shape,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val activity = ActivityType.ofName(track.activityType)
                // Activity token: a clear category cue that stays quiet.
                TonalIconDisc(
                    icon = activityIcon(activity),
                    tint = activityColor(activity),
                    contentDescription = ActivityType.labelFor(track.activityType),
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    // What happened leads; when it happened is the metadata line.
                    Text(
                        "${ActivityType.labelFor(track.activityType)} · ${formatKm(track.distanceMeters)}",
                        style = MaterialTheme.typography.titleMedium,
                        // Explicit: the inherited card colour dims to onSurfaceVariant under
                        // dynamic colour (contentColorFor matches surfaceVariant first).
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    val start = timeFormat.format(Date(track.startedAt))
                    val timeLine = track.endedAt?.let { "$start – ${timeFormat.format(Date(it))}" } ?: start
                    Text(
                        "$timeLine · ${formatDuration(track.startedAt, track.endedAt)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// Unnamed clusters visited at least this often surface their count as a naming invitation.
private const val VISIT_COUNT_BADGE_MIN = 3

/**
 * A derived stationary period between two tracks. A resolved place shows its label, an unnamed
 * recurring cluster shows its visit count. Tap → name. (The derivation's observed/inferred
 * provenance is deliberately NOT rendered: the customer can't act on it either way.)
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun StayRow(
    item: TimelineItem.StayItem,
    shape: RoundedCornerShape,
    onMerge: (() -> Unit)?,
    onClick: () -> Unit,
) {
    val place = item.place
    val named = place?.label != null
    val card = @Composable { StayCard(item, shape, named, onClick) }
    // A short same-activity stay can be swiped to merge its two tracks. Ineligible stays aren't
    // swipeable (the swipe would just spring back with nothing to do).
    if (onMerge == null) {
        card()
        return
    }
    SwipeActionRow(
        shape = shape,
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        icon = Icons.AutoMirrored.Filled.CallMerge,
        iconDescription = "Merge tracks",
        onAction = onMerge,
        // Never dismiss the row: merge replaces it, or the swipe springs back.
        confirmSettle = false,
    ) { card() }
}

@Composable
private fun StayCard(
    item: TimelineItem.StayItem,
    shape: RoundedCornerShape,
    named: Boolean,
    onClick: () -> Unit,
) {
    val stay = item.stay
    val place = item.place
    Card(modifier = Modifier.fillMaxWidth(), shape = shape, onClick = onClick) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A merge-eligible short stop is its own species: tertiary accent (matching the
            // swipe-to-merge hint) and a pause glyph instead of the place pin.
            val mergeable = item.merge != null
            val tint = when {
                mergeable -> MaterialTheme.colorScheme.tertiary
                named -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            TonalIconDisc(
                icon = if (mergeable) Icons.Filled.Pause else Icons.Filled.Place,
                tint = tint,
                contentDescription = if (mergeable) "Short stop" else "Stay",
                discAlpha = 0.16f,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                // The place leads; when (with midnight slices phrased humanly) is the metadata line.
                // Merge-eligible stays (always unnamed) name the situation instead.
                Text(
                    place?.label ?: if (mergeable) "Short stop" else "Stayed",
                    style = MaterialTheme.typography.titleMedium,
                    color = placeTitleColor(named),
                )
                val start = timeFormat.format(Date(stay.start))
                val end = stay.end
                val startsAtMidnight = isLocalMidnight(stay.start)
                val endsAtMidnight = end != null && isLocalMidnight(end)
                val timePhrase = when {
                    // Ongoing from midnight = all of today so far; completed midnight-to-midnight
                    // slices of a multi-day stay read the same.
                    startsAtMidnight && (end == null || endsAtMidnight) -> "All day"
                    end == null -> "$start – now"
                    startsAtMidnight -> "Until ${timeFormat.format(Date(end))}"
                    endsAtMidnight -> "From $start"
                    else -> "$start – ${timeFormat.format(Date(end))}"
                }
                val visits = place?.visitCount?.takeIf { !named && it >= VISIT_COUNT_BADGE_MIN }
                Text(
                    buildAnnotatedString {
                        append(timePhrase)
                        // A midnight-sliced bound makes the duration both redundant (it restates
                        // the clock time) and misleading (the real stay continues across the
                        // slice) — only whole stays show one.
                        if (!startsAtMidnight && !endsAtMidnight) {
                            append(" · ")
                            append(formatDurationMs((end ?: System.currentTimeMillis()) - stay.start))
                        }
                        if (visits != null) {
                            append(" · " + visitCountLabel(visits))
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Movement the recorder missed: neighbouring track endpoints disagree. Deliberately subdued.
 * Most such gaps are really one place misclustered as two, so the card names both sides as
 * full-width tappable lines — the app's row-tap language, not inline links — each opening its
 * place, where re-pinning or widening the radius fixes the split. Two pin glyphs joined by a
 * dashed connector in the icon column draw the unrecorded leg the way a map would. Newest-first
 * timeline: the destination sits above (adjacent to the later track), the source below.
 */
@Composable
private fun GapRow(item: TimelineItem.GapItem, shape: RoundedCornerShape, onOpenPlace: (String) -> Unit) {
    val gap = item.gap
    Card(modifier = Modifier.fillMaxWidth(), shape = shape) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            GapPlaceLine(item.toPlace, onOpenPlace)
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val strokeColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                // Built once, not per draw pass — the ripple invalidates the row on every press.
                val density = LocalDensity.current
                val dashEffect = remember(density) {
                    with(density) { PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 6.dp.toPx())) }
                }
                Canvas(modifier = Modifier.width(36.dp).height(24.dp)) {
                    drawLine(
                        color = strokeColor,
                        start = Offset(size.width / 2, 0f),
                        end = Offset(size.width / 2, size.height),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                        pathEffect = dashEffect,
                    )
                }
                Spacer(Modifier.width(16.dp))
                Text(
                    "missing recording · ${formatDurationMs(gap.end - gap.start)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
            GapPlaceLine(item.fromPlace, onOpenPlace)
        }
    }
}

/**
 * One side of a gap: a full-width tappable line (pin glyph + place name, ripple across the row,
 * like every other tappable row in the app) opening the place's detail screen. A side that's
 * unknown renders nothing — its position tells the story; one with no Places-screen row to open
 * (an unnamed cluster with no visits) renders without the tap affordance.
 */
@Composable
private fun GapPlaceLine(place: PlaceResolver.ResolvedStay?, onOpenPlace: (String) -> Unit) {
    if (place == null) return
    val openable = place.placeId != null || place.visitCount > 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (openable) {
                    Modifier.clickable { onOpenPlace(placeDetailKeyOf(place.placeId, place.centroid)) }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Filled.Place,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Text(
            text = place.label ?: "unnamed place",
            style = MaterialTheme.typography.titleMedium,
            color = placeTitleColor(named = place.label != null),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Top bars sit on the scaffold canvas instead of the default lighter surface — visible since
 * the light theme dips the canvas below the cards; identical tones in dark.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun canvasTopBarColors() =
    TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)

private val discardedWhenFormat = DateTimeFormatter.ofPattern("d MMM HH:mm")

private fun formatDuration(startedAt: Long, endedAt: Long?): String {
    val end = endedAt ?: return "recording"
    return formatDurationMs(end - startedAt)
}

private fun formatDurationMs(durationMs: Long): String {
    val minutes = (durationMs / 60000.0).roundToLong()
    // A day or more: minutes stop mattering — round to whole hours and split off days.
    if (minutes >= 24 * 60) {
        val hours = ((minutes + 30) / 60)
        return if (hours % 24 == 0L) "%dd".format(hours / 24)
        else "%dd %dh".format(hours / 24, hours % 24)
    }
    return when {
        minutes >= 60 && minutes % 60 == 0L -> "%dh".format(minutes / 60)
        minutes >= 60 -> "%dh %02dm".format(minutes / 60, minutes % 60)
        else -> "%dm".format(minutes)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackMapScreen(
    trackId: Long,
    summary: TrackSummary?,
    viewModel: TrackListViewModel,
    onBack: () -> Unit,
) {
    // null = still loading the track's points from the database.
    val context = LocalContext.current
    val points by produceState<List<TrackPoint>?>(initialValue = null, trackId) {
        value = viewModel.getPoints(trackId)
    }
    val noisyPoints by produceState<List<TrackPoint>>(initialValue = emptyList(), trackId) {
        value = viewModel.getIgnoredPoints(trackId)
    }
    // Embedded stays: venue-scale dwells detected from the loaded points (see DwellDetector).
    val dwells by produceState<List<DwellDetector.Dwell>>(initialValue = emptyList(), points) {
        value = points?.let { pts ->
            withContext(Dispatchers.Default) { DwellDetector.detect(pts, distance = AndroidDistance) }
        } ?: emptyList()
    }
    val activity = remember(summary) {
        summary?.let { ActivityType.ofName(it.activityType) }
    }
    var colorMode by remember { mutableStateOf(ColorMode.SPEED) }
    // Noisy (ignored) fixes are hidden by default; the warning toggle shows them with a legend.
    var showNoisy by remember(trackId) { mutableStateOf(false) }
    // Detected stops default to visible — this screen is the validation tool for the detector.
    var showStops by remember(trackId) { mutableStateOf(true) }
    // Point picked on the metric graph, highlighted on the map. Index into the good-points list.
    var selectedIndex by remember(trackId) { mutableStateOf<Int?>(null) }
    var showTypeDialog by remember(trackId) { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                colors = canvasTopBarColors(),
                title = {
                    Column {
                        Text(summary?.let { ActivityType.labelFor(it.activityType) } ?: "Track")
                        if (summary != null) {
                            Text(
                                dateFormat.format(Date(summary.startedAt)) +
                                    (summary.endedAt?.let { " – ${timeFormat.format(Date(it))}" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = { BackNavIcon(onBack) },
                actions = {
                    if (dwells.isNotEmpty()) {
                        IconButton(onClick = { showStops = !showStops }) {
                            Icon(
                                Icons.Filled.Place,
                                contentDescription =
                                    if (showStops) "Hide detected stops" else "Show detected stops",
                                tint = if (showStops) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                    if (noisyPoints.isNotEmpty()) {
                        IconButton(onClick = { showNoisy = !showNoisy }) {
                            Icon(
                                Icons.Filled.Warning,
                                contentDescription =
                                    if (showNoisy) "Hide noisy fixes" else "Show noisy fixes",
                                tint = if (showNoisy) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                    if (summary != null) {
                        IconButton(onClick = { showTypeDialog = true }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Change track type")
                        }
                    }
                    IconButton(onClick = {
                        viewModel.shareTracks(listOf(trackId)) { intent ->
                            if (intent != null) context.startActivity(intent)
                        }
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = "Share GPX")
                    }
                },
            )
        },
    ) { inner ->
        Box(modifier = Modifier.padding(inner).fillMaxSize().clipToBounds()) {
            val loaded = points
            when {
                loaded == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                loaded.size < 2 -> Text(
                    "Not enough points to draw this track on a map.",
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> Column(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (summary != null) {
                        Card(Modifier.fillMaxWidth()) { TrackStatsHeader(summary) }
                    }
                    val darkTheme = isSystemInDarkTheme()
                    val graph = remember(loaded, colorMode, activity, darkTheme) {
                        metricGraphData(loaded, colorMode, activity, darkTheme)
                    }
                    // Metric chips, map, and scrubber read as one group: small gaps, small
                    // corners between neighbours.
                    Column(
                        Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        val blocks = if (graph != null) 3 else 2
                        Card(Modifier.fillMaxWidth(), shape = groupedRowShape(0, blocks)) {
                            ColorModeSelector(colorMode) { colorMode = it }
                        }
                        // The map card takes the stretch.
                        Card(Modifier.weight(1f).fillMaxWidth(), shape = groupedRowShape(1, blocks)) {
                            Box(Modifier.fillMaxSize().clipToBounds()) {
                                MapLibreTrackMap(
                                    points = loaded,
                                    noisyPoints = if (showNoisy) noisyPoints else emptyList(),
                                    activity = activity,
                                    colorMode = colorMode,
                                    showLegend = true,
                                    selectedPoint = selectedIndex?.let { loaded.getOrNull(it) },
                                    dwells = if (showStops) dwells else emptyList(),
                                    modifier = Modifier.fillMaxSize(),
                                )
                                if (showNoisy) {
                                    // Top-right, clear of the colour-metric legend (bottom-right).
                                    NoisyLegend(noisyPoints, Modifier.align(Alignment.TopEnd).padding(12.dp))
                                }
                                if (showStops && dwells.isNotEmpty()) {
                                    // Top-left: the noisy legend owns the top-right corner.
                                    DwellLegend(dwells, Modifier.align(Alignment.TopStart).padding(12.dp))
                                }
                            }
                        }
                        if (graph != null) {
                            Card(Modifier.fillMaxWidth(), shape = groupedRowShape(2, 3)) {
                                MetricGraph(
                                    graph = graph,
                                    selectedIndex = selectedIndex,
                                    onSelect = { selectedIndex = it },
                                    modifier = Modifier.fillMaxWidth().height(130.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showTypeDialog && summary != null) {
        AlertDialog(
            onDismissRequest = { showTypeDialog = false },
            icon = { Icon(activityIcon(activity), contentDescription = null) },
            title = { Text("Track type") },
            text = {
                Column {
                    // Selecting applies immediately: the summary flow re-emits and the title,
                    // icon, colours and speed scale all follow.
                    for (option in ActivityType.entries.filter { it.recording && it != ActivityType.UNKNOWN }) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    viewModel.setTrackActivity(trackId, option)
                                    showTypeDialog = false
                                }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                activityIcon(option),
                                contentDescription = null,
                                tint = activityColor(option),
                            )
                            Spacer(Modifier.width(16.dp))
                            Text(option.label, style = MaterialTheme.typography.bodyLarge)
                            if (option == activity) {
                                Spacer(Modifier.weight(1f))
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "Current type",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTypeDialog = false }) { Text("Cancel") }
            },
        )
    }
}

/** Per-point series for the metric graph: values (null = gap), map-matching colours, and a unit. */
@Immutable
internal class MetricGraphData(
    val points: List<TrackPoint>,
    val values: List<Float?>,
    val colors: IntArray,
    val unit: String,
)

/** Null when no point carries the metric. */
internal fun metricGraphData(
    points: List<TrackPoint>,
    mode: ColorMode,
    activity: ActivityType?,
    dark: Boolean,
): MetricGraphData? {
    // Computed unconditionally: trackColoring below needs it whatever the mode.
    val speeds = TrackQuality.pointSpeedsKmh(points)
    val (values, unit) = metricSeries(points, mode, speeds)
    if (values.all { it == null }) return null
    val colors = trackColoring(points, speeds, mode, activity, dark).colors
    return MetricGraphData(points, values, colors, unit)
}

/**
 * The metric polyline alone, rasterized once per (graph, size) into a bitmap and blitted per frame.
 * Recording/issuing thousands of drawLine ops every frame is what made scrubbing long tracks lag
 * (the cursor overlay invalidates each frame); a cached bitmap makes the plot a single drawImage.
 * Takes only the (immutable) series and scale, never the selection, so Compose also skips it.
 */
@Composable
private fun MetricPlot(
    graph: MetricGraphData,
    minV: Float,
    span: Float,
    t0: Long,
    tSpan: Float,
    modifier: Modifier,
) {
    val strokePx = with(LocalDensity.current) { 2.dp.toPx() }
    val padPx = with(LocalDensity.current) { 8.dp.toPx() }
    Spacer(
        modifier.drawWithCache {
            val bitmap = ImageBitmap(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
            CanvasDrawScope().draw(this, layoutDirection, ComposeCanvas(bitmap), size) {
                val h = size.height - 2 * padPx
                var prev: Offset? = null
                for (i in graph.points.indices) {
                    val v = graph.values[i]
                    if (v == null) {
                        prev = null
                        continue
                    }
                    if (graph.points[i].segmentStart) prev = null
                    val x = (graph.points[i].timestamp - t0) / tSpan * size.width
                    val y = padPx + h - ((v - minV) / span) * h
                    val current = Offset(x, y)
                    prev?.let { drawLine(Color(graph.colors[i]), it, current, strokeWidth = strokePx) }
                    prev = current
                }
            }
            onDrawBehind { drawImage(bitmap) }
        },
    )
}

/**
 * The selected colour metric over the track's time span, stroked point-to-point in the same colours
 * as the map's track line, with a time axis. Missing values and segment starts break the line.
 * Tapping or dragging picks the nearest point ([onSelect]); the selection is drawn as a cursor with
 * a value/time readout, and the caller highlights the same point on the map.
 */
@Composable
private fun MetricGraph(
    graph: MetricGraphData,
    selectedIndex: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier,
) {
    // Remembered: MetricGraph recomposes per touch event while scrubbing, and the min/max scan
    // is O(points) — the series is immutable per graph instance.
    val (minV, maxV) = remember(graph) {
        val present = graph.values.filterNotNull()
        present.min() to present.max()
    }
    val span = (maxV - minV).let { if (it < 1e-3f) 1f else it }
    val t0 = graph.points.first().timestamp
    val tSpan = (graph.points.last().timestamp - t0).coerceAtLeast(1L).toFloat()

    // x (0..width) -> index of the nearest point that actually has a value. Runs per drag event
    // while scrubbing, so it binary-searches the (sorted) timestamps instead of scanning them all.
    fun indexAt(x: Float, width: Float): Int? {
        if (width <= 0f) return null
        // Keep the epoch-millis math in Long: t0 (~1.8e12) + Float promotes to Float, whose
        // precision at that magnitude quantizes the target to ~131 s steps.
        val target = t0 + ((x / width).coerceIn(0f, 1f) * tSpan).toLong()
        // First index with timestamp >= target.
        var lo = 0
        var hi = graph.points.lastIndex
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (graph.points[mid].timestamp < target) lo = mid + 1 else hi = mid
        }
        // Nearest valued point on each side of the boundary; distances only grow further out.
        var left = lo - 1
        while (left >= 0 && graph.values[left] == null) left--
        var right = lo
        while (right <= graph.points.lastIndex && graph.values[right] == null) right++
        val leftDist = if (left >= 0) target - graph.points[left].timestamp else Long.MAX_VALUE
        val rightDist = if (right <= graph.points.lastIndex) {
            kotlin.math.abs(graph.points[right].timestamp - target)
        } else {
            Long.MAX_VALUE
        }
        return when {
            left < 0 && right > graph.points.lastIndex -> null
            leftDist <= rightDist -> left
            else -> right
        }
    }

    Surface(modifier = modifier, tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Column(Modifier.fillMaxSize()) {
            val strokePx = with(LocalDensity.current) { 2.dp.toPx() }
            val padPx = with(LocalDensity.current) { 8.dp.toPx() }
            val cursorColor = MaterialTheme.colorScheme.onSurface
            val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
            val view = LocalView.current
            // Tick only when the scrubber actually lands on a different point.
            val pointTick = remember(graph) { ThrottledTick(view, tickOnFirst = true) }
            fun select(index: Int?) {
                pointTick.onChange(index)
                onSelect(index)
            }
            // The selection, bounds-checked once for both the cursor and the readout below.
            val sel = selectedIndex?.takeIf { it in graph.points.indices }
            val selValue = sel?.let { graph.values[it] }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // Static plot in its own skippable composable: scrubbing recomposes MetricGraph per
                // touch event, and redrawing the full multi-thousand-segment polyline each time is
                // what made long tracks feel laggy. Only the cursor overlay below redraws.
                MetricPlot(graph, minV, span, t0, tSpan, Modifier.fillMaxSize())
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(graph) {
                            detectTapGestures { offset ->
                                select(indexAt(offset.x, size.width.toFloat()))
                            }
                        }
                        .pointerInput(graph) {
                            detectHorizontalDragGestures { change, _ ->
                                change.consume()
                                select(indexAt(change.position.x, size.width.toFloat()))
                            }
                        },
                ) {
                    val h = size.height - 2 * padPx
                    if (sel != null && selValue != null) {
                        val x = (graph.points[sel].timestamp - t0) / tSpan * size.width
                        val y = padPx + h - ((selValue - minV) / span) * h
                        drawLine(
                            cursorColor.copy(alpha = 0.6f),
                            Offset(x, 0f),
                            Offset(x, size.height),
                            strokeWidth = strokePx / 2,
                        )
                        drawCircle(cursorColor, radius = strokePx * 2, center = Offset(x, y))
                    }
                }
                Text(
                    "%.0f %s".format(maxV, graph.unit),
                    modifier = Modifier.align(Alignment.TopStart).padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                )
                Text(
                    "%.0f %s".format(minV, graph.unit),
                    modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                )
                if (sel != null && selValue != null) {
                    Text(
                        "%.0f %s · %s".format(
                            selValue, graph.unit,
                            timeFormat.format(Date(graph.points[sel].timestamp)),
                        ),
                        modifier = Modifier.align(Alignment.TopEnd).padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(timeFormat.format(Date(t0)), style = MaterialTheme.typography.labelSmall, color = labelColor)
                Text(
                    timeFormat.format(Date(t0 + (tSpan / 2).toLong())),
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                )
                Text(
                    timeFormat.format(Date(t0 + tSpan.toLong())),
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                )
            }
        }
    }
}

// Chip colours match the marker drawables (ic_marker_noisy / _jump / _gnss).
private fun noisyLegendEntry(reason: IgnoreReason?): Pair<String, Color> = when (reason) {
    IgnoreReason.JUMP -> "Speed jump" to Color(0xFFE53935)
    IgnoreReason.NO_GNSS -> "No satellite fix" to Color(0xFFAB47BC)
    IgnoreReason.ACCURACY, null -> "Low accuracy" to Color(0xFFFF8F00)
}

/** Detected in-track stops: one row per dwell — "14:36 – 16:10 · 1h 34m". */
@Composable
private fun DwellLegend(dwells: List<DwellDetector.Dwell>, modifier: Modifier) {
    LegendSurface(modifier) {
        Text(
            "Stops",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (d in dwells) {
            Text(
                "${timeFormat.format(Date(d.entryTs))} – ${timeFormat.format(Date(d.exitTs))}" +
                    " · ${formatDuration(d.entryTs, d.exitTs)}",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** Legend for the noisy-fix markers: one row per rejection reason present in [noisyPoints]. */
@Composable
private fun NoisyLegend(noisyPoints: List<TrackPoint>, modifier: Modifier) {
    val entries = remember(noisyPoints) {
        noisyPoints.map { noisyLegendEntry(IgnoreReason.fromCode(it.ignoreReason)) }.distinct()
    }
    LegendSurface(modifier) {
        for ((label, color) in entries) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun TrackStatsHeader(summary: TrackSummary) {
    val durationS = summary.endedAt?.let { (it - summary.startedAt) / 1000.0 } ?: 0.0
    val avgKmh = avgSpeedKmh(summary.distanceMeters, durationS)
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        HeaderStat("Distance", formatKm(summary.distanceMeters), Modifier.weight(1f))
        HeaderStat("Duration", formatDuration(summary.startedAt, summary.endedAt), Modifier.weight(1f))
        HeaderStat("Avg speed", if (avgKmh > 0) formatKmh(avgKmh) else "—", Modifier.weight(1f))
    }
}

@Composable
private fun HeaderStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

private fun activityIcon(activity: ActivityType?): ImageVector = when (activity) {
    ActivityType.WALKING -> Icons.AutoMirrored.Filled.DirectionsWalk
    ActivityType.RUNNING -> Icons.AutoMirrored.Filled.DirectionsRun
    ActivityType.CYCLING -> Icons.AutoMirrored.Filled.DirectionsBike
    ActivityType.DRIVING -> Icons.Filled.DirectionsCar
    ActivityType.TAXI -> Icons.Filled.LocalTaxi
    // Route, not Place: the pin means "a stay" in the timeline, and UNKNOWN tracks (e.g. a GPX
    // import without a <type>) are still movement.
    else -> Icons.Filled.Route
}

// A qualitative (categorical) palette for activity type. M3 has no categorical roles, so this is a
// derived set: one fixed saturation + lightness, only the hue rotates, so every category carries
// equal visual weight. It's a calmer sibling of the map's speed ramp (lower saturation) so the list
// stays quiet. Green is nudged toward teal to avoid colliding with the app's green theme accent.
// STILL/UNKNOWN fall back to the neutral scheme colour.
private const val ACTIVITY_SAT = 0.5f
private const val ACTIVITY_LUM = 0.62f

@Composable
private fun activityColor(activity: ActivityType?): Color = when (activity) {
    ActivityType.DRIVING -> Color.hsl(210f, ACTIVITY_SAT, ACTIVITY_LUM) // blue
    ActivityType.TAXI -> Color.hsl(48f, ACTIVITY_SAT, ACTIVITY_LUM)     // taxi yellow
    ActivityType.CYCLING -> Color.hsl(165f, ACTIVITY_SAT, ACTIVITY_LUM) // teal-green
    ActivityType.RUNNING -> Color.hsl(30f, ACTIVITY_SAT, ACTIVITY_LUM)  // orange
    ActivityType.WALKING -> Color.hsl(275f, ACTIVITY_SAT, ACTIVITY_LUM) // violet
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

// Static, per-activity speed→colour scale so tracks are visually comparable across the whole list:
// red (slow) → green (a good cruising pace) → blue (fast). Hue runs 0°(red)→240°(blue), so with an
// evenly-spaced min/mid/max the midpoint speed lands exactly on green.
private const val HUE_RED = 0f
private const val HUE_BLUE = 240f
private const val SPEED_SATURATION = 0.9f

// L=0.5 glows against the dark basemap but washes out (especially the green/yellow middle of
// the ramp) on the pale light basemap — deeper colours there.
private fun rampLuminance(dark: Boolean) = if (dark) 0.5f else 0.33f

/** Speed thresholds (km/h) anchoring the red / green / blue points of the colour ramp per activity. */
private data class SpeedScale(val minKmh: Float, val midKmh: Float, val maxKmh: Float)

private fun speedScaleFor(activity: ActivityType): SpeedScale = when (activity) {
    ActivityType.DRIVING, ActivityType.TAXI, ActivityType.UNKNOWN -> SpeedScale(30f, 90f, 150f)
    ActivityType.CYCLING -> SpeedScale(10f, 22f, 34f)
    ActivityType.RUNNING -> SpeedScale(6f, 11f, 16f)
    ActivityType.WALKING, ActivityType.STILL -> SpeedScale(2f, 5f, 8f)
}

// --- Track line colouring by metric ------------------------------------------------------------

/** Which per-point metric the track line is coloured by. */
enum class ColorMode(val label: String) {
    SPEED("Speed"),
    ELEVATION("Elevation"),
    ACCURACY("Accuracy"),
    SATELLITES("Satellites"),
    CN0("Signal"),
}

/** Grey for points the metric has no value for; darker on the light basemap. */
private fun noDataArgb(dark: Boolean) = Color.hsl(0f, 0f, if (dark) 0.6f else 0.45f).toArgb()

/** Legend content for the current colour mode. */
internal sealed interface Legend {
    /** Continuous red→green→blue ramp with anchor labels. */
    data class Ramp(val left: String, val mid: String, val right: String) : Legend
    /** No point in the track carries this metric. */
    data class None(val message: String) : Legend
}

internal class TrackColoring(val colors: IntArray, val legend: Legend)

/** ARGB on the red(0°)→green(120°)→blue(240°) ramp for [value] between the [redAt] and [blueAt] anchors. */
private fun rampColor(value: Float?, redAt: Float, blueAt: Float, luminance: Float, noData: Int): Int {
    if (value == null) return noData
    val t = ((value - redAt) / (blueAt - redAt)).coerceIn(0f, 1f)
    val hue = HUE_RED + t * (HUE_BLUE - HUE_RED)
    return Color.hsl(hue, SPEED_SATURATION, luminance).toArgb()
}

private fun rampColoring(
    values: List<Float?>, redAt: Float, blueAt: Float, unit: String, emptyMsg: String, dark: Boolean,
): TrackColoring {
    // Resolved once per coloring, not per point — Color.hsl is a real conversion.
    val noData = noDataArgb(dark)
    if (values.all { it == null }) {
        return TrackColoring(IntArray(values.size) { noData }, Legend.None(emptyMsg))
    }
    val luminance = rampLuminance(dark)
    val colors = IntArray(values.size) { rampColor(values[it], redAt, blueAt, luminance, noData) }
    fun num(v: Float) = "%.0f".format(v)
    // Unit only on the rightmost label, else three "… unit" labels overflow the fixed-width legend.
    val right = num(blueAt).let { if (unit.isEmpty()) it else "$it $unit" }
    return TrackColoring(colors, Legend.Ramp(num(redAt), num((redAt + blueAt) / 2f), right))
}

/**
 * The per-point value series for [mode] (null where a point lacks the metric) and its display
 * unit — the single mode→series/unit mapping, feeding both the graph and the map colouring.
 */
private fun metricSeries(
    points: List<TrackPoint>, mode: ColorMode, speedsKmh: FloatArray,
): Pair<List<Float?>, String> = when (mode) {
    ColorMode.SPEED -> List(points.size) { speedsKmh[it] } to "km/h"
    ColorMode.ELEVATION -> points.map { it.altitude?.toFloat() } to "m"
    ColorMode.ACCURACY -> points.map { it.accuracy } to "m"
    ColorMode.SATELLITES -> points.map { it.satellitesInFix?.toFloat() } to "sat"
    ColorMode.CN0 -> points.map { it.cn0 } to "dB"
}

/**
 * Per-point colours + legend for [mode]. Ramps go red→green→blue between two anchor values; where an
 * anchor is "worse" it's placed at red (e.g. accuracy: 50 m = red, 0 m = blue). Points missing the
 * metric are grey.
 */
internal fun trackColoring(
    points: List<TrackPoint>, speedsKmh: FloatArray, mode: ColorMode, activity: ActivityType?,
    dark: Boolean,
): TrackColoring {
    val (values, unit) = metricSeries(points, mode, speedsKmh)
    return when (mode) {
        ColorMode.SPEED -> {
            val s = speedScaleFor(activity ?: ActivityType.UNKNOWN)
            rampColoring(values, s.minKmh, s.maxKmh, unit, "No speed data", dark)
        }
        ColorMode.ELEVATION -> {
            val present = values.filterNotNull()
            if (present.isEmpty()) {
                val noData = noDataArgb(dark)
                TrackColoring(IntArray(points.size) { noData }, Legend.None("No elevation data"))
            } else {
                val lo = present.min()
                val hi = present.max()
                val span = if (hi - lo < 1f) 1f else hi - lo // avoid a zero-width ramp on a flat track
                rampColoring(values, lo, lo + span, unit, "No elevation data", dark)
            }
        }
        // Lower accuracy radius is better, so 0 m sits at the blue (good) end.
        ColorMode.ACCURACY -> rampColoring(values, 50f, 0f, unit, "No accuracy data", dark)
        ColorMode.SATELLITES -> rampColoring(values, 0f, 12f, unit, "No satellite data", dark)
        ColorMode.CN0 -> rampColoring(values, 15f, 45f, unit, "No signal data", dark)
    }
}

/** Horizontally-scrollable chips to pick how the track line is coloured. */
@Composable
private fun ColorModeSelector(selected: ColorMode, onSelect: (ColorMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (mode in ColorMode.entries) {
            FilterChip(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                label = { Text(mode.label) },
            )
        }
    }
}

@Composable
internal fun TrackLegend(legend: Legend, modifier: Modifier) {
    when (legend) {
        is Legend.None ->
            LegendSurface(modifier) {
                Text(legend.message, style = MaterialTheme.typography.labelSmall)
            }
        is Legend.Ramp ->
            LegendSurface(modifier) {
                val luminance = rampLuminance(isSystemInDarkTheme())
                // Dense stops along the same HSL ramp the track uses: the brush blends
                // neighbours in RGB, and RGB midpoints of red/green and green/blue are muddy
                // brown/grey — 30° hue steps stay on-hue.
                val rampBrush = remember(luminance) {
                    Brush.horizontalGradient(
                        (0..8).map {
                            val hue = HUE_RED + it * (HUE_BLUE - HUE_RED) / 8f
                            Color.hsl(hue, SPEED_SATURATION, luminance)
                        },
                    )
                }
                Box(
                    Modifier
                        .width(132.dp)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(rampBrush),
                )
                Spacer(Modifier.height(2.dp))
                Row(Modifier.width(132.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(legend.left, style = MaterialTheme.typography.labelSmall)
                    Text(legend.mid, style = MaterialTheme.typography.labelSmall)
                    Text(legend.right, style = MaterialTheme.typography.labelSmall)
                }
            }
    }
}

@Composable
private fun LegendSurface(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Column(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = content,
        )
    }
}

@Composable
private fun PermissionCard(title: String, body: String, button: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onClick) { Text(button) }
        }
    }
}
