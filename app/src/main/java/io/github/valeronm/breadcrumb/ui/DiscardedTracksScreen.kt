package io.github.valeronm.breadcrumb.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.DISCARDED_RETENTION_DAYS
import io.github.valeronm.breadcrumb.data.db.DiscardedSummary
import io.github.valeronm.breadcrumb.data.db.Track
import io.github.valeronm.breadcrumb.domain.ActivityType
import java.time.LocalDate
import java.time.ZoneId

/**
 * "Recently deleted": every soft-deleted track — deleted by the user, filtered by the keep
 * thresholds, or replaced by a merge — with why it's here and how long until the retention
 * purge removes it for good. Rows restore in place; tapping opens the full track detail.
 *
 * Rows are headed by the day the trip was *recorded*, which is what the reader recognises a trip by.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DiscardedTracksScreen(
    viewModel: TrackListViewModel,
    onBack: () -> Unit,
    // Tapping a row opens its full detail as a layer above this list; back returns here.
    onOpenTrack: (Long) -> Unit,
) {
    val tracks by viewModel.discardedTracks.collectAsStateWithLifecycle()
    val nowMs = remember { System.currentTimeMillis() }
    var showClearDialog by remember { mutableStateOf(false) }
    val zone = timelineZone()
    // Dates partition here where the timeline's can repeat, every row being read on one clock.
    // Rows arrive newest first, and groupBy keeps that order.
    val days = remember(tracks, zone) { tracks.groupBy { it.track.startedAt.toLocalDate(zone) } }
    val today = remember(zone) { LocalDate.now(zone) }
    val todayText = stringResource(R.string.relative_today).standaloneCase()
    val yesterdayText = stringResource(R.string.relative_yesterday).standaloneCase()
    Scaffold(
        topBar = {
            TopAppBar(
                colors = canvasTopBarColors(),
                title = {
                    Text(
                        if (tracks.isEmpty()) {
                            stringResource(R.string.discarded_title)
                        } else {
                            stringResource(R.string.discarded_title_count, tracks.size)
                        },
                    )
                },
                navigationIcon = { BackNavIcon(onBack) },
                actions = {
                    if (tracks.isNotEmpty()) {
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(
                                Icons.Filled.DeleteForever,
                                contentDescription = stringResource(R.string.discarded_clear_all),
                            )
                        }
                    }
                },
            )
        },
    ) { inner ->
        if (tracks.isEmpty()) {
            EmptyState(
                stringResource(R.string.discarded_empty, DISCARDED_RETENTION_DAYS),
                Modifier.padding(inner).fillMaxSize().padding(horizontal = 24.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.padding(inner).fillMaxSize().padding(horizontal = 16.dp)) {
                item {
                    Text(
                        stringResource(R.string.discarded_retention, DISCARDED_RETENTION_DAYS),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                days.forEach { (date, dayRows) ->
                    stickyHeader(key = "header:$date") {
                        Text(
                            dayLabel(date, today, todayText, yesterdayText),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth()
                                .background(MaterialTheme.colorScheme.background)
                                .swallowTaps()
                                .padding(top = 14.dp, bottom = 6.dp),
                        )
                    }
                    itemsIndexed(dayRows, key = { _, row -> row.track.id }) { index, row ->
                        DiscardedRow(
                            row = row,
                            zone = zone,
                            nowMs = nowMs,
                            onOpen = { onOpenTrack(row.track.id) },
                            onRestore = { viewModel.restoreTrack(row.track.id) },
                        )
                        // The next header divides the last row of a day from what follows it.
                        if (index < dayRows.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                        }
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        ConfirmDialog(
            icon = Icons.Filled.DeleteForever,
            title = stringResource(R.string.discarded_clear_confirm_title),
            text = pluralStringResource(
                R.plurals.discarded_clear_confirm_body,
                tracks.size,
                tracks.size,
            ),
            confirmLabel = stringResource(R.string.discarded_delete_all),
            onConfirm = {
                viewModel.purgeAllDiscarded()
                showClearDialog = false
            },
            onDismiss = { showClearDialog = false },
        )
    }
}

@Composable
private fun DiscardedRow(
    row: DiscardedSummary,
    zone: ZoneId,
    nowMs: Long,
    onOpen: () -> Unit,
    onRestore: () -> Unit,
) {
    val t = row.track
    val activity = ActivityType.ofName(t.activityType)
    val activityName = activityLabel(LocalContext.current, t.activityType)
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconDisc(
            activityIcon(activity),
            activityDiscStyle(activity),
            contentDescription = activityName,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            val started = timeText(t.startedAt, zone)
            Text(
                "$activityName · $started",
                style = MaterialTheme.typography.bodyLarge,
            )
            // The separators stay in code, out of the wording: they are layout
            // between facts, not part of any of them. Each fact is a whole phrase —
            // the count included, which is why it comes from the same plural the
            // track detail counts fixes with rather than from a word written here.
            Text(
                listOfNotNull(
                    pluralStringResource(
                        R.plurals.track_points,
                        t.pointCount,
                        t.pointCount,
                    ),
                    distanceText(t.distanceMeters),
                    durationText(t.startedAt, t.endedAt),
                    if (t.ignoredCount > 0) {
                        pluralStringResource(
                            R.plurals.discarded_excluded,
                            t.ignoredCount,
                            t.ignoredCount,
                        )
                    } else {
                        null
                    },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                listOfNotNull(
                    discardReasonRes(row.discardReason)?.let { stringResource(it) },
                    purgeCountdown(row.discardedAt, nowMs),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRestore) {
            Icon(
                Icons.Filled.RestoreFromTrash,
                contentDescription = stringResource(R.string.discarded_restore),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@StringRes
private fun discardReasonRes(reason: String?): Int? = when (reason) {
    Track.REASON_DELETED -> R.string.discarded_reason_deleted
    Track.REASON_FILTERED -> R.string.discarded_reason_filtered
    Track.REASON_MERGED -> R.string.discarded_reason_merged
    else -> null
}

/** "9 days left" until the retention purge; clamps at "removal due". */
@Composable
@ReadOnlyComposable
private fun purgeCountdown(discardedAt: Long, nowMs: Long): String {
    val daysGone = ((nowMs - discardedAt) / (24 * 60 * 60_000L)).toInt()
    val left = DISCARDED_RETENTION_DAYS - daysGone
    return if (left <= 0) {
        stringResource(R.string.discarded_removal_due)
    } else {
        pluralStringResource(R.plurals.discarded_days_left, left, left)
    }
}
