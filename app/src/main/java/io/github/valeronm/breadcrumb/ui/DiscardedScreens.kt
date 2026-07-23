package io.github.valeronm.breadcrumb.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.valeronm.breadcrumb.data.ActivityType
import io.github.valeronm.breadcrumb.data.DISCARDED_RETENTION_DAYS
import io.github.valeronm.breadcrumb.data.db.DiscardedSummary
import io.github.valeronm.breadcrumb.data.db.Track
import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.util.PerLocale
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * "Recently deleted": every soft-deleted track — deleted by the user, filtered by the keep
 * thresholds, or replaced by a merge — with why it's here and how long until the retention
 * purge removes it for good. Rows restore in place; tapping opens the full track detail.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
                                "${t.pointCount} pts · ${LocalUnits.current.distance(t.distanceMeters)} · " +
                                    formatDurationMs((t.endedAt ?: t.startedAt) - t.startedAt) +
                                    // "excluded", not "noisy": the count covers both species —
                                    // bad fixes and the recorder's overrun at the edges — and what
                                    // they have in common is being left out of the path.
                                    (if (t.ignoredCount > 0) " · ${t.ignoredCount} excluded" else ""),
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
    Track.REASON_TRIMMED -> "Stay split from a track"
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

internal fun DiscardedSummary.toTrackSummary() = TrackSummary(
    id = id, activityType = activityType, startedAt = startedAt, endedAt = endedAt,
    distanceMeters = distanceMeters, pointCount = pointCount, ignoredCount = ignoredCount,
)

private val discardedWhenFormat by PerLocale { DateTimeFormatter.ofPattern("d MMM HH:mm", it) }
