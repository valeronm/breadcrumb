package io.github.valeronm.breadcrumb.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The rule deciding when an unlocked session survives a trip out of the app.
 *
 * The grace period is not a comfort setting — it is what keeps the lock usable at all. The app
 * sends itself to the background through its own flows (the document and folder pickers, the
 * permission deep-link into system settings, the maps-app action from a place), and a lock that
 * re-prompts on the way back from each of those is one the user turns off. So the boundary
 * semantics are load-bearing and pinned here: the clock runs from the moment the app was left,
 * a grace of zero locks on any return, and never having left is not a return at all.
 *
 * [Privacy] is a process-scoped object, so each case establishes its own starting state rather
 * than inheriting one — which is also why [Privacy.markUnlocked] clears the away stamp: a fresh
 * session must not carry the previous one's bookkeeping.
 *
 * The keyguard cases below are the second rule over the same stamp: a phone unlocked *since* the app
 * was left can stand in for the app's own prompt, where the user has said it may. What pins the
 * setting as safe is the pair — a dismissal after the departure counts, one before it does not,
 * because a phone handed over already unlocked dismissed nothing.
 */
class AppLockGraceTest {

    private val left = 1_000L

    @Before
    fun unlock() = Privacy.markUnlocked()

    @Test
    fun `a return inside the grace keeps the session`() {
        Privacy.onStopped(left)
        Privacy.onStarted(left + 29_999, graceSec = 30, trustsKeyguard = false)
        assertTrue(Privacy.unlocked)
    }

    @Test
    fun `a return once the grace is spent re-locks`() {
        Privacy.onStopped(left)
        // Exactly at the deadline, not past it: the window is closed at its edge, so the setting
        // reads as "30 seconds of grace" rather than "30 seconds and a bit".
        Privacy.onStarted(left + 30_000, graceSec = 30, trustsKeyguard = false)
        assertFalse(Privacy.unlocked)
    }

    @Test
    fun `a grace of zero locks on any return at all`() {
        Privacy.onStopped(left)
        Privacy.onStarted(left, graceSec = 0, trustsKeyguard = false)
        assertFalse(Privacy.unlocked)
    }

    @Test
    fun `starting without having left leaves the session alone`() {
        // The cold-start and configuration-change path: an observer added to a started lifecycle
        // is handed ON_START immediately, and that must not be mistaken for a return.
        Privacy.onStarted(left + 999_999, graceSec = 0, trustsKeyguard = false)
        assertTrue(Privacy.unlocked)
    }

    @Test
    fun `the away stamp is spent on the first return`() {
        Privacy.onStopped(left)
        Privacy.onStarted(left + 1, graceSec = 30, trustsKeyguard = false)
        // A second ON_START with no intervening ON_STOP: the app never left again, so however
        // long has passed since, there is nothing to measure.
        Privacy.onStarted(left + 999_999, graceSec = 30, trustsKeyguard = false)
        assertTrue(Privacy.unlocked)
    }

    @Test
    fun `a keyguard passed since leaving stands in for the app's own prompt`() {
        Privacy.onStopped(left)
        Privacy.onKeyguardPassed(left + 500_000)

        Privacy.onStarted(left + 999_999, graceSec = 0, trustsKeyguard = true)

        assertTrue(Privacy.unlocked)
    }

    @Test
    fun `the same unlock counts for nothing while the setting is off`() {
        Privacy.onStopped(left)
        Privacy.onKeyguardPassed(left + 500_000)

        Privacy.onStarted(left + 999_999, graceSec = 0, trustsKeyguard = false)

        assertFalse(Privacy.unlocked)
    }

    @Test
    fun `an unlock from before the app was left is not one the return can use`() {
        // The phone handed over already unlocked, which is what the whole lock is for: whoever took
        // it dismissed no keyguard, so the setting must not open the history to them.
        Privacy.onKeyguardPassed(left - 1)
        Privacy.onStopped(left)

        Privacy.onStarted(left + 999_999, graceSec = 0, trustsKeyguard = true)

        assertFalse(Privacy.unlocked)
    }

    @Test
    fun `a phone that never locked leaves the grace period to decide`() {
        // Switching to another app and back on a phone that stayed awake throughout: no keyguard has
        // been through at all, so there is nothing for the setting to trust either way.
        Privacy.onStopped(left)

        Privacy.onStarted(left + 999_999, graceSec = 30, trustsKeyguard = true)

        assertFalse(Privacy.unlocked)
    }

    @Test
    fun `backgrounding while locked stamps nothing to come back to`() {
        Privacy.onStopped(left)
        Privacy.onStarted(left + 999_999, graceSec = 0, trustsKeyguard = false)
        assertFalse(Privacy.unlocked)

        // Still locked, so leaving and returning inside the grace must not resurrect the session.
        Privacy.onStopped(left + 1_000_000)
        Privacy.onStarted(left + 1_000_001, graceSec = 300, trustsKeyguard = false)
        assertFalse(Privacy.unlocked)
    }
}
