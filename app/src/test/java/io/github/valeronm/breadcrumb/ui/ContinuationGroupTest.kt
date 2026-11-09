package io.github.valeronm.breadcrumb.ui

import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.data.Settings
import io.github.valeronm.breadcrumb.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ContinuationGroupTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun `with the window off a walking-pause switch left on shows off and disabled`() {
        Settings.setStitchWindowSec(context, 0)
        Settings.setWaitOutWalkPauses(context, true)

        compose.setContent { AppTheme { ContinuationGroup() } }

        compose.onNode(isToggleable()).assertIsOff().assertIsNotEnabled()
        compose.onNodeWithText(
            context.getString(
                R.string.continuation_walk_pauses_needs_window,
                context.getString(R.string.continuation_window),
            ),
        ).assertExists()
    }

    @Test fun `with the window on the walking-pause switch shows the stored choice`() {
        Settings.setStitchWindowSec(context, Settings.DEFAULT_STITCH_WINDOW_SEC)
        Settings.setWaitOutWalkPauses(context, true)

        compose.setContent { AppTheme { ContinuationGroup() } }

        compose.onNode(isToggleable()).assertIsOn().assertIsEnabled()
        compose.onNodeWithText(context.getString(R.string.continuation_walk_pauses_sub)).assertExists()
    }
}
