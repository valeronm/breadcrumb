package io.github.valeronm.breadcrumb.data

import io.github.valeronm.breadcrumb.domain.HiddenStay
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HideStayTest {
    private val test = TestDb()
    private val tracks = test.repository
    private val now = TEST_START + 100_000_000L

    @After fun tearDown() = test.close()

    /** Walks of 100 s, starting 20 minutes apart, each where the one before ended. */
    private suspend fun walksAroundStays(count: Int): List<Long> = (0 until count).map { i ->
        test.walk(TEST_START + i * 1_200_000L, i * 10, i * 10 + 10)
    }

    private suspend fun hide(beforeId: Long, afterId: Long): Boolean = tracks.hideStays(listOf(HiddenStay(beforeId, afterId))).isNotEmpty()

    @Test fun `hiding a stay leaves nothing between its tracks`() = runTest {
        val (first, second) = walksAroundStays(2)
        assertEquals("STAY", test.intervalTypeAfter(first))

        assertTrue(hide(first, second))

        assertNull(test.intervalTypeAfter(first))
        DerivedConsistency.assertMatchesFreshDerive(test.db, now)
    }

    @Test fun `unhiding brings the stay back`() = runTest {
        val (first, second) = walksAroundStays(2)
        hide(first, second)

        tracks.unhideStays(listOf(HiddenStay(first, second)))

        assertEquals("STAY", test.intervalTypeAfter(first))
        DerivedConsistency.assertMatchesFreshDerive(test.db, now)
    }

    @Test fun `tracks with another between them hide nothing`() = runTest {
        val (first, _, third) = walksAroundStays(3)

        assertFalse(hide(first, third))

        assertFalse(test.dao.track(first)!!.hidesStayAfter)
        assertFalse(test.dao.track(third)!!.hidesStayBefore)
    }

    @Test fun `a hide does not pass to the next neighbor, and comes back with its track`() = runTest {
        val (first, second, _) = walksAroundStays(3)
        hide(first, second)

        tracks.deleteTrack(second)
        assertEquals("GAP", test.intervalTypeAfter(first))
        DerivedConsistency.assertInternallyConsistent(test.db, now)

        tracks.restoreTrack(second)
        assertNull(test.intervalTypeAfter(first))
        DerivedConsistency.assertInternallyConsistent(test.db, now)
    }

    @Test fun `a merge keeps the hide on the end it carries`() = runTest {
        val (first, second, third) = walksAroundStays(3)
        hide(first, second)

        val merged = tracks.mergeTracks(second, third)!!

        assertTrue(test.dao.track(merged)!!.hidesStayBefore)
        assertNull(test.intervalTypeAfter(first))
        DerivedConsistency.assertMatchesFreshDerive(test.db, now)
    }

    @Test fun `a split keeps the hide on the half holding that end, and an unsplit takes it back`() = runTest {
        val (first, second) = walksAroundStays(2)
        hide(first, second)

        val split = tracks.splitTrack(first, atTs = TEST_START + 50_000L)!!

        assertFalse(test.dao.track(first)!!.hidesStayAfter)
        assertTrue(test.dao.track(split.secondId)!!.hidesStayAfter)
        assertNull(test.intervalTypeAfter(split.secondId))
        DerivedConsistency.assertMatchesFreshDerive(test.db, now)

        tracks.unsplitTracks(first, split)

        assertTrue(test.dao.track(first)!!.hidesStayAfter)
        assertNull(test.intervalTypeAfter(first))
        DerivedConsistency.assertMatchesFreshDerive(test.db, now)
    }

    /** Walks timed as [walksAroundStays] times them: into a spot, on to two more 1.1 km apart, then back into the first and out. */
    private suspend fun aroundTheStart(): List<Long> = listOf(10 to 0, 0 to 10, 10 to 20, 20 to 0, 0 to 10)
        .mapIndexed { i, (from, to) -> test.walk(TEST_START + i * 1_200_000L, from, to) }

    @Test fun `hiding several stays at one spot leaves the stays between them`() = runTest {
        val walks = aroundTheStart()
        val atStart = listOf(HiddenStay(walks[0], walks[1]), HiddenStay(walks[3], walks[4]))

        assertEquals(atStart, tracks.hideStays(atStart))

        assertNull(test.intervalTypeAfter(walks[0]))
        assertNull(test.intervalTypeAfter(walks[3]))
        assertEquals("STAY", test.intervalTypeAfter(walks[1]))
        assertEquals("STAY", test.intervalTypeAfter(walks[2]))
        DerivedConsistency.assertMatchesFreshDerive(test.db, now)
    }

    @Test fun `unhiding several stays brings each back`() = runTest {
        val walks = aroundTheStart()
        val atStart = listOf(HiddenStay(walks[0], walks[1]), HiddenStay(walks[3], walks[4]))
        tracks.hideStays(atStart)

        tracks.unhideStays(atStart)

        assertEquals("STAY", test.intervalTypeAfter(walks[0]))
        assertEquals("STAY", test.intervalTypeAfter(walks[3]))
        DerivedConsistency.assertMatchesFreshDerive(test.db, now)
    }

    @Test fun `a stay whose tracks are no longer neighbors is left out of the hide, and the rest go`() = runTest {
        val (first, second, third) = walksAroundStays(3)

        assertEquals(listOf(HiddenStay(second, third)), tracks.hideStays(listOf(HiddenStay(first, third), HiddenStay(second, third))))

        assertFalse(test.dao.track(first)!!.hidesStayAfter)
        assertEquals("STAY", test.intervalTypeAfter(first))
        assertNull(test.intervalTypeAfter(second))
        DerivedConsistency.assertMatchesFreshDerive(test.db, now)
    }
}
