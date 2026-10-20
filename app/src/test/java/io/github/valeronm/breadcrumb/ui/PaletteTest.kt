package io.github.valeronm.breadcrumb.ui

import androidx.compose.ui.graphics.Color
import io.github.valeronm.breadcrumb.data.Settings.TimelineColorPalette
import io.github.valeronm.breadcrumb.domain.ActivityType
import org.junit.Assert.assertEquals
import org.junit.Test

class PaletteTest {
    @Test fun `arc palette groups supported activities and preserves classic fallback`() {
        val fallback = Color(0xFF123456)

        assertEquals(
            Color(0xFF4CAF50),
            timelineActivityColorOr(ActivityType.WALKING, TimelineColorPalette.ARC_TIMELINE, fallback),
        )
        assertEquals(
            Color(0xFF9C27B0),
            timelineActivityColorOr(ActivityType.TRANSIT, TimelineColorPalette.ARC_TIMELINE, fallback),
        )
        assertEquals(
            fallback,
            timelineActivityColorOr(null, TimelineColorPalette.ARC_TIMELINE, fallback),
        )
        assertEquals(
            activityColorOr(ActivityType.WALKING, fallback),
            timelineActivityColorOr(ActivityType.WALKING, TimelineColorPalette.CLASSIC, fallback),
        )
    }
}
