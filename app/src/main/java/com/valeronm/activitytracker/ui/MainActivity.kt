package com.valeronm.activitytracker.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import com.valeronm.activitytracker.data.Settings as AppSettings
import com.valeronm.activitytracker.data.db.TrackSummary
import com.valeronm.activitytracker.location.LocationRecordingService
import com.valeronm.activitytracker.location.TrackingStatus
import com.valeronm.activitytracker.ui.theme.AppTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                MainScreen()
            }
        }
    }
}

// --- Permission helpers ------------------------------------------------------

private fun foregroundPermissions(): List<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Manifest.permission.ACTIVITY_RECOGNITION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}

private fun Context.isGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

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
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }.onFailure {
        // Some OEMs don't expose the direct dialog; fall back to the settings list.
        runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen() {
    val context = LocalContext.current
    val viewModel: TrackListViewModel = viewModel()
    val tracks by viewModel.tracks.collectAsState()
    val status by TrackingStatus.state.collectAsState()

    // Permission state, refreshed whenever the activity resumes (e.g. back from Settings).
    var foregroundOk by remember { mutableStateOf(context.foregroundGranted()) }
    var backgroundOk by remember { mutableStateOf(context.backgroundGranted()) }
    var autoOn by remember { mutableStateOf(AppSettings.isAutoRecord(context)) }
    var batteryOk by remember { mutableStateOf(context.isBatteryOptimizationIgnored()) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                foregroundOk = context.foregroundGranted()
                backgroundOk = context.backgroundGranted()
                autoOn = AppSettings.isAutoRecord(context)
                batteryOk = context.isBatteryOptimizationIgnored()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val requestForeground = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        foregroundOk = context.foregroundGranted()
    }
    val requestBackground = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        backgroundOk = context.backgroundGranted()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Activity GPS Tracker") }) },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp),
        ) {
            when {
                !foregroundOk -> PermissionCard(
                    title = "Location & activity access needed",
                    body = "Grant location and physical-activity access so the app can detect " +
                        "whether you're walking, driving or cycling and record GPS.",
                    button = "Grant permissions",
                    onClick = { requestForeground.launch(foregroundPermissions().toTypedArray()) },
                )

                !backgroundOk -> PermissionCard(
                    title = "Allow background location",
                    body = "Set location access to \"Allow all the time\" so tracks keep recording " +
                        "when the screen is off or the app is closed.",
                    button = "Allow in the background",
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            // Android 11+ only grants this from the app's settings page.
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null),
                                ),
                            )
                        } else {
                            requestBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        }
                    },
                )

                else -> {
                    AutoRecordControls(
                        autoOn = autoOn,
                        status = status,
                        onToggle = { enabled ->
                            autoOn = enabled
                            if (enabled) LocationRecordingService.start(context)
                            else LocationRecordingService.stop(context)
                        },
                    )
                    if (autoOn && !batteryOk) {
                        Spacer(Modifier.height(12.dp))
                        PermissionCard(
                            title = "Keep recording in the background",
                            body = "Allow this app to ignore battery optimization so Android " +
                                "doesn't stop tracking after a while in the background.",
                            button = "Allow unrestricted",
                            onClick = { context.requestIgnoreBatteryOptimization() },
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Recorded tracks", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            TrackList(tracks = tracks, viewModel = viewModel)
        }
    }
}

@Composable
private fun AutoRecordControls(
    autoOn: Boolean,
    status: TrackingStatus.State,
    onToggle: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Auto recording", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (autoOn) {
                            "Armed — records automatically based on your activity."
                        } else {
                            "Off — turn on once and tracks record themselves."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = autoOn, onCheckedChange = onToggle)
            }
            if (autoOn) {
                Spacer(Modifier.height(12.dp))
                Text(
                    when {
                        !status.tracking -> "Starting…"
                        status.recording -> "Recording ${status.activityLabel}: %.2f km · %d points"
                            .format(status.distanceMeters / 1000.0, status.points)
                        else -> "Paused — waiting for movement"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun TrackList(tracks: List<TrackSummary>, viewModel: TrackListViewModel) {
    val context = LocalContext.current
    if (tracks.isEmpty()) {
        Text(
            "No tracks yet. They'll appear here once recording captures some movement.",
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(tracks, key = { it.id }) { track ->
            TrackRow(
                track = track,
                onShare = {
                    viewModel.share(track.id) { intent ->
                        if (intent != null) context.startActivity(intent)
                    }
                },
                onDelete = { viewModel.delete(track.id) },
            )
        }
    }
}

private val dateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

@Composable
private fun TrackRow(track: TrackSummary, onShare: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${track.activityType.lowercase(Locale.US).replaceFirstChar { it.uppercase() }}",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    dateFormat.format(Date(track.startedAt)),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "%.2f km · %d pts · %s".format(
                        track.distanceMeters / 1000.0,
                        track.pointCount,
                        formatDuration(track.startedAt, track.endedAt),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Filled.Share, contentDescription = "Export GPX")
            }
            OutlinedButton(onClick = onDelete) { Text("Delete") }
        }
    }
}

private fun formatDuration(startedAt: Long, endedAt: Long?): String {
    val end = endedAt ?: return "recording"
    val seconds = ((end - startedAt) / 1000.0).roundToLong()
    val m = seconds / 60
    val s = seconds % 60
    return if (m >= 60) "%dh %02dm".format(m / 60, m % 60) else "%dm %02ds".format(m, s)
}

@Composable
private fun PermissionCard(title: String, body: String, button: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onClick) { Text(button) }
        }
    }
}
