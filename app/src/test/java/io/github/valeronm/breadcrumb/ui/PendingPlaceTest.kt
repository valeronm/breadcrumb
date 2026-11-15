package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.ui.TrackListViewModel.PendingPlace
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingPlaceTest {

    private val home = GapFixtures.place(7, "Home")
    private val before = listOf(home.copy(label = "House"))
    private val carrying = listOf(home)

    private val written = PendingPlace("place:7", home, after = 4)

    @Test fun `a row is drawn over every reading that lacks it`() {
        assertFalse(written.settled)
        assertTrue(written.appliesTo(0, before))
        assertTrue(written.appliesTo(Long.MAX_VALUE, before))
    }

    @Test fun `a row is drawn over no reading that holds it, settled or not`() {
        assertFalse(written.appliesTo(4, carrying))
        assertFalse(written.appliesTo(5, carrying))
    }

    @Test fun `a row with no id yet is held by no reading`() {
        val created = PendingPlace("cluster:0,0", home.copy(id = 0), after = 4)
        assertTrue(created.appliesTo(5, carrying))
        assertNull(created.settledBy(5, carrying))
        assertFalse(created.identified(7).appliesTo(5, carrying))
    }

    @Test fun `a reading from before the write settles nothing, though it says the same`() {
        assertNull(written.settledBy(4, carrying))
    }

    @Test fun `a later reading that lacks the row settles nothing`() {
        assertNull(written.settledBy(5, before))
    }

    @Test fun `a settled row is drawn over older readings that lack it, and over none from the settling one on`() {
        val settled = written.settledBy(6, carrying)!!
        assertTrue(settled.settled)
        assertTrue(settled.appliesTo(5, before))
        assertFalse(settled.appliesTo(6, before))
        assertFalse(settled.appliesTo(7, before))
    }
}
