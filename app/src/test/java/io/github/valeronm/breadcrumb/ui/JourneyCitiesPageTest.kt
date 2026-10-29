package io.github.valeronm.breadcrumb.ui

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.domain.TravelDeriver
import io.github.valeronm.breadcrumb.domain.TravelNaming
import io.github.valeronm.breadcrumb.domain.atlas.AtlasCity
import io.github.valeronm.breadcrumb.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class JourneyCitiesPageTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun show(timeByCity: Map<AtlasCity, Long>) {
        val night = LocalDate.of(2024, 5, 12)
        val travel = TravelDeriver.Travel(night, night, 0, 0, null, null, 0, HOUR, emptyMap())
        val summary = TravelNaming.Summary(
            travel,
            emptyList(),
            timeByCity.keys,
            timeByCity.keys.map { it.country }.toSet(),
            timeByCity,
        )
        compose.setContent {
            CompositionLocalProvider(LocalDurationSymbols provides durationSymbols(LocalContext.current)) {
                AppTheme { CitiesPage(summary) }
            }
        }
    }

    @Test fun `two towns sharing a name in one country each show their region`() {
        show(
            linkedMapOf(
                AtlasCity(0, "Twin", "XX", "North") to 3 * HOUR,
                AtlasCity(1, "Twin", "XX", "South") to 2 * HOUR,
                AtlasCity(2, "Lone", "XX", "North") to HOUR,
            ),
        )

        compose.onNodeWithText(context.getString(R.string.common_city_in_region, "Twin", "North")).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.common_city_in_region, "Twin", "South")).assertIsDisplayed()
        compose.onNodeWithText("Lone").assertIsDisplayed()
        compose.onAllNodesWithText("North", substring = true).assertCountEquals(1)
    }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
