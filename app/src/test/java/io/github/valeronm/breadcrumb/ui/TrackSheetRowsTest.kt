package io.github.valeronm.breadcrumb.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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

    private fun rows(
        focus: TripFocus,
        chain: Pair<Trip, TripClocks>?,
        actions: List<TrackAction> = listOf(TrackAction.SHARE),
        picked: MutableList<String> = mutableListOf(),
    ) = screen {
        TrackSheetRows(
            focus = focus,
            chain = chain,
            actions = actions,
            onShowWhole = { picked += "whole" },
            onShowPart = { picked += "part $it" },
            onAction = { picked += it.name },
        )
    }

    private val alone = TripFocus.Part(Trip(recorded), recorded)

    @Test fun `each action reads as its label and reports itself`() {
        val picked = mutableListOf<String>()
        rows(alone, null, listOf(TrackAction.CHANGE_TYPE, TrackAction.SHARE, TrackAction.DELETE_PART), picked = picked)

        compose.onNodeWithText(context.getString(R.string.track_change_type)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.track_share_gpx)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.track_delete_part)).performClick()
        assertEquals(listOf("DELETE_PART"), picked)
    }

    @Test fun `a trip off the timeline offers no way to it`() {
        screen { TripTopBarActions(trip, onShowOnTimeline = null) }

        compose.onNodeWithContentDescription(context.getString(R.string.common_show_on_timeline)).assertDoesNotExist()
    }

    @Test fun `a trip on the timeline shows it`() {
        val shown = mutableListOf<Trip>()
        screen { TripTopBarActions(trip) { shown += it } }

        compose.onNodeWithContentDescription(context.getString(R.string.common_show_on_timeline)).performClick()
        assertEquals(listOf(trip), shown)
    }

    @Test fun `a part lists its trip to switch to`() {
        val picked = mutableListOf<String>()
        rows(TripFocus.Part(trip, recorded), trip to TripClocks.allOn(trip, timelineZone()), picked = picked)

        compose.onNodeWithText(context.getString(R.string.activity_driving), substring = true, useUnmergedTree = true).performClick()
        assertEquals(listOf("whole"), picked)
    }

    @Test fun `a lone track lists no trip`() {
        rows(alone, Trip(recorded) to TripClocks.allOn(Trip(recorded), timelineZone()))

        compose.onNodeWithText(context.getString(R.string.track_share_gpx)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.activity_driving), substring = true).assertDoesNotExist()
    }
}
