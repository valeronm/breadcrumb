package io.github.valeronm.breadcrumb.data

import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.IgnoreReason
import io.github.valeronm.breadcrumb.domain.StayDeriver
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The manual write path: a trip the user typed in must land kept — past the two-point purge floor
 * that would delete it on any other path — refuse a span another track holds, and sit so exactly at
 * its own bounds that the history-wide sweeps have nothing to rewrite. A rewrite answers to all
 * three again, and to one more: the row it replaces is not a span it collides with.
 */
@RunWith(RobolectricTestRunner::class)
class ManualTrackTest {

    private val test = TestDb()
    private val repository get() = test.repository
    private val dao get() = test.dao

    @After fun tearDown() = test.close()

    /** A three-hour flight-shaped entry: two typed ends, nothing between. */
    private suspend fun manualTrip(startMs: Long = TEST_START): TrackRepository.ManualTrackResult =
        repository.insertManualTrack(
            ActivityType.FLIGHT,
            TrackRepository.ManualEnd(StayDeriver.Endpoint(1.0, -2.0), startMs),
            TrackRepository.ManualEnd(StayDeriver.Endpoint(1.5, -1.0), startMs + 3 * 3_600_000L),
        )

    private fun trackIdOf(result: TrackRepository.ManualTrackResult): Long =
        (result as TrackRepository.ManualTrackResult.Saved).trackId

    /** A finished minute of walking — the neighbour a typed trip is measured against. */
    private suspend fun walkTrack(): Long {
        val id = repository.startTrack(ActivityType.WALKING, TEST_START)
        repository.addPoints((0..5).map { test.point(id, it) })
        repository.finishTrack(id, TEST_START + 60_000)
        return id
    }

    @Test fun `a manual trip lands kept, aggregates and all, despite the two-point purge floor`() = runTest {
        val id = trackIdOf(manualTrip())

        val track = dao.track(id)!!
        assertEquals(ActivityType.FLIGHT.name, track.activityType)
        assertEquals(TEST_START, track.startedAt)
        assertEquals(TEST_START + 3 * 3_600_000L, track.endedAt)
        assertNull("an explicit entry is kept as-is", track.discardedAt)
        assertEquals(2, track.pointCount)
        assertEquals(0, track.ignoredCount)
        test.assertStatsMatchPoints(id)
    }

    @Test fun `a manual trip over an existing track's span is refused`() = runTest {
        // The half of the overlap rule that still counts: these fixes are the walk's path, and a
        // trip claiming the same minutes would be a second one over them.
        val walk = walkTrack()

        val result = manualTrip(startMs = TEST_START + 30_000L)

        assertTrue(result is TrackRepository.ManualTrackResult.Overlapping)
        assertEquals(listOf(walk), dao.allTrackIds())
    }

    @Test fun `a trip filling a gap is not refused by the overrun trimmed off its neighbour`() = runTest {
        // The ordinary case, and it was refused: the edge-stay rule pulls a track's endedAt in to
        // its last *good* fix and leaves the ignored overrun past it, on about a third of a real
        // history's tracks. The gap the timeline draws starts at that pulled-in bound, so a trip
        // entered to fill it starts there too — and an overlap check that counted the overrun found
        // the neighbour sitting in the very interval it had just been trimmed out of.
        //
        // Stated outright rather than produced by finishing a track that lingers: what is under
        // test is the overlap check, and a fixture built by the trimmer would answer to that rule's
        // tuning as well as to this one.
        val walk = walkTrack()
        // The overrun: fixes past the row's own end, ignored, exactly as a trim leaves them.
        val trimmedEnd = TEST_START + 30_000
        dao.closeTrack(walk, trimmedEnd)
        dao.setIgnored(
            dao.allPointsFor(walk).filter { it.timestamp > trimmedEnd }.map { it.id },
            IgnoreReason.EDGE_STAY.code,
        )

        val result = repository.insertManualTrack(
            ActivityType.FLIGHT,
            TrackRepository.ManualEnd(StayDeriver.Endpoint(1.0, -2.0), trimmedEnd),
            TrackRepository.ManualEnd(StayDeriver.Endpoint(1.5, -1.0), trimmedEnd + 3_600_000L),
        )

        assertTrue("the trimmed fixes are not a path to collide with", result is TrackRepository.ManualTrackResult.Saved)
    }

    @Test fun `a rewritten trip keeps its row and answers to the new ends alone`() = runTest {
        val id = trackIdOf(manualTrip())
        val movedStart = TEST_START + 4 * 3_600_000L

        val result = repository.updateManualTrack(
            id,
            ActivityType.FERRY,
            TrackRepository.ManualEnd(StayDeriver.Endpoint(1.2, -2.2), movedStart),
            TrackRepository.ManualEnd(StayDeriver.Endpoint(1.4, -1.4), movedStart + 3_600_000L),
        )

        assertEquals(id, (result as TrackRepository.ManualTrackResult.Saved).trackId)
        // Rewritten in place: one row, still the one that was there.
        assertEquals(listOf(id), dao.allTrackIds())
        val track = dao.track(id)!!
        assertEquals(ActivityType.FERRY.name, track.activityType)
        assertEquals(movedStart, track.startedAt)
        assertEquals(movedStart + 3_600_000L, track.endedAt)
        // The old pair is gone rather than joined by the new one, and the aggregates followed.
        val points = dao.allPointsFor(id)
        assertEquals(2, points.size)
        assertEquals(movedStart, points.first().timestamp)
        assertEquals(2, track.pointCount)
        test.assertStatsMatchPoints(id)
    }

    @Test fun `a rewrite is not refused by the span it is replacing`() = runTest {
        // The row's own fixes sit inside the span it is moving to — nudging one end by a minute is
        // the ordinary edit, and a check that counted them would refuse every one of them.
        val id = trackIdOf(manualTrip())

        val result = repository.updateManualTrack(
            id,
            ActivityType.FLIGHT,
            TrackRepository.ManualEnd(StayDeriver.Endpoint(1.0, -2.0), TEST_START + 60_000L),
            TrackRepository.ManualEnd(StayDeriver.Endpoint(1.5, -1.0), TEST_START + 3 * 3_600_000L),
        )

        assertTrue(result is TrackRepository.ManualTrackResult.Saved)
        assertEquals(TEST_START + 60_000L, dao.track(id)!!.startedAt)
    }

    @Test fun `a rewrite onto another track's span is refused, and writes nothing`() = runTest {
        walkTrack()
        val id = trackIdOf(manualTrip(startMs = TEST_START + 3_600_000L))
        val before = dao.track(id)!!

        val result = repository.updateManualTrack(
            id,
            ActivityType.FLIGHT,
            TrackRepository.ManualEnd(StayDeriver.Endpoint(1.0, -2.0), TEST_START + 30_000L),
            TrackRepository.ManualEnd(StayDeriver.Endpoint(1.5, -1.0), TEST_START + 4 * 3_600_000L),
        )

        assertTrue(result is TrackRepository.ManualTrackResult.Overlapping)
        assertEquals(before.startedAt, dao.track(id)!!.startedAt)
        assertEquals(2, dao.allPointsFor(id).size)
    }

    @Test fun `only a manual track may be rewritten`() = runTest {
        // Every other track's fixes are a measurement or a file's, and a rewrite replaces them.
        val walk = walkTrack()

        val result = repository.updateManualTrack(
            walk,
            ActivityType.FLIGHT,
            TrackRepository.ManualEnd(StayDeriver.Endpoint(1.0, -2.0), TEST_START),
            TrackRepository.ManualEnd(StayDeriver.Endpoint(1.5, -1.0), TEST_START + 60_000),
        )

        assertTrue(result is TrackRepository.ManualTrackResult.NotEditable)
        assertEquals(6, dao.allPointsFor(walk).size)
        assertEquals(ActivityType.WALKING.name, dao.track(walk)!!.activityType)
    }

    @Test fun `the sweeps hand a manual trip back exactly as entered`() = runTest {
        // Its points sit exactly at the row's bounds, so the edge-stay boundary fix has nothing to
        // widen and the stats sweep nothing to rewrite — else every app start would touch the row.
        val id = trackIdOf(manualTrip())
        val before = dao.track(id)!!

        repository.sweepEdgeStays()
        repository.sweepStats()

        val after = dao.track(id)!!
        assertEquals(before.startedAt, after.startedAt)
        assertEquals(before.endedAt, after.endedAt)
        assertEquals(0, after.ignoredCount)
        test.assertStatsMatchPoints(id)
    }
}
