package io.github.valeronm.breadcrumb.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class WorldPageTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun days(n: Int) = context.resources.getQuantityString(R.plurals.insights_world_days, n, n)

    private val countries = listOf(
        Country("XX", 40, listOf(City(TravelNaming.SpentCity(0, "Hometown", "XX"), 38, sharesName = false), City(SEASIDE, 3, sharesName = false))),
        Country("YY", 1, emptyList()),
    )

    private val opened = mutableListOf<TravelNaming.SpentCity>()

    private fun toggle(@StringRes label: Int) = SemanticsMatcher("taps to ${context.getString(label)}") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == context.getString(label)
    }

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

    @Test fun `tapping anywhere on a country's card opens and closes its cities, as one labeled control`() {
        show()

        compose.onNodeWithText(days(40), substring = true).performClick()
        compose.onNodeWithText("Seaside").assertIsDisplayed()
        compose.onNode(toggle(R.string.insights_world_hide_cities)).assertIsDisplayed()
        compose.onAllNodes(hasContentDescription(context.getString(R.string.insights_world_hide_cities))).assertCountEquals(0)

        compose.onNodeWithText(days(40), substring = true).performClick()
        compose.onAllNodesWithText("Seaside").assertCountEquals(0)
    }

    @Test fun `tapping a city opens it with its country`() {
        show()
        compose.onAllNodes(hasClickAction()).onFirst().performClick()

        compose.onNodeWithText("Seaside").performClick()

        assertEquals(listOf(SEASIDE), opened)
    }

    @Test fun `a city sharing its name shows its region`() {
        val twins = listOf(
            Country(
                "XX",
                2,
                listOf(
                    City(TravelNaming.SpentCity(0, "Twin", "XX", "North"), 1, sharesName = true),
                    City(TravelNaming.SpentCity(1, "Twin", "XX", "South"), 1, sharesName = true),
                ),
            ),
        )
        compose.setContent { AppTheme { CountryList(twins) { opened += it } } }
        compose.onAllNodes(hasClickAction()).onFirst().performClick()

        compose.onNodeWithText(context.getString(R.string.common_city_in_region, "Twin", "South")).performClick()

        assertEquals(listOf(1), opened.map { it.row })
    }

    private fun showLongList() {
        val towns = (0 until 40).map { City(TravelNaming.SpentCity(it, "Town $it", "XX"), 1, sharesName = false) }
        val others = Locale.getISOCountries().take(20).map { Country(it, 1, emptyList()) }
        compose.setContent { AppTheme { CountryList(listOf(Country("XX", 40, towns)) + others) { opened += it } } }
        compose.onNode(toggle(R.string.insights_world_show_cities)).performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Town 39"))
    }

    @Test fun `an open country stays on screen while its cities scroll`() {
        showLongList()

        compose.onNode(toggle(R.string.insights_world_hide_cities)).assertIsDisplayed()
    }

    @Test fun `closing a country from deep in its cities lands on that country`() {
        showLongList()

        compose.onNode(toggle(R.string.insights_world_hide_cities)).performClick()

        compose.onNode(toggle(R.string.insights_world_show_cities)).assertIsDisplayed()
    }

    @Test fun `a country with no cities offers nothing to open`() {
        show()

        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        compose.onNodeWithText(days(1)).assertIsDisplayed()
    }

    private companion object {
        val SEASIDE = TravelNaming.SpentCity(1, "Seaside", "XX")
    }
}
