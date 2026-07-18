package io.github.valeronm.breadcrumb.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Raising and withdrawing the "automatic recording isn't responding" warning. */
class DeafnessWarningTest {

    private val warning = DeafnessWarning(liveMaxAgeMs = 60_000, replayWindowMs = 5_000)

    /** A prompt reading, well clear of any re-registration. */
    private fun live() = warning.onReading(readingAgeMs = 2_000, sinceRegistrationMs = 600_000)

    @Test fun `one detection does not warn`() {
        // The service restarts on the first detection; the episode often ends there.
        assertFalse(warning.onDeafDetected())
        assertFalse(warning.warned)
    }

    @Test fun `a second detection warns`() {
        warning.onDeafDetected()
        assertTrue(warning.onDeafDetected())
        assertTrue(warning.warned)
    }

    @Test fun `further detections do not warn again`() {
        warning.onDeafDetected()
        warning.onDeafDetected()
        assertFalse(warning.onDeafDetected())
        assertTrue(warning.warned)
    }

    @Test fun `a live delivery withdraws a standing warning`() {
        warning.onDeafDetected()
        warning.onDeafDetected()
        assertTrue(live())
        assertFalse(warning.warned)
    }

    @Test fun `a live delivery with no warning standing withdraws nothing`() {
        assertFalse(live())
    }

    @Test fun `recovering resets the count, so warning again takes two more detections`() {
        warning.onDeafDetected()
        live()
        assertFalse(warning.onDeafDetected())
        assertTrue(warning.onDeafDetected())
    }

    @Test fun `a replay just after a re-registration is not proof of live delivery`() {
        // The signature that makes deafness invisible: re-registration replays the current
        // activity, and a dead registration answers it exactly as a healthy one would.
        warning.onDeafDetected()
        warning.onDeafDetected()
        assertFalse(warning.onReading(readingAgeMs = 2_000, sinceRegistrationMs = 100))
        assertTrue(warning.warned)
    }

    @Test fun `a stale reading is not proof of live delivery`() {
        warning.onDeafDetected()
        warning.onDeafDetected()
        assertFalse(warning.onReading(readingAgeMs = 445_000, sinceRegistrationMs = 600_000))
        assertTrue(warning.warned)
    }

    @Test fun `reset clears both the count and a standing warning`() {
        warning.onDeafDetected()
        warning.onDeafDetected()
        warning.reset()
        assertFalse(warning.warned)
        assertFalse(warning.onDeafDetected())
    }
}
