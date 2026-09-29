package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.Place
import org.junit.Assert.assertEquals
import org.junit.Test

class MergeKeeperTest {

    private fun place(id: Long, meters: Double, label: String? = null, provider: String? = null) = Place(
        id = id,
        label = label,
        lat = ORIGIN_LAT,
        lon = lonAt(meters),
        createdAt = 0,
        radiusM = 150.0,
        externalProvider = provider,
        externalId = provider?.let { "x$id" },
    )

    @Test fun `the initial keeper is the nearest named place`() {
        val opened = place(10, 0.0, provider = "google")
        val far = place(11, 100.0, label = "Far")
        val near = place(12, 50.0, label = "Near")
        assertEquals(12L, initialKeeper(opened, listOf(far, near), flatDistance).id)
    }

    @Test fun `an unnamed place is never the initial keeper`() {
        val opened = place(10, 0.0, provider = "google")
        val unnamed = place(12, 40.0)
        assertEquals(10L, initialKeeper(opened, listOf(unnamed), flatDistance).id)
    }

    @Test fun `a named place an import made can be the initial keeper`() {
        val opened = place(10, 0.0, provider = "google")
        val named = place(11, 50.0, label = "Named", provider = "google")
        assertEquals(11L, initialKeeper(opened, listOf(named), flatDistance).id)
    }

    @Test fun `an opened place of the user's own stays the keeper`() {
        val opened = place(10, 0.0, label = "Mine")
        val other = place(11, 30.0, label = "Also mine")
        assertEquals(10L, initialKeeper(opened, listOf(other), flatDistance).id)
    }
}
