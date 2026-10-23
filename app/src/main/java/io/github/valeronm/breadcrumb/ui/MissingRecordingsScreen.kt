package io.github.valeronm.breadcrumb.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.domain.TimelineItem

/** Every missing recording as one card holding both its ends in time order, under the day it began. */
@Composable
internal fun MissingRecordingsScreen(
    viewModel: TrackListViewModel,
    onBack: () -> Unit,
    onOpen: (afterTrackId: Long) -> Unit,
) {
    val missing by viewModel.missingRecordings.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                colors = canvasTopBarColors(),
                title = {
                    val count = missing?.count ?: 0
                    Text(
                        if (count == 0) {
                            stringResource(R.string.missing_recordings_title)
                        } else {
                            stringResource(R.string.missing_recordings_title_count, count)
                        },
                    )
                },
                navigationIcon = { BackNavIcon(onBack) },
            )
        },
    ) { inner ->
        val days = missing?.days ?: return@Scaffold
        if (days.isEmpty()) {
            EmptyState(
                stringResource(R.string.missing_recordings_empty),
                Modifier.padding(inner).fillMaxSize().padding(horizontal = 24.dp),
            )
        } else {
            MissingRecordingList(days, Modifier.padding(inner), onOpen)
        }
    }
}

@Composable
private fun MissingRecordingList(
    days: List<DayGroup<TimelineItem.GapItem>>,
    modifier: Modifier,
    onOpen: (afterTrackId: Long) -> Unit,
) {
    val dayLabelOf = rememberDayLabel()
    val listState = rememberLazyListState()
    val stops = remember(days) { headingScrollStops(days.map { it.date to it.items.size }) }
    Box(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (day in days) {
                val rows = day.items
                stickyHeading(key = day.headingKey) {
                    DayHeading(dayLabelOf(day.date))
                }
                itemsIndexed(rows, key = { _, row -> row.rowKey() }) { index, row ->
                    GapCard(
                        item = row,
                        shape = groupedRowShape(index, rows.size),
                        chronological = true,
                        onClick = { onOpen(row.gap.afterTrackId) },
                    )
                }
            }
        }
        FastScroller(
            state = listState,
            stops = stops,
            contentDescription = stringResource(R.string.missing_recordings_scroll_to_day),
            label = { dayLabelOf(it) },
        )
    }
}
