package io.github.valeronm.breadcrumb.ui

import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.domain.PlaceCategory
import io.github.valeronm.breadcrumb.domain.PlaceClusterer
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The gate in front of the stay derivation — the most expensive computation in the app, and the one
 * every place and timeline screen waits behind.
 *
 * The rule: **a write that only says what a place is called or what it is for must not re-cluster
 * anything.** Clustering reads a place's pin and its reach and nothing else, so a rename or a tag
 * cannot move a visit between places — but both are `places` writes, and a gate that watched the
 * row instead of the pin would re-derive the whole history for a field it never reads. That is
 * visible to the user as a category taking seconds to appear on a place's own screen.
 *
 * These drive [pinnedRows] itself rather than a copy of its logic, so re-widening the gate fails
 * here rather than only on a device with a long history.
 */
class PlaceDerivationGateTest {

    private fun place(
        id: Long = 1,
        label: String = "Kestrel Market",
        lat: Double = 1.0,
        lon: Double = -2.0,
        radiusM: Double = 150.0,
        category: PlaceCategory? = null,
    ) = Place(
        id = id,
        label = label,
        lat = lat,
        lon = lon,
        createdAt = 0L,
        radiusM = radiusM,
        category = category?.code,
    )

    /** How many times the derivation would run for this sequence of readings of the places table. */
    private suspend fun clusterings(vararg readings: List<Place>) =
        pinnedRows(flowOf(*readings)).toList().size

    @Test fun `tagging a place does not re-cluster`() = runTest {
        val untagged = listOf(place())
        val tagged = listOf(place(category = PlaceCategory.GROCERIES))
        assertEquals(1, clusterings(untagged, tagged))
    }

    @Test fun `retagging and untagging do not re-cluster`() = runTest {
        assertEquals(
            1,
            clusterings(
                listOf(place(category = PlaceCategory.GROCERIES)),
                listOf(place(category = PlaceCategory.SHOPPING)),
                listOf(place(category = null)),
            ),
        )
    }

    @Test fun `renaming a place does not re-cluster`() = runTest {
        assertEquals(
            1,
            clusterings(listOf(place(label = "Kestrel Market")), listOf(place(label = "Kestrel Market North"))),
        )
    }

    /** The two writes that share the dialog, together: neither is a reason to re-cluster. */
    @Test fun `a rename and a tag in succession do not re-cluster`() = runTest {
        assertEquals(
            1,
            clusterings(
                listOf(place()),
                listOf(place(label = "Halvard Fuel")),
                listOf(place(label = "Halvard Fuel", category = PlaceCategory.GAS_STATION)),
            ),
        )
    }

    @Test fun `moving a pin re-clusters`() = runTest {
        assertEquals(2, clusterings(listOf(place()), listOf(place(lat = 1.001))))
        assertEquals(2, clusterings(listOf(place()), listOf(place(lon = -2.001))))
    }

    @Test fun `changing a capture radius re-clusters`() = runTest {
        assertEquals(2, clusterings(listOf(place()), listOf(place(radiusM = 220.0))))
    }

    @Test fun `adding or deleting a place re-clusters`() = runTest {
        assertEquals(2, clusterings(listOf(place()), listOf(place(), place(id = 2, lat = 1.01))))
        assertEquals(2, clusterings(listOf(place(), place(id = 2, lat = 1.01)), listOf(place())))
    }

    /** A move buried among renames still gets through — suppression is per reading, not a latch. */
    @Test fun `a pin moved between renames still re-clusters`() = runTest {
        assertEquals(
            2,
            clusterings(
                listOf(place(label = "Kestrel Market")),
                listOf(place(label = "Kestrel Market North")),
                listOf(place(label = "Kestrel Market North", lat = 1.002)),
            ),
        )
    }

    @Test fun `the gate hands on the rows, not a projection of them`() = runTest {
        val rows = listOf(place(label = "Kestrel Market", category = PlaceCategory.GROCERIES))
        assertEquals(rows, pinnedRows(flowOf(rows)).toList().single())
    }

    /**
     * The gate rests on [PlaceClusterer.Seed] being a value. Were it compared by identity, a
     * freshly built projection would differ every time and the gate would silently admit
     * everything — which is what a hand-written field comparison in this layer existed to avoid.
     */
    @Test fun `seeds of the same pins are equal`() {
        assertEquals(PlaceClusterer.seedsOf(listOf(place())), PlaceClusterer.seedsOf(listOf(place())))
        assertNotEquals(
            PlaceClusterer.seedsOf(listOf(place())),
            PlaceClusterer.seedsOf(listOf(place(radiusM = 151.0))),
        )
    }

    /** Order comes from `createdAt, id`, which naming cannot disturb — so a reorder is a real change. */
    @Test fun `reordered pins are not the same pins`() = runTest {
        val a = place(id = 1, lat = 1.0)
        val b = place(id = 2, lat = 1.01)
        assertEquals(2, clusterings(listOf(a, b), listOf(b, a)))
    }
}
