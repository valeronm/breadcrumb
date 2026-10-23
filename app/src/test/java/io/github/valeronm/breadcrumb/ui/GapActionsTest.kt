package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.domain.GapDiagnosis
import io.github.valeronm.breadcrumb.domain.TrackMerge
import io.github.valeronm.breadcrumb.ui.GapFixtures.detail
import io.github.valeronm.breadcrumb.ui.GapFixtures.place
import io.github.valeronm.breadcrumb.ui.GapFixtures.side
import io.github.valeronm.breadcrumb.ui.GapFixtures.verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.ZoneId

class GapActionsTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    // --- Stayed here -----------------------------------------------------------

    @Test
    fun `two sides on one place need no question`() {
        val from = side(place(id = 7, label = "Gym"), visits = 1)
        val to = side(place(id = 7, label = "Gym"), visits = 1)

        val choice = stayedHereChoice(listOf(from, to)) as StayedHereChoice.Direct

        assertSame(from, choice.side)
    }

    @Test
    fun `a lone named side takes the stay without a question`() {
        val from = side(place(id = 1, label = null), visits = 9)
        val to = side(place(id = 2, label = "Home"), visits = 1)

        val choice = stayedHereChoice(listOf(from, to)) as StayedHereChoice.Direct

        assertSame(to, choice.side)
    }

    @Test
    fun `with both sides named the busier one is preselected`() {
        val from = side(place(id = 1, label = "Work"), visits = 2)
        val to = side(place(id = 2, label = "Home"), visits = 5)

        val choice = stayedHereChoice(listOf(from, to)) as StayedHereChoice.Ask

        assertSame(to, choice.preselected)
    }

    // --- Dispatch --------------------------------------------------------------

    @Test
    fun `the missed start opens the form on its own draft`() {
        val action = gapAction(GapDiagnosis.Fix.MISSED_START, detail(zone)) as GapAction.AddTrip

        assertNull(action.draft.origin?.timeMs)
    }

    @Test
    fun `the whole gap opens the form on its own draft`() {
        val d = detail(zone)

        val action = gapAction(GapDiagnosis.Fix.ADD_TRIP, d) as GapAction.AddTrip

        assertEquals(d.gap.start, action.draft.origin?.timeMs)
    }

    @Test
    fun `a merge carries the verdict's plan`() {
        val plan = TrackMerge.Plan(earlierId = 1, laterId = 2)
        val d = detail(zone, verdict = verdict(GapDiagnosis.Fix.MERGE, GapDiagnosis.Reason.DROPOUT, merge = plan))

        assertEquals(GapAction.Merge(plan), gapAction(GapDiagnosis.Fix.MERGE, d))
    }
}
