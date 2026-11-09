package io.github.valeronm.breadcrumb.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StackFrameTest {

    @get:Rule
    val compose = createComposeRule()

    private fun framed(underlayTaps: () -> Unit, content: @Composable BoxScope.() -> Unit): BackStackHost<String> {
        val host = BackStackHost<String>()
        host.push("page")
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().clickable { underlayTaps() })
                StackPages(host) { content() }
            }
        }
        compose.waitForIdle()
        return host
    }

    @Test
    fun `a page that draws nothing yet takes the touches meant for what is beneath it`() {
        var taps = 0
        framed({ taps++ }) {}

        compose.onRoot().performTouchInput { click(center) }

        assertEquals(0, taps)
    }

    @Test
    fun `a page's own content still receives its touches`() {
        var underlay = 0
        var button = 0
        framed({ underlay++ }) { Button(onClick = { button++ }) { Text("Save") } }

        compose.onNodeWithText("Save").performClick()

        assertEquals(1, button)
        assertEquals(0, underlay)
    }

    @Test
    fun `a closing page lets touches through to what it uncovers`() {
        var taps = 0
        val host = framed({ taps++ }) {}
        compose.mainClock.autoAdvance = false
        host.close(host.stack.top!!.id)
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()

        compose.onRoot().performTouchInput { click(center) }

        assertEquals(1, taps)
    }

    @Test
    fun `only the top page is read out`() {
        val host = BackStackHost<String>()
        host.push("Beneath")
        host.push("Top")
        compose.setContent { StackPages(host) { Text(it.destination) } }
        compose.waitForIdle()

        compose.onNodeWithText("Top").assertExists()
        compose.onNodeWithText("Beneath").assertDoesNotExist()
    }

    @Test
    fun `a page uncovered is read out again`() {
        val host = BackStackHost<String>()
        host.push("Beneath")
        val top = host.push("Top")
        compose.setContent { StackPages(host) { Text(it.destination) } }
        compose.waitForIdle()

        host.close(top)
        compose.waitForIdle()

        compose.onNodeWithText("Beneath").assertExists()
    }
}
