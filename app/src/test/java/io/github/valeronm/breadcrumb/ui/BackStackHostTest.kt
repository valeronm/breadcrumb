package io.github.valeronm.breadcrumb.ui

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackStackHostTest {

    private val host = BackStackHost<String>()

    @Test fun `the tabs are uncovered only while nothing is open`() {
        assertTrue(host.uncovered)
        val place = host.push("place")
        assertFalse(host.uncovered)
        host.close(place)
        assertTrue(host.uncovered)
    }

    @Test fun `a page is blurred by the strongest page drawn over it`() = runTest {
        host.push("journey")
        val place = host.push("place")
        val editor = host.push("editor")
        host.motionOf(place).presence.snapTo(1f)
        host.motionOf(editor).presence.snapTo(1f)
        host.motionOf(editor).backProgress.snapTo(1f)

        assertEquals(12f, host.blurDp(0), 0.001f)
        assertEquals(12f * 0.3f, host.blurDp(1), 0.001f)
        assertEquals(0f, host.blurDp(2), 0.001f)
    }

    @Test fun `the tabs are covered by any drawn page, a closing one included`() = runTest {
        val place = host.push("place")
        host.motionOf(place).presence.snapTo(0.4f)
        host.close(place)

        assertEquals(0.4f, host.tabsCovered, 0.001f)
    }

    @Test fun `a finished close forgets the entry's motion`() = runTest {
        val place = host.push("place")
        host.motionOf(place).presence.snapTo(1f)
        host.close(place)
        host.closed(place)

        assertEquals(0f, host.motionOf(place).presence.value, 0.001f)
    }

    @Test fun `a covered page dismissing itself forgets its motion`() = runTest {
        val gap = host.push("gap")
        host.push("place")
        host.motionOf(gap).presence.snapTo(1f)
        host.dismiss(gap)

        assertEquals(listOf("place"), host.stack.entries.map { it.destination })
        assertEquals(0f, host.motionOf(gap).presence.value, 0.001f)
    }

    @Test fun `only the first close of a page reports closing it`() {
        val place = host.push("place")

        assertTrue(host.close(place))
        assertFalse(host.close(place))
        assertFalse(host.dismiss(place))
    }

    @Test fun `a page dismissing itself reports leaving`() {
        val gap = host.push("gap")
        host.push("place")

        assertTrue(host.dismiss(gap))
        assertFalse(host.dismiss(gap))
    }

    @Test fun `closing onto a page the stack does not hold opens nothing`() {
        assertNull(host.closeOnto(99, "place"))
        assertTrue(host.stack.entries.isEmpty())
    }
}
