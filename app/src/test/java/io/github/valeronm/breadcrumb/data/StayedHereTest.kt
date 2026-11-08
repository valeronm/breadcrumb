package io.github.valeronm.breadcrumb.data

import androidx.test.core.app.ApplicationProvider
import io.github.valeronm.breadcrumb.domain.ActivityType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StayedHereTest {
    private val test = TestDb()
    private val places = PlaceRepository(ApplicationProvider.getApplicationContext(), test.db)
    private val now = TEST_START + 10_000_000L

    @After fun tearDown() = test.close()

    /** Two walks ~1.1 km apart, so their facing ends sit in different clusters and leave a gap. */
    private suspend fun twoTracksAroundAGap(): Pair<Long, Long> {
        val first = test.walk(TEST_START, 0, 5)
        val second = test.walk(TEST_START + 3_600_000L, 5, 10, lonOffset = 0.01)
        assertEquals("GAP", test.intervalTypeAfter(first))
        return first to second
    }

    @Test fun `linking both ends to a place turns the gap into a stay`() = runTest {
        val (first, second) = twoTracksAroundAGap()
        val placeId = places.create(test.place("A", 1.0, -2.0))

        places.stayedHere(first, second, placeId)

        assertEquals(placeId, test.dao.track(first)!!.endPlaceId)
        assertEquals(placeId, test.dao.track(second)!!.startPlaceId)
        assertEquals("STAY", test.intervalTypeAfter(first))
        DerivedConsistency.assertMatchesFreshDerive(test.db, now)
    }

    @Test fun `undo puts the previous links back`() = runTest {
        val (first, second) = twoTracksAroundAGap()
        val imported = places.create(test.place("I", 1.0, -1.99))
        val target = places.create(test.place("A", 1.0, -2.0))
        test.dao.stateEnds(listOf(first), imported)
        val done = places.stayedHere(first, second, target)!!

        places.undoStayedHere(done)

        assertEquals(imported, test.dao.track(first)!!.endPlaceId)
        assertNull(test.dao.track(second)!!.startPlaceId)
        assertEquals("GAP", test.intervalTypeAfter(first))
        DerivedConsistency.assertMatchesFreshDerive(test.db, now)
    }

    @Test fun `undo leaves an end unlinked when its earlier place was deleted since`() = runTest {
        val (first, second) = twoTracksAroundAGap()
        val imported = places.create(test.place("I", 1.0, -1.99))
        val target = places.create(test.place("A", 1.0, -2.0))
        test.dao.stateEnds(listOf(first), imported)
        val done = places.stayedHere(first, second, target)!!
        places.delete(test.db.placeDao().place(imported)!!)

        places.undoStayedHere(done)

        assertNull(test.dao.track(first)!!.endPlaceId)
        assertEquals("GAP", test.intervalTypeAfter(first))
        DerivedConsistency.assertMatchesFreshDerive(test.db, now)
    }

    @Test fun `a stay over ends already linked to that place writes nothing`() = runTest {
        val (first, second) = twoTracksAroundAGap()
        val placeId = places.create(test.place("A", 1.0, -2.0))
        places.stayedHere(first, second, placeId)

        assertNull(places.stayedHere(first, second, placeId))
    }

    @Test fun `a stay moves ends already linked to another place`() = runTest {
        val (first, second) = twoTracksAroundAGap()
        val linked = places.create(test.place("A", 1.0, -2.0))
        val other = places.create(test.place("B", 1.0, -1.99))
        places.stayedHere(first, second, linked)

        val done = places.stayedHere(first, second, other)!!

        assertEquals(linked, done.previousEnd)
        assertEquals(other, test.dao.track(first)!!.endPlaceId)
        assertEquals(other, test.dao.track(second)!!.startPlaceId)
        DerivedConsistency.assertMatchesFreshDerive(test.db, now)
    }

    @Test fun `a stay at a place that is gone writes nothing`() = runTest {
        val (first, second) = twoTracksAroundAGap()

        assertNull(places.stayedHere(first, second, placeId = 999))
        assertNull(test.dao.track(first)!!.endPlaceId)
        assertEquals("GAP", test.intervalTypeAfter(first))
    }

    @Test fun `with no track after, only the arrival is stated`() = runTest {
        val only = test.walk(TEST_START, 0, 5)
        val placeId = places.create(test.place("A", 1.0, -2.0))

        places.stayedHere(only, afterId = null, placeId)

        assertEquals(placeId, test.dao.track(only)!!.endPlaceId)
        DerivedConsistency.assertMatchesFreshDerive(test.db, now)
    }

    @Test fun `undo of an arrival-only stay puts the arrival's link back`() = runTest {
        val only = test.walk(TEST_START, 0, 5)
        val placeId = places.create(test.place("A", 1.0, -2.0))
        val done = places.stayedHere(only, afterId = null, placeId)!!

        places.undoStayedHere(done)

        assertNull(test.dao.track(only)!!.endPlaceId)
        DerivedConsistency.assertMatchesFreshDerive(test.db, now)
    }

    @Test fun `an arrival already linked to that place writes nothing`() = runTest {
        val only = test.walk(TEST_START, 0, 5)
        val placeId = places.create(test.place("A", 1.0, -2.0))
        places.stayedHere(only, afterId = null, placeId)

        assertNull(places.stayedHere(only, afterId = null, placeId))
    }

    @Test fun `a stay stated into the open recording keeps its start once the recording closes`() = runTest {
        val only = test.walk(TEST_START, 0, 5)
        val placeId = places.create(test.place("A", 1.0, -2.0))
        val openedAt = TEST_START + 3_600_000L
        val open = test.repository.startTrack(ActivityType.WALKING, openedAt)
        val back = (5 downTo 0).mapIndexed { step, i -> test.point(open, i).copy(timestamp = openedAt + step * 10_000L) }
        test.repository.addPoints(back.take(3))

        places.stayedHere(only, open, placeId)
        DerivedConsistency.assertMatchesFreshDerive(test.db, now)
        test.repository.addPoints(back.drop(3))
        test.repository.finishTrack(open, back.last().timestamp)

        assertEquals(placeId, test.dao.track(open)!!.startPlaceId)
        assertEquals(placeId, test.dao.track(only)!!.endPlaceId)
        assertEquals("STAY", test.intervalTypeAfter(only))
        DerivedConsistency.assertMatchesFreshDerive(test.db, now)
    }

    private suspend fun outAndBack(): Pair<Long, Long> {
        val out = test.walk(TEST_START, 0, 5)
        val back = test.walk(TEST_START + 3_600_000L, 5, 0)
        assertEquals("STAY", test.intervalTypeAfter(out))
        return out to back
    }

    @Test fun `a place made for a stay whose ends lie inside it is stated to the stay`() = runTest {
        val (out, back) = outAndBack()

        val created = places.createForStay(test.place("New", 1.005, -2.0), out, back)

        assertEquals(created, test.dao.track(out)!!.endPlaceId)
        assertEquals(created, test.dao.track(back)!!.startPlaceId)
        assertEquals("STAY", test.intervalTypeAfter(out))
        DerivedConsistency.assertMatchesFreshDerive(test.db, now)
    }

    @Test fun `a place made for an arrival-only stay is stated to the arrival alone`() = runTest {
        val only = test.walk(TEST_START, 0, 5)

        val created = places.createForStay(test.place("New", 1.005, -2.0), only, null)

        assertEquals(created, test.dao.track(only)!!.endPlaceId)
        DerivedConsistency.assertMatchesFreshDerive(test.db, now)
    }

    @Test fun `a place made for a stay with an end outside it is stated to the stay`() = runTest {
        val (first, second) = twoTracksAroundAGap()

        val created = places.createForStay(test.place("New", 1.005, -2.0), first, second)

        assertEquals(created, test.dao.track(first)!!.endPlaceId)
        assertEquals(created, test.dao.track(second)!!.startPlaceId)
        assertEquals("STAY", test.intervalTypeAfter(first))
        DerivedConsistency.assertMatchesFreshDerive(test.db, now)
    }

    @Test fun `a place made for a stay with an end stated elsewhere is stated to the stay`() = runTest {
        val (out, back) = outAndBack()
        val imported = places.create(test.place("I", 1.0, -2.0))
        test.dao.stateEnds(listOf(out), imported)

        val created = places.createForStay(test.place("New", 1.005, -2.0), out, back)

        assertEquals(created, test.dao.track(out)!!.endPlaceId)
        assertEquals(created, test.dao.track(back)!!.startPlaceId)
        DerivedConsistency.assertMatchesFreshDerive(test.db, now)
    }
}
