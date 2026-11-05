package io.github.valeronm.breadcrumb.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.valeronm.breadcrumb.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MapSheetPageTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a page whose subject arrives after its first frame opens lowered`() {
        var subject by mutableStateOf<String?>(null)
        compose.setContent {
            AppTheme {
                MapSheetPage(
                    topBar = {},
                    subject = subject,
                    peek = { Text("peek of $it") },
                    raised = { Text("raised for $it") },
                    map = { _, _, _ -> },
                )
            }
        }

        subject = "trip"

        compose.onNodeWithText("peek of trip").assertIsDisplayed()
        compose.onNodeWithText("raised for trip").assertIsNotDisplayed()
    }

    @Test
    fun `a page that opens with nothing raised stays lowered when raised rows appear`() {
        var rows by mutableStateOf(false)
        compose.setContent {
            AppTheme {
                MapSheetPage(
                    topBar = {},
                    subject = "gap",
                    peek = { Text("peek of $it") },
                    raised = { if (rows) Text("raised for $it") },
                    map = { _, _, _ -> },
                )
            }
        }

        rows = true

        compose.onNodeWithText("peek of gap").assertIsDisplayed()
        compose.onNodeWithText("raised for gap").assertIsNotDisplayed()
    }
}
