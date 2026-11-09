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
import androidx.compose.material.icons.filled.HistoryToggleOff
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.valeronm.breadcrumb.BuildConfig
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.AndroidDistance
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.domain.PlaceClusterer
import io.github.valeronm.breadcrumb.domain.PlaceOverlap
import io.github.valeronm.breadcrumb.domain.PlaceResolver
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.TravelNaming
import io.github.valeronm.breadcrumb.domain.atlas.ListedCity
import io.github.valeronm.breadcrumb.ui.theme.AppTheme
import io.github.valeronm.breadcrumb.util.BuildIdentity
import io.github.valeronm.breadcrumb.util.UnitChoice
import org.maplibre.android.camera.CameraPosition
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
        DebugTools.load(this)
        watchKeyguard(this)
        setContent {
            AppTheme {
                var unitChoice by remember { mutableStateOf(storedUnitChoice(this)) }
                var mapShade by remember { mutableStateOf(MapShade.of(AppSettings.mapDark(this))) }
                var smoothTracks by remember { mutableStateOf(AppSettings.smoothTracks(this)) }
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
                    LocalSmoothTracks provides smoothTracks,
                ) {
                    PrivacyGate(waitingImports = pendingGpxImport.value?.size ?: 0) {
                        MainScreen(
                            language = locale.language,
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
                            onSmoothTracks = {
                                smoothTracks = it
                                AppSettings.setSmoothTracks(this, it)
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
    RECORD(R.string.nav_record, Icons.Filled.MyLocation, titleRes = R.string.app_brand),
    TIMELINE(R.string.nav_timeline, Icons.Filled.Route),
    PLACES(R.string.nav_places, Icons.Filled.Place),
    INSIGHTS(R.string.nav_insights, Icons.Filled.Insights),
}

/** A page the back stack can hold; each carries what identifies it and what it opens with. */
private sealed interface Destination {
    /** [wholeTrip] opens on the trip [id] is part of. */
    data class TripPage(val id: Long, val wholeTrip: Boolean = false) : Destination
    data object SettingsHub : Destination
    data class SettingsSection(val page: SettingsPage) : Destination
    data class DeletedTrackPage(val trackId: Long) : Destination
    data object MissingRecordingsPage : Destination
    data class GapPage(val afterTrackId: Long, val atDeparture: Boolean = true) : Destination
    data class StayPage(val afterTrackId: Long) : Destination

    /** Keyed by the journey's first night's sample instant, the only identity a derived journey has. */
    data class JourneyPage(val firstNightAt: Long) : Destination
    data class CityPage(val city: ListedCity) : Destination

    /** [seed] is the summary the page shows until [key] resolves. */
    data class PlacePage(override val key: String, val seed: PlaceResolver.PlaceSummary? = null) : PlaceShown

    /**
     * The capture-area editor. [placeEntry] is the place page it edits, which a create re-keys; null
     * where a spot is named with no place page beneath.
     */
    data class PlaceEditor(
        override val key: String,
        val seed: PlaceResolver.PlaceSummary,
        val placeEntry: Long?,
        val stayClaim: StayClaim? = null,
    ) : PlaceShown
    data class PlaceMerge(override val key: String, val seed: PlaceResolver.PlaceSummary, val placeEntry: Long) : PlaceShown

    /** [start] is null for a pick opened with no map showing. */
    data class NewPlaceMap(val start: CameraPosition?, val stayClaim: StayClaim? = null) : Destination
    data class TripForm(val draft: TripDraft) : Destination
}

/** A page showing one place by its [key]. */
private sealed interface PlaceShown : Destination {
    val key: String
}

private fun PlaceShown.withKey(key: String): PlaceShown = when (this) {
    is Destination.PlacePage -> copy(key = key)
    is Destination.PlaceEditor -> copy(key = key)
    is Destination.PlaceMerge -> copy(key = key)
}

/** The pages showing a place, moved to [key]; any other page is left as it is. */
private fun Destination.rekeyed(key: String): Destination = (this as? PlaceShown)?.withKey(key) ?: this

/** [value] as last seen [uncovered]: a map under a page keeps what it draws rather than rebuilding unseen. */
@Composable
private fun <T> heldWhileCovered(uncovered: Boolean, value: T): T {
    val held = remember { mutableStateOf(value) }
    SideEffect { if (uncovered) held.value = value }
    return held.value
}

@Composable
private fun MainScreen(
    language: String,
    pendingGpxImport: MutableState<List<Uri>?>,
    unitChoice: UnitChoice,
    onUnitChoice: (UnitChoice) -> Unit,
    onMapShade: (MapShade) -> Unit,
    onSmoothTracks: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val viewModel: TrackListViewModel = viewModel()
    LaunchedEffect(viewModel, language) { viewModel.useLanguage(language) }
    val timeline by viewModel.timeline.collectAsStateWithLifecycle()
    val hasMissingRecordings by viewModel.hasMissingRecordings.collectAsStateWithLifecycle()

    // Waits for the lock rather than relying on the gate: PrivacyGate draws over this composition
    // instead of replacing it, so an import shared in while the app is locked would otherwise run
    // and report itself behind the lock screen. Unlocking re-runs the effect.
    SideEffect(pendingGpxImport.value, Privacy.unlocked) {
        val uris = pendingGpxImport.value ?: return@SideEffect
        if (Privacy.isLocked(context)) return@SideEffect
        pendingGpxImport.value = null
        viewModel.importExport.importGpx(uris)
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
    val backStack = remember { BackStackHost<Destination>() }
    val openTrack: (Long) -> Unit = { backStack.push(Destination.TripPage(it)) }
    val openPlace: (String) -> Unit = { backStack.push(Destination.PlacePage(it)) }
    val openJourney: (TravelNaming.Summary) -> Unit = { backStack.push(Destination.JourneyPage(it.travel.firstNightAt)) }
    val openGap: (Long) -> Unit = { backStack.push(Destination.GapPage(it)) }
    val openTripForm: (TripDraft) -> Unit = { backStack.push(Destination.TripForm(it)) }
    val openData: () -> Unit = { backStack.push(Destination.SettingsSection(SettingsPage.Data)) }
    var selectedTab by remember { mutableStateOf(HomeTab.RECORD) }
    val tabStateHolder = rememberSaveableStateHolder()
    // A tab switch replaces the tab's whole composition, but disposing a focused search field is
    // not a reliable keyboard dismissal — drop the focus with the tab that owned it.
    val tabFocusManager = LocalFocusManager.current
    SideEffect(selectedTab) { tabFocusManager.clearFocus() }

    // Held here: a tab or the list/map toggle leaving composition drops the map page's own remember.
    val placesMapCamera = remember { CameraCarry() }
    // What another screen has asked the Timeline to land on — see [TimelineJump].
    var timelineJump by remember { mutableStateOf<TimelineJump?>(null) }
    // Tapping the open Places tab sends it home. A counter rather than a slot like the Timeline's,
    // because the map reads it as its frame key — a value that must change, not one that is
    // consumed.
    var placesHomeRequest by remember { mutableIntStateOf(0) }
    // The day the Timeline showed when the form was opened — its pickers start there, a trip
    // added while looking at a day usually being a trip on it.
    val timelineViewedDay = remember { TimelineViewedDay() }

    // A cross-screen jump to the Timeline tab: the landing is the tabs themselves, so every page closes.
    val showOnTimeline: (TimelineJump) -> Unit = { jump ->
        timelineJump = jump
        backStack.clear()
        selectedTab = HomeTab.TIMELINE
    }

    // Owned here, not in a tab: a tab switch disposes the tab's composition and its coroutine scope,
    // and a snackbar still on its timer with them.
    val snackbarHostState = remember { SnackbarHostState() }
    val undo = rememberUndoSnackbar(snackbarHostState)
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
        }
    }
    var tabsBarHeightPx by remember { mutableIntStateOf(0) }
    // A place goes by the editor's Remove button, and the way back is the Undo — which has to be
    // raised from this host, not from a screen that may be dismissed by the same tap. Deleting
    // leaves the stays, as a detected stop again, and restoring puts the row and the ends stated to
    // it back exactly as they were.
    // Resolved here: the callback below runs outside the composition.
    val placeDeleted = stringResource(R.string.places_deleted)
    val stayedUndoMessage = stringResource(R.string.gap_stayed_undo)
    val stayPlaceChangedMessage = stringResource(R.string.stay_place_changed_undo)
    val mergedUndoMessage = stringResource(R.string.timeline_undo_merged)
    val hiddenUndoMessage = stringResource(R.string.timeline_undo_hidden)
    val unnamedPlaceDeleted = stringResource(R.string.places_deleted_unnamed)
    val removePlace: (Place) -> Unit = { place ->
        viewModel.deletePlace(place)
        val message = place.label?.let { placeDeleted.format(it) } ?: unnamedPlaceDeleted
        undo.show(message) { viewModel.restorePlace(place) }
    }
    val stayedHere: (StayClaim, Long, String) -> Unit = { claim, placeId, message ->
        viewModel.stayedHere(claim, placeId) { done ->
            undo.show(message) { viewModel.undoStayedHere(done) }
        }
    }

    val tabsMapShade = heldWhileCovered(backStack.uncovered, LocalMapShade.current)
    val tabsSmoothTracks = heldWhileCovered(backStack.uncovered, LocalSmoothTracks.current)

    Box(modifier = Modifier.fillMaxSize()) {
        // The tabbed UI stays composed underneath so it can be previewed during the back gesture.
        CompositionLocalProvider(
            LocalMapShade provides tabsMapShade,
            LocalSmoothTracks provides tabsSmoothTracks,
        ) {
            Scaffold(
                modifier = Modifier.blurredBy { backStack.tabsBlurDp }.readOutOnlyWhen(backStack.uncovered),
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
                                if (hasMissingRecordings) {
                                    IconButton(onClick = { backStack.push(Destination.MissingRecordingsPage) }) {
                                        Icon(
                                            Icons.Filled.HistoryToggleOff,
                                            contentDescription = stringResource(R.string.missing_recordings_title),
                                        )
                                    }
                                }
                                IconButton(onClick = {
                                    backStack.push(Destination.TripForm(TripDraft(day = timelineViewedDay.read())))
                                }) {
                                    Icon(
                                        Icons.Filled.Add,
                                        contentDescription = stringResource(R.string.action_add_missing_trip),
                                    )
                                }
                            }
                            if (selectedTab == HomeTab.PLACES) {
                                IconButton(onClick = {
                                    backStack.push(Destination.NewPlaceMap(placesMapCamera.position()))
                                }) {
                                    Icon(
                                        Icons.Filled.Add,
                                        contentDescription = stringResource(R.string.places_new_place),
                                    )
                                }
                            }
                            IconButton(onClick = { backStack.push(Destination.SettingsHub) }) {
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
                    // A tab leaves composition when another is picked, which discards its
                    // rememberSaveable state, a list's scroll among it, unless a holder keeps it.
                    tabStateHolder.SaveableStateProvider(selectedTab) {
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
                                onOpen = openTrack,
                                onOpenTrip = { backStack.push(Destination.TripPage(it, wholeTrip = true)) },
                                onOpenPlace = openPlace,
                                onOpenJourney = openJourney,
                                onOpenGap = openGap,
                                onOpenStay = { backStack.push(Destination.StayPage(it)) },
                                onReplay = { track ->
                                    TrackReplayer.start(context, track.id)
                                    selectedTab = HomeTab.RECORD
                                },
                                onOpenRecording = { selectedTab = HomeTab.RECORD },
                                onOpenData = openData,
                            )

                            HomeTab.PLACES -> PlacesTab(
                                viewModel = viewModel,
                                homeRequest = placesHomeRequest,
                                onOpenPlace = openPlace,
                                mapCamera = placesMapCamera,
                                onOpenData = openData,
                            )
                            HomeTab.INSIGHTS -> InsightsTab(
                                viewModel = viewModel,
                                onOpenJourney = openJourney,
                                onOpenData = openData,
                                onOpenCity = { backStack.push(Destination.CityPage(it)) },
                            )
                        }
                    }
                }
            }
        }

        StackPages(backStack) { entry ->
            // A closing page's buttons stay live, so an action that ends the page writes only when this reports it closing.
            val closes: () -> Boolean = { backStack.dismiss(entry.id) }
            val close: () -> Unit = { closes() }
            val rekey: (String) -> Unit = { key -> backStack.update(entry.id) { it.rekeyed(key) } }
            when (val page = entry.destination) {
                is Destination.TripPage -> TripDetailPage(
                    page = page,
                    viewModel = viewModel,
                    undo = undo,
                    closes = closes,
                    onEditTrip = openTripForm,
                    onShowOnTimeline = showOnTimeline,
                )

                Destination.SettingsHub -> SettingsScreen(
                    viewModel::say,
                    onBack = close,
                    onOpenPage = { backStack.push(Destination.SettingsSection(it)) },
                )

                is Destination.SettingsSection -> SettingsSectionPage(
                    page = page.page,
                    viewModel = viewModel,
                    unitChoice = unitChoice,
                    onUnitChoice = onUnitChoice,
                    onMapShade = onMapShade,
                    onSmoothTracks = onSmoothTracks,
                    onBack = close,
                    onOpenTrack = { backStack.push(Destination.DeletedTrackPage(it)) },
                )

                is Destination.DeletedTrackPage -> DeletedTrackDetailPage(
                    trackId = page.trackId,
                    viewModel = viewModel,
                    onBack = close,
                )

                Destination.MissingRecordingsPage -> MissingRecordingsScreen(
                    viewModel = viewModel,
                    onBack = close,
                    onOpen = openGap,
                )

                is Destination.GapPage -> GapDetailScreen(
                    afterTrackId = page.afterTrackId,
                    viewModel = viewModel,
                    onBack = close,
                    onAddTrip = openTripForm,
                    onStayedHere = { claim, placeId -> stayedHere(claim, placeId, stayedUndoMessage) },
                    onMerge = { plan -> undo.mergeTracks(viewModel, plan, mergedUndoMessage) },
                    onShowOnTimeline = { gap -> showOnTimeline(TimelineJump.Gap(gap, atDeparture = page.atDeparture)) },
                    onOpenPlace = openPlace,
                )

                is Destination.StayPage -> StayDetailScreen(
                    afterTrackId = page.afterTrackId,
                    viewModel = viewModel,
                    onBack = close,
                    onShowOnTimeline = { stay -> showOnTimeline(TimelineJump.Visit(stay)) },
                    onOpenPlace = openPlace,
                    onStayAt = { claim, place -> stayedHere(claim, place.id, stayPlaceChangedMessage) },
                    onNewPlace = { claim, middle ->
                        val start = CameraPosition.Builder().target(middle.toLatLng()).zoom(17.0).build()
                        backStack.push(Destination.NewPlaceMap(start, claim))
                    },
                    onNameSpot = { backStack.push(Destination.PlaceEditor(it.key, seed = it, placeEntry = null)) },
                    onMerge = { plan -> undo.mergeTracks(viewModel, plan, mergedUndoMessage) },
                    onHide = { undo.hideStays(viewModel, listOf(it)) { hiddenUndoMessage } },
                    onHideAll = { stays ->
                        undo.hideStays(viewModel, stays) { context.counted(R.plurals.stay_hidden_all, it) }
                    },
                )

                is Destination.JourneyPage -> JourneyDetailPage(
                    firstNightAt = page.firstNightAt,
                    viewModel = viewModel,
                    onBack = close,
                    onOpenDay = { day -> showOnTimeline(TimelineJump.Day(day)) },
                    onOpenPlace = openPlace,
                )

                is Destination.CityPage -> CityScreen(
                    city = page.city,
                    viewModel = viewModel,
                    onBack = close,
                    onOpenPlace = openPlace,
                )

                is Destination.PlacePage -> PlaceDetailPage(
                    page = page,
                    viewModel = viewModel,
                    onRekey = rekey,
                    onBack = close,
                    onOpenVisit = { stay -> backStack.push(Destination.StayPage(stay.afterTrackId)) },
                    onOpenGap = { listed ->
                        backStack.push(Destination.GapPage(listed.gap.afterTrackId, atDeparture = listed.fromHere))
                    },
                    onOpenPlace = openPlace,
                    onAdjustArea = { shown ->
                        backStack.push(Destination.PlaceEditor(shown.key, shown, placeEntry = entry.id))
                    },
                    onRemove = { place -> if (closes()) removePlace(place) },
                    onOpenMerge = { shown ->
                        backStack.push(Destination.PlaceMerge(shown.key, shown, placeEntry = entry.id))
                    },
                )

                is Destination.PlaceEditor -> PlaceEditPage(
                    page = page,
                    viewModel = viewModel,
                    snackbarHostState = snackbarHostState,
                    onRekey = rekey,
                    onClose = close,
                    // A spot named with no place page beneath opens one as the editor closes; a create then
                    // re-keys whichever place page shows it.
                    onSaved = save@{
                        val landing = if (page.placeEntry != null) {
                            page.placeEntry.takeIf { closes() }
                        } else {
                            backStack.closeOnto(entry.id, Destination.PlacePage(page.key, seed = page.seed))
                        } ?: return@save null
                        return@save { id -> backStack.update(landing) { it.rekeyed(PlaceResolver.keyOf(id)) } }
                    },
                    // The place page beneath goes with it, since its key would resolve against a row that is gone.
                    onRemove = { place ->
                        if (closes()) {
                            page.placeEntry?.let(backStack::close)
                            removePlace(place)
                        }
                    },
                )

                is Destination.PlaceMerge -> PlaceMergePage(
                    page = page,
                    leaving = entry.leaving,
                    viewModel = viewModel,
                    onRekey = rekey,
                    onBack = close,
                    onMerge = { keep, absorbed ->
                        if (closes()) {
                            viewModel.mergePlaces(keep, absorbed)
                            undo.show(context.counted(R.plurals.places_merged, absorbed.size)) {
                                viewModel.unmergePlaces(keep)
                            }
                            backStack.update(page.placeEntry) { it.rekeyed(PlaceResolver.keyOf(keep.id)) }
                        }
                    },
                )

                is Destination.NewPlaceMap -> NewPlaceScreen(
                    viewModel = viewModel,
                    start = page.start,
                    onClose = close,
                    // The editor opens over the closing map, and back from it lands on what opened the map.
                    onPicked = { at ->
                        if (closes()) {
                            val spot = PlaceResolver.emptySpot(at, PlaceClusterer.DEFAULT_RADIUS_M)
                            backStack.push(
                                Destination.PlaceEditor(spot.key, seed = spot, placeEntry = null, stayClaim = page.stayClaim),
                            )
                        }
                    },
                )

                is Destination.TripForm -> AddTripScreen(
                    viewModel = viewModel,
                    draft = page.draft,
                    leaving = entry.leaving,
                    onClose = close,
                )
            }
        }

        // Over every page, since an undo is often raised as one page closes onto another. Over the
        // tabs it sits where their Scaffold would put it, above the navigation bar.
        val navigationBars = WindowInsets.navigationBars
        SnackbarHost(
            snackbarHostState,
            Modifier
                .align(Alignment.BottomCenter)
                .zIndex(Float.MAX_VALUE)
                .windowInsetsPadding(navigationBars.only(WindowInsetsSides.Horizontal))
                .offset {
                    IntOffset(0, -lerp(tabsBarHeightPx, navigationBars.getBottom(this), backStack.tabsCovered))
                },
            snackbar = { SwipeDismissSnackbar(it) },
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
private fun DeletedTrackDetailPage(
    trackId: Long,
    viewModel: TrackListViewModel,
    onBack: () -> Unit,
) {
    // Collected here, so the aggregate query stays live no longer than this rarely-open page.
    val discardedTracks by viewModel.discardedTracks.collectAsStateWithLifecycle()
    val tracks = remember(discardedTracks, trackId) {
        listOfNotNull(discardedTracks.firstOrNull { it.track.id == trackId }?.track)
    }
    TrackDetailScreen(
        tracks = tracks,
        openedId = trackId,
        openWhole = false,
        viewModel = viewModel,
        onBack = onBack,
        // No splitting or editing here: these tracks are on their way out of the timeline, not
        // being organized on it. A deleted trip is restored first and edited after.
        onSplit = null,
        onDelete = null,
        onEditTrip = null,
        onShowOnTimeline = null,
    )
}

@Composable
private fun TripDetailPage(
    page: Destination.TripPage,
    viewModel: TrackListViewModel,
    undo: UndoSnackbar,
    closes: () -> Boolean,
    onEditTrip: (TripDraft) -> Unit,
    onShowOnTimeline: (TimelineJump) -> Unit,
) {
    // Resolved here rather than in the callback below, which is not a composable scope.
    val splitUndoMessage = stringResource(R.string.undo_track_split)
    val trimUndoMessage = stringResource(R.string.undo_track_trimmed)
    val deletedUndoMessage = stringResource(R.string.timeline_undo_deleted)

    // Off the summaries, not the resolved timeline: the row is all a page needs, and the
    // timeline also re-emits for derivation changes no track row moved.
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    TrackDetailScreen(
        tracks = tracks,
        openedId = page.id,
        openWhole = page.wholeTrip,
        viewModel = viewModel,
        onBack = { closes() },
        onSplit = { trackId, atTs ->
            if (closes()) {
                viewModel.splitTrack(trackId, atTs) { split ->
                    undo.show(if (split.trimmed) trimUndoMessage else splitUndoMessage) {
                        viewModel.unsplitTracks(trackId, split)
                    }
                }
            }
        },
        onDelete = { trackIds -> if (closes()) undo.deleteTracks(viewModel, trackIds, deletedUndoMessage) },
        // The form stacks on this screen, which stays open underneath.
        onEditTrip = onEditTrip,
        onShowOnTimeline = { trip -> onShowOnTimeline(TimelineJump.TripRow(trip)) },
    )
}

/** True once [subject] has been [present] and no longer is; a new [subject] has to be seen again. */
@Composable
internal fun rememberVanished(subject: Any?, present: Boolean): Boolean {
    val seen = remember(subject) { mutableStateOf(false) }
    SideEffect(subject, present) { if (present) seen.value = true }
    return seen.value && !present
}

/**
 * The live summary [key] resolves to ([rememberPlaceSummary]), holding the last one it resolved to
 * from [seed] on, and reporting through [onRekey] a summary found under another key.
 */
@Composable
private fun rememberFollowedPlace(
    summaries: List<PlaceResolver.PlaceSummary>?,
    pending: TrackListViewModel.PendingPlace?,
    key: String,
    seed: PlaceResolver.PlaceSummary?,
    onRekey: (String) -> Unit,
): PlaceResolver.PlaceSummary? {
    var snapshot by remember { mutableStateOf(seed) }
    val summary = rememberPlaceSummary(summaries, key, snapshot, pending)
    SideEffect(summary) {
        // Only a summary the fresh list held may re-key: `reacquire` hands back this very snapshot
        // when neither the key nor the pin matched anything yet, and following that key would
        // rewrite it back to the cluster a place was just created from.
        if (summary != null && summary !== snapshot) {
            snapshot = summary
            if (summary.key != key) onRekey(summary.key)
        }
    }
    return summary
}

/**
 * A place's detail, re-found by the page's key on each derivation.
 *
 * A write the editor has just committed is already carried by the summary this resolves to, dressed
 * in `TrackListViewModel.places` for every reader of it.
 */
@Composable
private fun PlaceDetailPage(
    page: Destination.PlacePage,
    viewModel: TrackListViewModel,
    onRekey: (String) -> Unit,
    onBack: () -> Unit,
    onOpenVisit: (StayDeriver.Stay) -> Unit,
    onOpenGap: (PlaceResolver.PlaceGap) -> Unit,
    onOpenPlace: (String) -> Unit,
    onAdjustArea: (PlaceResolver.PlaceSummary) -> Unit,
    onRemove: (Place) -> Unit,
    onOpenMerge: (PlaceResolver.PlaceSummary) -> Unit,
) {
    // Collected here, so the summaries behind `places` are computed only while this page is open:
    // the derivation itself stays hot for the timeline, but this reading of it is idle unless a
    // screen wants it.
    val placeSummaries by viewModel.places.collectAsStateWithLifecycle()
    val pending by viewModel.pendingPlaceRow.collectAsStateWithLifecycle()
    val summary = rememberFollowedPlace(placeSummaries, pending, page.key, page.seed, onRekey)
    // `storedPlaces` starts empty, and a created row reaches it after the page already shows it.
    val storedPlaces by viewModel.storedPlaces.collectAsStateWithLifecycle()
    val placeId = summary?.place?.id
    val stored = remember(storedPlaces, placeId) { storedPlaces.any { it.id == placeId } }
    val removed = rememberVanished(placeId, stored)
    if (removed) LaunchedEffect(Unit) { onBack() }
    summary?.let { detail ->
        val canMerge = remember(detail.place, placeSummaries) {
            val row = detail.place ?: return@remember false
            PlaceOverlap.candidatesFor(row, placeSummaries.orEmpty().mapNotNull { it.place }, AndroidDistance)
                .isNotEmpty()
        }
        PlaceDetailScreen(
            summary = detail,
            viewModel = viewModel,
            onBack = onBack,
            onOpenVisit = onOpenVisit,
            onOpenGap = onOpenGap,
            onOpenPlace = onOpenPlace,
            onAdjustArea = { onAdjustArea(detail) },
            onRemove = onRemove,
            canMerge = canMerge,
            onOpenMerge = { onOpenMerge(detail) },
        )
    }
}

@Composable
private fun JourneyDetailPage(
    firstNightAt: Long,
    viewModel: TrackListViewModel,
    onBack: () -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    onOpenPlace: (String) -> Unit,
) {
    // Collected here, so the journeys are derived only while this page is open.
    val travels by viewModel.travels.collectAsStateWithLifecycle()
    // The last summary the key resolved to keeps the screen alive while the derivation
    // re-runs, and through a re-derivation that moved the journey's first night out from
    // under its key. Local to the page: its lifetime is the entry's, so nothing above
    // needs to hold or clear it.
    var snapshot by remember { mutableStateOf<TravelNaming.Summary?>(null) }
    val resolved = travels?.firstOrNull { it.travel.firstNightAt == firstNightAt }
    if (resolved != null) SideEffect(resolved) { snapshot = resolved }
    (resolved ?: snapshot)?.let { detail ->
        JourneyDetailScreen(
            summary = detail,
            viewModel = viewModel,
            onBack = onBack,
            onOpenDay = onOpenDay,
            onOpenPlace = onOpenPlace,
        )
    }
}

@Composable
private fun PlaceEditPage(
    page: Destination.PlaceEditor,
    viewModel: TrackListViewModel,
    snackbarHostState: SnackbarHostState,
    onRekey: (String) -> Unit,
    onClose: () -> Unit,
    onSaved: () -> ((createdId: Long) -> Unit)?,
    onRemove: (Place) -> Unit,
) {
    val placeSummaries by viewModel.places.collectAsStateWithLifecycle()
    val pending by viewModel.pendingPlaceRow.collectAsStateWithLifecycle()
    // A named place's key is `place:<id>` and nothing can move it, but a cluster being named here is
    // keyed `cluster:<n>` — an index a re-derivation is free to reassign, which without the held
    // summary would drop the editor mid-edit.
    val summary = rememberFollowedPlace(placeSummaries, pending, page.key, page.seed, onRekey)
    summary?.let { detail ->
        val neighborhood = rememberNeighborhood(page.key, detail, placeSummaries)
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
            onClose = onClose,
            onSaved = onSaved,
            stayClaim = page.stayClaim,
            onRemove = onRemove,
        )
    }
}

@Composable
private fun PlaceMergePage(
    page: Destination.PlaceMerge,
    leaving: Boolean,
    viewModel: TrackListViewModel,
    onRekey: (String) -> Unit,
    onBack: () -> Unit,
    onMerge: (Place, List<Place>) -> Unit,
) {
    val liveSummaries by viewModel.places.collectAsStateWithLifecycle()
    val livePending by viewModel.pendingPlaceRow.collectAsStateWithLifecycle()
    // A merge removes the absorbed rows, the opened one among them, while this page is still
    // animating out.
    val held = remember { HeldMergeSources() }
    if (!leaving || held.summaries == null) {
        held.summaries = liveSummaries
        held.pending = livePending
    }
    val placeSummaries = held.summaries
    val pending = held.pending
    val opened = rememberFollowedPlace(placeSummaries, pending, page.key, page.seed, onRekey)?.place ?: return
    val placed = remember(placeSummaries) {
        placeSummaries.orEmpty().mapNotNull { s -> s.place?.let { it to s } }
    }
    val rows = remember(placed) { placed.map { it.first } }
    // Fixed on open: choosing a keeper changes only the roles.
    val memberIds = remember(page.key, placeSummaries != null) {
        listOf(opened.id) + PlaceOverlap.candidatesFor(opened, rows, AndroidDistance).map { it.id }
    }
    val members = remember(placed, memberIds) {
        val byId = placed.filter { it.first.id in memberIds }.associate { it.first.id to it.second }
        memberIds.mapNotNull { byId[it] }
    }
    MergePlacesScreen(
        opened = opened,
        members = members,
        onBack = onBack,
        onMerge = onMerge,
    )
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
private fun SettingsSectionPage(
    page: SettingsPage,
    viewModel: TrackListViewModel,
    unitChoice: UnitChoice,
    onUnitChoice: (UnitChoice) -> Unit,
    onMapShade: (MapShade) -> Unit,
    onSmoothTracks: (Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenTrack: (Long) -> Unit,
) {
    when (page) {
        SettingsPage.Recording -> RecordingSettingsScreen(onBack)
        SettingsPage.Trips -> TripsSettingsScreen(onBack)
        SettingsPage.Display -> DisplaySettingsScreen(onBack, unitChoice, onUnitChoice, onMapShade, onSmoothTracks)
        SettingsPage.Privacy -> PrivacySettingsScreen(onBack)
        SettingsPage.Data -> DataSettingsScreen(onBack, viewModel)
        SettingsPage.RecentlyDeleted -> DiscardedTracksScreen(
            viewModel = viewModel,
            onBack = onBack,
            onOpenTrack = onOpenTrack,
        )
        SettingsPage.Logs -> LogsScreen(onBack = onBack)
        SettingsPage.About -> AboutSettingsScreen(viewModel::say, onBack)
    }
}

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
