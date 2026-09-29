package io.github.valeronm.breadcrumb.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.data.db.PlaceIdentity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaceMergeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val target = TestDb()
    private val places = PlaceRepository(context, target.db)
    private val later = TEST_START + 86_400_000L

    @After fun tearDown() = target.close()

    private suspend fun row(label: String?, lat: Double): Place {
        val id = places.create(target.place(label ?: "x", lat, -2.0).copy(label = label))
        return target.db.placeDao().allPlaces().single { it.id == id }
    }

    private suspend fun rows() = target.db.placeDao().allPlaces()

    private suspend fun endPlaceOf(trackId: Long) = target.dao.track(trackId)!!.endPlaceId

    @Test fun `ends stated to an absorbed place move to the kept one, and the kept row is untouched`() = runTest {
        val keep = row("Home", 1.0)
        val copy = row(null, 1.0012)
        val trip = target.walk(TEST_START, 5, 0)
        target.dao.stateEnds(listOf(trip), copy.id)

        places.merge(keep, listOf(copy))

        assertEquals(keep.id, endPlaceOf(trip))
        assertEquals(listOf(keep), rows())
        DerivedConsistency.assertMatchesFreshDerive(target.db, later)
    }

    @Test fun `ends no place was stated for stay unstated, inside the kept circle or out of it`() = runTest {
        val keep = row("Home", 1.0)
        val copy = row(null, 1.0025)
        // Ends ~111 m from the kept pin (inside its 150 m circle) and ~333 m (outside it).
        val inside = target.walk(TEST_START, 4, 1)
        val outside = target.walk(TEST_START + 3_600_000L, 0, 3)

        places.merge(keep, listOf(copy))

        assertNull(endPlaceOf(inside))
        assertNull(endPlaceOf(outside))
        DerivedConsistency.assertMatchesFreshDerive(target.db, later)
    }

    @Test fun `several places fold in at once, one with nothing stated to it`() = runTest {
        val keep = row("Home", 1.0)
        val first = row(null, 1.0012)
        val second = row(null, 1.0020)
        val trip = target.walk(TEST_START, 5, 0)
        target.dao.stateStarts(listOf(trip), first.id)

        places.merge(keep, listOf(first, second))

        assertEquals(keep.id, target.dao.track(trip)!!.startPlaceId)
        assertEquals(listOf(keep.id), rows().map { it.id })
    }

    @Test fun `undo returns every row and every stated end`() = runTest {
        val keep = row("Home", 1.0)
        val copy = row(null, 1.0012)
        val out = target.walk(TEST_START, 5, 0)
        val back = target.walk(TEST_START + 3_600_000L, 0, 5)
        target.dao.stateEnds(listOf(out), copy.id)
        target.dao.stateStarts(listOf(back), copy.id)
        val before = rows()

        places.unmerge(places.merge(keep, listOf(copy)))

        assertEquals(before, rows())
        assertEquals(copy.id, endPlaceOf(out))
        assertEquals(copy.id, target.dao.track(back)!!.startPlaceId)
        DerivedConsistency.assertMatchesFreshDerive(target.db, later)
    }

    private suspend fun identities() = target.db.placeIdentityDao().all().toSet()

    @Test fun `an absorbed place's identities name the kept one, and undo hands them back`() = runTest {
        val keep = row("Home", 1.0)
        val copy = row(null, 1.0012)
        target.db.placeIdentityDao().upsert(listOf(PlaceIdentity("google", "ChIJ-1", copy.id)))

        val merge = places.merge(keep, listOf(copy))
        assertEquals(setOf(PlaceIdentity("google", "ChIJ-1", keep.id)), identities())

        places.unmerge(merge)
        assertEquals(setOf(PlaceIdentity("google", "ChIJ-1", copy.id)), identities())
    }

    @Test fun `deleting a place takes its identities, and undo brings them back`() = runTest {
        val place = row(null, 1.0)
        target.db.placeIdentityDao().upsert(listOf(PlaceIdentity("google", "ChIJ-1", place.id)))

        val removal = places.delete(place)
        assertTrue(identities().isEmpty())

        places.restore(removal)
        assertEquals(setOf(PlaceIdentity("google", "ChIJ-1", place.id)), identities())
    }

    @Test fun `a place gone by the time of the merge is skipped`() = runTest {
        val keep = row("Home", 1.0)
        val copy = row(null, 1.0012)
        places.delete(copy)

        val merge = places.merge(keep, listOf(copy))

        assertTrue(merge.removals.isEmpty())
        assertEquals(listOf(keep), rows())
    }
}
