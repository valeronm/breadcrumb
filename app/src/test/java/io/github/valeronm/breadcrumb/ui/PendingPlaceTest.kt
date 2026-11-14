package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.ui.TrackListViewModel.PendingPlace
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingPlaceTest {

    private val written = PendingPlace("cluster:0,0", Place(label = "Home", lat = 0.0, lon = 0.0, createdAt = 0, radiusM = 50.0))

    @Test fun `a row no reading says yet is drawn over every reading`() {
        assertFalse(written.settled)
        assertTrue(written.appliesTo(0))
        assertTrue(written.appliesTo(Long.MAX_VALUE))
    }

    @Test fun `a settled row is drawn over readings older than the one that settled it`() {
        val settled = written.settledFrom(5)
        assertTrue(settled.settled)
        assertTrue(settled.appliesTo(4))
    }

    @Test fun `a settled row is drawn over no reading from the one that settled it on`() {
        val settled = written.settledFrom(5)
        assertFalse(settled.appliesTo(5))
        assertFalse(settled.appliesTo(6))
    }
}
