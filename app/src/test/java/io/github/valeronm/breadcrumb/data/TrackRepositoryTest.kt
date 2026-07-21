package io.github.valeronm.breadcrumb.data

import io.github.valeronm.breadcrumb.data.db.Track
import io.github.valeronm.breadcrumb.data.export.GpxParser
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // --- Edge-stay trim -------------------------------------------------------------------------

    /** A fix every 10 s advancing 14 m north at walking Doppler speed. */
    private fun walkPoints(trackId: Long, fromIndex: Int, count: Int, fromLat: Double) =
        (0 until count).map { i ->
            test.point(trackId, fromIndex + i, lat = fromLat + i * 0.000126)
                .copy(speed = 1.4f)
        }

    /** A fix every 10 s jittering ±15 m around [lat] at standstill Doppler speed. */
    private fun lingerPoints(trackId: Long, fromIndex: Int, count: Int, lat: Double) =
        (0 until count).map { i ->
            test.point(trackId, fromIndex + i, lat = lat + if (i % 2 == 0) 0.000135 else -0.000135)
                .copy(speed = 0.1f)
        }

    private suspend fun discardedTracks(): List<Track> =
        dao.observeDiscardedSummaries().first().map { dao.track(it.id)!! }

    /** 10 min of walking (840 m) then 6 min lingering where the walk ended — the AR-lag tail.
     *  Returns the track's raw end time (96 fixes, one per 10 s). */
    private suspend fun addWalkThenLingerTail(id: Long): Long {
        repository.addPoints(
            walkPoints(id, 0, 60, fromLat = 38.7) +
                lingerPoints(id, 60, 36, lat = 38.7 + 60 * 0.000126),
        )
        return TEST_START + 96 * 10_000L
    }

    @Test fun `finishing a track that ends lingering splits the stay off as a trimmed track`() = runTest {
        val id = repository.startTrack(ActivityType.WALKING, TEST_START)
        val endedAt = addWalkThenLingerTail(id)
        repository.finishTrack(id, endedAt)

        val head = dao.track(id)!!
        assertNull("the walk itself is kept", head.discardedAt)
        // The cut lands at the speed collapse — the walk-to-linger transition, bin-quantized.
        val walkEndTs = TEST_START + 59 * 10_000L
        assertTrue(head.endedAt!! in walkEndTs..(walkEndTs + 60_000))
        assertStatsMatchPoints(id)

        val tail = discardedTracks().single()
        assertEquals(Track.REASON_TRIMMED, tail.discardReason)
        // Both tracks bound at the cut: the zero-length seam is what StayDeriver turns into the
        // stay carrying the merge-back offer if the tail is restored.
        assertEquals(head.endedAt, tail.startedAt)
        assertEquals(endedAt, tail.endedAt)
        // Points moved, not copied: the two tracks partition the original 96.
        assertEquals(96, head.pointCount + tail.pointCount)
        assertStatsMatchPoints(tail.id)
    }

    @Test fun `restoring the trimmed tail and merging it back reconstitutes the track`() = runTest {
        val id = repository.startTrack(ActivityType.WALKING, TEST_START)
        repository.finishTrack(id, addWalkThenLingerTail(id))
        val tailId = discardedTracks().single().id

        repository.restoreTrack(tailId)
        val mergedId = repository.mergeTracks(id, tailId)!!

        assertEquals(96, dao.track(mergedId)!!.pointCount)
        assertStatsMatchPoints(mergedId)
    }

    @Test fun `a track that starts lingering is trimmed at its start`() = runTest {
        val id = repository.startTrack(ActivityType.WALKING, TEST_START)
        // 15 min lingering before departure, then 10 min of walking. The linger must be long:
        // the departure passes *through* the corral, dragging every dwell window's exit ~40 m
        // along the walk, and only a long span dilutes that below the drift gate — a short
        // start-stay followed by walking away is deliberately not detectable (transit-shaped).
        repository.addPoints(
            lingerPoints(id, 0, 90, lat = 38.7) +
                walkPoints(id, 90, 60, fromLat = 38.7),
        )
        repository.finishTrack(id, TEST_START + 150 * 10_000L)

        val walk = dao.track(id)!!
        assertNull(walk.discardedAt)
        // startedAt moved up to the departure — the start of sustained movement, bin-quantized
        // (TEST_START is not bin-aligned, so up to one speed bin of slop either way).
        val walkStartTs = TEST_START + 90 * 10_000L
        assertTrue(walk.startedAt in (walkStartTs - 60_000)..(walkStartTs + 30_000))
        val stay = discardedTracks().single()
        assertEquals(Track.REASON_TRIMMED, stay.discardReason)
        assertEquals(TEST_START, stay.startedAt)
        assertEquals(walk.startedAt, stay.endedAt)
        assertStatsMatchPoints(id)
        assertStatsMatchPoints(stay.id)
    }

    @Test fun `an imported GPX track ending in a linger is trimmed via derived speed`() = runTest {
        // GPX carries no Doppler speed — the boundary must come from the displacement lookback.
        // The linger jitters ±9 m so its 30 s net displacement stays under the moving threshold.
        val walkEnd = 38.7 + 60 * 0.000126
        val points = (0 until 60).map { i ->
            GpxParser.ImportPoint(
                lat = 38.7 + i * 0.000126, lon = -9.3, ele = null,
                timeMs = TEST_START + i * 10_000L, speed = null, segmentStart = false,
            )
        } + (0 until 36).map { i ->
            GpxParser.ImportPoint(
                lat = walkEnd + if (i % 2 == 0) 0.00008 else -0.00008, lon = -9.3, ele = null,
                timeMs = TEST_START + (60 + i) * 10_000L, speed = null, segmentStart = false,
            )
        }
        val counts = repository.importTracks(
            listOf(
                GpxParser.ImportableTrack(
                    activityTypeName = "WALKING",
                    startedAt = TEST_START,
                    endedAt = TEST_START + 96 * 10_000L,
                    points = points,
                ),
            ),
        )

        assertEquals(1, counts.imported)
        val tail = discardedTracks().single()
        assertEquals(Track.REASON_TRIMMED, tail.discardReason)
        val head = dao.track(dao.allTrackIds().single())!!
        val walkEndTs = TEST_START + 59 * 10_000L
        assertTrue(head.endedAt!! in walkEndTs..(walkEndTs + 90_000))
        assertEquals(head.endedAt, tail.startedAt)
        assertStatsMatchPoints(head.id)
        assertStatsMatchPoints(tail.id)
    }

    @Test fun `the trim backfill is idempotent`() = runTest {
        val id = repository.startTrack(ActivityType.WALKING, TEST_START)
        val endedAt = addWalkThenLingerTail(id)
        // Close without the trim (as an old build would have): straight to the DAO.
        dao.closeTrack(id, endedAt)

        repository.trimEdgeStaysBackfill()
        assertEquals(1, discardedTracks().size) // the trimmed tail
        val headEnd = dao.track(id)!!.endedAt

        repository.trimEdgeStaysBackfill()

        assertEquals(1, discardedTracks().size)
        assertEquals(headEnd, dao.track(id)!!.endedAt)
    }
}
