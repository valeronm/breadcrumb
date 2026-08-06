package io.github.valeronm.breadcrumb.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.valeronm.breadcrumb.BuildConfig
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.domain.LiveFigures
import io.github.valeronm.breadcrumb.domain.RecordCardState
import io.github.valeronm.breadcrumb.domain.activityTotals
import io.github.valeronm.breadcrumb.domain.recordCardState
import io.github.valeronm.breadcrumb.domain.recorderText
import io.github.valeronm.breadcrumb.location.TrackingStatus
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.time.Duration.Companion.milliseconds

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
                title = stringResource(R.string.record_perm_foreground_title),
                body = stringResource(R.string.record_perm_foreground_body),
                button = stringResource(R.string.record_perm_foreground_button),
                onClick = onGrantForeground,
            )

            !backgroundOk -> PermissionCard(
                title = stringResource(R.string.record_perm_background_title),
                body = stringResource(R.string.record_perm_background_body),
                button = stringResource(R.string.record_perm_background_button),
                onClick = onGrantBackground,
            )

            else -> {
                AutoRecordControls(autoOn = autoOn, onToggle = onToggleAuto)
                if (autoOn && !batteryOk) {
                    Spacer(Modifier.height(8.dp))
                    PermissionCard(
                        title = stringResource(R.string.record_perm_battery_title),
                        body = stringResource(R.string.record_perm_battery_body),
                        button = stringResource(R.string.record_perm_battery_button),
                        onClick = onRequestBattery,
                    )
                }
                // The middle stretches so the keep-screen-on row is anchored at the bottom; while
                // recording (or replaying, debug), the track preview card fills all of it.
                val replay = if (BuildConfig.DEV_TOOLS) {
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
    val todayLabel = stringResource(R.string.relative_today).standaloneCase()
    val thisMonthLabel = stringResource(R.string.record_period_this_month)
    val periods = remember(byDate, today, todayLabel, thisMonthLabel) {
        val prevMonth = YearMonth.from(today).minusMonths(1)
        listOf(
            todayLabel to byDate.filter { it.second == today },
            thisMonthLabel to byDate.filter { it.second.year == today.year && it.second.month == today.month },
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
            stringResource(R.string.record_no_tracks),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        val totals = remember(tracks) { activityTotals(tracks, System.currentTimeMillis()) }
        val context = LocalContext.current
        for (total in totals) {
            // Named once for both the disc's description and the row's own text.
            val label = activityLabel(context, total.activityType)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TonalIconDisc(
                    icon = activityIcon(total.type),
                    // A hue each here, unlike the Timeline's neutral: this tab is about how the day
                    // moved, and no place shares the screen for the color to be taken from.
                    tint = activityColor(total.type),
                    contentDescription = label,
                    size = 28.dp,
                    iconSize = 16.dp,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${distanceText(total.meters)} · ${durationText(total.durationMs)}",
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
            stringResource(R.string.record_replaying, replay.trackLabel, replay.speedX.toString()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onStop) { Text(stringResource(R.string.common_stop)) }
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
        val fresh = if (lastId == null) {
            viewModel.getPoints(activeId)
        } else {
            viewModel.getPointsAfter(activeId, lastId)
        }
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
    // Two cards reading as one block, as the track screen's map and scrubber do: small gap, small
    // corners where they meet. The seam is what lets the stats sit against the card's own width
    // without the map's rounded corner cutting into the row.
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // The map takes whatever height the card is given beyond the stats block.
        Card(Modifier.fillMaxWidth().weight(1f), shape = groupedRowShape(0, 2)) {
            Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
                if (points.size >= 2) {
                    MapLibreTrackMap(points = points, activity = activity, directionalEnd = true)
                } else {
                    Text(
                        stringResource(R.string.record_waiting_for_fix),
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // Vertical padding only: the stats row below divides the card's own width, so an inset
        // on it would leave the two outer columns wider than the inner ones by that much and
        // put the rules off-centre. The title takes the inset it needs on its own.
        Card(Modifier.fillMaxWidth(), shape = groupedRowShape(1, 2)) {
            // Less above than below: the seam over the title adds to the gap, and the title's own
            // line leading adds again, so equal padding reads top-heavy.
            Column(Modifier.padding(top = 10.dp, bottom = 16.dp)) {
                Text(
                    stringResource(
                        R.string.record_current_track,
                        status.activity?.let { stringResource(it.labelRes) }
                            ?: stringResource(R.string.record_activity_idle),
                    ),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                // Live trip stats; the status flow updates per fix, which keeps these ticking.
                val startedAt = status.startedAtMillis
                // Equal-width columns, not SpaceBetween: these values change every fix, and with
                // content-sized cells the free space between them is redistributed on each change
                // — every separator shifts as a digit is gained or lost. Weights pin the rules and
                // let the text re-centre under them instead.
                Row(
                    // Intrinsic height so each separator spans this row's own cells — see
                    // [StatSeparator], whose rule is sized by the row rather than by a constant.
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val noValue = stringResource(R.string.common_no_value)
                    StatItem(
                        stringResource(R.string.common_stat_distance),
                        distanceText(status.distanceMeters),
                        Modifier.weight(1f),
                    )
                    StatSeparator()
                    StatItem(
                        stringResource(R.string.common_stat_duration),
                        startedAt?.let { durationText(it, System.currentTimeMillis()) } ?: noValue,
                        Modifier.weight(1f),
                    )
                    StatSeparator()
                    StatItem(
                        stringResource(R.string.record_stat_speed),
                        status.speedMps?.let { speedText(it * 3.6) } ?: noValue,
                        Modifier.weight(1f),
                    )
                    StatSeparator()
                    StatItem(
                        stringResource(R.string.record_stat_elevation),
                        status.altitudeM?.let { shortDistanceText(it) } ?: noValue,
                        Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** Recorder state while there's no track to draw: starting, idle, paused or waiting for GPS. */
@Composable
private fun RecorderStateCard(state: RecordCardState, status: TrackingStatus.State) {
    // A 1 Hz tick drives the pause countdown and the "last signal" age.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state) {
        while (true) {
            delay(1_000.milliseconds)
            nowMs = System.currentTimeMillis()
        }
    }
    // Remembered: this composable re-runs on every 1 Hz tick.
    val context = LocalContext.current
    val words = remember(context) { recorderWords(context) }
    // The live surface: the detail counts down and quotes figures, joined onto the title as one line.
    val text = words.recorderText(
        state = state,
        activity = status.activity,
        pausedActivity = status.pausedActivity,
        deaf = status.deaf,
        live = LiveFigures(
            nowMs = nowMs,
            pausedUntilMs = status.pausedUntilMillis,
            lastReadingAtMs = status.lastReadingAtMillis,
            rejectedAccuracyM = status.lastFixAccuracyM?.takeIf { status.lastFixRejectedByAccuracy },
            gpsSuspendedSinceMs = status.gpsSuspendedSinceMillis,
        ),
    )
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text.oneLine(),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/**
 * Keep-screen-on toggle; only actionable on the charger (car-mount use), grayed out on battery.
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
                stringResource(R.string.record_keep_screen_on),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(
                    if (charging) {
                        R.string.record_keep_screen_on_charging
                    } else {
                        R.string.record_keep_screen_on_idle
                    },
                ),
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
            MaterialTheme.colorScheme.onPrimaryContainer
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
                stringResource(R.string.record_auto_recording),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconSwitch(checked = autoOn, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun StatItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
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
