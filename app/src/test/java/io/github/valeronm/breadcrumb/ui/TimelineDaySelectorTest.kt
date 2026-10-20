package io.github.valeronm.breadcrumb.ui

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class TimelineDaySelectorTest {
    @get:Rule
    val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `today appears once and actions dispatch from the menu`() {
        val today = LocalDate.now(timelineZone())
        var shared = 0
        var added = 0
        compose.setContent {
            AppTheme {
                DaySelector(today, {}, onAddTrip = { added++ }, onShare = { shared++ }, canShare = true)
            }
        }
        val label = context.getString(R.string.relative_today).standaloneCase()
        compose.onAllNodesWithText(label).assertCountEquals(1)
        compose.onNodeWithContentDescription(context.getString(R.string.action_settings)).assertDoesNotExist()
        val menu = context.getString(R.string.timeline_day_actions)
        compose.onNodeWithContentDescription(menu).performClick()
        compose.onNodeWithText(context.getString(R.string.timeline_share_day, label)).performClick()
        assertEquals(1, shared)
        compose.onNodeWithContentDescription(menu).performClick()
        compose.onNodeWithText(context.getString(R.string.action_add_missing_trip)).performClick()
        assertEquals(1, added)
    }

    @Test
    fun `empty day disables share but still allows adding a trip`() {
        compose.setContent {
            AppTheme { DaySelector(LocalDate.now(timelineZone()), {}, onAddTrip = {}, onShare = {}) }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.timeline_day_actions)).performClick()
        val label = context.getString(R.string.relative_today).standaloneCase()
        compose.onNodeWithText(context.getString(R.string.timeline_share_day, label)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.action_add_missing_trip)).assertIsEnabled()
    }

    @Test
    fun `previous day selects yesterday and today prevents stepping forward`() {
        val today = LocalDate.now(timelineZone())
        var selected = today
        compose.setContent { AppTheme { DaySelector(today, { selected = it }) } }
        compose.onNodeWithContentDescription(context.getString(R.string.timeline_map_next_day)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(context.getString(R.string.timeline_map_prev_day)).performClick()
        assertEquals(today.minusDays(1), selected)
    }
}
