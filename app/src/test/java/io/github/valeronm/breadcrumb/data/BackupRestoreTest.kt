package io.github.valeronm.breadcrumb.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.valeronm.breadcrumb.data.db.AppDatabase
import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.data.db.Track
import io.github.valeronm.breadcrumb.data.db.TrackPoint
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

    @After fun tearDown() {
        source.close()
        target.close()
    }

    /**
     * Every kept track's fixes against the ones restored under it, the two histories laid side by
     * side in export order. [of] is what a fix is compared *by*, whole row by default — a case
     * narrows it only where the fixture cannot hold up its end: [TestDb.point] walks latitude by
     * adding 0.001 at a time, which drifts off the export's grid after a hundred-odd steps, so past
     * that a whole-row comparison fails on the rounding rather than on anything carried wrongly.
     */
    private suspend fun assertRestoredFixesMatch(of: (TrackPoint) -> Any? = { it.copy(id = 0, trackId = 0) }) {
        val sourceTracks = source.repository.exportTracks()
        val restoredTracks = target.repository.exportTracks()
        // Before the zip, which would truncate away a dropped track rather than fail on it.
        assertEquals(sourceTracks.size, restoredTracks.size)
        for ((src, dst) in sourceTracks.zip(restoredTracks)) {
            assertEquals(
                source.dao.allPointsFor(src.id).map(of),
                target.dao.allPointsFor(dst.id).map(of),
            )
        }
    }

    private suspend fun roundTrip(): BackupImporter.Summary {
        val json = java.io.StringWriter()
        BackupExporter.writeJson(
            json,
            5_000L,
            BackupExporter.Content(
                tracks = source.repository.exportTracks(),
                pointsFor = { source.repository.pointsForTracks(it) },
                places = source.db.placeDao().allPlaces(),
            ),
        )
        return BackupImporter.restore(
            java.io.StringReader(json.toString()),
            BackupRepositories(
                tracks = target.repository,
                places = PlaceRepository(context, target.db),
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
        val summary = roundTrip()

        assertEquals(2, summary.tracks) // discarded and open tracks stayed behind
        assertEquals(10, summary.points)
        assertEquals(2, summary.places)

        fun Track.comparable() = copy(id = 0)
        assertEquals(
            source.repository.exportTracks().map { it.comparable() },
            target.repository.exportTracks().map { it.comparable() },
        )
        assertRestoredFixesMatch()
        assertEquals(
            source.db.placeDao().allPlaces().map { it.copy(id = 0) },
            targetPlaces.allPlaces().map { it.copy(id = 0) },
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

    /**
     * A track whose fixes all carry digits below the export's grid, so every rounding it states is
     * a visible one. Two things are deliberate: enough points, far enough apart, to clear the keep
     * thresholds, and a step that is *not* a whole number of grid units — step by one the grid can
     * land on and every fix rounds identically, the legs between them come out unchanged, and a
     * suite meaning to ask what rounding does to a distance asks nothing at all.
     */
    private suspend fun insertFullPrecisionTrack() {
        val id = source.dao.insertTrack(Track(source = RECORDED, activityType = "WALKING", startedAt = TEST_START))
        source.dao.insertPoints(
            (0..4).map { index ->
                TrackPoint(
                    trackId = id,
                    latitude = 1.0023456789012345 + index * 0.0010000345,
                    longitude = -2.0034567891234567,
                    altitude = 141.06837105389872,
                    accuracy = 2.8817503f,
                    speed = 0.0179898f,
                    bearing = 357.7112f,
                    timestamp = TEST_START + index * 10_000L,
                    verticalAccuracy = 3.16f,
                    speedAccuracy = 0.02069424f,
                    bearingAccuracy = 179.94999f,
                    satellitesInFix = 15,
                    cn0 = 36.32854f,
                )
            },
        )
        source.repository.finishTrack(id, TEST_START + 40_000L)
    }

    @Test fun `a point is written to the precision its instrument has`() = runTest {
        insertFullPrecisionTrack()

        roundTrip()

        val restored = target.dao
            .allPointsFor(target.repository.exportTracks().single().id)
            .first()
        assertEquals(1.0023457, restored.latitude, 0.0)
        assertEquals(-2.0034568, restored.longitude, 0.0)
        assertEquals(141.1, restored.altitude!!, 0.0)
        assertEquals(2.88f, restored.accuracy!!, 0f)
        assertEquals(0.02f, restored.speed!!, 0f)
        assertEquals(357.7f, restored.bearing!!, 0f)
        assertEquals(3.2f, restored.verticalAccuracy!!, 0f)
        assertEquals(0.02f, restored.speedAccuracy!!, 0f)
        assertEquals(179.9f, restored.bearingAccuracy!!, 0f)
        assertEquals(15, restored.satellitesInFix)
        assertEquals(36.3f, restored.cn0!!, 0f)
    }

    /**
     * The export reads several tracks per query, so its fixes arrive keyed by track and in an order
     * that is not the document's. Nothing smaller than a batch can catch a track handed another's
     * fixes, or a boundary that drops the track it falls on — so this sizes itself off
     * [BackupExporter.FIXES_PER_READ] and spans more than one.
     */
    @Test fun `tracks keep their own fixes across a read boundary`() = runTest {
        val perTrack = 500
        val tracks = BackupExporter.FIXES_PER_READ / perTrack + 2
        repeat(tracks) { t ->
            source.walk(TEST_START + t * 10_000_000L, 0, perTrack - 1)
        }

        roundTrip()

        assertEquals(tracks, target.repository.exportTracks().size)
        // By timestamp, which no grid moves and which no two of these tracks share — 500 fixes a
        // track is past where the fixture's latitudes stay on the grid.
        assertRestoredFixesMatch { it.timestamp }
    }

    /**
     * A restored row states what the fixes stored under it say, the file's own figures having been
     * measured before the export rounded them. [TrackStats.Stats.matches] is the comparison the
     * stats sweep makes, on an exact `Double` — so a row left disagreeing is one the next rule
     * version rewrites, taking a re-derivation of the whole history with it.
     */
    @Test fun `a restored track's aggregates already match its own fixes`() = runTest {
        insertFullPrecisionTrack()

        roundTrip()

        val restored = target.repository.exportTracks().single()
        val points = target.dao.allPointsFor(restored.id)
        assertTrue(
            "the next stats sweep would rewrite this row",
            TrackStats.of(points).matches(restored),
        )
    }
}
