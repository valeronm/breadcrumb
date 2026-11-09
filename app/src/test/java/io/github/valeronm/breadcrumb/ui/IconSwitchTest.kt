package io.github.valeronm.breadcrumb.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.valeronm.breadcrumb.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IconSwitchTest {

    @get:Rule
    val compose = createComposeRule()

    @Test fun `a disabled switch shows off whatever it holds`() {
        compose.setContent { AppTheme { IconSwitch(checked = true, onCheckedChange = {}, enabled = false) } }

        compose.onNode(isToggleable()).assertIsOff().assertIsNotEnabled()
    }
}
