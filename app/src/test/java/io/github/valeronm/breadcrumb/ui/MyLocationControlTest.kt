package io.github.valeronm.breadcrumb.ui

import android.content.Context
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.valeronm.breadcrumb.R
import io.github.valeronm.breadcrumb.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MyLocationControlTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun `the button keeps its name while it waits for a fix`() {
        val waiting = MyLocationState(
            granted = { true },
            blocked = { false },
            fetch = { null },
            launch = {},
            onUnavailable = {},
        )
        compose.setContent { AppTheme { MyLocationControl(waiting) } }
        compose.runOnIdle { waiting.goThere() }
        val spinner = hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)
        val named = hasContentDescription(context.getString(R.string.places_my_location))
        compose.onAllNodes(spinner and named, useUnmergedTree = true).assertCountEquals(1)
    }
}
