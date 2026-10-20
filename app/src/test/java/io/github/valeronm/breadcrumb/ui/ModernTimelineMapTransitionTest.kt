package io.github.valeronm.breadcrumb.ui

import androidx.compose.ui.unit.dp
import io.github.valeronm.breadcrumb.data.Settings.TimelineColorPalette
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.domain.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModernTimelineMapTransitionTest {
    @Test
    fun `rejected fixes stay hidden on drawable tracks until explicitly enabled`() {
        val rejected = TrackPoint(
            trackId = 12,
            latitude = 1.0,
            longitude = -2.0,
            altitude = null,
            accuracy = 100f,
            speed = null,
            bearing = null,
            timestamp = 1,
            ignored = true,
        )
        val hidden = track(12).copy(
            noisyPoints = listOf(rejected),
            showNoisy = showNoisyTrackPoints(2, null),
        )
        val shown = hidden.copy(showNoisy = showNoisyTrackPoints(2, true))

        assertTrue(hidden.visibleNoisyPoints.isEmpty())
        assertEquals(listOf(rejected), shown.visibleNoisyPoints)
        assertTrue(shown.copy(showNoisy = false).visibleNoisyPoints.isEmpty())
        assertFalse(modernTimelineMapNeedsReframe(hidden, shown, paddingChanged = false))
    }

    @Test
    fun `tracks without a drawable line reveal rejected fixes unless the reader hides them`() {
        assertFalse(showNoisyTrackPoints(null, null))
        assertTrue(showNoisyTrackPoints(0, null))
        assertTrue(showNoisyTrackPoints(1, null))
        assertFalse(showNoisyTrackPoints(1, false))
        assertTrue(showNoisyTrackPoints(2, true))
    }

    @Test
    fun `changing map mode or detail target reframes the retained map`() {
        val overview = overview("day-1")
        val track = track(12L)
        val place = place("home")

        assertTrue(modernTimelineMapNeedsReframe(overview, track, paddingChanged = false))
        assertTrue(modernTimelineMapNeedsReframe(track, place, paddingChanged = false))
        assertTrue(modernTimelineMapNeedsReframe(place, overview, paddingChanged = false))
    }

    @Test
    fun `camera stays still while track selection or legend position changes`() {
        val first = track(12L)
        val second = first.copy(legendBottomPadding = 320.dp)

        assertFalse(modernTimelineMapNeedsReframe(first, second, paddingChanged = false))
    }

    @Test
    fun `settled drawer padding and overview day changes request a new frame`() {
        val overview = overview("day-1")

        assertTrue(modernTimelineMapNeedsReframe(overview, overview, paddingChanged = true))
        assertTrue(modernTimelineMapNeedsReframe(overview, overview("day-2"), paddingChanged = false))
    }

    private fun overview(frameKey: String) = ModernTimelineMapState.Overview(
        lines = emptyList(),
        places = emptyList(),
        frameKey = frameKey,
        linesComplete = true,
        colorPalette = TimelineColorPalette.CLASSIC,
        bottomPaddingPx = 0,
    )

    private fun track(id: Long) = ModernTimelineMapState.Track(
        id = id,
        points = emptyList(),
        noisyPoints = emptyList(),
        activity = null,
        colorMode = ColorMode.SPEED,
        selectedPoint = null,
        dwells = emptyList(),
        overruns = emptyList(),
        endPlaces = emptyList(),
        coloring = null,
        seams = null,
        contextLines = emptyList(),
        showContextLines = false,
        showLegend = false,
        legendBottomPadding = 0.dp,
        bottomPaddingPx = 0,
    )

    private fun place(key: String) = ModernTimelineMapState.PlaceTarget(
        key = key,
        marker = PlaceMarker(Coordinate(52.0, 21.0)),
        radiusM = 100.0,
        bottomPaddingPx = 0,
    )
}
