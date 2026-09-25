package io.github.valeronm.breadcrumb.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.DISCARDED_RETENTION_DAYS
import io.github.valeronm.breadcrumb.data.export.BackupExporter
import io.github.valeronm.breadcrumb.data.export.LogExporter
import io.github.valeronm.breadcrumb.util.BuildIdentity
import io.github.valeronm.breadcrumb.util.DebugLog
import io.github.valeronm.breadcrumb.util.SliderStops
import io.github.valeronm.breadcrumb.util.UnitChoice
import io.github.valeronm.breadcrumb.util.WebLinks
import io.github.valeronm.breadcrumb.util.canAuthenticate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.valeronm.breadcrumb.data.Settings as AppSettings

@Composable
internal fun SettingsScreen(onBack: () -> Unit, onOpenPage: (SettingsPage) -> Unit) {
    SettingsSubScreen(
        stringResource(R.string.settings_title),
        onBack,
        actions = {
            IconButton(onClick = { onOpenPage(SettingsPage.About) }) {
                Icon(Icons.Filled.Info, contentDescription = stringResource(R.string.settings_about))
            }
        },
    ) {
        GroupedRows(
            { NavRow(stringResource(R.string.settings_group_recording), icon = Icons.Filled.MyLocation) { onOpenPage(SettingsPage.Recording) } },
            { NavRow(stringResource(R.string.settings_trips), icon = Icons.Filled.Route) { onOpenPage(SettingsPage.Trips) } },
            { NavRow(stringResource(R.string.settings_group_display), icon = Icons.Filled.Tune) { onOpenPage(SettingsPage.Display) } },
            { NavRow(stringResource(R.string.settings_group_privacy), icon = Icons.Filled.Lock) { onOpenPage(SettingsPage.Privacy) } },
        )
        Spacer(Modifier.height(24.dp))
        GroupedRows(
            { NavRow(stringResource(R.string.settings_group_data), icon = Icons.Filled.ImportExport) { onOpenPage(SettingsPage.Data) } },
            { NavRow(stringResource(R.string.settings_logs), icon = Icons.AutoMirrored.Filled.ReceiptLong) { onOpenPage(SettingsPage.Logs) } },
        )
        Spacer(Modifier.height(24.dp))
        GroupedRows(
            {
                NavRow(
                    stringResource(R.string.discarded_title),
                    subtitle = stringResource(R.string.settings_recently_deleted_sub, DISCARDED_RETENTION_DAYS),
                    icon = Icons.Filled.Delete,
                ) { onOpenPage(SettingsPage.RecentlyDeleted) }
            },
        )
        Spacer(Modifier.height(32.dp))
        FootnoteText(BuildIdentity.shown)
    }
}

@Composable
internal fun SettingsSubScreen(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                colors = canvasTopBarColors(),
                title = { Text(title) },
                navigationIcon = { BackNavIcon(onBack) },
                actions = actions,
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

@Composable
private fun SettingsGroup(
    title: String,
    description: String,
    resetPrefs: List<Pref<*>>,
    rows: @Composable () -> Unit,
) {
    Row(Modifier.minimumInteractiveComponentSize(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (resetPrefs.any { !it.isDefault }) {
            TextButton(onClick = { resetPrefs.forEach { it.reset() } }) {
                Text(stringResource(R.string.settings_reset))
            }
        }
    }
    Text(
        description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    rows()
}

@Composable
internal fun RecordingSettingsScreen(onBack: () -> Unit) {
    SettingsSubScreen(stringResource(R.string.settings_group_recording), onBack) {
        SamplingGroup()
        Spacer(Modifier.height(24.dp))
        PointFilterGroup()
        Spacer(Modifier.height(24.dp))
        PositioningGroup()
    }
}

@Composable
private fun SamplingGroup() {
    val context = LocalContext.current
    val intervalSec = rememberPref(
        AppSettings.DEFAULT_SAMPLING_MIN_INTERVAL_SEC,
        { AppSettings.minIntervalSec(context) },
    ) { AppSettings.setMinIntervalSec(context, it) }
    val distanceM = rememberPref(
        AppSettings.DEFAULT_SAMPLING_MIN_DISTANCE_M,
        { AppSettings.minDistanceM(context) },
    ) { AppSettings.setMinDistanceM(context, it) }
    SettingsGroup(
        stringResource(R.string.settings_sampling),
        stringResource(R.string.sampling_description),
        listOf(intervalSec, distanceM),
    ) {
        GroupedRows(
            {
                SliderSetting(
                    stringResource(R.string.sampling_time_between),
                    intervalSec.value.toFloat(),
                    1f..30f,
                    1,
                    { stringResource(R.string.duration_seconds_step, it.toInt()) },
                ) {
                    intervalSec.set(it.toInt())
                }
            },
            {
                val scale = rememberDistanceScale(SliderStops(1, 50, 1), SliderStops(5, 165, 5))
                SliderSetting(stringResource(R.string.sampling_distance_between), distanceM.value, scale) {
                    distanceM.set(it)
                }
            },
        )
    }
}

@Composable
private fun PointFilterGroup() {
    val context = LocalContext.current
    val accuracyGateM = rememberPref(
        AppSettings.DEFAULT_ACCURACY_GATE_M,
        { AppSettings.accuracyGateM(context) },
    ) { AppSettings.setAccuracyGateM(context, it) }
    val requireGnssFix = rememberPref(
        AppSettings.DEFAULT_REQUIRE_GNSS_FIX,
        { AppSettings.requireGnssFix(context) },
    ) { AppSettings.setRequireGnssFix(context, it) }
    SettingsGroup(
        stringResource(R.string.settings_point_quality),
        stringResource(R.string.quality_description),
        listOf(accuracyGateM, requireGnssFix),
    ) {
        GroupedRows(
            {
                SwitchSettingRow(
                    title = stringResource(R.string.quality_require_fix),
                    subtitle = stringResource(R.string.quality_require_fix_sub),
                    checked = requireGnssFix.value,
                    onCheckedChange = { requireGnssFix.set(it) },
                )
            },
            {
                Text(
                    stringResource(R.string.quality_accuracy_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                val scale = rememberDistanceScale(SliderStops(10, 150, 10), SliderStops(25, 500, 25))
                SliderSetting(stringResource(R.string.quality_max_accuracy), accuracyGateM.value, scale) {
                    accuracyGateM.set(it)
                }
            },
        )
    }
}

@Composable
private fun PositioningGroup() {
    val context = LocalContext.current
    val gpsGiveUpSec = rememberPref(
        AppSettings.DEFAULT_GPS_GIVE_UP_SEC,
        { AppSettings.gpsGiveUpSec(context) },
    ) { AppSettings.setGpsGiveUpSec(context, it) }
    SettingsGroup(
        stringResource(R.string.settings_gps_search),
        stringResource(R.string.gps_description),
        listOf(gpsGiveUpSec),
    ) {
        GroupedRows(
            {
                SliderSetting(
                    stringResource(R.string.gps_give_up_after),
                    gpsGiveUpSec.value.toFloat(),
                    0f..600f,
                    60,
                    { durationSettingLabel(it.toInt()) },
                ) {
                    gpsGiveUpSec.set(it.toInt())
                }
            },
        )
    }
}

@Composable
internal fun TripsSettingsScreen(onBack: () -> Unit) {
    SettingsSubScreen(stringResource(R.string.settings_trips), onBack) {
        DepartureGroup()
        Spacer(Modifier.height(24.dp))
        ContinuationGroup()
        Spacer(Modifier.height(24.dp))
        TripFilteringGroup()
    }
}

/**
 * The ways the recorder can notice a journey starting when activity detection does not report one.
 * Three switches rather than a single "detect harder": they cost differently, they are blind in
 * different places, and which combination is right depends on the phone — the same build on two
 * devices can have activity detection announce a car within seconds, or never announce it at all.
 */
@Composable
private fun DepartureGroup() {
    val context = LocalContext.current
    val fence = rememberPref(
        true,
        { AppSettings.departureFence(context) },
    ) { AppSettings.setDepartureFence(context, it) }
    val motion = rememberPref(
        true,
        { AppSettings.departureMotion(context) },
    ) { AppSettings.setDepartureMotion(context, it) }
    val continuous = rememberPref(
        false,
        { AppSettings.departureContinuous(context) },
    ) { AppSettings.setDepartureContinuous(context, it) }
    SettingsGroup(
        stringResource(R.string.settings_departure_triggers),
        stringResource(R.string.departure_description),
        listOf(fence, motion, continuous),
    ) {
        GroupedRows(
            {
                SwitchSettingRow(
                    title = stringResource(R.string.departure_fence),
                    subtitle = stringResource(R.string.departure_fence_sub),
                    checked = fence.value,
                    onCheckedChange = { fence.set(it) },
                )
            },
            {
                SwitchSettingRow(
                    title = stringResource(R.string.departure_motion),
                    subtitle = stringResource(R.string.departure_motion_sub),
                    checked = motion.value,
                    onCheckedChange = { motion.set(it) },
                )
            },
            {
                SwitchSettingRow(
                    title = stringResource(R.string.departure_continuous),
                    subtitle = stringResource(R.string.departure_continuous_sub),
                    checked = continuous.value,
                    onCheckedChange = { continuous.set(it) },
                )
            },
        )
    }
}

@Composable
private fun ContinuationGroup() {
    val context = LocalContext.current
    val stitchWindowSec = rememberPref(
        AppSettings.DEFAULT_STITCH_WINDOW_SEC,
        { AppSettings.stitchWindowSec(context) },
    ) { AppSettings.setStitchWindowSec(context, it) }
    SettingsGroup(
        stringResource(R.string.settings_trip_continuation),
        stringResource(R.string.continuation_description),
        listOf(stitchWindowSec),
    ) {
        GroupedRows(
            {
                SliderSetting(
                    stringResource(R.string.continuation_window),
                    stitchWindowSec.value.toFloat(),
                    0f..600f,
                    60,
                    { durationSettingLabel(it.toInt()) },
                ) {
                    stitchWindowSec.set(it.toInt())
                }
            },
        )
    }
}

@Composable
private fun TripFilteringGroup() {
    val context = LocalContext.current
    val minDurationSec = rememberPref(
        AppSettings.DEFAULT_TRACK_MIN_DURATION_SEC,
        { AppSettings.minTrackDurationSec(context) },
    ) { AppSettings.setMinTrackDurationSec(context, it) }
    val minLengthM = rememberPref(
        AppSettings.DEFAULT_TRACK_MIN_LENGTH_M,
        { AppSettings.minTrackLengthM(context) },
    ) { AppSettings.setMinTrackLengthM(context, it) }
    val minExtentM = rememberPref(
        AppSettings.DEFAULT_TRACK_MIN_EXTENT_M,
        { AppSettings.minTrackExtentM(context) },
    ) { AppSettings.setMinTrackExtentM(context, it) }
    // Min length and min extent share one scale: both are "how far did the track get" thresholds.
    val lengthScale =
        rememberDistanceScale(SliderStops(0, 500, 50), SliderStops(0, 1650, 150), zeroIsOff = true)
    SettingsGroup(
        stringResource(R.string.settings_track_filtering),
        stringResource(R.string.filter_description),
        listOf(minDurationSec, minLengthM, minExtentM),
    ) {
        GroupedRows(
            {
                SliderSetting(
                    stringResource(R.string.filter_min_duration),
                    minDurationSec.value.toFloat(),
                    0f..300f,
                    30,
                    { durationSettingLabel(it.toInt()) },
                ) {
                    minDurationSec.set(it.toInt())
                }
            },
            {
                SliderSetting(stringResource(R.string.filter_min_length), minLengthM.value, lengthScale) {
                    minLengthM.set(it)
                }
            },
            {
                Text(
                    stringResource(R.string.filter_extent_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                SliderSetting(stringResource(R.string.filter_min_extent), minExtentM.value, lengthScale) {
                    minExtentM.set(it)
                }
            },
        )
    }
}

/** The grace choices, in seconds. Not a slider: these are four named behaviours, not a range. */
private val LOCK_GRACE_CHOICES = listOf(0, 30, 60, 300)

@Composable
internal fun PrivacySettingsScreen(onBack: () -> Unit) {
    SettingsSubScreen(stringResource(R.string.settings_group_privacy), onBack) {
        AppLockGroup()
        Spacer(Modifier.height(24.dp))
        OnlineServicesGroup()
    }
}

@Composable
private fun AppLockGroup() {
    val context = LocalContext.current
    val graceSec = rememberPref(
        AppSettings.DEFAULT_APP_LOCK_GRACE_SEC,
        { AppSettings.appLockGraceSec(context) },
    ) { AppSettings.setAppLockGraceSec(context, it) }
    // Not remembered: a user sent away to set a screen lock comes back to this same screen, and a
    // cached "you have none" would still be telling them to go and do what they just did.
    val lockable = context.canAuthenticate()
    val trustsKeyguard = rememberPref(
        false,
        { AppSettings.appLockTrustsKeyguard(context) },
    ) { AppSettings.setAppLockTrustsKeyguard(context, it) }
    SettingsGroup(
        stringResource(R.string.settings_app_lock),
        stringResource(R.string.privacy_description),
        listOf(graceSec, trustsKeyguard),
    ) {
        GroupedRows(
            { RequireUnlockRow(context, lockable, graceSec, trustsKeyguard) },
            {
                SwitchSettingRow(
                    title = stringResource(R.string.privacy_block_screenshots),
                    subtitle = stringResource(R.string.privacy_block_screenshots_sub),
                    checked = Privacy.blockScreenshots,
                    onCheckedChange = { Privacy.setBlockScreenshots(context, it) },
                )
            },
        )
    }
}

@Composable
private fun OnlineServicesGroup() {
    val context = LocalContext.current
    val onlineSearch = rememberPref(
        true,
        { AppSettings.isOnlinePlaceSearch(context) },
    ) { AppSettings.setOnlinePlaceSearch(context, it) }
    SettingsGroup(
        stringResource(R.string.settings_online_services),
        stringResource(R.string.online_services_description),
        listOf(onlineSearch),
    ) {
        GroupedRows(
            {
                SwitchSettingRow(
                    title = stringResource(R.string.privacy_online_search),
                    subtitle = stringResource(R.string.privacy_online_search_sub),
                    checked = onlineSearch.value,
                    onCheckedChange = { onlineSearch.set(it) },
                )
            },
        )
    }
}

@Composable
private fun RequireUnlockRow(
    context: Context,
    lockable: Boolean,
    graceSec: Pref<Int>,
    trustsKeyguard: Pref<Boolean>,
) {
    SwitchSettingRow(
        title = stringResource(R.string.lock_require_unlock),
        subtitle = stringResource(
            if (lockable) R.string.lock_require_unlock_sub else R.string.lock_no_screen_lock,
        ),
        // A lock this device can't open would be a lockout with no way back to the history.
        checked = lockable && Privacy.lockEnabled,
        enabled = lockable,
        onCheckedChange = { Privacy.setLockEnabled(context, it) },
    )
    if (lockable && Privacy.lockEnabled) {
        LockGraceChips(graceSec)
        Spacer(Modifier.height(12.dp))
        // What the switch costs is said in the subtitle rather than left to be worked out: it is
        // the difference between a lock that stands on its own and one that rests on the phone's.
        SwitchSettingRow(
            title = stringResource(R.string.lock_trust_keyguard),
            subtitle = stringResource(R.string.lock_trust_keyguard_sub),
            checked = trustsKeyguard.value,
            onCheckedChange = { trustsKeyguard.set(it) },
        )
    }
}

@Composable
private fun LockGraceChips(graceSec: Pref<Int>) {
    Spacer(Modifier.height(12.dp))
    Text(stringResource(R.string.lock_again), style = MaterialTheme.typography.bodyMedium)
    Text(
        stringResource(R.string.lock_again_sub),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (seconds in LOCK_GRACE_CHOICES) {
            FilterToggleChip(
                selected = seconds == graceSec.value,
                label = lockGraceLabel(seconds),
                onClick = { graceSec.set(seconds) },
            )
        }
    }
}

// Zero is the one choice the duration ladder can't spell: it renders as "Off", which here would
// read as "never lock again" rather than "lock the moment you leave".
@Composable
private fun lockGraceLabel(sec: Int): String =
    if (sec == 0) {
        stringResource(R.string.lock_immediately)
    } else {
        stringResource(R.string.lock_after, durationSettingLabel(sec))
    }

@Composable
internal fun LogsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val entries by DebugLog.entries.collectAsStateWithLifecycle(initialValue = emptyList())
    Scaffold(
        topBar = {
            TopAppBar(
                colors = canvasTopBarColors(),
                title = { Text(stringResource(R.string.logs_title, entries.size)) },
                navigationIcon = { BackNavIcon(onBack) },
                actions = {
                    // The chooser title is chrome and translates; the log body it carries does not.
                    val shareLogs = stringResource(R.string.logs_share)
                    val scope = rememberCoroutineScope()
                    IconButton(onClick = {
                        // A stream, not EXTRA_TEXT: the persisted history runs to megabytes, and a
                        // string that size dies in the binder transaction the intent rides.
                        scope.launch(Dispatchers.IO) {
                            val uri = LogExporter.export(context)
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            withContext(Dispatchers.Main) {
                                context.startActivity(Intent.createChooser(share, shareLogs))
                            }
                        }
                    }) { Icon(Icons.Filled.Share, contentDescription = shareLogs) }
                    IconButton(onClick = { DebugLog.clear() }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.logs_clear),
                        )
                    }
                },
            )
        },
    ) { inner ->
        if (entries.isEmpty()) {
            EmptyState(
                stringResource(R.string.logs_empty),
                Modifier.padding(inner).fillMaxSize(),
            )
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

internal enum class SettingsPage { Recording, Trips, Display, Privacy, Data, RecentlyDeleted, Logs, About }

@Composable
internal fun DisplaySettingsScreen(
    onBack: () -> Unit,
    unitChoice: UnitChoice,
    onUnitChoice: (UnitChoice) -> Unit,
) {
    SettingsSubScreen(stringResource(R.string.settings_group_display), onBack) {
        GroupedRows(
            {
                Column {
                    Text(stringResource(R.string.settings_units), style = MaterialTheme.typography.bodyLarge)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (choice in UnitChoice.entries) {
                            FilterToggleChip(
                                selected = choice == unitChoice,
                                label = stringResource(choice.labelRes),
                                onClick = { onUnitChoice(choice) },
                            )
                        }
                    }
                }
            },
        )
    }
}

@Composable
internal fun DataSettingsScreen(onBack: () -> Unit, viewModel: TrackListViewModel) {
    SettingsSubScreen(stringResource(R.string.settings_group_data), onBack) {
        GroupedRows(
            { ExportBackupRow(viewModel) },
            {
                LinkRow(
                    stringResource(R.string.data_viewer),
                    WebLinks.VIEWER,
                    subtitle = stringResource(R.string.data_viewer_sub),
                )
            },
        )
        Spacer(Modifier.height(24.dp))
        GroupedRows(
            { ImportTracksRow(viewModel) },
            { ExportTracksRow(viewModel) },
        )
    }
}

@Composable
internal fun AboutSettingsScreen(onBack: () -> Unit) {
    SettingsSubScreen(stringResource(R.string.settings_about), onBack) {
        AppIcon(Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.app_name),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            stringResource(R.string.about_version, BuildIdentity.shown),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        GroupedRows(
            { LinkRow(stringResource(R.string.about_google_play), WebLinks.PLAY) },
            { LinkRow(stringResource(R.string.about_privacy_policy), WebLinks.PRIVACY_POLICY) },
            { LinkRow(stringResource(R.string.about_website), WebLinks.SITE) },
            { LinkRow(stringResource(R.string.about_source_code), WebLinks.SOURCE) },
        )
        Spacer(Modifier.height(24.dp))
        // CC BY 4.0 asks for the credit wherever the work is used, and the timeline's place names
        // are that use.
        FootnoteText(stringResource(R.string.credit_geonames))
        // ODbL asks for the credit wherever OSM-derived results show.
        FootnoteText(stringResource(R.string.credit_osm))
    }
}

/** `painterResource` cannot draw an adaptive icon. */
@Composable
private fun AppIcon(modifier: Modifier) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { APP_ICON_SIZE.roundToPx() }
    val icon = remember(sizePx) {
        AppCompatResources.getDrawable(context, R.mipmap.ic_launcher)!!.toBitmap(sizePx, sizePx).asImageBitmap()
    }
    Image(icon, contentDescription = null, modifier = modifier.size(APP_ICON_SIZE))
}

private val APP_ICON_SIZE = 72.dp

@Composable
private fun ImportTracksRow(viewModel: TrackListViewModel) {
    val context = LocalContext.current
    val importProgress by viewModel.importExport.importProgress.collectAsStateWithLifecycle()
    val appContext = context.applicationContext
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        viewModel.importExport.importGpx(uris) { result ->
            Toast.makeText(appContext, gpxImportMessage(appContext, result), Toast.LENGTH_LONG).show()
        }
    }
    val progress = importProgress
    DataActionRow(
        stringResource(R.string.data_import_tracks),
        subtitle = if (progress == null) {
            stringResource(R.string.data_import_idle)
        } else {
            stringResource(
                R.string.data_importing,
                (progress.filesDone + 1).coerceAtMost(progress.filesTotal),
                progress.filesTotal,
                progress.imported,
            )
        },
        // Files, where the others count tracks — an import's unit is the file it opens, which is
        // also what its subtitle counts.
        done = progress?.filesDone,
        total = progress?.filesTotal,
    ) {
        importLauncher.launch(
            arrayOf(
                "application/gpx+xml", "application/octet-stream",
                "text/xml", "application/xml",
            ),
        )
    }
}

/**
 * A data action's row: the row, and under it while the action runs, how far it has got. Disabled
 * for as long as its own action runs, a second start of one being ignored rather than queued —
 * the actions are not exclusive of each other, and two of them can run at once.
 *
 * [done] is null when nothing is running, which is the only thing that decides whether a bar is
 * there — [total] is the operation's own business and says which bar (see [OperationProgressBar]).
 * Counts rather than one of the controller's progress types, because the operations count
 * different things and a row is not the place to learn which.
 *
 * Emitted into the group's own column rather than a column of its own, as [GroupedRows] gives each
 * row one already.
 */
@Composable
private fun DataActionRow(
    label: String,
    subtitle: String,
    done: Int?,
    total: Int?,
    onClick: () -> Unit,
) {
    NavRow(label, subtitle, enabled = done == null, onClick = onClick)
    if (done != null) {
        Spacer(Modifier.height(8.dp))
        OperationProgressBar(done, total, Modifier.fillMaxWidth())
    }
}

/**
 * The busy subtitle shared by the export rows. Each row hands over its own whole phrases rather
 * than a verb and a noun to be assembled here: only English composes that way, and a noun built
 * outside its sentence can agree with nothing.
 */
@Composable
private fun exportSubtitle(
    progress: ImportExportController.OpProgress?,
    idle: String,
    @StringRes verb: Int,
    @StringRes busy: Int,
): String = when {
    progress == null -> idle
    progress.tracksTotal != null ->
        stringResource(busy, progress.tracksDone, progress.tracksTotal)
    else -> stringResource(verb)
}

private fun exportResultToast(context: Context, count: Int?) {
    val message = if (count == null) {
        context.getString(R.string.data_export_failed)
    } else {
        context.resources.getQuantityString(R.plurals.data_exported, count, count)
    }
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}

@Composable
private fun ExportTracksRow(viewModel: TrackListViewModel) {
    val appContext = LocalContext.current.applicationContext
    val progress by viewModel.importExport.gpxExportProgress.collectAsStateWithLifecycle()
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.importExport.exportAll(uri) { count -> exportResultToast(appContext, count) }
    }
    DataActionRow(
        stringResource(R.string.data_export_tracks),
        subtitle = exportSubtitle(
            progress,
            idle = stringResource(R.string.data_export_tracks_idle),
            verb = R.string.data_exporting,
            busy = R.string.data_exporting_progress,
        ),
        done = progress?.tracksDone,
        total = progress?.tracksTotal,
    ) { exportLauncher.launch(null) }
}

@Composable
private fun ExportBackupRow(viewModel: TrackListViewModel) {
    val appContext = LocalContext.current.applicationContext
    val progress by viewModel.importExport.exportProgress.collectAsStateWithLifecycle()
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupExporter.MIME_TYPE),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.importExport.exportBackup(uri) { count -> exportResultToast(appContext, count) }
    }
    DataActionRow(
        stringResource(R.string.data_backup),
        subtitle = exportSubtitle(
            progress,
            idle = stringResource(R.string.data_backup_idle),
            verb = R.string.data_backup_verb,
            busy = R.string.data_backup_progress,
        ),
        done = progress?.tracksDone,
        total = progress?.tracksTotal,
    ) { exportLauncher.launch(BackupExporter.fileName(System.currentTimeMillis())) }
}
