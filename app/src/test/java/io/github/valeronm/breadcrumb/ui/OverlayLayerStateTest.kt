package io.github.valeronm.breadcrumb.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayLayerStateTest {

    private val floor = OverlayLayerState<Unit>()
    private val empty = OverlayLayerState<Unit>(floor)
    private val page = OverlayLayerState<Unit>(empty)

    @Test fun `a page open above an empty layer covers the floor`() {
        page.requested = true

        assertFalse(floor.onTop)
    }

    @Test fun `the floor is on top while nothing above it is open`() {
        assertTrue(floor.onTop)
    }
}
