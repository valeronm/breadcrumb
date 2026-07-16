package io.github.valeronm.breadcrumb.data

import io.github.valeronm.breadcrumb.data.db.Track
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The aggregates denormalized onto the track row must never drift from the points they summarize —
 * the timeline reads them instead of counting, so a stale value is a wrong track list, and a stale
 * distance means the keep-thresholds discard a real track. Every path that changes a track's points
 * is checked here against a recount from the points themselves.
 */
@RunWith(RobolectricTestRunner::class)
class TrackRepositoryTest {

    private val test = TestDb()
    private val repository get() = test.repository
    private val dao get() = test.dao

    @After fun tearDown() = test.close()

    /** What the stored aggregates should be, recomputed from the track's points on the spot. */
    private suspend fun assertStatsMatchPoints(trackId: Long) {
        val track = dao.track(trackId)!!
        val expected = TrackStats.of(dao.allPointsFor(trackId))
        assertEquals(expected.pointCount, track.pointCount)
        assertEquals(expected.ignoredCount, track.ignoredCount)
        assertEquals(expected.distanceMeters, track.distanceMeters, 0.5)
        assertEquals(expected.startLat, track.startLat)
        assertEquals(expected.startLon, track.startLon)
        assertEquals(expected.endLat, track.endLat)
        assertEquals(expected.endLon, track.endLon)
    }

    @Test fun `finishing a track writes the aggregates the recorder never wrote`() = runTest {
        val id = repository.startTrack(ActivityType.WALKING, TEST_START)
        repository.addPoints((0..5).map { test.point(id, it) })
        // The recorder writes no distance while it records — the row is still zeroed here.
        assertEquals(0.0, dao.track(id)!!.distanceMeters, 0.0)

        repository.finishTrack(id, TEST_START + 60_000)

        val track = dao.track(id)!!
        assertNotNull(track.endedAt)
        assertNull("a real walk must not be discarded", track.discardedAt)
        assertEquals(6, track.pointCount)
        assertStatsMatchPoints(id)
    }

    @Test fun `ignored fixes are counted separately and excluded from the endpoints`() = runTest {
        val id = repository.startTrack(ActivityType.WALKING, TEST_START)
        repository.addPoints(
            listOf(
                test.point(id, 0, ignored = true, lat = 0.0), // a stray, nowhere near the walk
                test.point(id, 1),
                test.point(id, 2),
                test.point(id, 3),
                test.point(id, 4),
            ),
        )

        repository.finishTrack(id, TEST_START + 60_000)

        val track = dao.track(id)!!
        assertEquals(4, track.pointCount)
        assertEquals(1, track.ignoredCount)
        assertEquals(38.701, track.startLat!!, 1e-9) // the first *good* point, not the stray
        assertStatsMatchPoints(id)
    }

    /**
     * The crash path. A track left open by a kill has a zeroed distance on its row (the recorder
     * never wrote one), so `finalizeDangling` must recompute from the points before applying the
     * keep rule — judging it on the row would discard every recovered track as zero-length.
     */
    @Test fun `finalizeDangling recomputes a crashed track instead of discarding it`() = runTest {
        val id = repository.startTrack(ActivityType.WALKING, TEST_START)
        repository.addPoints((0..5).map { test.point(id, it) })

        repository.finalizeDangling(exceptTrackId = null)

        val track = dao.track(id)!!
        assertNotNull("the dangling track is closed", track.endedAt)
        assertNull("and kept — its distance came from the points, not the stale row", track.discardedAt)
        assertStatsMatchPoints(id)
    }

    @Test fun `the track being recorded is left alone by finalizeDangling`() = runTest {
        val id = repository.startTrack(ActivityType.WALKING, TEST_START)
        repository.addPoints((0..5).map { test.point(id, it) })

        repository.finalizeDangling(exceptTrackId = id)

        assertNull("still recording", dao.track(id)!!.endedAt)
    }

    @Test fun `a track too short to keep is discarded, with its aggregates still written`() = runTest {
        val id = repository.startTrack(ActivityType.WALKING, TEST_START)
        // Three points a metre apart over 20 s: enough points to escape the purge floor, but under
        // every keep threshold.
        repository.addPoints(
            listOf(
                test.point(id, 0, lat = 38.7),
                test.point(id, 1, lat = 38.700009),
                test.point(id, 2, lat = 38.700018),
            ),
        )

        repository.finishTrack(id, TEST_START + 20_000)

        val track = dao.track(id)!!
        assertEquals(Track.REASON_FILTERED, track.discardReason)
        assertStatsMatchPoints(id)
    }

    @Test fun `a track with two points or fewer is purged outright, not discarded`() = runTest {
        val id = repository.startTrack(ActivityType.WALKING, TEST_START)
        repository.addPoints(listOf(test.point(id, 0), test.point(id, 1)))

        repository.finishTrack(id, TEST_START + 10_000)

        assertNull("nothing to review, so no Recently deleted row", dao.track(id))
        assertEquals("its points cascade away with it", 0, dao.allPointsFor(id).size)
    }

    @Test fun `ignored fixes count against the purge floor - a noisy track is discarded instead`() = runTest {
        val id = repository.startTrack(ActivityType.WALKING, TEST_START)
        // No good points at all, but a body of rejected fixes: reviewable evidence, so it goes to
        // Recently deleted (where the map marks the noisy fixes) rather than being destroyed.
        repository.addPoints((0..4).map { test.point(id, it, ignored = true) })

        repository.finishTrack(id, TEST_START + 50_000)

        val track = dao.track(id)!!
        assertEquals(Track.REASON_FILTERED, track.discardReason)
        assertEquals(5, track.ignoredCount)
        assertStatsMatchPoints(id)
    }

    @Test fun `the point-starved backfill purges old finished rows but never open or noisy ones`() = runTest {
        // A pre-rule row: finished with two points stored on it.
        val starved = dao.insertTrack(
            Track(activityType = "WALKING", startedAt = TEST_START, endedAt = TEST_START + 10_000, pointCount = 2),
        )
        // A noisy row with no good points: its ignored fixes are evidence, not emptiness.
        val noisy = dao.insertTrack(
            Track(
                activityType = "WALKING", startedAt = TEST_START + 20_000, endedAt = TEST_START + 30_000,
                pointCount = 0, ignoredCount = 5,
            ),
        )
        // A real kept track, and an open one whose zeroed pointCount is stale by design.
        val kept = repository.startTrack(ActivityType.WALKING, TEST_START + 100_000)
        repository.addPoints((0..5).map { test.point(kept, it) })
        repository.finishTrack(kept, TEST_START + 160_000)
        val open = repository.startTrack(ActivityType.WALKING, TEST_START + 200_000)

        repository.purgePointStarvedTracks()

        assertNull("the point-starved row is gone", dao.track(starved))
        assertNotNull("a noisy-only row keeps its evidence", dao.track(noisy))
        assertNotNull("a track with real points survives", dao.track(kept))
        assertNotNull("an open track is never judged on its stale row", dao.track(open))
    }

    @Test fun `merging two tracks recomputes the merged track's aggregates`() = runTest {
        val first = repository.startTrack(ActivityType.WALKING, TEST_START)
        repository.addPoints((0..3).map { test.point(first, it) })
        repository.finishTrack(first, TEST_START + 60_000)
        val second = repository.startTrack(ActivityType.WALKING, TEST_START + 120_000)
        repository.addPoints((4..7).map { test.point(second, it) })
        repository.finishTrack(second, TEST_START + 180_000)

        val mergedId = repository.mergeTracks(first, second)!!

        val merged = dao.track(mergedId)!!
        assertEquals(8, merged.pointCount)
        // The join is a segment break, so the stay between the two halves isn't counted as travel.
        assertStatsMatchPoints(mergedId)
        assertEquals(Track.REASON_MERGED, dao.track(first)!!.discardReason)
        assertEquals(Track.REASON_MERGED, dao.track(second)!!.discardReason)
    }

    @Test fun `repairing a leading stray updates the counts and the distance together`() = runTest {
        val id = repository.startTrack(ActivityType.DRIVING, TEST_START)
        // A cold-start fix 20 km away, then a normal drive — the stray the repair is for.
        repository.addPoints(
            listOf(test.point(id, 0, lat = 38.9)) + (1..5).map { test.point(id, it) },
        )
        repository.finishTrack(id, TEST_START + 60_000)

        val dropped = repository.repairLeadingPoints(id)

        assertEquals(1, dropped)
        val track = dao.track(id)!!
        assertEquals(1, track.ignoredCount)
        assertEquals(5, track.pointCount)
        assertEquals(38.701, track.startLat!!, 1e-9) // the stray is no longer the start
        assertStatsMatchPoints(id)
    }
}
