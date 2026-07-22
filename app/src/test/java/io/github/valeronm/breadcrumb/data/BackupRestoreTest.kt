package io.github.valeronm.breadcrumb.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.valeronm.breadcrumb.data.db.AppDatabase
import io.github.valeronm.breadcrumb.data.db.LivenessEvent
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.data.db.Track
import io.github.valeronm.breadcrumb.data.export.BackupExporter
import io.github.valeronm.breadcrumb.data.export.BackupImporter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The whole backup/restore loop against real Room: export one database through
 * [BackupExporter.writeJson], restore into a fresh one through [BackupImporter.restore] — the
 * same batching, re-keying and counting path a real restore takes — and compare. Covers what the
 * parser round-trip test can't: id re-keying on insertion, the batch/flush accounting, and the
 * discarded/open-track exclusions of the export query.
 */
@RunWith(RobolectricTestRunner::class)
class BackupRestoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val source = TestDb()
    private val target = TestDb()
    private val targetDb: AppDatabase = target.db
    private val targetPlaces = targetDb.placeDao()
    private val targetLiveness = targetDb.livenessDao()

    @After fun tearDown() {
        source.close()
        target.close()
    }

    private suspend fun roundTrip(): BackupImporter.Summary {
        val json = StringBuilder()
        BackupExporter.writeJson(
            json,
            5_000L,
            source.repository.exportTracks(),
            { source.repository.allPointsFor(it) },
            source.db.placeDao().allPlaces(),
            source.db.livenessDao().allEvents(),
        )
        return BackupImporter.restore(
            java.io.StringReader(json.toString()),
            target.repository,
            PlaceRepository(context, target.db),
            LivenessRepository(context, target.db),
        )
    }

    @Test fun `a restored database matches the exported one, ids aside`() = runTest {
        // Two finished tracks with mixed points, a discarded one, and an open one.
        val kept1 = source.dao.insertTrack(Track(activityType = "WALKING", startedAt = TEST_START))
        source.dao.insertPoints((0..4).map { source.point(kept1, it) })
        source.repository.finishTrack(kept1, TEST_START + 40_000L)
        val kept2 = source.dao.insertTrack(Track(activityType = "RUNNING", startedAt = TEST_START + 100_000L))
        source.dao.insertPoints(
            (0..4).map { source.point(kept2, it, ignored = it == 2, segmentStart = it == 3) },
        )
        source.repository.finishTrack(kept2, TEST_START + 140_000L)
        val discarded = source.dao.insertTrack(Track(activityType = "WALKING", startedAt = TEST_START + 200_000L))
        source.dao.insertPoints((0..4).map { source.point(discarded, it) })
        source.repository.finishTrack(discarded, TEST_START + 240_000L)
        source.repository.deleteTrack(discarded)
        source.dao.insertTrack(Track(activityType = "WALKING", startedAt = TEST_START + 300_000L)) // open

        source.db.placeDao().insert(Place(label = "Home", lat = 38.7, lon = -9.3, createdAt = TEST_START))
        source.db.livenessDao().insert(LivenessEvent(type = "ARMED", at = TEST_START))
        source.db.livenessDao().insert(LivenessEvent(type = "OUTAGE", at = TEST_START + 1_000L, until = TEST_START + 2_000L))

        val summary = roundTrip()

        assertEquals(2, summary.tracks) // discarded and open tracks stayed behind
        assertEquals(10, summary.points)
        assertEquals(1, summary.places)
        assertEquals(2, summary.events)

        fun Track.comparable() = copy(id = 0)
        assertEquals(
            source.repository.exportTracks().map { it.comparable() },
            target.repository.exportTracks().map { it.comparable() },
        )
        for ((src, dst) in source.repository.exportTracks().zip(target.repository.exportTracks())) {
            assertEquals(
                source.repository.allPointsFor(src.id).map { it.copy(id = 0, trackId = 0) },
                target.repository.allPointsFor(dst.id).map { it.copy(id = 0, trackId = 0) },
            )
        }
        assertEquals(
            source.db.placeDao().allPlaces().map { it.copy(id = 0) },
            targetPlaces.allPlaces().map { it.copy(id = 0) },
        )
        assertEquals(
            source.db.livenessDao().allEvents().map { it.copy(id = 0) },
            targetLiveness.allEvents().map { it.copy(id = 0) },
        )
        // The restored timeline actually shows the tracks.
        assertEquals(2, targetDb.trackDao().observeSummaries().first().size)
    }

    @Test fun `restore re-derives the edge stay instead of trusting the file`() = runTest {
        // A file written by an older rule can carry flags the current one wouldn't set, so a
        // restore asks the current detector rather than replaying an old verdict. This track has
        // no edge stay at all, so its flagged fix must come back on the path — and the aggregates
        // with it, or the row would describe points it no longer has.
        val id = source.dao.insertTrack(Track(activityType = "WALKING", startedAt = TEST_START))
        source.dao.insertPoints((0..4).map { source.point(id, it) })
        source.repository.finishTrack(id, TEST_START + 40_000L)
        val last = source.dao.allPointsFor(id).last()
        source.dao.setIgnored(last.id, IgnoreReason.EDGE_STAY.code)

        roundTrip()

        val restored = target.repository.exportTracks().single()
        assertEquals(5, restored.pointCount)
        assertEquals(0, restored.ignoredCount)
        assertTrue(target.repository.edgeStayPointsFor(restored.id).isEmpty())
    }
}
