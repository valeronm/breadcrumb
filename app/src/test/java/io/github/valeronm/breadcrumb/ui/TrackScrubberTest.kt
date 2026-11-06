package io.github.valeronm.breadcrumb.ui

import android.content.Context
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.valeronm.breadcrumb.data.TrackQuality
import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.MIN
import io.github.valeronm.breadcrumb.domain.TrackOrigin
import io.github.valeronm.breadcrumb.ui.theme.AppTheme
import io.github.valeronm.breadcrumb.util.Measures
import io.github.valeronm.breadcrumb.util.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TrackScrubberTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val measures get() = Measures(UnitSystem.METRIC, unitSymbols(context))

    private val start = 1_700_000_000_000L

    private val lineArgb = 0xFF2196F3.toInt()

    private val points = (0..3).map { i ->
        TrackPoint(
            trackId = 1,
            latitude = 0.0,
            longitude = i * 0.01,
            altitude = null,
            accuracy = null,
            speed = null,
            bearing = null,
            timestamp = start + i * 10 * MIN,
        )
    }

    private var selected by mutableStateOf<Int?>(null)

    private fun speed(): MetricGraphData {
        val seams = TrackQuality.seams(points)
        return metricGraphData(seams, trackColoring(plottedSeries(seams, ColorMode.SPEED, measures, unmeasured = false, smoothed = true), ActivityType.DRIVING, dark = false))!!
    }

    private fun scrubber(data: ScrubberData) {
        compose.setContent {
            CompositionLocalProvider(
                LocalMeasures provides measures,
                LocalReaderClock provides readerClockOf(context),
            ) {
                AppTheme {
                    TrackScrubber(
                        data = data,
                        selectedIndex = selected,
                        onSelect = { selected = it },
                        cut = null,
                        onCutOffered = null,
                        onCut = {},
                        zone = timelineZone(),
                        modifier = Modifier.width(300.dp),
                    )
                }
            }
        }
    }

    private fun tapAt(fraction: Float) = compose.onRoot().performTouchInput { click(Offset(width * fraction, height * 0.3f)) }

    private fun timeOf(index: Int) = readerClockOf(context).time(points[index].timestamp, timelineZone())

    @Test fun `a tap on the metric card picks the fix nearest the tapped time and reads its value and time`() {
        scrubber(speed())

        tapAt(0.62f)
        assertEquals(2, selected)
        compose.onNodeWithText(" · ${timeOf(2)}", substring = true).assertExists()
    }

    @Test fun `a tap on a card of times alone picks the fix nearest the tapped time and reads its time alone`() {
        scrubber(FixTimes(points, lineArgb))

        tapAt(0.36f)
        assertEquals(1, selected)
        compose.onNodeWithText(timeOf(1)).assertExists()
    }

    @Test fun `a tap at either edge of the card picks the fix at that end`() {
        scrubber(FixTimes(points, lineArgb))

        tapAt(0.005f)
        assertEquals(0, selected)
        tapAt(0.995f)
        assertEquals(3, selected)
    }

    @Test fun `each fix gets a dot at its time`() {
        assertEquals(listOf(0, 100, 200, 299), FixTimes(points, lineArgb).fixColumns(300).toList())
    }

    @Test fun `fixes within one pixel share a dot`() {
        val close = listOf(points[0], points[0].copy(timestamp = start + 1000), points[3])
        assertEquals(listOf(0, 299), FixTimes(close, lineArgb).fixColumns(300).toList())
    }

    @Test fun `a part with a metric is scrubbed over it`() {
        val metric = speed()
        assertSame(metric, scrubberDataOf(metric, points, TrackOrigin.RECORDED, lineArgb))
    }

    @Test fun `a Google Timeline part with no metric is scrubbed for its fixes' times`() {
        assertEquals(points, (scrubberDataOf(null, points, TrackOrigin.GOOGLE_TIMELINE, lineArgb) as? FixTimes)?.points)
    }

    @Test fun `an entered part has no scrubber`() {
        assertNull(scrubberDataOf(null, points, TrackOrigin.MANUAL, lineArgb))
    }

    @Test fun `a part with a single fix has no scrubber`() {
        assertNull(scrubberDataOf(null, points.take(1), TrackOrigin.GOOGLE_TIMELINE, lineArgb))
    }
}
