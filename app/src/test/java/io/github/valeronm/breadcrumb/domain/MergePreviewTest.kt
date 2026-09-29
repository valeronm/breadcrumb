package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.domain.MergePreview.Lands
import org.junit.Assert.assertEquals
import org.junit.Test

class MergePreviewTest {

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

    private val keeper = place(1, 0.0, label = "Kept")
    private val absorbed = place(2, 250.0)

    private fun landings(
        members: List<MergePreview.Member>,
        places: List<Place>,
        absorbedIds: Set<Long> = setOf(absorbed.id),
    ) = MergePreview.dots(members, keeper.id, absorbedIds, places, flatDistance).map { it.lands to it.stated }

    @Test fun `an absorbed place's stated end goes to the keeper however far it lies`() {
        val member = MergePreview.Member(absorbed, listOf(at(390.0)), stated = listOf(at(390.0)))
        assertEquals(listOf(Lands.KEEPER to true), landings(listOf(member), listOf(keeper, absorbed)))
    }

    @Test fun `an absorbed place's measured end inside the keeper's circle goes to the keeper`() {
        val member = MergePreview.Member(absorbed, listOf(at(120.0)), stated = emptyList())
        assertEquals(listOf(Lands.KEEPER to false), landings(listOf(member), listOf(keeper, absorbed)))
    }

    @Test fun `a measured end goes to the nearest remaining circle covering it`() {
        val third = place(3, 260.0, label = "Third")
        val member = MergePreview.Member(absorbed, listOf(at(140.0)), stated = emptyList())
        assertEquals(listOf(Lands.OTHER_PLACE to false), landings(listOf(member), listOf(keeper, absorbed, third)))
    }

    @Test fun `a measured end only an absorbed circle covered lands at no place`() {
        val member = MergePreview.Member(absorbed, listOf(at(330.0)), stated = emptyList())
        assertEquals(listOf(Lands.NO_PLACE to false), landings(listOf(member), listOf(keeper, absorbed)))
    }

    @Test fun `places not absorbed keep their visits`() {
        val kept = MergePreview.Member(keeper, listOf(at(10.0), at(20.0)), stated = listOf(at(20.0)))
        val left = MergePreview.Member(absorbed, listOf(at(260.0)), stated = emptyList())
        assertEquals(
            listOf(Lands.KEEPER to false, Lands.KEEPER to true, Lands.OTHER_PLACE to false),
            landings(listOf(kept, left), listOf(keeper, absorbed), absorbedIds = emptySet()),
        )
    }

    @Test fun `stated ends are matched to endpoints by count at one coordinate`() {
        val member = MergePreview.Member(absorbed, listOf(at(330.0), at(330.0)), stated = listOf(at(330.0)))
        assertEquals(
            listOf(Lands.KEEPER to true, Lands.NO_PLACE to false),
            landings(listOf(member), listOf(keeper, absorbed)),
        )
    }

    @Test fun `the initial keeper is the nearest named place no import made`() {
        val opened = place(10, 0.0, provider = "google")
        val far = place(11, 100.0, label = "Far")
        val near = place(12, 50.0, label = "Near")
        assertEquals(12L, MergePreview.initialKeeper(opened, listOf(far, near), flatDistance).id)
    }

    @Test fun `imported and unnamed places are never the initial keeper`() {
        val opened = place(10, 0.0, provider = "google")
        val imported = place(11, 50.0, label = "Imported", provider = "google")
        val unnamed = place(12, 40.0)
        assertEquals(10L, MergePreview.initialKeeper(opened, listOf(imported, unnamed), flatDistance).id)
    }

    @Test fun `an opened place of the user's own stays the keeper`() {
        val opened = place(10, 0.0, label = "Mine")
        val other = place(11, 30.0, label = "Also mine")
        assertEquals(10L, MergePreview.initialKeeper(opened, listOf(other), flatDistance).id)
    }
}
