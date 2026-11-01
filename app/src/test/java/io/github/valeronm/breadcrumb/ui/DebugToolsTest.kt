package io.github.valeronm.breadcrumb.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.Settings
import io.github.valeronm.breadcrumb.ui.theme.AppTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DebugToolsTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun load() {
        DebugTools.load(context)
    }

    @Test
    fun `settings offers debug tools enabled by default and can turn them off`() {
        compose.setContent {
            AppTheme {
                SettingsScreen(say = {}, onBack = {}, onOpenPage = {})
            }
        }

        compose.onNodeWithText(context.getString(R.string.settings_debug)).performScrollTo().assertIsDisplayed()
        compose.onNode(isToggleable()).assertIsOn().performClick().assertIsOff()
        assertFalse(Settings.debugTools(context))
    }

    @Test
    fun `a saved opt-out survives reloading settings`() {
        DebugTools.setEnabled(context, false)

        DebugTools.load(context)

        assertFalse(DebugTools.enabled)
        assertFalse(Settings.debugTools(context))
    }

    @Test
    fun `loading follows the stored choice in either direction`() {
        Settings.setDebugTools(context, false)
        DebugTools.load(context)
        assertFalse(DebugTools.enabled)

        Settings.setDebugTools(context, true)
        DebugTools.load(context)
        assertTrue(DebugTools.enabled)
    }
}
