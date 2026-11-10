package io.github.valeronm.breadcrumb.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import io.github.valeronm.breadcrumb.data.AndroidDistance
import io.github.valeronm.breadcrumb.data.TrackPoints
import io.github.valeronm.breadcrumb.domain.DwellDetector
import io.github.valeronm.breadcrumb.domain.StopFinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class DebugCircles(val stops: List<MapCircle>, val dwells: List<MapCircle>) {
    companion object {
        val NONE = DebugCircles(emptyList(), emptyList())
    }
}

// A stop whose fixes barely spread would draw a circle too small to show beside the track line.
private const val MIN_STOP_CIRCLE_M = 30.0

/** Both detectors run only while [DebugTools.enabled]. */
@Composable
internal fun rememberDebugCircles(trackPoints: TrackPoints?, source: String?): State<DebugCircles> {
    val enabled = DebugTools.enabled
    return produceState(DebugCircles.NONE, trackPoints?.all, trackPoints?.good, source, enabled) {
        value = DebugCircles.NONE
        val loaded = trackPoints ?: return@produceState
        if (!enabled) return@produceState
        value = withContext(Dispatchers.Default) {
            DebugCircles(
                stops = if (StopFinder.runsFor(source)) {
                    StopFinder.find(loaded.all, StopFinder.SHIPPED, AndroidDistance)
                        .map { MapCircle(it.center, maxOf(it.spreadM, MIN_STOP_CIRCLE_M)) }
                } else {
                    emptyList()
                },
                dwells = DwellDetector.detect(loaded.good, DwellDetector.TRACK_OVERLAY, AndroidDistance)
                    .map { MapCircle(it.centroid, it.corralRadiusM) },
            )
        }
    }
}
