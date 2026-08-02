package io.github.valeronm.breadcrumb.data

import io.github.valeronm.breadcrumb.domain.ActivityType
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
 * The manual insert path: a trip the user typed in must land kept — past the two-point purge floor
 * that would delete it on any other path — refuse a span another track holds, and sit so exactly at
 * its own bounds that the history-wide sweeps have nothing to rewrite.
 */
@RunWith(RobolectricTestRunner::class)
class ManualTrackTest {

    private val test = TestDb()
    private val repository get() = test.repository
    private val dao get() = test.dao

    @After fun tearDown() = test.close()

    /** A three-hour flight-shaped entry: two typed ends, nothing between. */
    private suspend fun manualTrip(startMs: Long = TEST_START): TrackRepository.ManualInsertResult =
        repository.insertManualTrack(
            ActivityType.FLIGHT,
            TrackRepository.ManualEnd(StayDeriver.Endpoint(1.0, -2.0), startMs),
            TrackRepository.ManualEnd(StayDeriver.Endpoint(1.5, -1.0), startMs + 3 * 3_600_000L),
        )

    private fun trackIdOf(result: TrackRepository.ManualInsertResult): Long =
        (result as TrackRepository.ManualInsertResult.Inserted).trackId

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
        val walk = repository.startTrack(ActivityType.WALKING, TEST_START)
        repository.addPoints((0..5).map { test.point(walk, it) })
        repository.finishTrack(walk, TEST_START + 60_000)

        val result = manualTrip(startMs = TEST_START + 30_000L)

        assertTrue(result is TrackRepository.ManualInsertResult.Overlapping)
        assertEquals(listOf(walk), dao.allTrackIds())
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
