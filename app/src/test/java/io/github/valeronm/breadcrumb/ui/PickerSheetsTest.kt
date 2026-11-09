package io.github.valeronm.breadcrumb.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.PlaceCategory
import io.github.valeronm.breadcrumb.domain.PlaceCategoryGroup
import io.github.valeronm.breadcrumb.domain.PlaceCategorySuggester
import io.github.valeronm.breadcrumb.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PickerSheetsTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun screen(content: @Composable () -> Unit) {
        compose.setContent { AppTheme { content() } }
    }

    @Test
    fun `the trip type sheet hands back the type tapped`() {
        var picked: ActivityType? = null
        screen {
            ActivityTypeSheet(
                options = listOf(ActivityType.WALKING, ActivityType.DRIVING),
                selected = ActivityType.WALKING,
                onDismiss = {},
                onPick = { picked = it },
            )
        }

        compose.onNodeWithText(context.getString(ActivityType.DRIVING.labelRes)).performClick()

        assertEquals(ActivityType.DRIVING, picked)
    }

    @Test
    fun `the trip types read as one set`() {
        val options = listOf(ActivityType.WALKING, ActivityType.DRIVING)
        screen { ActivityTypeSheet(options, selected = ActivityType.WALKING, onDismiss = {}, onPick = {}) }

        assertOneSetHolds(options.map { context.getString(it.labelRes) })
    }

    @Test
    fun `the categories read as one set across their groups`() {
        openCategorySheet {}

        val labels = PlaceCategory.entries.map { context.getString(it.labelRes) }
        assertOneSetHolds(labels + context.getString(R.string.place_category_unset))
    }

    @Test
    fun `the category sheet heads every group`() {
        openCategorySheet {}

        for (group in PlaceCategoryGroup.entries) {
            val heading = context.getString(group.labelRes)
            assertTrue(heading, compose.onAllNodesWithText(heading).fetchSemanticsNodes().isNotEmpty())
        }
    }

    @Test
    fun `the category sheet hands back the category tapped`() {
        var picked: PlaceCategory? = null
        openCategorySheet { picked = it }

        val first = PlaceCategory.entries.first()
        compose.onNodeWithText(context.getString(first.labelRes)).performClick()

        assertEquals(first, picked)
    }

    private fun assertOneSetHolds(labels: List<String>) {
        val set = SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)
        compose.onAllNodes(set).assertCountEquals(1)
        for (label in labels) {
            compose.onAllNodes(hasText(label) and hasAnyAncestor(set)).assertCountEquals(1)
        }
    }

    private fun openCategorySheet(onPick: (PlaceCategory?) -> Unit) {
        screen {
            PlaceCategorySection(
                place = GapFixtures.place(1, label = null),
                suggester = PlaceCategorySuggester.train(emptyList()),
                onPick = onPick,
            )
        }
        compose.onNodeWithText(context.getString(R.string.place_category_set)).performClick()
    }
}
