package io.github.valeronm.breadcrumb.ui

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.MIN
import io.github.valeronm.breadcrumb.domain.TrackOrigin
import io.github.valeronm.breadcrumb.domain.Trip
import io.github.valeronm.breadcrumb.domain.TripClocks
import io.github.valeronm.breadcrumb.domain.TripFocus
import io.github.valeronm.breadcrumb.domain.trackSummary
import io.github.valeronm.breadcrumb.ui.theme.AppTheme
import io.github.valeronm.breadcrumb.util.Measures
import io.github.valeronm.breadcrumb.util.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TripSelectorTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val entered = trackSummary(1, ActivityType.DRIVING.name, 0, 10 * MIN, source = TrackOrigin.MANUAL.code)
    private val imported = trackSummary(2, ActivityType.DRIVING.name, 10 * MIN, 20 * MIN, source = TrackOrigin.GOOGLE_TIMELINE.code)
    private val trip = Trip(listOf(entered, imported))

    private fun selector(focus: TripFocus, picked: MutableList<String>) {
        compose.setContent {
            CompositionLocalProvider(
                LocalMeasures provides Measures(UnitSystem.METRIC, unitSymbols(context)),
                LocalDurationSymbols provides durationSymbols(LocalContext.current),
                LocalReaderClock provides readerClockOf(context),
            ) {
                AppTheme {
                    TripSelector(
                        trip = trip,
                        clocks = TripClocks.allOn(trip, timelineZone()),
                        focus = focus,
                        onShowWhole = { picked += "whole" },
                        onShowPart = { picked += "part $it" },
                    )
                }
            }
        }
    }

    private fun text(id: Int) = context.getString(id)

    @Test fun `the whole trip's row counts its parts and shows it`() {
        val picked = mutableListOf<String>()
        selector(TripFocus.Part(trip, entered), picked)

        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.track_part_count, 2, 2)).performClick()
        assertEquals(listOf("whole"), picked)
    }

    @Test fun `a part's row shows that part`() {
        val picked = mutableListOf<String>()
        selector(TripFocus.Whole(trip), picked)

        compose.onNodeWithText(text(R.string.common_origin_google_timeline)).performClick()
        assertEquals(listOf("part 2"), picked)
    }

    @Test fun `the row on show takes no tap`() {
        val picked = mutableListOf<String>()
        selector(TripFocus.Part(trip, entered), picked)

        compose.onNodeWithText(text(R.string.common_origin_entered_by_hand)).performClick()
        compose.onNodeWithText(text(R.string.common_origin_google_timeline)).performClick()
        assertEquals(listOf("part 2"), picked)
    }

    @Test fun `the row on show reads as selected and the others as not`() {
        selector(TripFocus.Part(trip, entered), mutableListOf())

        compose.onNodeWithText(text(R.string.common_origin_entered_by_hand)).assertIsSelected()
        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.track_part_count, 2, 2)).assertIsNotSelected()
    }
}
