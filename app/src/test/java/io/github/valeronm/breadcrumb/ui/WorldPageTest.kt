package io.github.valeronm.breadcrumb.ui

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.domain.TravelNaming
import io.github.valeronm.breadcrumb.domain.VisitedWorld.City
import io.github.valeronm.breadcrumb.domain.VisitedWorld.Country
import io.github.valeronm.breadcrumb.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorldPageTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun days(n: Int) = context.resources.getQuantityString(R.plurals.insights_world_days, n, n)

    private val countries = listOf(
        Country("XX", 40, listOf(City("Hometown", 38), City("Seaside", 3))),
        Country("YY", 1, emptyList()),
    )

    private val opened = mutableListOf<TravelNaming.SpentCity>()

    private fun show() = compose.setContent { AppTheme { CountryList(countries) { opened += it } } }

    @Test fun `a country's cities show once it is tapped and hide on a second tap`() {
        show()
        compose.onAllNodesWithText("Seaside").assertCountEquals(0)

        compose.onAllNodes(hasClickAction()).onFirst().performClick()
        compose.onNodeWithText("Seaside").assertIsDisplayed()
        compose.onNodeWithText(days(3)).assertIsDisplayed()

        compose.onAllNodes(hasClickAction()).onFirst().performClick()
        compose.onAllNodesWithText("Seaside").assertCountEquals(0)
    }

    @Test fun `tapping a city opens it with its country`() {
        show()
        compose.onAllNodes(hasClickAction()).onFirst().performClick()

        compose.onNodeWithText("Seaside").performClick()

        assertEquals(listOf(TravelNaming.SpentCity("Seaside", "XX")), opened)
    }

    @Test fun `a country with no cities offers nothing to open`() {
        show()

        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        compose.onNodeWithText(days(1)).assertIsDisplayed()
    }
}
