package io.github.valeronm.breadcrumb.ui

import android.Manifest
import android.app.Application
import android.os.PowerManager
import android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
import android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import io.github.valeronm.breadcrumb.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class RecorderSetupTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    private val asked = mutableListOf<List<String>>()

    private fun grant(vararg permissions: String) = shadowOf(app).grantPermissions(*permissions)

    private fun exemptFromBatteryOptimization() =
        shadowOf(app.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(app.packageName, true)

    private fun lastOpened() = shadowOf(app).peekNextStartedActivity()?.action

    private fun setup() = RecorderSetup(activity).also { it.launchPermissions = { permissions -> asked += permissions } }

    private val location = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
    )

    @Test fun `the battery dialog answered after a permission dialog carries the run on to notifications`() {
        grant(*location)
        val setup = setup()
        setup.toggleAuto(true)
        grant(Manifest.permission.ACTIVITY_RECOGNITION)
        setup.onDialogAnswered()
        assertEquals(ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, lastOpened())

        // The resume the permission dialog's closing causes, before the battery dialog is up.
        setup.onResumed()
        setup.onPaused()
        exemptFromBatteryOptimization()
        setup.onResumed()

        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), asked.last())
        setup.onDialogAnswered()
        assertTrue(setup.autoOn)
    }

    @Test fun `a lock screen under the blocked-step prompt is not the step's answer`() {
        grant(*location)
        exemptFromBatteryOptimization()
        Settings.markPermissionsAsked(app, listOf(Manifest.permission.ACTIVITY_RECOGNITION))
        val setup = setup()
        setup.toggleAuto(true)
        assertEquals(SetupPrompt.Blocked(SetupStep.ACTIVITY), setup.prompt)

        setup.onPaused()
        setup.onResumed()
        assertFalse(setup.autoOn)

        setup.answerPrompt()
        assertEquals(ACTION_APPLICATION_DETAILS_SETTINGS, lastOpened())
        setup.onPaused()
        grant(Manifest.permission.ACTIVITY_RECOGNITION)
        setup.onResumed()

        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), asked.last())
    }
}
