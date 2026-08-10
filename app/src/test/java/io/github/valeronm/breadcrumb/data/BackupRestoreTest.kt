package io.github.valeronm.breadcrumb.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.valeronm.breadcrumb.data.db.AppDatabase
import io.github.valeronm.breadcrumb.data.db.LivenessEvent
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.data.db.Track
import io.github.valeronm.breadcrumb.data.export.BackupExporter
import io.github.valeronm.breadcrumb.data.export.BackupImporter
import io.github.valeronm.breadcrumb.data.export.BackupRepositories
import io.github.valeronm.breadcrumb.domain.IgnoreReason
import io.github.valeronm.breadcrumb.domain.PlaceCategory
import io.github.valeronm.breadcrumb.domain.PlaceClusterer
import io.github.valeronm.breadcrumb.domain.TrackOrigin
import io.github.valeronm.breadcrumb.domain.placeCategory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The whole backup/restore loop against real Room: export one database through [BackupExporter.writeJson],
 * restore into a fresh one through [BackupImporter.restore] — the batching, re-keying and counting path a
 * real restore takes — and compare. Covers what the parser round-trip test can't: id re-keying on
 * insertion, batch/flush accounting, and the export query's discarded/open-track exclusions.
 */
@RunWith(RobolectricTestRunner::class)
class BackupRestoreTest {

    /** Fixture rows declare a writer because every real row does — a row that doesn't is restored
     *  with one reconstructed from its fixes, which is the legacy-file path, not this one. */
    private val RECORDED = TrackOrigin.RECORDED.code

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
            BackupExporter.Content(
                tracks = source.repository.exportTracks(),
                pointsFor = { source.repository.allPointsFor(it) },
                places = source.db.placeDao().allPlaces(),
                liveness = source.db.livenessDao().allEvents(),
            ),
        )
        return BackupImporter.restore(
            java.io.StringReader(json.toString()),
            BackupRepositories(
                tracks = target.repository,
                places = PlaceRepository(context, target.db),
                liveness = LivenessRepository(context, target.db),
                derivation = DerivationStore(context, target.db),
            ),
        )
    }

    @Test fun `a restored database matches the exported one, ids aside`() = runTest {
        // Two finished tracks with mixed points, a discarded one, and an open one.
        val kept1 = source.dao.insertTrack(Track(source = RECORDED, activityType = "WALKING", startedAt = TEST_START))
        source.dao.insertPoints((0..4).map { source.point(kept1, it) })
        source.repository.finishTrack(kept1, TEST_START + 40_000L)
        val kept2 = source.dao.insertTrack(Track(source = RECORDED, activityType = "RUNNING", startedAt = TEST_START + 100_000L))
        source.dao.insertPoints(
            (0..4).map { source.point(kept2, it, ignored = it == 2, segmentStart = it == 3) },
        )
        source.repository.finishTrack(kept2, TEST_START + 140_000L)
        val discarded = source.dao.insertTrack(Track(source = RECORDED, activityType = "WALKING", startedAt = TEST_START + 200_000L))
        source.dao.insertPoints((0..4).map { source.point(discarded, it) })
        source.repository.finishTrack(discarded, TEST_START + 240_000L)
        source.repository.deleteTrack(discarded)
        source.dao.insertTrack(Track(source = RECORDED, activityType = "WALKING", startedAt = TEST_START + 300_000L)) // open

        // One categorized place and one untagged, so both branches of the place object are written:
        // an untagged place carries no `category` key at all.
        source.db.placeDao().insert(
            Place(
                label = "Home", lat = 1.0, lon = -2.0, createdAt = TEST_START,
                radiusM = PlaceClusterer.DEFAULT_RADIUS_M, category = PlaceCategory.HOME.code,
            ),
        )
        source.db.placeDao().insert(
            Place(
                label = "Trailhead", lat = 1.01, lon = -2.01, createdAt = TEST_START + 1_000L,
                radiusM = PlaceClusterer.DEFAULT_RADIUS_M,
            ),
        )
        source.db.livenessDao().insert(LivenessEvent(type = "ARMED", at = TEST_START))
        source.db.livenessDao().insert(LivenessEvent(type = "OUTAGE", at = TEST_START + 1_000L, until = TEST_START + 2_000L))

        val summary = roundTrip()

        assertEquals(2, summary.tracks) // discarded and open tracks stayed behind
        assertEquals(10, summary.points)
        assertEquals(2, summary.places)
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

    /**
     * A category code this build can't name still has to come back out of the file. The column
     * keeps the raw string for exactly this: a backup written by a later version, restored here and
     * exported again, must not lose what it couldn't display in between.
     */
    @Test fun `a category code this build doesn't know survives the round trip`() = runTest {
        source.db.placeDao().insert(
            Place(
                label = "Somewhere", lat = 1.0, lon = -2.0, createdAt = TEST_START,
                radiusM = PlaceClusterer.DEFAULT_RADIUS_M, category = "laundromat",
            ),
        )

        roundTrip()

        val restored = targetPlaces.allPlaces().single()
        assertEquals("laundromat", restored.category)
        assertNull("unknown codes read as untagged", restored.placeCategory)
    }

    @Test fun `restore re-derives the edge stay instead of trusting the file`() = runTest {
        // A file written by an older rule can carry flags the current one wouldn't set, so a
        // restore asks the current detector rather than replaying an old verdict. This track has
        // no edge stay at all, so its flagged fix must come back on the path — and the aggregates
        // with it, or the row would describe points it no longer has.
        val id = source.dao.insertTrack(Track(source = RECORDED, activityType = "WALKING", startedAt = TEST_START))
        source.dao.insertPoints((0..4).map { source.point(id, it) })
        source.repository.finishTrack(id, TEST_START + 40_000L)
        val last = source.dao.allPointsFor(id).last()
        source.dao.setIgnored(last.id, IgnoreReason.EDGE_STAY.code)

        roundTrip()

        val restored = target.repository.exportTracks().single()
        assertEquals(5, restored.pointCount)
        assertEquals(0, restored.ignoredCount)
        assertTrue(target.repository.trackPointsFor(restored.id).edgeStay.isEmpty())
    }
}
