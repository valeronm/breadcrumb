package io.github.valeronm.breadcrumb.data

import io.github.valeronm.breadcrumb.data.db.NO_TRACK
import io.github.valeronm.breadcrumb.data.export.GpxParser
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.IgnoreReason
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PeriodTakenTest {

    private val test = TestDb()
    private val repository get() = test.repository
    private val dao get() = test.dao

    @After fun tearDown() = test.close()

    /** Its fixes lie on the line a [TestDb.walkPoints] walk from latitude 1.0 draws. */
    private fun importableWalk(fromIndex: Int, count: Int) = GpxParser.ImportableTrack(
        activityTypeName = "WALKING",
        startedAt = TEST_START + fromIndex * 10_000L,
        endedAt = TEST_START + (fromIndex + count - 1) * 10_000L,
        points = test.walkPoints(NO_TRACK, fromIndex, count, fromLat = 1.0 + fromIndex * 0.000126),
    )

    @Test fun `a file overlapping an existing track is skipped, not laid over it`() = runTest {
        assertEquals(1, repository.importTracks(listOf(importableWalk(0, 60))).imported)

        val counts = repository.importTracks(listOf(importableWalk(30, 60)))

        assertEquals(0, counts.imported)
        assertEquals("not an exact duplicate — the spans differ", 0, counts.duplicates)
        assertEquals(1, counts.overlapping)
        assertEquals(1, dao.allTrackIds().size)
    }

    @Test fun `back-to-back legs touching at one instant both import`() = runTest {
        val counts = repository.importTracks(listOf(importableWalk(0, 60), importableWalk(59, 60)))

        assertEquals(2, counts.imported)
        assertEquals(0, counts.overlapping)
    }

    @Test fun `a track still recording holds every period after its start`() = runTest {
        val recording = repository.startTrack(ActivityType.WALKING, TEST_START)
        repository.addPoints(test.walkPoints(recording, 0, 6, fromLat = 1.0))

        val counts = repository.importTracks(listOf(importableWalk(100, 60)))

        assertEquals(0, counts.imported)
        assertEquals(1, counts.overlapping)
    }

    @Test fun `a track with no good fix still holds its bounds`() = runTest {
        assertEquals(1, repository.importTracks(listOf(importableWalk(0, 60))).imported)
        val (id) = dao.allTrackIds()
        dao.setIgnored(dao.allPointsFor(id).map { it.id }, IgnoreReason.EDGE_STAY.code)

        val counts = repository.importTracks(listOf(importableWalk(30, 60)))

        assertEquals(0, counts.imported)
        assertEquals(1, counts.overlapping)
    }

    @Test fun `a span held only by a deleted track imports`() = runTest {
        val recorded = repository.startTrack(ActivityType.WALKING, TEST_START)
        repository.addPoints(test.walkPoints(recorded, 0, 60, fromLat = 1.0))
        repository.finishTrack(recorded, TEST_START + 60 * 10_000L)
        repository.deleteTrack(recorded)

        val counts = repository.importTracks(listOf(importableWalk(0, 60)))

        assertEquals(1, counts.imported)
        assertEquals(0, counts.duplicates)
        assertEquals(0, counts.overlapping)
    }
}
