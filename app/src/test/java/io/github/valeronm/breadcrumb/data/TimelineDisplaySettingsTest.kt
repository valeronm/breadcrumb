package io.github.valeronm.breadcrumb.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.valeronm.breadcrumb.data.Settings.TimelineColorPalette
import io.github.valeronm.breadcrumb.data.Settings.TimelineDisplayOptions
import io.github.valeronm.breadcrumb.data.Settings.TimelineLocationColorMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TimelineDisplaySettingsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun clearSettings() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `timeline display defaults show visit times and summaries`() {
        assertEquals(TimelineDisplayOptions(), Settings.timelineDisplayOptions(context))
        assertEquals(true, Settings.timelineDisplayOptions(context).showPauseIcon)
        assertEquals(TimelineColorPalette.CLASSIC, Settings.timelineColorPalette(context))
    }

    @Test
    fun `timeline display choices persist`() {
        val options = TimelineDisplayOptions(
            showVisitTime = false,
            showDaySummary = false,
            showPauseIcon = false,
            locationColorMode = TimelineLocationColorMode.LOCATION_COLORS,
            modernTimelineLayout = false,
        )
        Settings.setTimelineDisplayOptions(context, options)
        Settings.setTimelineColorPalette(context, TimelineColorPalette.ARC_TIMELINE)

        assertEquals(options, Settings.timelineDisplayOptions(context))
        assertEquals(TimelineColorPalette.ARC_TIMELINE, Settings.timelineColorPalette(context))
    }
}
