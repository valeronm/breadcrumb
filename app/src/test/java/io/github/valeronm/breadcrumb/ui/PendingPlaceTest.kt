package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.ui.TrackListViewModel.PendingPlace
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingPlaceTest {

    private val written = PendingPlace("cluster:0,0", GapFixtures.place(0, "Home"))

    @Test fun `a row no reading says yet is drawn over every reading`() {
        assertFalse(written.settled)
        assertTrue(written.appliesTo(0))
        assertTrue(written.appliesTo(Long.MAX_VALUE))
    }

    @Test fun `a settled row is drawn over readings older than the one that settled it`() {
        val settled = written.settledAt(5)
        assertTrue(settled.settled)
        assertTrue(settled.appliesTo(4))
    }

    @Test fun `a settled row is drawn over no reading from the one that settled it on`() {
        val settled = written.settledAt(5)
        assertFalse(settled.appliesTo(5))
        assertFalse(settled.appliesTo(6))
    }
}
