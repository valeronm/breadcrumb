package io.github.valeronm.breadcrumb.data

import androidx.test.core.app.ApplicationProvider
import io.github.valeronm.breadcrumb.data.db.AppDatabase
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.PlaceCategory
import io.github.valeronm.breadcrumb.domain.PlaceClusterer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * **The claim the whole persisted derivation rests on**: a history assembled the way a phone
 * assembles one — a track at a time, with deletes, merges, splits, hand-entered trips and renames
 * among them — must leave the rows a single pass over that same history would have written.
 *
 * `DerivationStoreTest` asks this of one mutation at a time, which is where a broken path is easiest
 * to read. This suite asks it of *sequences*, which is where the two writers actually diverge: a
 * repair judges a seam against rows an earlier repair left, so an error survives, moves and compounds
 * where a single-mutation case would never show it.
 *
 * Both modes are [DerivedConsistency]'s, and which one a case is owed is the point of the split.
 * Exact agreement is owed wherever the history only ever grew or was rewritten in place. Where a
 * track was deleted or merged away it is not: a cluster's anchor is its first-ever member, so losing
 * the track that founded one leaves the anchor where a fresh pass would not have put it. That
 * divergence is the design's, stated on `StayLedger` — so those cases assert the weaker claim and
 * then require that a rebuild restores the stronger one.
 */
@RunWith(RobolectricTestRunner::class)
class DerivedConsistencyTest {

    private val test = TestDb()
    private val db: AppDatabase = test.db
    private val repository get() = test.repository
    private val store = DerivationStore(ApplicationProvider.getApplicationContext(), db)
    private val places = PlaceRepository(ApplicationProvider.getApplicationContext(), db)

    /** Well past the fixture, so nothing is still open behind the clock. */
    private val now = TEST_START + 30L * 24 * 60 * 60_000L

    @After fun tearDown() = test.close()

    private suspend fun assertExact() = DerivedConsistency.assertMatchesFreshDerive(db, now)

    private suspend fun assertCoherent() = DerivedConsistency.assertInternallyConsistent(db, now)

    /**
     * A recorded walk from [fromIndex] to [toIndex] on the fixture's northbound line, offset
     * [lonOffset] degrees east — which is how a case puts a track somewhere else entirely, an
     * offset of 0.01 being ~1.1 km and well outside any capture radius.
     */
    private suspend fun walk(startedAt: Long, fromIndex: Int, toIndex: Int, lonOffset: Double = 0.0): Long {
        // Either direction along the line — a walk home retraces the one out, and its endpoints
        // therefore land in the other's clusters, which is what gives the history places to share.
        val steps = if (fromIndex <= toIndex) fromIndex..toIndex else fromIndex downTo toIndex
        val id = repository.startTrack(ActivityType.WALKING, startedAt)
        repository.addPoints(
            steps.mapIndexed { step, i ->
                test.point(id, i).copy(
                    timestamp = startedAt + step * 10_000L,
                    longitude = -2.0 + lonOffset,
                )
            },
        )
        repository.finishTrack(id, startedAt + (steps.count() - 1) * 10_000L)
        return id
    }

    private fun hours(n: Int) = TEST_START + n * 60L * 60_000L

    /**
     * A history a phone could have recorded: walks out and back over four days, two of them from a
     * second neighbourhood, so the clustering has more than one place to get wrong. Returns the ids
     * in the order they were recorded.
     */
    private suspend fun recordedHistory(): List<Long> = listOf(
        walk(hours(0), 0, 5),
        walk(hours(6), 5, 0),
        walk(hours(30), 0, 4),
        walk(hours(36), 4, 0, lonOffset = 0.01),
        walk(hours(54), 0, 5, lonOffset = 0.01),
        walk(hours(60), 5, 0),
        walk(hours(78), 0, 3),
        walk(hours(84), 3, 0),
    )

    // --- Exact agreement, where the history only grew or was rewritten in place ----

    @Test fun `a history recorded a track at a time is what one pass over it would leave`() = runTest {
        recordedHistory()

        // What the rest of this suite takes on trust: the fixture is a history with something in it.
        // A guard that agreed about nothing would pass every case below.
        val dao = db.derivedDao()
        assertTrue("the fixture holds several places", dao.clustersOnce().size >= 3)
        assertTrue("and an interval between each pair", dao.intervalsOnce().size >= 5)
        assertEquals("every endpoint is filed somewhere", 16, dao.membersOnce().size)
        assertExact()
    }

    @Test fun `a track left open by a crash agrees once it is finalized`() = runTest {
        recordedHistory()
        val dangling = repository.startTrack(ActivityType.WALKING, hours(100))
        repository.addPoints((0..5).map { test.point(dangling, it).copy(timestamp = hours(100) + it * 10_000L) })

        repository.finalizeDangling(exceptTrackId = null)

        assertExact()
    }

    @Test fun `a trip entered by hand, then rewritten, agrees at each step`() = runTest {
        recordedHistory()
        // The absence between the second day's walks, which is where a reader would enter one.
        val entered = repository.insertManualTrack(
            ActivityType.DRIVING,
            TrackRepository.ManualEnd(Coordinate(1.0, -2.0), hours(31)),
            TrackRepository.ManualEnd(Coordinate(1.02, -1.98), hours(33)),
        )
        assertTrue(entered is TrackRepository.ManualTrackResult.Saved)
        assertExact()

        val trackId = (entered as TrackRepository.ManualTrackResult.Saved).trackId
        val rewritten = repository.updateManualTrack(
            trackId,
            ActivityType.TRANSIT,
            // Both ends moved, so its endpoints leave the clusters they were in and join others.
            TrackRepository.ManualEnd(Coordinate(1.03, -2.0), hours(31)),
            TrackRepository.ManualEnd(Coordinate(1.05, -1.95), hours(33)),
        )
        assertTrue(rewritten is TrackRepository.ManualTrackResult.Saved)
        assertExact()
    }

    @Test fun `a retype across the foot-vehicle line agrees with the edges it moved`() = runTest {
        val ids = recordedHistory()

        // WALKING to DRIVING crosses EdgeStayDetector.paramsFor's line, so the overrun is re-derived
        // against the vehicle tuning and the track's bounds — the deriver's whole input — can move.
        repository.setActivityType(ids[2], ActivityType.DRIVING)

        assertExact()
    }

    @Test fun `naming a place agrees, and renaming it changes nothing at all`() = runTest {
        recordedHistory()
        val id = places.create("Home", 1.0, -2.0, TEST_START, PlaceClusterer.DEFAULT_RADIUS_M)
        assertExact()
        val afterNaming = db.derivedDao().clustersOnce()

        // The two ops that reach clustering nowhere. Each must leave every row exactly as it was —
        // not merely leave the derivation *equivalent*, which a needless rebuild would also do.
        places.save(id, "Home base", 1.0, -2.0, PlaceClusterer.DEFAULT_RADIUS_M)
        places.setCategory(id, PlaceCategory.HOME)

        assertEquals("metadata writes re-derive nothing", afterNaming, db.derivedDao().clustersOnce())
        assertExact()
    }

    @Test fun `a place deleted and restored leaves the derivation it had before the delete`() = runTest {
        recordedHistory()
        val id = places.create("Home", 1.0, -2.0, TEST_START, PlaceClusterer.DEFAULT_RADIUS_M)
        val named = db.placeDao().allPlaces().single()
        val before = DerivedReadModel.derivationOf(
            stored = store.observeStored().first(),
            places = db.placeDao().allPlaces(),
            liveness = db.livenessDao().allEvents(),
            nowMs = now,
            activeStartedAt = null,
        )

        places.delete(id)
        assertExact()

        // The whole argument for clearing the link rather than keeping the boundary: the round trip
        // has to land where it started, which an O(1) delete that kept the cluster would break.
        places.restore(named)
        assertExact()
        val after = DerivedReadModel.derivationOf(
            stored = store.observeStored().first(),
            places = db.placeDao().allPlaces(),
            liveness = db.livenessDao().allEvents(),
            nowMs = now,
            activeStartedAt = null,
        )
        assertEquals(before.intervals, after.intervals)
        assertEquals(before.clusters.map { it.anchor }, after.clusters.map { it.anchor })
    }

    @Test fun `splitting a track and undoing it agrees at each step`() = runTest {
        val ids = recordedHistory()

        val split = checkNotNull(repository.splitTrack(ids[4], hours(54) + 25_000L))
        assertExact()

        repository.unsplitTracks(ids[4], split)
        assertExact()
    }

    // --- The weaker claim, where a track left the history ---------------------
    //
    // A delete or a merge can strand an anchor at a coordinate a fresh pass would not choose. What
    // must still hold is that the rows describe a legal derivation of the history that remains —
    // and that asking for the whole thing again brings exact agreement back.

    @Test fun `deleting a track mid-history leaves rows a rebuild then makes exact`() = runTest {
        val ids = recordedHistory()

        repository.deleteTrack(ids[3])
        repository.deleteTrack(ids[6])
        assertCoherent()

        store.rebuild(now)
        assertExact()
    }

    @Test fun `merging then unmerging leaves rows a rebuild then makes exact`() = runTest {
        val ids = recordedHistory()

        val merged = checkNotNull(repository.mergeTracks(ids[0], ids[1]))
        assertCoherent()

        repository.unmergeTracks(merged, ids[0], ids[1])
        assertCoherent()

        store.rebuild(now)
        assertExact()
    }

    @Test fun `a run of every mutation in turn leaves rows a rebuild then makes exact`() = runTest {
        // The case single-mutation tests cannot reach: each repair judges a seam against what the
        // last one left, so an error that survives one step is carried into the next.
        val ids = recordedHistory()
        places.create("Home", 1.0, -2.0, TEST_START, PlaceClusterer.DEFAULT_RADIUS_M)
        repository.deleteTrack(ids[2])
        val merged = checkNotNull(repository.mergeTracks(ids[4], ids[5]))
        repository.setActivityType(ids[6], ActivityType.DRIVING)
        val split = checkNotNull(repository.splitTrack(ids[7], hours(84) + 15_000L))
        repository.restoreTrack(ids[2])
        repository.unsplitTracks(ids[7], split)
        repository.unmergeTracks(merged, ids[4], ids[5])

        assertCoherent()

        store.rebuild(now)
        assertExact()
    }
}
