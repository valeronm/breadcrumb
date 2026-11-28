package io.github.valeronm.breadcrumb.domain.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiLinksTest {

    private val links = WifiLinks()

    @Test fun `losing the only connection leaves none joined`() {
        links.onJoined("100")
        assertTrue(links.onLost("100").offWifi)
    }

    @Test fun `a loss with another connection joined is a switch`() {
        links.onJoined("100")
        links.onJoined("101")
        val lost = links.onLost("100")
        assertFalse(lost.offWifi)
        assertEquals(listOf("101"), lost.stillJoined)
    }

    @Test fun `the connection switched to is lost like any other`() {
        links.onJoined("100")
        links.onJoined("101")
        links.onLost("100")
        assertTrue(links.onLost("101").offWifi)
    }

    @Test fun `a loss of a connection never seen joining leaves the others standing`() {
        links.onJoined("101")
        assertFalse(links.onLost("100").offWifi)
    }

    @Test fun `a loss with nothing joined leaves none joined`() {
        assertTrue(links.onLost("100").offWifi)
    }

    @Test fun `a frequency is news once`() {
        links.onJoined("100")
        assertTrue(links.frequencyChanged("100", 2437))
        assertFalse(links.frequencyChanged("100", 2437))
    }

    @Test fun `a changed frequency is news`() {
        links.onJoined("100")
        links.frequencyChanged("100", 2437)
        assertTrue(links.frequencyChanged("100", 5180))
    }

    @Test fun `a loss carries the frequency last heard`() {
        links.onJoined("100")
        links.frequencyChanged("100", 2437)
        links.frequencyChanged("100", 5180)
        assertEquals(5180, links.onLost("100").frequencyMhz)
    }

    @Test fun `a loss before any frequency carries none`() {
        links.onJoined("100")
        assertNull(links.onLost("100").frequencyMhz)
    }

    @Test fun `a connection heard on a frequency counts as joined`() {
        assertTrue(links.frequencyChanged("100", 5180))
        assertEquals(listOf("100"), links.onLost("101").stillJoined)
    }
}
