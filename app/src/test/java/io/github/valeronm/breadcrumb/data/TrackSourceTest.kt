package io.github.valeronm.breadcrumb.data

import io.github.valeronm.breadcrumb.data.export.GpxParser
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.TrackOrigin
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A track's writer is declared where the row is inserted and never re-derived from the fixes, so
 * the declaration is only as good as the paths that carry it: every operation that creates a track
 * row from an existing one has to hand it on, or a merge would launder an import into a recording.
 */
@RunWith(RobolectricTestRunner::class)
class TrackSourceTest {

    private val test = TestDb()
    private val repository get() = test.repository
    private val dao get() = test.dao

    @After fun tearDown() = test.close()

    private suspend fun recordedTrack(fromIndex: Int, count: Int): Long {
        val id = repository.startTrack(ActivityType.WALKING, TEST_START + fromIndex * 10_000L)
        repository.addPoints((fromIndex until fromIndex + count).map { test.point(id, it) })
        repository.finishTrack(id, TEST_START + (fromIndex + count) * 10_000L)
        return id
    }

    private fun importableWalk(fromIndex: Int, count: Int) = GpxParser.ImportableTrack(
        activityTypeName = "WALKING",
        startedAt = TEST_START + fromIndex * 10_000L,
        endedAt = TEST_START + (fromIndex + count - 1) * 10_000L,
        points = (fromIndex until fromIndex + count).map { i ->
            // A parsed file carries no accuracy radius — that absence is what the pre-column rows
            // are reconstructed from, and it must stay true of what the importer stores.
            GpxParser.ImportPoint(
                lat = 1.0 + i * 0.001, lon = -2.0, ele = null,
                timeMs = TEST_START + i * 10_000L, speed = null, segmentStart = false,
            )
        },
    )

    @Test fun `the recorder declares itself when it opens a track`() = runTest {
        val id = repository.startTrack(ActivityType.WALKING, TEST_START)
        assertEquals(TrackOrigin.RECORDED.code, dao.track(id)!!.source)
    }

    @Test fun `an imported file declares itself, and stores no accuracy to be mistaken for one`() = runTest {
        assertEquals(1, repository.importTracks(listOf(importableWalk(0, 10))).imported)

        val id = dao.allTrackIds().single()
        assertEquals(TrackOrigin.IMPORTED.code, dao.track(id)!!.source)
        assertEquals(TrackOrigin.IMPORTED, TrackOrigin.inferFrom(dao.allPointsFor(id)))
    }

    @Test fun `a merge hands the writer on to the track it creates`() = runTest {
        val first = recordedTrack(0, 6)
        val second = recordedTrack(20, 6)

        val mergedId = repository.mergeTracks(first, second)!!

        assertEquals(TrackOrigin.RECORDED.code, dao.track(mergedId)!!.source)
    }

    @Test fun `a merge of imported tracks stays imported`() = runTest {
        // TrackMerge refuses across writers, so a merge only ever sees one — and the row it builds
        // must keep it rather than defaulting to the recorder.
        repository.importTracks(listOf(importableWalk(0, 10), importableWalk(20, 10)))
        val (first, second) = dao.allTrackIds().sorted()

        val mergedId = repository.mergeTracks(first, second)!!

        assertEquals(TrackOrigin.IMPORTED.code, dao.track(mergedId)!!.source)
    }

    @Test fun `a cut introduces no writer - both halves keep the original's`() = runTest {
        repository.importTracks(listOf(importableWalk(0, 20)))
        val id = dao.allTrackIds().single()
        val cutTs = dao.allPointsFor(id).let { it[it.size / 2].timestamp }

        val split = repository.splitTrack(id, cutTs)!!

        assertEquals(TrackOrigin.IMPORTED.code, dao.track(id)!!.source)
        assertEquals(TrackOrigin.IMPORTED.code, dao.track(split.secondId)!!.source)
    }
}
