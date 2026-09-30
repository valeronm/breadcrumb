package io.github.valeronm.breadcrumb.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import androidx.core.content.IntentCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.valeronm.breadcrumb.BuildConfig
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.AndroidDistance
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.PlaceClusterer
import io.github.valeronm.breadcrumb.domain.PlaceOverlap
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.TravelNaming
import io.github.valeronm.breadcrumb.ui.theme.AppTheme
import io.github.valeronm.breadcrumb.util.BuildIdentity
import io.github.valeronm.breadcrumb.util.UnitChoice
import java.time.LocalDate
import io.github.valeronm.breadcrumb.data.Settings as AppSettings

private fun Window.setFlag(flag: Int, on: Boolean) {
    if (on) addFlags(flag) else clearFlags(flag)
}

/** A FragmentActivity only because [PrivacyGate]'s biometric prompt hosts itself in a fragment. */
class MainActivity : FragmentActivity() {

    /** GPX URIs handed to us via share/open-with, waiting for the UI to import them. */
    private val pendingGpxImport = mutableStateOf<List<Uri>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        consumeGpxIntent(intent)
        Privacy.load(this)
        watchKeyguard(this)
        setContent {
            AppTheme {
                var unitChoice by remember { mutableStateOf(storedUnitChoice(this)) }
                var mapShade by remember { mutableStateOf(MapShade.of(AppSettings.mapDark(this))) }
                // FLAG_SECURE covers screenshots and the recents thumbnail together — the window
                // either holds still-sensitive content or it doesn't, and the system draws no
                // distinction between who is capturing it.
                SideEffect(Privacy.blockScreenshots) {
                    window.setFlag(
                        WindowManager.LayoutParams.FLAG_SECURE,
                        Privacy.blockScreenshots,
                    )
                }
                // The configuration locale, not Locale.getDefault(): composition observes it, so a
                // mid-process language switch re-resolves the Automatic units choice.
                val locale = LocalConfiguration.current.locales[0]
                // The unit *system* follows the locale's country (metric vs imperial); the symbols
                // follow its language. Two different questions of the same locale, which is why the
                // language picker below must never be allowed to decide the first of them.
                val system = unitChoice.resolve(locale.country)
                val context = LocalContext.current
                val durations = remember(context) { durationSymbols(context) }
                // Keyed on the resolved system rather than on the choice behind it, so its identity
                // is stable: the two screens that colour a track key an O(points) walk on it, and
                // two choices that resolve alike (Automatic and Metric here) must not redo that walk.
                // The symbols need no key — they resolve each string from the context as it is asked.
                val measures = remember(context, system) { measuresOf(context, system) }
                val readerClock = rememberReaderClock()
                CompositionLocalProvider(
                    LocalMeasures provides measures,
                    LocalDurationSymbols provides durations,
                    LocalReaderClock provides readerClock,
                    LocalMapShade provides mapShade,
                ) {
                    PrivacyGate(waitingImports = pendingGpxImport.value?.size ?: 0) {
                        MainScreen(
                            pendingGpxImport = pendingGpxImport,
                            unitChoice = unitChoice,
                            onUnitChoice = {
                                unitChoice = it
                                AppSettings.setUnitChoice(this, it.name)
                            },
                            onMapShade = {
                                mapShade = it
                                AppSettings.setMapDark(this, it.dark)
                            },
                        )
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

private enum class HomeTab(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    /**
     * What the top bar calls the tab. Defaulted to the tab's own name, which is what every tab but
     * one wants — two resources per tab is two places a translator can rename half of it.
     */
    @StringRes val titleRes: Int = labelRes,
) {
    // The Record tab heads the app rather than itself, so its title is the product's name.
    RECORD(R.string.nav_record, Icons.Filled.MyLocation, titleRes = R.string.title_record),
    TIMELINE(R.string.nav_timeline, Icons.Filled.Route),
    PLACES(R.string.nav_places, Icons.Filled.Place),
    INSIGHTS(R.string.nav_insights, Icons.Filled.Insights),
}

/** Track detail or the Settings hub: the full-screen destinations a tab opens directly onto. */
private sealed interface MainDestination {
    data class TrackDetail(val id: Long) : MainDestination
    data object Settings : MainDestination
}

@Composable
private fun MainScreen(
    pendingGpxImport: MutableState<List<Uri>?>,
    unitChoice: UnitChoice,
    onUnitChoice: (UnitChoice) -> Unit,
    onMapShade: (MapShade) -> Unit,
) {
    val context = LocalContext.current
    val viewModel: TrackListViewModel = viewModel()
    val timeline by viewModel.timeline.collectAsStateWithLifecycle()

    // Waits for the lock rather than relying on the gate: PrivacyGate draws over this composition
    // instead of replacing it, so an import shared in while the app is locked would otherwise run
    // and report itself behind the lock screen. Unlocking re-runs the effect.
    SideEffect(pendingGpxImport.value, Privacy.unlocked) {
        val uris = pendingGpxImport.value ?: return@SideEffect
        if (Privacy.isLocked(context)) return@SideEffect
        pendingGpxImport.value = null
        viewModel.importExport.importGpx(
            uris,
            onBusy = { busyToast(context) },
        ) { result ->
            Toast.makeText(context, gpxImportMessage(context, result), Toast.LENGTH_LONG).show()
        }
    }

    // Keep-screen-on while charging: live charger state + persisted preference; the window flag
    // holds the screen only while this activity is in the foreground (no wakelock, no permission).
    val charging = rememberChargingState()
    var keepScreenOn by remember { mutableStateOf(AppSettings.keepScreenOnCharging(context)) }
    val window = (context as? ComponentActivity)?.window
    SideEffect(charging, keepScreenOn, window) {
        window?.setFlag(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, charging && keepScreenOn)
    }

    // Everything recording needs from Android, and the toggle that asks for it — see [RecorderSetup].
    val setup = rememberRecorderSetup()
    var mainDestination by remember { mutableStateOf<MainDestination?>(null) }
    var selectedTab by remember { mutableStateOf(HomeTab.RECORD) }
    // A tab switch replaces the tab's whole composition, but disposing a focused search field is
    // not a reliable keyboard dismissal — drop the focus with the tab that owned it.
    val tabFocusManager = LocalFocusManager.current
    SideEffect(selectedTab) { tabFocusManager.clearFocus() }

    // Full-screen destinations stack above the tabs as overlay layers, each animated in on open and
    // scaled/shifted by the predictive back gesture (Android 14+), previewing what's underneath.

    // The detail (map) screen or Settings — previews the tabs underneath. Its back handler yields
    // while a layer is stacked above it.
    var settingsPage by remember { mutableStateOf<SettingsPage?>(null) }
    // A deleted track's full detail, stacked above the Recently deleted list.
    var discardedTrackId by remember { mutableStateOf<Long?>(null) }
    // Place detail is opened from the Places list or a timeline stay — back lands wherever it was
    // opened from. Keyed by PlaceSummary.key so the screen tracks the live summary while the
    // derivation re-runs underneath (rename, radius change).
    var placeDetailKey by remember { mutableStateOf<String?>(null) }
    // The last summary the key resolved to: keeps the screen stable between re-derivations and
    // re-finds a just-named cluster by centroid (naming moves its key from cluster: to place:).
    var placeDetailSnapshot by remember { mutableStateOf<PlaceResolver.PlaceSummary?>(null) }
    // Whether that place's capture area is being tuned, stacked above its detail. Its own layer
    // because its map wants the whole screen where the detail's is a card — see PlaceEditScreen.
    // A flag rather than a second key: it can only ever be the place the detail below is showing,
    // so deriving the layer's content from that key keeps the two from needing to agree.
    var editingArea by remember { mutableStateOf(false) }
    var mergingPlaces by remember { mutableStateOf(false) }
    // A spot no stop has found, being named in the editor with no detail beneath it: there is no
    // place yet for a detail to be about.
    var newPlaceSpot by remember { mutableStateOf<PlaceResolver.PlaceSummary?>(null) }
    var newPlacePick by remember { mutableStateOf<NewPlacePick?>(null) }
    val placesMapCamera = remember { CameraSlot() }
    // A journey opened from Insights or a Timeline band, keyed by its first night's sample
    // instant — the same key its list row uses, and the only identity a derived journey has.
    var journeyKey by remember { mutableStateOf<Long?>(null) }
    // What another screen has asked the Timeline to land on — see [TimelineJump].
    var timelineJump by remember { mutableStateOf<TimelineJump?>(null) }
    // Tapping the open Places tab sends it home. A counter rather than a slot like the Timeline's,
    // because the map reads it as its frame key — a value that must change, not one that is
    // consumed.
    var placesHomeRequest by remember { mutableIntStateOf(0) }
    // The add-trip form's input, which is also the flag that it is open: the top bar opens it on
    // the day the Timeline was showing, a gap row on the ends that row already knows. Everything
    // the form does with it stays local to the form until its check mark.
    var tripDraft by remember { mutableStateOf<TripDraft?>(null) }
    // The day the Timeline showed when the form was opened — its pickers start there, a trip
    // added while looking at a day usually being a trip on it.
    val timelineViewedDay = remember { TimelineViewedDay() }

    // The stack, declared bottom-up. A layer's `over` is the one it opens on top of; that single
    // mention decides everything stacking implies: which gesture back reaches, which content blurs
    // beneath which, which draws over which, and what a landing on a layer closes. The tabs are its
    // floor — a content-less layer standing for the tabbed UI so what opens over it can name it
    // like any parent; without one, the layers over the tabs would restate wherever the tabs blur
    // a relation stated once here.
    val tabsLayer = remember { OverlayLayerState<Unit>() }
    val mainLayer = rememberOverlayLayer(
        content = mainDestination,
        over = tabsLayer,
        dismiss = { mainDestination = null },
    )
    // Back from a Settings page previews the hub rather than the tabs.
    val settingsLayer = rememberOverlayLayer(
        content = settingsPage,
        over = mainLayer,
        dismiss = { settingsPage = null },
    )
    // Deleted-track detail: back returns to the Recently deleted list, previewing it under the gesture.
    val discardedLayer = rememberOverlayLayer(
        content = discardedTrackId,
        over = settingsLayer,
        dismiss = { discardedTrackId = null },
    )
    // Journey detail: reached from the Insights tab or a Timeline band, so back previews the tabs.
    val journeyLayer = rememberOverlayLayer(
        content = journeyKey,
        over = tabsLayer,
        dismiss = { journeyKey = null },
    )
    val newPlaceLayer = rememberOverlayLayer(
        content = newPlacePick,
        over = tabsLayer,
        dismiss = { newPlacePick = null },
    )
    // Place detail is reached from the Timeline, the Places list, or a journey's map — stacked on
    // the journey layer so one opened there draws above it and back returns to it. Opened from a
    // tab, the journey layer holds nothing, which is the tabs showing through anyway (the same
    // shape as the trip form over a track detail that isn't there).
    val placeLayer = rememberOverlayLayer(
        content = placeDetailKey,
        over = journeyLayer,
        dismiss = { placeDetailKey = null },
        onClosed = { placeDetailSnapshot = null },
    )
    // Capture-area tuning stacks above the place detail — back returns to it, previewed under the
    // gesture, and discards the radius by simply never having written it. Its content is the
    // detail's own key, so the two cannot drift apart or outlive one another, unless it is naming a
    // new spot, where the detail layer is empty and back returns to the tabs.
    val placeEditLayer = rememberOverlayLayer(
        content = placeDetailKey?.takeIf { editingArea } ?: newPlaceSpot?.key,
        over = placeLayer,
        dismiss = {
            editingArea = false
            newPlaceSpot = null
        },
    )
    // Back from a merge returns to the place detail with nothing written.
    val mergeLayer = rememberOverlayLayer(
        content = placeDetailKey?.takeIf { mergingPlaces },
        over = placeLayer,
        dismiss = { mergingPlaces = false },
    )
    // The trip form: back discards the half-entered trip by construction, nothing having been
    // written until its check mark. Stacked on the track detail rather than on the tabs, because a
    // manual track is edited *from* that screen and back must return to it — and a form opened from
    // the Timeline sits above a page that isn't there, which is the tabs showing through anyway.
    val addTripLayer = rememberOverlayLayer(
        content = tripDraft,
        over = mainLayer,
        dismiss = { tripDraft = null },
    )

    // A cross-screen jump to the Timeline tab: the landing is the tabs themselves, so every layer
    // over them closes — asked of the stack, which is what knows what is over the tabs.
    val landOnTimeline = {
        tabsLayer.dismissAbove()
        selectedTab = HomeTab.TIMELINE
    }

    // Owned here, not in a tab: a tab switch disposes the tab's composition and its coroutine scope,
    // and a snackbar still on its timer with them.
    val snackbarHostState = remember { SnackbarHostState() }
    val undo = rememberUndoSnackbar(snackbarHostState)
    var tabsBarHeightPx by remember { mutableIntStateOf(0) }
    // A place goes by the editor's Remove button, and the way back is the Undo — which has to be
    // raised from this host, not from a screen that may be dismissed by the same tap. Deleting
    // leaves the stays, as a detected stop again, and restoring puts the row and the ends stated to
    // it back exactly as they were.
    // Resolved here: the callback below runs outside the composition.
    val placeDeleted = stringResource(R.string.places_deleted)
    val unnamedPlaceDeleted = stringResource(R.string.places_deleted_unnamed)
    val removePlace: (Place) -> Unit = { place ->
        viewModel.deletePlace(place)
        val message = place.label?.let { placeDeleted.format(it) } ?: unnamedPlaceDeleted
        undo.show(message) { viewModel.restorePlace(place) }
    }
    val mergePlaces: (Place, List<Place>) -> Unit = { keep, absorbed ->
        viewModel.mergePlaces(keep, absorbed)
        undo.show(context.counted(R.plurals.places_merged, absorbed.size)) {
            viewModel.unmergePlaces(keep)
        }
        mergingPlaces = false
        placeDetailKey = PlaceResolver.keyOf(keep.id)
    }

    // A map covered by another layer keeps its shade until it is uncovered, rather than rebuilding
    // unseen.
    val mapShade = LocalMapShade.current
    val tabsMapShade = remember { mutableStateOf(mapShade) }
    val tabsUncovered = tabsLayer.onTop
    SideEffect {
        if (tabsUncovered) tabsMapShade.value = mapShade
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // The tabbed UI stays composed underneath so it can be previewed during the back gesture.
        CompositionLocalProvider(LocalMapShade provides tabsMapShade.value) {
            Scaffold(
                modifier = Modifier.blurredBy { tabsLayer.blurDp },
                topBar = {
                    TopAppBar(
                        colors = canvasTopBarColors(),
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(selectedTab.titleRes))
                                // What it says is the variant, named in one place; whether to draw one
                                // at all is a decision of its own, off on release and on demo so that
                                // neither a shipped screen nor a screenshot carries a badge.
                                val badge = BuildIdentity.variant?.takeIf { BuildConfig.SHOW_BUILD_BADGE }
                                if (badge != null) {
                                    Spacer(Modifier.width(8.dp))
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.tertiaryContainer,
                                    ) {
                                        Text(
                                            badge,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                                        )
                                    }
                                }
                            }
                        },
                        actions = {
                            // Only where the trip would land: the Timeline is the one tab that shows
                            // what a manual entry changes.
                            if (selectedTab == HomeTab.TIMELINE) {
                                IconButton(onClick = {
                                    tripDraft = TripDraft(day = timelineViewedDay.read())
                                }) {
                                    Icon(
                                        Icons.Filled.Add,
                                        contentDescription = stringResource(R.string.action_add_missing_trip),
                                    )
                                }
                            }
                            if (selectedTab == HomeTab.PLACES) {
                                IconButton(onClick = {
                                    newPlacePick = NewPlacePick(placesMapCamera.camera?.position())
                                }) {
                                    Icon(
                                        Icons.Filled.Add,
                                        contentDescription = stringResource(R.string.places_new_place),
                                    )
                                }
                            }
                            IconButton(onClick = { mainDestination = MainDestination.Settings }) {
                                Icon(
                                    Icons.Filled.Settings,
                                    contentDescription = stringResource(R.string.action_settings),
                                )
                            }
                        },
                    )
                },
                bottomBar = {
                    // One container step below the canvas: the default surfaceContainer became the
                    // light theme's canvas tone, which made the bar invisible against it.
                    NavigationBar(
                        modifier = Modifier.onSizeChanged { tabsBarHeightPx = it.height },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        for (tab in HomeTab.entries) {
                            NavigationBarItem(
                                selected = selectedTab == tab,
                                onClick = {
                                    // Re-tapping the open tab is the standard "go home" gesture: the
                                    // Timeline returns to today, Places to the top of its list or the
                                    // whole field of places. The tabs that don't wander take nothing.
                                    if (selectedTab != tab) {
                                        selectedTab = tab
                                    } else {
                                        when (tab) {
                                            HomeTab.TIMELINE -> timelineJump = TimelineJump.Home
                                            HomeTab.PLACES -> placesHomeRequest++
                                            else -> Unit
                                        }
                                    }
                                },
                                icon = { Icon(tab.icon, contentDescription = null) },
                                label = { Text(stringResource(tab.labelRes)) },
                            )
                        }
                    }
                },
            ) { inner ->
                Box(modifier = Modifier.fillMaxSize().padding(inner)) {
                    when (selectedTab) {
                        HomeTab.RECORD -> RecordTab(
                            setup = setup.state,
                            autoOn = setup.autoOn,
                            charging = charging,
                            keepScreenOn = keepScreenOn,
                            onToggleKeepScreenOn = { enabled ->
                                keepScreenOn = enabled
                                AppSettings.setKeepScreenOnCharging(context, enabled)
                            },
                            viewModel = viewModel,
                            onGrantSetupStep = setup::grant,
                            onToggleAuto = setup::toggleAuto,
                        )

                        HomeTab.TIMELINE -> TimelineTab(
                            items = timeline,
                            viewModel = viewModel,
                            undo = undo,
                            jump = timelineJump,
                            onJumpShown = { timelineJump = null },
                            viewedDay = timelineViewedDay,
                            onOpen = { mainDestination = MainDestination.TrackDetail(it) },
                            onOpenPlace = { placeDetailKey = it },
                            onOpenJourney = { journeyKey = it.travel.firstNightAt },
                            onAddTrip = { tripDraft = it },
                            onReplay = { track ->
                                TrackReplayer.start(context, track.id)
                                selectedTab = HomeTab.RECORD
                            },
                            onOpenRecording = { selectedTab = HomeTab.RECORD },
                            onOpenData = { settingsPage = SettingsPage.Data },
                        )

                        HomeTab.PLACES -> PlacesTab(
                            viewModel = viewModel,
                            homeRequest = placesHomeRequest,
                            onOpenPlace = { placeDetailKey = it },
                            mapCamera = placesMapCamera,
                            onOpenData = { settingsPage = SettingsPage.Data },
                        )
                        HomeTab.INSIGHTS -> InsightsTab(
                            viewModel = viewModel,
                            onOpenJourney = { journeyKey = it.travel.firstNightAt },
                            onOpenData = { settingsPage = SettingsPage.Data },
                        )
                    }
                }
            }
        }

        // The stacked full-screen layers, bottom to top; each animates in on open and scales/
        // shifts with the predictive-back gesture, previewing the layer underneath.
        MainDestinationOverlay(
            layer = mainLayer,
            viewModel = viewModel,
            undo = undo,
            onOpenSettingsPage = { settingsPage = it },
            onEditTrip = { tripDraft = it },
        )

        NewPlaceOverlay(
            layer = newPlaceLayer,
            viewModel = viewModel,
            // A pick left open beneath the editor keeps its map rendering under two covering layers.
            onPicked = { at ->
                newPlaceSpot = PlaceResolver.emptySpot(at, PlaceClusterer.DEFAULT_RADIUS_M)
                newPlacePick = null
            },
        )

        PlaceDetailOverlay(
            layer = placeLayer,
            viewModel = viewModel,
            snapshot = placeDetailSnapshot,
            onResolved = { s ->
                // Only a summary the fresh list actually held may re-key the screen: `reacquire`
                // hands back this very snapshot when neither the key nor the pin matched anything yet
                // (a derivation still catching up), and following *that* key would rewrite it back to
                // the cluster a place was just created from — after which a create that moved the pin
                // never resolves again.
                if (s !== placeDetailSnapshot) {
                    placeDetailSnapshot = s
                    // The layer stays composed through its exit animation, so a derivation landing
                    // then must not resurrect a key the close just cleared.
                    if (placeDetailKey != null && placeDetailKey != s.key) placeDetailKey = s.key
                }
            },
            onOpenVisit = { stay ->
                timelineJump = TimelineJump.Visit(stay)
                landOnTimeline()
            },
            onAdjustArea = { editingArea = true },
            onRemove = { place ->
                placeLayer.dismiss()
                removePlace(place)
            },
            onOpenMerge = { mergingPlaces = true },
        )

        PlaceEditOverlay(
            layer = placeEditLayer,
            viewModel = viewModel,
            snapshot = newPlaceSpot ?: placeDetailSnapshot,
            snackbarHostState = snackbarHostState,
            // A named spot opens its detail as a create from a stop leaves one, and [onCreated]
            // re-keys it onto the row.
            onSaved = {
                newPlaceSpot?.let { spot ->
                    placeDetailSnapshot = spot
                    placeDetailKey = spot.key
                }
                placeEditLayer.dismiss()
            },
            // The row's id is the only thing that identifies a just-created place until a derivation
            // has run — by position it can't be followed, a hand-placed pin being exactly what may
            // have moved.
            onCreated = { id -> placeDetailKey = PlaceResolver.keyOf(id) },
            // Both layers go with it: the editor and the detail underneath are both about a row that
            // no longer exists, and the detail's key (`place:<id>`) would resolve against nothing.
            onRemove = { place ->
                placeLayer.dismissAbove()
                placeLayer.dismiss()
                removePlace(place)
            },
        )

        MergePlacesOverlay(
            layer = mergeLayer,
            viewModel = viewModel,
            snapshot = placeDetailSnapshot,
            onMerge = mergePlaces,
        )

        SettingsPagesOverlay(
            layer = settingsLayer,
            viewModel = viewModel,
            unitChoice = unitChoice,
            onUnitChoice = onUnitChoice,
            onMapShade = onMapShade,
            onOpenTrack = { discardedTrackId = it },
        )

        DiscardedTrackOverlay(
            layer = discardedLayer,
            viewModel = viewModel,
        )

        AddTripOverlay(
            layer = addTripLayer,
            viewModel = viewModel,
            snackbarHostState = snackbarHostState,
        )

        JourneyDetailOverlay(
            layer = journeyLayer,
            viewModel = viewModel,
            onOpenDay = { day ->
                timelineJump = TimelineJump.Day(day)
                landOnTimeline()
            },
            onOpenPlace = { placeDetailKey = it },
        )

        // Over every layer, since an undo is often raised as one layer closes onto another. Over the
        // tabs it sits where their Scaffold would put it, above the navigation bar.
        val navigationBars = WindowInsets.navigationBars
        SnackbarHost(
            snackbarHostState,
            Modifier
                .align(Alignment.BottomCenter)
                .zIndex(Float.MAX_VALUE)
                .windowInsetsPadding(navigationBars.only(WindowInsetsSides.Horizontal))
                .offset {
                    IntOffset(0, -lerp(tabsBarHeightPx, navigationBars.getBottom(this), tabsLayer.covered))
                },
        )

        when (val open = setup.prompt) {
            null -> Unit
            is SetupPrompt.AllTimeLocation -> BackgroundLocationDisclosure(
                onContinue = setup::answerPrompt,
                onDismiss = setup::dismissPrompt,
            )

            is SetupPrompt.Blocked -> BlockedStepDialog(
                step = open.step,
                bodyRes = open.step.bodyRes(setup.state),
                onOpenSettings = setup::answerPrompt,
                onDismiss = setup::dismissPrompt,
            )
        }
    }
}

@Composable
private fun NewPlaceOverlay(
    layer: OverlayLayerState<NewPlacePick>,
    viewModel: TrackListViewModel,
    onPicked: (Coordinate) -> Unit,
) {
    OverlayFrame(layer) { pick ->
        NewPlaceScreen(viewModel = viewModel, start = pick.start, onClose = layer.dismiss, onPicked = onPicked)
    }
}

/** The add-trip form, over the tabs — back lands on the Timeline that opened it. */
@Composable
private fun AddTripOverlay(
    layer: OverlayLayerState<TripDraft>,
    viewModel: TrackListViewModel,
    snackbarHostState: SnackbarHostState,
) {
    OverlayFrame(layer) { draft ->
        AddTripScreen(
            viewModel = viewModel,
            draft = draft,
            snackbarHostState = snackbarHostState,
            onClose = layer.dismiss,
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
) {
    OverlayFrame(layer) { trackId ->
        // Collected inside the frame, which composes only while the layer has content: the
        // aggregate query stays live no longer than this rarely-open layer.
        val discardedTracks by viewModel.discardedTracks.collectAsStateWithLifecycle()
        TrackDetailScreen(
            trackId = trackId,
            summary = discardedTracks.firstOrNull { it.track.id == trackId }?.track,
            viewModel = viewModel,
            onBack = layer.dismiss,
            // No splitting or editing here: these tracks are on their way out of the timeline, not
            // being organized on it. A deleted trip is restored first and edited after.
            onSplit = null,
            onEditTrip = null,
        )
    }
}

/**
 * The first layer over the tabs, so the predictive-back preview under it shows the tabs — which is
 * where back lands from here.
 */
@Composable
private fun MainDestinationOverlay(
    layer: OverlayLayerState<MainDestination>,
    viewModel: TrackListViewModel,
    undo: UndoSnackbar,
    onOpenSettingsPage: (SettingsPage) -> Unit,
    onEditTrip: (TripDraft) -> Unit,
) {
    // Resolved here rather than in the callback below, which is not a composable scope.
    val splitUndoMessage = stringResource(R.string.undo_track_split)
    OverlayFrame(layer) { rendered ->
        when (rendered) {
            is MainDestination.TrackDetail -> {
                // Off the summaries, not the resolved timeline: the row is all this lookup needs,
                // and the timeline also re-emits for derivation changes no track row moved.
                val tracks by viewModel.tracks.collectAsStateWithLifecycle()
                TrackDetailScreen(
                    trackId = rendered.id,
                    summary = tracks.firstOrNull { it.id == rendered.id },
                    viewModel = viewModel,
                    onBack = layer.dismiss,
                    onSplit = { atTs ->
                        val trackId = rendered.id
                        viewModel.splitTrack(trackId, atTs) { split ->
                            undo.show(splitUndoMessage) { viewModel.unsplitTracks(trackId, split) }
                        }
                        layer.dismiss()
                    },
                    // The form stacks *on* this screen, so it stays open underneath and shows the
                    // rewritten track when the form closes over it.
                    onEditTrip = onEditTrip,
                )
            }

            MainDestination.Settings -> SettingsScreen(onBack = layer.dismiss, onOpenPage = onOpenSettingsPage)
        }
    }
}

/**
 * Place detail: the tabs beneath it, the capture-area editor above. The live summary is re-found by
 * the layer's key each derivation; [onResolved] reports what it resolved to so the caller can keep
 * its snapshot (and key) tracking a renamed cluster.
 *
 * A write the editor has just committed is already carried by the summary this resolves to, dressed
 * in `TrackListViewModel.places` for every reader of it. So the key machinery here reads one thing —
 * what the derivation says — and [snapshot] means only what it has always meant: the last summary
 * this key resolved to, kept so a re-derivation cannot empty the screen mid-flight.
 */
@Composable
private fun PlaceDetailOverlay(
    layer: OverlayLayerState<String>,
    viewModel: TrackListViewModel,
    snapshot: PlaceResolver.PlaceSummary?,
    onResolved: (PlaceResolver.PlaceSummary) -> Unit,
    onOpenVisit: (StayDeriver.Stay) -> Unit,
    onAdjustArea: () -> Unit,
    onRemove: (Place) -> Unit,
    onOpenMerge: () -> Unit,
) {
    OverlayFrame(layer) { detailKey ->
        // Inside the frame, so the summaries behind `places` are computed only while this layer is
        // up — the derivation itself stays hot for the timeline, but this reading of it is idle
        // unless a screen wants it, and the frame is what knows that.
        val placeSummaries by viewModel.places.collectAsStateWithLifecycle()
        val pending by viewModel.pendingPlaceRow.collectAsStateWithLifecycle()
        val summary = rememberPlaceSummary(placeSummaries, detailKey, snapshot, pending)
        SideEffect(summary) {
            summary?.let(onResolved)
        }
        summary?.let { detail ->
            val canMerge = remember(detail.place, placeSummaries) {
                val row = detail.place ?: return@remember false
                PlaceOverlap.candidatesFor(row, placeSummaries.orEmpty().mapNotNull { it.place }, AndroidDistance)
                    .isNotEmpty()
            }
            PlaceDetailScreen(
                summary = detail,
                viewModel = viewModel,
                onBack = layer.dismiss,
                onOpenVisit = onOpenVisit,
                onAdjustArea = onAdjustArea,
                onRemove = onRemove,
                canMerge = canMerge,
                onOpenMerge = onOpenMerge,
            )
        }
    }
}

/** Journey detail, over the tabs — back lands on the Insights list that opened it. */
@Composable
private fun JourneyDetailOverlay(
    layer: OverlayLayerState<Long>,
    viewModel: TrackListViewModel,
    onOpenDay: (LocalDate) -> Unit,
    onOpenPlace: (String) -> Unit,
) {
    OverlayFrame(layer) { key ->
        // Inside the frame, so the journeys are derived only while this layer is up.
        val travels by viewModel.travels.collectAsStateWithLifecycle()
        // The last summary the key resolved to keeps the screen alive while the derivation
        // re-runs, and through a re-derivation that moved the journey's first night out from
        // under its key. Local to the frame: its lifetime is the layer's, so nothing above
        // needs to hold or clear it.
        var snapshot by remember { mutableStateOf<TravelNaming.Summary?>(null) }
        val resolved = travels?.firstOrNull { it.travel.firstNightAt == key }
        if (resolved != null) SideEffect(resolved) { snapshot = resolved }
        (resolved ?: snapshot)?.let { detail ->
            JourneyDetailScreen(
                summary = detail,
                viewModel = viewModel,
                onBack = layer.dismiss,
                onOpenDay = onOpenDay,
                onOpenPlace = onOpenPlace,
            )
        }
    }
}

/**
 * Tuning a place's capture area: stacked above its detail, which the predictive-back gesture
 * previews underneath.
 */
@Composable
private fun PlaceEditOverlay(
    layer: OverlayLayerState<String>,
    viewModel: TrackListViewModel,
    snapshot: PlaceResolver.PlaceSummary?,
    snackbarHostState: SnackbarHostState,
    onSaved: () -> Unit,
    onCreated: (Long) -> Unit,
    onRemove: (Place) -> Unit,
) {
    OverlayFrame(layer) { editKey ->
        val placeSummaries by viewModel.places.collectAsStateWithLifecycle()
        // The detail screen's own snapshot, shared: a named place's key is `place:<id>` and nothing
        // can move it, but a cluster being named here is keyed `cluster:<n>` — an index a
        // re-derivation is free to reassign, which without the fallback would drop the editor
        // mid-edit.
        val pending by viewModel.pendingPlaceRow.collectAsStateWithLifecycle()
        val summary = rememberPlaceSummary(placeSummaries, editKey, snapshot, pending)
        summary?.let { detail ->
            val neighborhood = rememberNeighborhood(editKey, detail, placeSummaries)
            // Their endpoints as gray dots, named neighbors as labeled pins — the only part of a
            // neighborhood that is about drawing rather than about what a radius would take.
            val neighbors = remember(neighborhood) {
                buildList(neighborhood.candidates.size) {
                    for (other in neighborhood.nearby) {
                        for (endpoint in other.endpoints) add(PlaceMarker(endpoint))
                        other.place?.let { add(PlaceMarker(other.anchor, it)) }
                    }
                }
            }
            PlaceEditScreen(
                summary = detail,
                neighbors = neighbors,
                candidates = neighborhood.candidates,
                rivals = neighborhood.rivals,
                viewModel = viewModel,
                snackbarHostState = snackbarHostState,
                onClose = layer.dismiss,
                onSaved = onSaved,
                onCreated = onCreated,
                onRemove = onRemove,
            )
        }
    }
}

@Composable
private fun MergePlacesOverlay(
    layer: OverlayLayerState<String>,
    viewModel: TrackListViewModel,
    snapshot: PlaceResolver.PlaceSummary?,
    onMerge: (Place, List<Place>) -> Unit,
) {
    OverlayFrame(layer) { key ->
        val liveSummaries by viewModel.places.collectAsStateWithLifecycle()
        val livePending by viewModel.pendingPlaceRow.collectAsStateWithLifecycle()
        // A merge removes the absorbed rows, the opened one among them, while this page is still
        // animating out.
        val held = remember { HeldMergeSources() }
        if (layer.requested || held.summaries == null) {
            held.summaries = liveSummaries
            held.pending = livePending
        }
        val placeSummaries = held.summaries
        val pending = held.pending
        val opened = rememberPlaceSummary(placeSummaries, key, snapshot, pending)?.place ?: return@OverlayFrame
        val placed = remember(placeSummaries) {
            placeSummaries.orEmpty().mapNotNull { s -> s.place?.let { it to s } }
        }
        val rows = remember(placed) { placed.map { it.first } }
        // Fixed on open: choosing a keeper changes only the roles.
        val memberIds = remember(key, placeSummaries != null) {
            listOf(opened.id) + PlaceOverlap.candidatesFor(opened, rows, AndroidDistance).map { it.id }
        }
        val members = remember(placed, memberIds) {
            val byId = placed.filter { it.first.id in memberIds }.associate { it.first.id to it.second }
            memberIds.mapNotNull { byId[it] }
        }
        MergePlacesScreen(
            opened = opened,
            members = members,
            onBack = layer.dismiss,
            onMerge = onMerge,
        )
    }
}

private class HeldMergeSources {
    var summaries: List<PlaceResolver.PlaceSummary>? = null
    var pending: TrackListViewModel.PendingPlace? = null
}

/**
 * The live summary a place-screen key points at. Zero-visit pass-through clusters are included
 * (summarize emits every cluster): gap sides open even without an earned stay, and their endpoints
 * show as neighbor context on adjacent places' maps. [snapshot] keeps the screen stable between
 * re-derivations and re-finds a just-named cluster by centroid (its key moves `cluster:` → `place:`).
 *
 * [summaries] is null until the derivation lands, which needs no case of its own: a key resolves
 * against nothing then, and null is already the answer for a key this list doesn't hold.
 *
 * [pending] dresses the answer too, since [snapshot] is in no list it dresses.
 */
@Composable
private fun rememberPlaceSummary(
    summaries: List<PlaceResolver.PlaceSummary>?,
    key: String?,
    snapshot: PlaceResolver.PlaceSummary?,
    pending: TrackListViewModel.PendingPlace?,
): PlaceResolver.PlaceSummary? =
    remember(summaries, key, snapshot, pending) {
        PlaceResolver.reacquire(summaries.orEmpty(), key, snapshot)?.let { pending?.dress(it) ?: it }
    }

@Composable
private fun SettingsPagesOverlay(
    layer: OverlayLayerState<SettingsPage>,
    viewModel: TrackListViewModel,
    unitChoice: UnitChoice,
    onUnitChoice: (UnitChoice) -> Unit,
    onMapShade: (MapShade) -> Unit,
    onOpenTrack: (Long) -> Unit,
) {
    OverlayFrame(layer) { rendered ->
        when (rendered) {
            SettingsPage.Recording -> RecordingSettingsScreen(layer.dismiss)
            SettingsPage.Trips -> TripsSettingsScreen(layer.dismiss)
            SettingsPage.Display -> DisplaySettingsScreen(layer.dismiss, unitChoice, onUnitChoice, onMapShade)
            SettingsPage.Privacy -> PrivacySettingsScreen(layer.dismiss)
            SettingsPage.Data -> DataSettingsScreen(layer.dismiss, viewModel)
            SettingsPage.RecentlyDeleted -> DiscardedTracksScreen(
                viewModel = viewModel,
                onBack = layer.dismiss,
                onOpenTrack = onOpenTrack,
            )
            SettingsPage.Logs -> LogsScreen(onBack = layer.dismiss)
            SettingsPage.About -> AboutSettingsScreen(layer.dismiss)
        }
    }
}

internal fun gpxImportMessage(
    context: Context,
    result: ImportExportController.GpxImportSummary,
): String = buildList {
    add(context.counted(R.plurals.gpx_imported, result.imported))
    if (result.duplicates > 0) add(context.counted(R.plurals.gpx_duplicates, result.duplicates))
    if (result.overlapping > 0) add(context.counted(R.plurals.gpx_overlapping, result.overlapping))
    if (result.failed > 0) add(context.counted(R.plurals.gpx_failed, result.failed))
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
