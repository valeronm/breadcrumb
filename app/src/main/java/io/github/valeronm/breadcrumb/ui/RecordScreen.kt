package io.github.valeronm.breadcrumb.ui

import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.valeronm.breadcrumb.BuildConfig
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.domain.RecordCardState
import io.github.valeronm.breadcrumb.domain.recordCardState
import io.github.valeronm.breadcrumb.domain.recorderCardTitle
import io.github.valeronm.breadcrumb.location.TrackingStatus
import io.github.valeronm.breadcrumb.util.avgSpeedKmh
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Date

@Composable
internal fun RecordTab(
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
    val status by TrackingStatus.state.collectAsStateWithLifecycle()
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
                    TrackReplayer.state.collectAsStateWithLifecycle().value
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
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
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
        val units = LocalUnits.current
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
                    "${units.distance(total.meters)} · ${formatDurationMs(total.durationMs)}",
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
                val units = LocalUnits.current
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    StatItem("Distance", units.distance(status.distanceMeters))
                    StatItem(
                        "Duration",
                        startedAt?.let { formatDuration(it, System.currentTimeMillis()) } ?: "—",
                    )
                    StatItem("Speed", status.speedMps?.let { units.speedFromKmh(it * 3.6) } ?: "—")
                    StatItem("Avg", if (avgKmh > 0) units.speedFromKmh(avgKmh) else "—")
                    StatItem("Elevation", status.altitudeM?.let { units.shortDistance(it) } ?: "—")
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
        deaf = status.deaf,
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

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall)
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
