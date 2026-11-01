package io.github.valeronm.breadcrumb.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
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
class TrackSheetRowsTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val recorded = trackSummary(1, ActivityType.DRIVING.name, 0, 10 * MIN, meters = 5_000.0)
    private val entered = trackSummary(
        2,
        ActivityType.DRIVING.name,
        10 * MIN,
        20 * MIN,
        meters = 3_000.0,
        source = TrackOrigin.MANUAL.code,
    )
    private val trip = Trip(listOf(recorded, entered))

    private fun screen(content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(
                LocalMeasures provides Measures(UnitSystem.METRIC, unitSymbols(context)),
                LocalDurationSymbols provides durationSymbols(LocalContext.current),
                LocalReaderClock provides readerClockOf(context),
            ) {
                AppTheme { content() }
            }
        }
    }

    private fun onTimeline(trip: Trip, opened: MutableList<String>) = TrackOnTimeline(
        partOf = trip.takeIf { it.chained },
        onOpenTrip = { opened += "trip ${trip.main.id}" },
        onOpenPart = { opened += "part $it" },
        onShowOnTimeline = { opened += "timeline" },
    )

    @Test fun `each action reads as its label and reports itself`() {
        val picked = mutableListOf<TrackAction>()
        screen {
            TrackSheetRows(
                listOf(TrackAction.CHANGE_TYPE, TrackAction.SHARE, TrackAction.DELETE_PART),
                recorded.id,
                chain = null,
                onTimeline = null,
                onAction = { picked += it },
            )
        }

        compose.onNodeWithText(context.getString(R.string.track_change_type)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.track_share_gpx)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.track_delete_part)).performClick()
        assertEquals(listOf(TrackAction.DELETE_PART), picked)
    }

    @Test fun `a track off the timeline offers no way to it`() {
        screen { TrackSheetRows(listOf(TrackAction.SHARE), recorded.id, null, onTimeline = null, onAction = {}) }

        compose.onNodeWithText(context.getString(R.string.track_share_gpx)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.common_show_on_timeline)).assertDoesNotExist()
    }

    @Test fun `a track on the timeline shows it`() {
        val opened = mutableListOf<String>()
        screen {
            TrackSheetRows(listOf(TrackAction.SHARE), recorded.id, null, onTimeline(Trip(recorded), opened), onAction = {})
        }

        compose.onNodeWithText(context.getString(R.string.common_show_on_timeline)).performClick()
        assertEquals(listOf("timeline"), opened)
    }

    @Test fun `a part's page opens the trip it is part of`() {
        val opened = mutableListOf<String>()
        val chain = trip to TripClocks.allOn(trip, timelineZone())
        screen { TrackSheetRows(listOf(TrackAction.SHARE), recorded.id, chain, onTimeline(trip, opened), onAction = {}) }

        compose.onNodeWithText(context.getString(ActivityType.DRIVING.labelRes), substring = true, useUnmergedTree = true)
            .performClick()
        assertEquals(listOf("trip ${trip.main.id}"), opened)
    }
}
