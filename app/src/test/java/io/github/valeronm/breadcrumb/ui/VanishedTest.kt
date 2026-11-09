package io.github.valeronm.breadcrumb.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VanishedTest {

    @get:Rule
    val compose = createComposeRule()

    private var subject by mutableStateOf<Long?>(1L)
    private var present by mutableStateOf(false)
    private var vanished = false

    private fun watch() {
        compose.setContent { vanished = rememberVanished(subject, present) }
        compose.waitForIdle()
    }

    @Test fun `a subject never seen has not vanished`() {
        watch()

        assertFalse(vanished)
    }

    @Test fun `a subject seen and then missing has vanished`() {
        present = true
        watch()
        present = false
        compose.waitForIdle()

        assertTrue(vanished)
    }

    @Test fun `a new subject has to be seen before it can vanish`() {
        present = true
        watch()
        subject = 2L
        present = false
        compose.waitForIdle()

        assertFalse(vanished)
    }
}
