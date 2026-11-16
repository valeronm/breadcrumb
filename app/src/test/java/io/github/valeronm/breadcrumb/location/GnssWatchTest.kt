package io.github.valeronm.breadcrumb.location

import android.Manifest
import android.app.Application
import android.location.GnssStatus
import android.location.LocationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class GnssWatchTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val locations = app.getSystemService(LocationManager::class.java)
    private var ticks = 0
    private val watch = GnssWatch(app) { ticks++ }

    @Before fun grantLocation() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun pass(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))

    private fun report() {
        shadowOf(locations).simulateGnssStatus(GnssStatus.Builder().build())
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun `a silent engine still ticks`() {
        watch.register()

        pass(60)

        assertTrue("ticks in a silent minute: $ticks", ticks >= 60 / 10)
    }

    @Test fun `an engine reporting every second ticks once per report`() {
        watch.register()

        repeat(60) {
            pass(1)
            report()
        }

        assertEquals(60, ticks)
    }

    @Test fun `a silence after reports is ticked through`() {
        watch.register()
        report()
        ticks = 0

        pass(60)

        assertTrue("ticks in a silent minute: $ticks", ticks >= 60 / 10)
    }

    @Test fun `nothing ticks once GPS stops`() {
        watch.register()
        watch.unregister()

        pass(60)

        assertEquals(0, ticks)
    }
}
