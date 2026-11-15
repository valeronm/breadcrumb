package io.github.valeronm.breadcrumb.ui

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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

@RunWith(RobolectricTestRunner::class)
class NotificationsSettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private var opened = 0

    @Test fun `a notification Android shows reads on and one it drops reads off`() {
        screen(ongoingShown = true, alertShown = false)

        assertEquals(1, compose.onAllNodesWithText(context.getString(R.string.common_on)).fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithText(context.getString(R.string.common_off)).fetchSemanticsNodes().size)
    }

    @Test fun `the link opens Android's notification settings`() {
        screen(ongoingShown = true, alertShown = false)

        compose.onNodeWithText(context.getString(R.string.notifications_android_settings)).performClick()

        assertEquals(1, opened)
    }

    private fun screen(ongoingShown: Boolean, alertShown: Boolean) {
        compose.setContent {
            AppTheme {
                NotificationsSettings(ongoingShown, alertShown, onOpenSettings = { opened++ }, onBack = {})
            }
        }
    }
}
