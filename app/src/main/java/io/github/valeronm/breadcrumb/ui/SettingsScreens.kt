package io.github.valeronm.breadcrumb.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.launch
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.valeronm.breadcrumb.BuildConfig
import io.github.valeronm.breadcrumb.data.Settings as AppSettings
import io.github.valeronm.breadcrumb.data.DISCARDED_RETENTION_DAYS
import io.github.valeronm.breadcrumb.data.export.BackupExporter
import io.github.valeronm.breadcrumb.data.db.Track
import io.github.valeronm.breadcrumb.util.DebugLog
import io.github.valeronm.breadcrumb.util.SliderStops
import io.github.valeronm.breadcrumb.util.UnitChoice

/** A Settings sub-page stacked above the Settings hub (shares one overlay slot in MainScreen). */
internal enum class SettingsPage {
    Sampling, PointQuality, AutoPause, GpsSearch, TrackFiltering, RecentlyDeleted, Logs,
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
                        subtitle = "How densely points are recorded",
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
            Text("Display", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            GroupedRows(
                {
                    Column {
                        Text("Units", style = MaterialTheme.typography.bodyLarge)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            for (choice in UnitChoice.entries) {
                                FilterChip(
                                    selected = choice == unitChoice,
                                    onClick = { onUnitChoice(choice) },
                                    label = { Text(choice.label) },
                                )
                            }
                        }
                    }
                },
            )
            Spacer(Modifier.height(24.dp))
            Text("Data", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            GroupedRows(
                { ImportTracksRow(viewModel) },
                { ExportTracksRow(viewModel) },
                { ExportBackupRow(viewModel) },
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
internal fun SamplingSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val intervalSec = rememberPref(AppSettings.DEFAULT_SAMPLING_MIN_INTERVAL_SEC,
        { AppSettings.minIntervalSec(context) }) { AppSettings.setMinIntervalSec(context, it) }
    val distanceM = rememberPref(AppSettings.DEFAULT_SAMPLING_MIN_DISTANCE_M,
        { AppSettings.minDistanceM(context) }) { AppSettings.setMinDistanceM(context, it) }
    SettingsSubPage("Sampling", onBack, listOf(intervalSec, distanceM)) {
        SettingsPageDescription(
            "How densely points are recorded while moving.",
        )
        GroupedRows(
            {
                SliderSetting("Time between points", intervalSec.value.toFloat(), 1f..30f, 1, { "${it.toInt()} s" }) {
                    intervalSec.set(it.toInt())
                }
            },
            {
                val scale = rememberDistanceScale(SliderStops(1, 50, 1), SliderStops(5, 165, 5))
                SliderSetting("Distance between points", distanceM.value, scale) {
                    distanceM.set(it)
                }
            },
        )
    }
}

@Composable
internal fun PointQualitySettingsScreen(onBack: () -> Unit) {
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
                    subtitle = "Drops guessed positions, like in a tunnel.",
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
                val scale = rememberDistanceScale(SliderStops(10, 150, 10), SliderStops(25, 500, 25))
                SliderSetting("Max accuracy radius", accuracyGateM.value, scale) {
                    accuracyGateM.set(it)
                }
            },
        )
    }
}

@Composable
internal fun AutoPauseSettingsScreen(onBack: () -> Unit) {
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
internal fun GpsSearchSettingsScreen(onBack: () -> Unit) {
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
internal fun TrackFilteringSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val minDurationSec = rememberPref(AppSettings.DEFAULT_TRACK_MIN_DURATION_SEC,
        { AppSettings.minTrackDurationSec(context) }) { AppSettings.setMinTrackDurationSec(context, it) }
    val minLengthM = rememberPref(AppSettings.DEFAULT_TRACK_MIN_LENGTH_M,
        { AppSettings.minTrackLengthM(context) }) { AppSettings.setMinTrackLengthM(context, it) }
    val minExtentM = rememberPref(AppSettings.DEFAULT_TRACK_MIN_EXTENT_M,
        { AppSettings.minTrackExtentM(context) }) { AppSettings.setMinTrackExtentM(context, it) }
    // Min length and min extent share one scale: both are "how far did the track get" thresholds.
    val lengthScale =
        rememberDistanceScale(SliderStops(0, 500, 50), SliderStops(0, 1650, 150), zeroIsOff = true)
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
                SliderSetting("Min length", minLengthM.value, lengthScale) {
                    minLengthM.set(it)
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
                SliderSetting("Min extent", minExtentM.value, lengthScale) {
                    minExtentM.set(it)
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
    val importProgress by viewModel.importProgress.collectAsStateWithLifecycle()
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

/** The busy subtitle shared by the export rows: "<verb> <noun> N of M" once the total is known. */
private fun exportSubtitle(progress: TrackListViewModel.OpProgress?, idle: String, verb: String, noun: String): String =
    when {
        progress == null -> idle
        progress.tracksTotal != null -> "$verb $noun ${progress.tracksDone} of ${progress.tracksTotal}"
        else -> verb
    }

private fun exportResultToast(context: Context, count: Int?) {
    val message = if (count == null) "Export failed" else "Exported $count tracks"
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}

/** Hub row that opens the folder picker and writes every track out as GPX. */
@Composable
private fun ExportTracksRow(viewModel: TrackListViewModel) {
    val appContext = LocalContext.current.applicationContext
    // Progress lives in the ViewModel, so it survives leaving Settings mid-export.
    val progress by viewModel.gpxExportProgress.collectAsStateWithLifecycle()
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.exportAll(uri) { count -> exportResultToast(appContext, count) }
    }
    NavRow(
        "Export tracks",
        subtitle = exportSubtitle(
            progress,
            idle = "Every track as a GPX file, into a folder you pick",
            verb = "Exporting…",
            noun = "file",
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
    val progress by viewModel.exportProgress.collectAsStateWithLifecycle()
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupExporter.MIME_TYPE),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.exportBackup(uri) { count -> exportResultToast(appContext, count) }
    }
    NavRow(
        "Back up everything",
        subtitle = exportSubtitle(
            progress,
            idle = "Tracks, places and history as one file",
            verb = "Backing up…",
            noun = "track",
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
