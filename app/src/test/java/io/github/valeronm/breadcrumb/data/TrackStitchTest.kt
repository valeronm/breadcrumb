package io.github.valeronm.breadcrumb.data

import io.github.valeronm.breadcrumb.data.db.Track
import io.github.valeronm.breadcrumb.domain.ActivityType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the recorder gets back when movement resumes after a stop. The rule itself is
 * `StitchRuleTest`'s, on a plain JVM; this asks only what the writes did — that a continued track is
 * the same row with the same fixes, that a track filed by the keep thresholds comes back off
 * Recently deleted when it does, and that the mutations a reader can reach on the timeline refuse a
 * row the recorder is filling.
 */
@RunWith(RobolectricTestRunner::class)
class TrackStitchTest {

    private val test = TestDb()
    private val repository get() = test.repository
    private val dao get() = test.dao

    private val window = 180_000L

    @After fun tearDown() = test.close()

    @Test fun `a stitched track is one row, holding both stretches' fixes`() = runTest {
        val first = repository.openOrStitch(ActivityType.WALKING, TEST_START, window)
        repository.addPoints((0..5).map { test.point(first.trackId, it) })
        repository.finishTrack(first.trackId, TEST_START + 5 * 10_000L)

        val resumedAt = TEST_START + 5 * 10_000L + 60_000L
        val second = repository.openOrStitch(ActivityType.WALKING, resumedAt, window)

        assertTrue("the stop was inside the window", second.stitched)
        assertEquals(first.trackId, second.trackId)
        assertNull("and it is open again", dao.track(second.trackId)!!.endedAt)

        repository.addPoints((6..11).map { test.point(second.trackId, it) })
        repository.finishTrack(second.trackId, TEST_START + 11 * 10_000L)

        assertEquals(12, dao.track(second.trackId)!!.pointCount)
        assertEquals(1, dao.allTrackIds().size)
        test.assertStatsMatchPoints(second.trackId)
    }

    @Test fun `a return past the window lands on a row of its own`() = runTest {
        val first = repository.openOrStitch(ActivityType.WALKING, TEST_START, window)
        repository.addPoints((0..5).map { test.point(first.trackId, it) })
        val lastFix = TEST_START + 5 * 10_000L
        repository.finishTrack(first.trackId, lastFix)

        val second = repository.openOrStitch(ActivityType.WALKING, lastFix + window, window)

        assertFalse(second.stitched)
        assertNotEquals(first.trackId, second.trackId)
    }

    @Test fun `the window runs from the last point, not from the trimmed end`() = runTest {
        // A track whose parked tail the overrun rule takes off the path: its stored end moves back
        // to the last good fix while the fixes themselves stay, and they are what dates the window.
        val first = repository.openOrStitch(ActivityType.WALKING, TEST_START, window)
        repository.addPoints((0..29).map { test.point(first.trackId, it) })
        val lastPoint = TEST_START + 29 * 10_000L
        // Parked at the last position for six minutes, which the overrun rule flags and trims.
        repository.addPoints(
            (1..36).map { test.point(first.trackId, 29).copy(timestamp = lastPoint + it * 10_000L) },
        )
        val parkedUntil = lastPoint + 36 * 10_000L
        repository.finishTrack(first.trackId, parkedUntil)

        val trimmedEnd = dao.track(first.trackId)!!.endedAt!!
        assertTrue("the overrun came off the clock", trimmedEnd < parkedUntil)

        // Inside the window measured from the last fix, outside the one measured from the row's end.
        val resumedAt = parkedUntil + 60_000L
        assertTrue(resumedAt > trimmedEnd + window)
        assertTrue(repository.openOrStitch(ActivityType.WALKING, resumedAt, window).stitched)
    }

    @Test fun `a stretch filed by the keep thresholds comes back when the trip grows`() = runTest {
        val first = repository.openOrStitch(ActivityType.WALKING, TEST_START, window)
        // Three fixes over twenty seconds: past the purge floor, under the keep thresholds.
        repository.addPoints((0..2).map { test.point(first.trackId, it) })
        val lastFix = TEST_START + 2 * 10_000L
        repository.finishTrack(first.trackId, lastFix)
        assertEquals(Track.REASON_FILTERED, dao.track(first.trackId)!!.discardReason)

        val second = repository.openOrStitch(ActivityType.WALKING, lastFix + 60_000L, window)

        assertTrue(second.stitched)
        assertEquals(first.trackId, second.trackId)
        val reopened = dao.track(second.trackId)!!
        assertNull("off Recently deleted", reopened.discardedAt)
        assertNull(reopened.discardReason)
    }

    @Test fun `a purged stretch leaves the track before it as the last one`() = runTest {
        val earlier = test.walk(TEST_START, 0, 20)
        val earlierEnd = TEST_START + 20 * 10_000L

        // A departure trigger's stub: opened on the signal alone, so UNKNOWN, whose group shares
        // with nothing and lands it on a row of its own. Two points in total is the purge floor —
        // the row and its fixes go outright, leaving no record that it existed.
        val stub = repository.openOrStitch(ActivityType.UNKNOWN, earlierEnd + 30_000L, window)
        assertFalse(stub.stitched)
        repository.addPoints((0..1).map { test.point(stub.trackId, it) })
        repository.finishTrack(stub.trackId, earlierEnd + 40_000L)
        assertNull("purged, not filed", dao.track(stub.trackId))

        // The walk before it is now the last track, and is judged on its own merits like any other.
        val resumed = repository.openOrStitch(ActivityType.WALKING, earlierEnd + 60_000L, window)

        assertTrue("its last point is still inside the window", resumed.stitched)
        assertEquals(earlier, resumed.trackId)
    }

    @Test fun `a track the user deleted is not resurrected`() = runTest {
        val id = test.walk(TEST_START, 0, 20)
        val lastFix = TEST_START + 20 * 10_000L
        repository.deleteTrack(id)

        val resumed = repository.openOrStitch(ActivityType.WALKING, lastFix + 60_000L, window)

        assertFalse(resumed.stitched)
        assertEquals(Track.REASON_DELETED, dao.track(id)!!.discardReason)
    }

    @Test fun `the label recorded under is the row's, not the one asked for`() = runTest {
        val id = test.walk(TEST_START, 0, 20)
        val lastFix = TEST_START + 20 * 10_000L
        // What a proven carrier case leaves behind: the row finished under a label the recorder
        // never asked for, and the fixes it takes next are gated by that one.
        repository.setActivityType(id, ActivityType.UNKNOWN)

        val resumed = repository.openOrStitch(ActivityType.UNKNOWN, lastFix + 60_000L, window)

        assertTrue(resumed.stitched)
        assertEquals(ActivityType.UNKNOWN, resumed.label)
    }

    // --- What a reader can reach while the recorder is filling a row ---------

    @Test fun `the timeline's mutations refuse a row being recorded into`() = runTest {
        val earlier = test.walk(TEST_START, 0, 20)
        val open = repository.openOrStitch(ActivityType.WALKING, TEST_START + 10 * 60_000L, window)
        repository.addPoints((0..5).map { test.point(open.trackId, it) })

        assertFalse("delete", repository.deleteTrack(open.trackId))
        assertNull("merge", repository.mergeTracks(earlier, open.trackId))
        assertNull("split", repository.splitTrack(open.trackId, TEST_START + 10 * 60_000L + 20_000L))

        val still = dao.track(open.trackId)!!
        assertNull("still open", still.endedAt)
        assertNull("and still on no one's deleted list", still.discardedAt)
        assertEquals(6, dao.allPointsFor(open.trackId).size)
    }
}
