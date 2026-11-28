package io.github.valeronm.breadcrumb.boundaryfixture.location.core

import android.content.Context
import io.github.valeronm.breadcrumb.boundaryfixture.data.TrackPolicy
import io.github.valeronm.breadcrumb.boundaryfixture.data.db.Track
import io.github.valeronm.breadcrumb.boundaryfixture.domain.ReachesSharedRow
import io.github.valeronm.breadcrumb.boundaryfixture.location.RecorderAdapter

sealed interface CoreEffect {
    class ReachesPlatform(val context: Context) : CoreEffect
}

class CoreReachesAdapter(val adapter: RecorderAdapter)

class CoreValue(
    val domain: ReachesSharedRow,
    val row: Track,
    val accumulators: Array<TrackPolicy.Accumulator>,
    val effect: CoreEffect,
)
