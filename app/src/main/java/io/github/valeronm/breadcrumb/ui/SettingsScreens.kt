package io.github.valeronm.breadcrumb.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.valeronm.breadcrumb.BuildConfig
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.DISCARDED_RETENTION_DAYS
import io.github.valeronm.breadcrumb.data.export.BackupExporter
import io.github.valeronm.breadcrumb.data.export.LogExporter
import io.github.valeronm.breadcrumb.util.DebugLog
import io.github.valeronm.breadcrumb.util.SliderStops
import io.github.valeronm.breadcrumb.util.UnitChoice
import io.github.valeronm.breadcrumb.util.canAuthenticate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.valeronm.breadcrumb.data.Settings as AppSettings

/** A Settings sub-page stacked above the Settings hub (shares one overlay slot in MainScreen). */
internal enum class SettingsPage {
    Sampling,
    PointQuality,
    AutoPause,
    GpsSearch,
    DepartureTriggers,
    TrackFiltering,
    AppLock,
    OnlineServices,
    RecentlyDeleted,
    Logs,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    viewModel: TrackListViewModel,
    unitChoice: UnitChoice,
    onUnitChoice: (UnitChoice) -> Unit,
    onBack: () -> Unit,
    onOpenPage: (SettingsPage) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                colors = canvasTopBarColors(),
                title = { Text(stringResource(R.string.settings_title)) },
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
            Text(stringResource(R.string.settings_group_recording), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            GroupedRows(
                {
                    NavRow(
                        stringResource(R.string.settings_sampling),
                        subtitle = stringResource(R.string.settings_sampling_sub),
                    ) { onOpenPage(SettingsPage.Sampling) }
                },
                {
                    NavRow(
                        stringResource(R.string.settings_point_quality),
                        subtitle = stringResource(R.string.settings_point_quality_sub),
                    ) { onOpenPage(SettingsPage.PointQuality) }
                },
                {
                    NavRow(
                        stringResource(R.string.settings_auto_pause),
                        subtitle = stringResource(R.string.settings_auto_pause_sub),
                    ) { onOpenPage(SettingsPage.AutoPause) }
                },
                {
                    NavRow(
                        stringResource(R.string.settings_gps_search),
                        subtitle = stringResource(R.string.settings_gps_search_sub),
                    ) { onOpenPage(SettingsPage.GpsSearch) }
                },
                {
                    NavRow(
                        stringResource(R.string.settings_departure_triggers),
                        subtitle = stringResource(R.string.settings_departure_triggers_sub),
                    ) { onOpenPage(SettingsPage.DepartureTriggers) }
                },
                {
                    NavRow(
                        stringResource(R.string.settings_track_filtering),
                        subtitle = stringResource(R.string.settings_track_filtering_sub),
                    ) { onOpenPage(SettingsPage.TrackFiltering) }
                },
            )
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.settings_group_display), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            GroupedRows(
                {
                    Column {
                        Text(stringResource(R.string.settings_units), style = MaterialTheme.typography.bodyLarge)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            for (choice in UnitChoice.entries) {
                                FilterChip(
                                    selected = choice == unitChoice,
                                    onClick = { onUnitChoice(choice) },
                                    label = { Text(stringResource(choice.labelRes)) },
                                )
                            }
                        }
                    }
                },
            )
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.settings_group_privacy), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            GroupedRows(
                {
                    NavRow(
                        stringResource(R.string.settings_app_lock),
                        subtitle = stringResource(R.string.settings_app_lock_sub),
                    ) { onOpenPage(SettingsPage.AppLock) }
                },
                {
                    NavRow(
                        stringResource(R.string.settings_online_services),
                        subtitle = stringResource(R.string.settings_online_services_sub),
                    ) { onOpenPage(SettingsPage.OnlineServices) }
                },
            )
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.settings_group_data), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            GroupedRows(
                { ImportTracksRow(viewModel) },
                { ExportTracksRow(viewModel) },
                { ExportBackupRow(viewModel) },
                {
                    NavRow(
                        stringResource(R.string.discarded_title),
                        subtitle = stringResource(
                            R.string.settings_recently_deleted_sub,
                            DISCARDED_RETENTION_DAYS,
                        ),
                    ) { onOpenPage(SettingsPage.RecentlyDeleted) }
                },
            )
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.settings_group_diagnostics), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            GroupedRows(
                { NavRow(stringResource(R.string.settings_logs)) { onOpenPage(SettingsPage.Logs) } },
            )
            Spacer(Modifier.height(32.dp))
            Text(
                "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Required by the gazetteer's licence, not a courtesy — CC BY 4.0 asks for the credit
            // wherever the work is used, and the place names on the timeline are that use.
            Text(
                stringResource(R.string.credit_geonames),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Likewise a licence term: ODbL asks for the credit wherever OSM-derived results show.
            Text(
                stringResource(R.string.credit_osm),
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
                        TextButton(onClick = { resetPrefs.forEach { it.reset() } }) {
                            Text(stringResource(R.string.settings_reset))
                        }
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
internal fun SamplingSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val intervalSec = rememberPref(
        AppSettings.DEFAULT_SAMPLING_MIN_INTERVAL_SEC,
        { AppSettings.minIntervalSec(context) },
    ) { AppSettings.setMinIntervalSec(context, it) }
    val distanceM = rememberPref(
        AppSettings.DEFAULT_SAMPLING_MIN_DISTANCE_M,
        { AppSettings.minDistanceM(context) },
    ) { AppSettings.setMinDistanceM(context, it) }
    SettingsSubPage(stringResource(R.string.settings_sampling), onBack, listOf(intervalSec, distanceM)) {
        SettingsPageDescription(stringResource(R.string.sampling_description))
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
internal fun PointQualitySettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val accuracyGateM = rememberPref(
        AppSettings.DEFAULT_ACCURACY_GATE_M,
        { AppSettings.accuracyGateM(context) },
    ) { AppSettings.setAccuracyGateM(context, it) }
    val requireGnssFix = rememberPref(
        AppSettings.DEFAULT_REQUIRE_GNSS_FIX,
        { AppSettings.requireGnssFix(context) },
    ) { AppSettings.setRequireGnssFix(context, it) }
    SettingsSubPage(
        stringResource(R.string.settings_point_quality),
        onBack,
        listOf(accuracyGateM, requireGnssFix),
    ) {
        SettingsPageDescription(stringResource(R.string.quality_description))
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
internal fun AutoPauseSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val resumeWindowSec = rememberPref(
        AppSettings.DEFAULT_STITCH_RESUME_WINDOW_SEC,
        { AppSettings.resumeWindowSec(context) },
    ) { AppSettings.setResumeWindowSec(context, it) }
    SettingsSubPage(
        stringResource(R.string.settings_auto_pause),
        onBack,
        listOf(resumeWindowSec),
    ) {
        SettingsPageDescription(stringResource(R.string.pause_description))
        GroupedRows(
            {
                SliderSetting(
                    stringResource(R.string.pause_resume_window),
                    resumeWindowSec.value.toFloat(),
                    0f..600f,
                    60,
                    { durationSettingLabel(it.toInt()) },
                ) {
                    resumeWindowSec.set(it.toInt())
                }
            },
        )
    }
}

@Composable
internal fun GpsSearchSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val gpsGiveUpSec = rememberPref(
        AppSettings.DEFAULT_GPS_GIVE_UP_SEC,
        { AppSettings.gpsGiveUpSec(context) },
    ) { AppSettings.setGpsGiveUpSec(context, it) }
    SettingsSubPage(stringResource(R.string.settings_gps_search), onBack, listOf(gpsGiveUpSec)) {
        SettingsPageDescription(stringResource(R.string.gps_description))
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

/**
 * The ways the recorder can notice a journey starting when activity detection does not report one.
 * Three switches rather than a single "detect harder": they cost differently, they are blind in
 * different places, and which combination is right depends on the phone — the same build on two
 * devices can have activity detection announce a car within seconds, or never announce it at all.
 */
@Composable
internal fun DepartureTriggersSettingsScreen(onBack: () -> Unit) {
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
    SettingsSubPage(
        stringResource(R.string.settings_departure_triggers),
        onBack,
        listOf(fence, motion, continuous),
    ) {
        SettingsPageDescription(stringResource(R.string.departure_description))
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
internal fun TrackFilteringSettingsScreen(onBack: () -> Unit) {
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
    SettingsSubPage(
        stringResource(R.string.settings_track_filtering),
        onBack,
        listOf(minDurationSec, minLengthM, minExtentM),
    ) {
        SettingsPageDescription(stringResource(R.string.filter_description))
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
internal fun AppLockSettingsScreen(onBack: () -> Unit) {
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
    SettingsSubPage(
        stringResource(R.string.settings_app_lock),
        onBack,
        listOf(graceSec, trustsKeyguard),
    ) {
        SettingsPageDescription(stringResource(R.string.privacy_description))
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
internal fun OnlineServicesSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val onlineSearch = rememberPref(
        true,
        { AppSettings.isOnlinePlaceSearch(context) },
    ) { AppSettings.setOnlinePlaceSearch(context, it) }
    SettingsSubPage(
        stringResource(R.string.settings_online_services),
        onBack,
        listOf(onlineSearch),
    ) {
        SettingsPageDescription(stringResource(R.string.online_services_description))
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
            FilterChip(
                selected = seconds == graceSec.value,
                onClick = { graceSec.set(seconds) },
                label = { Text(lockGraceLabel(seconds)) },
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

/** Hub row that opens the GPX picker directly; the subtitle doubles as import progress. */
@Composable
private fun ImportTracksRow(viewModel: TrackListViewModel) {
    val context = LocalContext.current
    // Progress lives in the ViewModel, so it survives leaving Settings mid-import.
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
    NavRow(
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
        enabled = progress == null,
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

/** Hub row that opens the folder picker and writes every track out as GPX. */
@Composable
private fun ExportTracksRow(viewModel: TrackListViewModel) {
    val appContext = LocalContext.current.applicationContext
    // Progress lives in the ViewModel, so it survives leaving Settings mid-export.
    val progress by viewModel.importExport.gpxExportProgress.collectAsStateWithLifecycle()
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.importExport.exportAll(uri) { count -> exportResultToast(appContext, count) }
    }
    NavRow(
        stringResource(R.string.data_export_tracks),
        subtitle = exportSubtitle(
            progress,
            idle = stringResource(R.string.data_export_tracks_idle),
            verb = R.string.data_exporting,
            busy = R.string.data_exporting_progress,
        ),
        enabled = progress == null,
    ) { exportLauncher.launch(null) }
}

/**
 * Hub row that writes the whole history as one gzipped JSON file — backup, and the web
 * companion's source. Progress lives in the ViewModel, so it survives leaving Settings
 * mid-export; the row is disabled (and counts up in its subtitle) while one runs.
 */
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
    NavRow(
        stringResource(R.string.data_backup),
        subtitle = exportSubtitle(
            progress,
            idle = stringResource(R.string.data_backup_idle),
            verb = R.string.data_backup_verb,
            busy = R.string.data_backup_progress,
        ),
        enabled = progress == null,
    ) { exportLauncher.launch(BackupExporter.fileName(System.currentTimeMillis())) }
}

@OptIn(ExperimentalMaterial3Api::class)
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
