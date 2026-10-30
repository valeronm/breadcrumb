package io.github.valeronm.breadcrumb.boundaryfixture.history

import io.github.valeronm.breadcrumb.boundaryfixture.data.TrackRepository
import io.github.valeronm.breadcrumb.boundaryfixture.domain.ReachesSharedRow
import io.github.valeronm.breadcrumb.boundaryfixture.location.RecorderAdapter
import kotlinx.coroutines.flow.Flow

class HistorySibling

class HistoryValue(val row: ReachesSharedRow, val flow: Flow<Int>, val sibling: HistorySibling)

class HistoryReachesData(val repository: TrackRepository)

class HistoryReachesRecorder(val adapter: RecorderAdapter)
