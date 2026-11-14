package io.github.valeronm.breadcrumb.ui

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.valeronm.breadcrumb.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SearchFieldTest {

    @get:Rule
    val compose = createComposeRule()

    private val spinner = hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)

    private fun field(busyLabel: String?) = compose.setContent {
        AppTheme { SearchField(TextFieldState("harbor"), placeholder = "", busyLabel = busyLabel) }
    }

    @Test fun `a busy field's spinner says what it is waiting for`() {
        field(busyLabel = "Looking")
        compose.onAllNodes(spinner and hasContentDescription("Looking")).assertCountEquals(1)
    }

    @Test fun `an idle field shows no spinner`() {
        field(busyLabel = null)
        compose.onAllNodes(spinner).assertCountEquals(0)
    }
}
