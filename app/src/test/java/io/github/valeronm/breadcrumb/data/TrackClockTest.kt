package io.github.valeronm.breadcrumb.data

import io.github.valeronm.breadcrumb.domain.ActivityType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * That a stored row's clock reaches the database — the wiring `TrackBounds` hangs off, which its own
 * suite cannot see. Each case covers a distinct write: the end at finish, the start at finish (a
 * different DAO call), and the sweep. What the rule *is* belongs in `TrackBoundsTest`, where it is
 * total on a plain JVM; nothing here should restate it.
 *
 * The fixture is the plain northbound line ([TestDb.point]): steady movement with no dwell at either
 * edge, so the overrun detector finds nothing and every bound here is this rule's doing alone.
 */
@RunWith(RobolectricTestRunner::class)
class TrackClockTest {

    private val test = TestDb()
    private val repository get() = test.repository
    private val dao get() = test.dao

    @After fun tearDown() = test.close()

    @Test fun `finishing writes the end the rule derived, not the one it was handed`() = runTest {
        val id = repository.startTrack(ActivityType.WALKING, TEST_START)
        repository.addPoints((0..59).map { test.point(id, it) })

        repository.finishTrack(id, TEST_START + 59 * 10_000L + 6 * 60_000L)

        val track = dao.track(id)!!
        assertNull("a real journey must not be discarded", track.discardedAt)
        assertEquals("nothing came off the path", 0, track.ignoredCount)
        test.assertStatsMatchPoints(id)
    }

    @Test fun `finishing writes the start too, through its own DAO call`() = runTest {
        val id = repository.startTrack(ActivityType.WALKING, TEST_START)
        repository.addPoints((30..89).map { test.point(id, it) })

        repository.finishTrack(id, TEST_START + 89 * 10_000L)

        assertEquals(TEST_START + 30 * 10_000L, dao.track(id)!!.startedAt)
        test.assertStatsMatchPoints(id)
    }

    @Test fun `the sweep reports a bound it moved on its own`() = runTest {
        val id = test.walk(TEST_START, 0, 59)
        val lastFix = TEST_START + 59 * 10_000L
        // A row as a build without this rule left it: closed where the transition landed. The only
        // case where the sweep moves a bound and no flag — so it is also the only one asking whether
        // a bound alone counts as "wrote", which is what the derivation's rebuild hangs on.
        dao.closeTrack(id, lastFix + 6 * 60_000L)

        assertTrue(repository.sweepEdgeStays())

        assertEquals(lastFix, dao.track(id)!!.endedAt)
    }
}
