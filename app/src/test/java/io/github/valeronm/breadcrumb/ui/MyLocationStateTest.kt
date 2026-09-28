package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.domain.Coordinate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MyLocationStateTest {

    private val here = Coordinate(1.0, -2.0)

    private var granted = false
    private var blocked = false
    private var asks = 0
    private var fetches = 0

    private val state = MyLocationState(
        granted = { granted },
        blocked = { blocked },
        fetch = {
            fetches++
            here
        },
        launch = { block -> runBlocking { block() } },
        onUnavailable = {},
    ).apply { ask = { asks++ } }

    @Test fun `with location granted a tap goes to the phone's position`() {
        granted = true
        state.goThere()
        assertEquals(here, state.goTo?.at)
        assertEquals(0, asks)
    }

    @Test fun `without it a tap asks Android and fetches nothing`() {
        state.goThere()
        assertEquals(1, asks)
        assertEquals(0, fetches)
        assertNull(state.goTo)
    }

    @Test fun `a grant carries the tap through to the map`() {
        state.goThere()
        granted = true
        state.onAnswered()
        assertEquals(here, state.goTo?.at)
        assertEquals(1, asks)
    }

    @Test fun `a refusal leaves the map where it is`() {
        state.goThere()
        state.onAnswered()
        assertNull(state.goTo)
        assertEquals(1, asks)
    }

    @Test fun `once Android stops asking a tap offers the app's settings instead`() {
        blocked = true
        state.goThere()
        assertTrue(state.showingSettingsDialog)
        assertEquals(0, asks)
    }

    @Test fun `a granted location is never sent to settings`() {
        granted = true
        blocked = true
        state.goThere()
        assertFalse(state.showingSettingsDialog)
        assertEquals(1, fetches)
    }
}
