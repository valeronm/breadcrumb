package io.github.valeronm.breadcrumb.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.valeronm.breadcrumb.data.db.NO_TRACK
import io.github.valeronm.breadcrumb.data.db.Track
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.DiscardReason
import io.github.valeronm.breadcrumb.domain.IgnoreReason
import io.github.valeronm.breadcrumb.domain.TrackOrigin
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The user's own cut: a split keeps both halves, a trim discards the one that fails the keep rule,
 * and the preview the confirmation shows is what the write stores. Every half's aggregates are
 * checked against a recount of its points, as [TrackRepositoryTest] checks every other write.
 */
@RunWith(RobolectricTestRunner::class)
class TrackSplitRepositoryTest {

    private val test = TestDb()
    private val repository get() = test.repository
    private val dao get() = test.dao

    @After fun tearDown() = test.close()

    /**
     * One journey the recorder never broke: [TestDb.addWalkThenLingerTail]'s walk and stop, walked out of again
     * — the stop sits mid-track, beyond any edge rule. It is 10 min to the tail's 6 so a cut through its
     * middle leaves an edge **either** side can see: the rule is far less sensitive at a start than an
     * end (a minute-long stay is found at an end, a start needs several), so a shorter stop cut in half
     * is caught arriving, missed departing — tuning, not the split. Don't shorten it to match the tail.
     */
    private suspend fun addWalkStopWalk(id: Long): Long {
        test.addWalkThenLingerTail(id, lingerFixes = 60)
        repository.addPoints(test.walkPoints(id, 120, 60, fromLat = 1.0 + 60 * 0.000126))
        return TEST_START + 180 * 10_000L
    }

    /** A finished walk of [count] fixes at walking pace, which keeps the overrun rule from finding a
     *  stay to flag. */
    private suspend fun finishedPacedWalk(count: Int): Long {
        val id = repository.startTrack(ActivityType.WALKING, TEST_START)
        repository.addPoints(test.walkPoints(id, 0, count, fromLat = 1.0))
        repository.finishTrack(id, TEST_START + count * 10_000L)
        return id
    }

    @Test fun `splitting keeps the original as the first half and rehomes the rest`() = runTest {
        val id = finishedPacedWalk(40)
        val cut = TEST_START + 20 * 10_000L

        val split = repository.splitTrack(id, cut)!!

        // Reassigned, not copied: the 40 fixes are spread over the two rows, not duplicated across
        // them, and no third row is left holding the period both now cover.
        assertEquals(20, dao.allPointsFor(id).size)
        assertEquals(20, dao.allPointsFor(split.secondId).size)
        assertNull("the original stays on the timeline", dao.track(id)!!.discardedAt)
        assertEquals("and keeps its start", TEST_START, dao.track(id)!!.startedAt)
        // The cut fix opens the second track rather than closing the first.
        assertEquals(cut, dao.track(split.secondId)!!.startedAt)
        assertTrue(dao.allPointsFor(id).last().timestamp < cut)
        test.assertStatsMatchPoints(id)
        test.assertStatsMatchPoints(split.secondId)
    }

    @Test fun `a cut at a stop takes the parked fixes off both halves' paths`() = runTest {
        val id = repository.startTrack(ActivityType.WALKING, TEST_START)
        repository.finishTrack(id, addWalkStopWalk(id))
        // No edge rule reaches a stop sitting mid-track, so the recorder's own track holds none.
        assertTrue(dao.allPointsFor(id).none { it.ignoreReason == IgnoreReason.EDGE_STAY.code })

        // Halfway through the stop, so each half keeps 5 min of it — see addWalkStopWalk for why
        // the arrival side alone would be satisfied by much less.
        val split = repository.splitTrack(id, TEST_START + 90 * 10_000L)!!

        // Cutting inside the stop turns it into an edge on both halves — the end of one journey
        // and the start of the next — which is exactly where the overrun rule applies.
        for (halfId in listOf(id, split.secondId)) {
            val ignored = dao.allPointsFor(halfId).filter { it.ignored }
            assertTrue("the parked fixes come off the path", ignored.isNotEmpty())
            assertTrue(ignored.all { it.ignoreReason == IgnoreReason.EDGE_STAY.code })
            test.assertStatsMatchPoints(halfId)
        }
    }

    @Test fun `a cut with no fix on one side is refused`() = runTest {
        val id = finishedPacedWalk(10)
        val endedAt = dao.track(id)!!.endedAt

        assertNull(repository.splitTrack(id, TEST_START))
        assertEquals("the row is left alone", endedAt, dao.track(id)!!.endedAt)
        assertEquals(10, dao.allPointsFor(id).size)
    }

    @Test fun `a trip its writer ended at its stops is not cut`() = runTest {
        val entered = (
            repository.insertManualTrack(
                ActivityType.WALKING,
                TrackRepository.ManualEnd(Coordinate(1.0, -2.0), TEST_START),
                TrackRepository.ManualEnd(Coordinate(1.03, -2.0), TEST_START + 3_600_000L),
                path = (1..9).map { Coordinate(1.0 + it * 0.003, -2.0) },
            ) as TrackRepository.ManualTrackResult.Saved
            ).trackId
        val google = TEST_START + 7_200_000L
        repository.insertBackupTracks(
            listOf(
                Track(activityType = ActivityType.WALKING.name, startedAt = google, endedAt = google + 400_000L, source = TrackOrigin.GOOGLE_TIMELINE.code) to
                    test.walkPoints(NO_TRACK, 0, 40, fromLat = 2.0).map { it.copy(timestamp = google + (it.timestamp - TEST_START)) },
            ),
        )
        val imported = dao.allTrackIds().single { it != entered }

        for (id in listOf(entered, imported)) {
            val points = dao.allPointsFor(id)
            val cut = points[points.size / 2].timestamp
            assertNull(repository.previewSplit(id, cut, points))
            assertNull(repository.splitTrack(id, cut))
            assertEquals("the fixes stay on the trip", points.size, dao.allPointsFor(id).size)
        }
    }

    @Test fun `a cut leaving one fix on a side trims that side into Recently deleted`() = runTest {
        val id = finishedPacedWalk(10)

        val split = repository.splitTrack(id, TEST_START + 10_000L)!!

        assertTrue(split.trimmed)
        assertEquals(DiscardReason.DELETED.code, dao.track(id)!!.discardReason)
        assertEquals(1, dao.allPointsFor(id).size)
        assertNull(dao.track(split.secondId)!!.discardedAt)
        assertEquals(9, dao.allPointsFor(split.secondId).size)
    }

    @Test fun `a recorded half below the keep thresholds is trimmed off`() = runTest {
        val id = finishedPacedWalk(40)
        Settings.setMinTrackDurationSec(ApplicationProvider.getApplicationContext<Context>(), 150)

        val split = repository.splitTrack(id, TEST_START + 30 * 10_000L)!!

        assertTrue(split.trimmed)
        assertNull("the long half stays", dao.track(id)!!.discardedAt)
        assertEquals(DiscardReason.DELETED.code, dao.track(split.secondId)!!.discardReason)
    }

    @Test fun `a cut losing both halves is refused, deleting the trip being that action`() = runTest {
        val id = finishedPacedWalk(40)
        Settings.setMinTrackDurationSec(ApplicationProvider.getApplicationContext<Context>(), 300)
        val cut = TEST_START + 20 * 10_000L

        assertTrue(repository.previewSplit(id, cut, dao.allPointsFor(id))!!.dropsBoth)
        assertNull(repository.splitTrack(id, cut))
        assertNull(dao.track(id)!!.discardedAt)
        assertEquals(40, dao.allPointsFor(id).size)
    }

    @Test fun `the preview is what the split stores`() = runTest {
        val id = repository.startTrack(ActivityType.WALKING, TEST_START)
        repository.finishTrack(id, addWalkStopWalk(id))
        // Inside the stop, where the halves' new edges move most from the cut.
        val cut = TEST_START + 90 * 10_000L

        val preview = repository.previewSplit(id, cut, dao.allPointsFor(id))!!
        val split = repository.splitTrack(id, cut)!!

        for ((half, rowId) in listOf(preview.first to id, preview.second to split.secondId)) {
            val row = dao.track(rowId)!!
            assertEquals(half.startedAt, row.startedAt)
            assertEquals(half.endedAt, row.endedAt)
            assertEquals(half.goodPoints, row.pointCount)
            assertEquals(half.distanceMeters, row.distanceMeters, 0.0)
        }
    }

    @Test fun `unsplitting a trim brings the discarded first half back`() = runTest {
        val id = finishedPacedWalk(10)
        val before = dao.track(id)!!
        val split = repository.splitTrack(id, TEST_START + 10_000L)!!

        repository.unsplitTracks(id, split)

        val reunited = dao.track(id)!!
        assertNull(reunited.discardedAt)
        assertNull(reunited.discardReason)
        assertNull(dao.track(split.secondId))
        assertEquals(10, dao.allPointsFor(id).size)
        assertEquals(before.startedAt, reunited.startedAt)
        assertEquals(before.endedAt, reunited.endedAt)
        test.assertStatsMatchPoints(id)
    }

    @Test fun `unsplitting hands the fixes back and drops the second half`() = runTest {
        val id = finishedPacedWalk(40)
        val before = dao.track(id)!!
        val split = repository.splitTrack(id, TEST_START + 20 * 10_000L)!!

        repository.unsplitTracks(id, split)

        assertNull("the second half's row is gone", dao.track(split.secondId))
        assertEquals("every fix is back on the original", 40, dao.allPointsFor(id).size)
        val reunited = dao.track(id)!!
        // The overrun rule reads the raw recording, so the reunited track re-derives to exactly what
        // it was — the end included, the second half having carried it on its own row until purged.
        assertEquals(before.endedAt, reunited.endedAt)
        assertEquals(before.startedAt, reunited.startedAt)
        assertEquals(before.pointCount, reunited.pointCount)
        assertEquals(before.distanceMeters, reunited.distanceMeters, 0.5)
        test.assertStatsMatchPoints(id)
    }
}
