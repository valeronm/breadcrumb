package io.github.valeronm.breadcrumb.ui.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupStateTest {

    @Test fun `recording is ready with notifications refused`() {
        assertTrue(GRANTED.copy(notificationsOk = false).complete)
    }

    @Test fun `a run asks for notifications after everything recording needs`() {
        val asked = mutableListOf<SetupStep>()
        val ladder = SetupLadder()
        var state = NOTHING
        ladder.start(state) { asked += it }
        while (ladder.current != null) {
            val step = ladder.current!!
            state = state.granting(step)
            ladder.answer(step, state) { asked += it }
        }

        assertEquals(
            listOf(
                SetupStep.LOCATION,
                SetupStep.LOCATION,
                SetupStep.ACTIVITY,
                SetupStep.BATTERY,
                SetupStep.NOTIFICATIONS,
            ),
            asked,
        )
    }

    @Test fun `a refused notification ask ends the run`() {
        val ladder = SetupLadder()
        val state = GRANTED.copy(notificationsOk = false)
        ladder.start(state) {}
        assertEquals(SetupStep.NOTIFICATIONS, ladder.current)

        ladder.onDialogAnswered(state) {}

        assertNull(ladder.current)
    }

    @Test fun `every step the recorder arms without is asked through a dialog`() {
        assertEquals(emptyList<SetupStep>(), SetupStep.entries.filter { !it.required && !it.asksThroughDialog })
    }

    @Test fun `a run does not ask for notifications a second time`() {
        val refused = GRANTED.copy(
            batteryOk = false,
            notificationsOk = false,
            offered = setOf(SetupStep.NOTIFICATIONS),
        )

        assertEquals(listOf(SetupStep.BATTERY), refused.pending)
    }

    @Test fun `the card lists only what the recorder cannot arm without`() {
        assertEquals(listOf(SetupStep.LOCATION, SetupStep.ACTIVITY, SetupStep.BATTERY), NOTHING.owed)
    }

    private fun SetupLadder.answer(step: SetupStep, state: SetupState, ask: (SetupStep) -> Unit) =
        if (step == SetupStep.BATTERY) {
            onOutsideScreenOpened()
            onPaused()
            onResumed(state, ask)
        } else {
            onDialogAnswered(state, ask)
        }

    private fun SetupState.granting(step: SetupStep): SetupState = when (step) {
        SetupStep.LOCATION -> if (locationOk) copy(backgroundOk = true) else copy(locationOk = true)
        SetupStep.ACTIVITY -> copy(activityOk = true)
        SetupStep.NOTIFICATIONS -> copy(notificationsOk = true)
        SetupStep.BATTERY -> copy(batteryOk = true)
    }

    private companion object {
        val GRANTED = SetupState(
            locationOk = true,
            backgroundOk = true,
            activityOk = true,
            notificationsOk = true,
            batteryOk = true,
        )
        val NOTHING = SetupState(
            locationOk = false,
            backgroundOk = false,
            activityOk = false,
            notificationsOk = false,
            batteryOk = false,
        )
    }
}
