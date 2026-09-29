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

    @Test fun `the initial keeper is the nearest named place no import made`() {
        val opened = place(10, 0.0, provider = "google")
        val far = place(11, 100.0, label = "Far")
        val near = place(12, 50.0, label = "Near")
        assertEquals(12L, initialKeeper(opened, listOf(far, near), flatDistance).id)
    }

    @Test fun `imported and unnamed places are never the initial keeper`() {
        val opened = place(10, 0.0, provider = "google")
        val imported = place(11, 50.0, label = "Imported", provider = "google")
        val unnamed = place(12, 40.0)
        assertEquals(10L, initialKeeper(opened, listOf(imported, unnamed), flatDistance).id)
    }

    @Test fun `an opened place of the user's own stays the keeper`() {
        val opened = place(10, 0.0, label = "Mine")
        val other = place(11, 30.0, label = "Also mine")
        assertEquals(10L, initialKeeper(opened, listOf(other), flatDistance).id)
    }
}
