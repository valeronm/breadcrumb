package io.github.valeronm.breadcrumb.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MapSelectionTargetTest {
    @Test
    fun `fully expanded drawer and unmeasured map skip camera correction`() {
        assertNull(mapSelectionTarget(Offset(100f, 100f), 1080, 1800, 1800))
        assertNull(mapSelectionTarget(Offset(100f, 100f), 1080, 1800, 1728))
        assertNull(mapSelectionTarget(Offset.Zero, 0, 0, 0))
        assertNull(mapSelectionTarget(Offset.Zero, 60, 500, 0))
    }

    @Test
    fun `selection is constrained to the visible map above a partial drawer`() {
        assertEquals(Offset(40f, 460f), mapSelectionTarget(Offset(-20f, 900f), 1000, 1000, 500))
        assertEquals(Offset(960f, 40f), mapSelectionTarget(Offset(1100f, -20f), 1000, 1000, 500))
        assertEquals(Offset(500f, 250f), mapSelectionTarget(Offset(500f, 250f), 1000, 1000, 500))
    }

    @Test
    fun `exactly enough space for the margins has a valid target`() {
        assertEquals(Offset(40f, 40f), mapSelectionTarget(Offset(200f, 200f), 80, 500, 420))
    }
}
