package io.github.valeronm.breadcrumb.ui

import androidx.compose.ui.unit.Dp
import io.github.valeronm.breadcrumb.data.JourneyLine
import io.github.valeronm.breadcrumb.data.Settings.TimelineColorPalette
import io.github.valeronm.breadcrumb.data.TrackQuality
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.DwellDetector
import io.github.valeronm.breadcrumb.domain.TrackSettle

/** Inputs for the one MapLibre host that stays mounted throughout modern Timeline navigation. */
internal sealed interface ModernTimelineMapState {
    val bottomPaddingPx: Int

    data class Overview(
        val lines: List<JourneyLine>,
        val places: List<OverviewPlace>,
        val frameKey: Any,
        val linesComplete: Boolean,
        val colorPalette: TimelineColorPalette,
        override val bottomPaddingPx: Int,
    ) : ModernTimelineMapState

    data class Loading(override val bottomPaddingPx: Int) : ModernTimelineMapState

    data class Track(
        val id: Long,
        val points: List<TrackPoint>,
        val noisyPoints: List<TrackPoint>,
        val activity: ActivityType?,
        val colorMode: ColorMode,
        val selectedPoint: TrackPoint?,
        val dwells: List<DwellDetector.Dwell>,
        val overruns: List<TrackSettle.Overrun>,
        val endPlaces: List<Place>,
        val coloring: TrackColoring?,
        val seams: TrackQuality.Seams?,
        val contextLines: List<JourneyLine>,
        val showContextLines: Boolean,
        val showLegend: Boolean,
        val legendBottomPadding: Dp,
        override val bottomPaddingPx: Int,
        val showNoisy: Boolean = false,
    ) : ModernTimelineMapState {
        val visibleNoisyPoints: List<TrackPoint> get() = if (showNoisy) noisyPoints else emptyList()
    }

    data class PlaceTarget(
        val key: String,
        val marker: PlaceMarker,
        val radiusM: Double,
        override val bottomPaddingPx: Int,
    ) : ModernTimelineMapState
}
