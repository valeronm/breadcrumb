package io.github.valeronm.breadcrumb.ui

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.Clocks
import io.github.valeronm.breadcrumb.domain.MIN
import io.github.valeronm.breadcrumb.domain.TrackOrigin
import io.github.valeronm.breadcrumb.domain.Trip
import io.github.valeronm.breadcrumb.domain.trackSummary
import io.github.valeronm.breadcrumb.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TripSpanRowTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val entered = trackSummary(1, ActivityType.DRIVING.name, 0, 10 * MIN, source = TrackOrigin.MANUAL.code)
    private val imported = trackSummary(2, ActivityType.DRIVING.name, 10 * MIN, 20 * MIN, source = TrackOrigin.IMPORTED.code)

    private fun span(trip: Trip) {
        compose.setContent {
            CompositionLocalProvider(LocalReaderClock provides readerClockOf(context)) {
                AppTheme { TripSpanRow(trip, Clocks.both(timelineZone())) }
            }
        }
    }

    @Test fun `a trip of several parts counts them in place of their writers`() {
        span(Trip(listOf(entered, imported)))

        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.track_part_count, 2, 2)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.common_origin_entered_by_hand)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.common_origin_gpx)).assertDoesNotExist()
    }

    @Test fun `a single track names its writer`() {
        span(Trip(imported))

        compose.onNodeWithText(context.getString(R.string.common_origin_gpx)).assertIsDisplayed()
    }
}
