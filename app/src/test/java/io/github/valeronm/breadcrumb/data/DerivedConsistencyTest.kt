package io.github.valeronm.breadcrumb.data

import androidx.test.core.app.ApplicationProvider
import io.github.valeronm.breadcrumb.data.db.AppDatabase
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.PlaceCategory
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
 * **What divides this suite from `DerivationStoreTest` is the history, not the number of mutations.**
 * That one runs its paths over tracks that all begin and end at the same two spots, where no cluster
 * can lose its founder and survive — so it can ask exact agreement of every path and read as a plain
 * statement of what each one does. This one runs the same paths over a history with several places
 * in it, which is where a delete or a merge can strand an anchor and where exact agreement stops
 * being owed. It also holds what only a longer history reaches: a crash-recovered finalize, a trip
 * entered and rewritten by hand, a retype across the tuning line, and a run of every mutation in
 * turn — the last being where the two writers really diverge, a repair judging each seam against
 * rows an earlier repair left, so an error survives and compounds.
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

    private suspend fun walk(startedAt: Long, fromIndex: Int, toIndex: Int, lonOffset: Double = 0.0) =
        test.walk(startedAt, fromIndex, toIndex, lonOffset)

    private fun hours(n: Int) = TEST_START + n * 60L * 60_000L

    /**
     * A history a phone could have recorded: walks out and back over four days, two of them from a
     * second neighbourhood, so the clustering has more than one place to get wrong. Returns the ids
     * in the order they were recorded.
     *
     * It asserts its own shape before handing over, and that is not belt-and-braces: every case here
     * takes on trust that this is a history with something in it, so a fixture that quietly stopped
     * producing several places — a radius change, a mistyped offset, a track too short to keep —
     * would leave every case passing about a history with one place and no absences in it.
     */
    private suspend fun recordedHistory(): List<Long> {
        val ids = listOf(
            walk(hours(0), 0, 5),
            walk(hours(6), 5, 0),
            walk(hours(30), 0, 4),
            walk(hours(36), 4, 0, lonOffset = 0.01),
            walk(hours(54), 0, 5, lonOffset = 0.01),
            walk(hours(60), 5, 0),
            walk(hours(78), 0, 3),
            walk(hours(84), 3, 0),
        )
        val dao = db.derivedDao()
        assertTrue("the fixture holds several places", dao.clustersOnce().size >= 3)
        assertTrue("and an interval between each pair", dao.intervalsOnce().size >= ids.size - 1)
        assertEquals("every track's two endpoints are filed", 2 * ids.size, dao.membersOnce().size)
        return ids
    }

    // --- Exact agreement, where the history only grew or was rewritten in place ----

    @Test fun `a history recorded a track at a time is what one pass over it would leave`() = runTest {
        recordedHistory()

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
        ) as TrackRepository.ManualTrackResult.Saved
        assertExact()

        repository.updateManualTrack(
            entered.trackId,
            ActivityType.TRANSIT,
            // Both ends moved, so its endpoints leave the clusters they were in and join others.
            TrackRepository.ManualEnd(Coordinate(1.03, -2.0), hours(31)),
            TrackRepository.ManualEnd(Coordinate(1.05, -1.95), hours(33)),
        ) as TrackRepository.ManualTrackResult.Saved
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
        val id = places.create(test.place("Home", 1.0, -2.0))
        assertExact()
        val afterNaming = db.derivedDao().clustersOnce()

        // The two ops that reach clustering nowhere. Each must leave every row exactly as it was —
        // not merely leave the derivation *equivalent*, which a needless rebuild would also do.
        places.save(test.place("Home base", 1.0, -2.0).copy(id = id))
        places.setCategory(id, PlaceCategory.HOME)

        assertEquals("metadata writes re-derive nothing", afterNaming, db.derivedDao().clustersOnce())
        assertExact()
    }

    @Test fun `a place deleted and restored leaves the derivation it had before the delete`() = runTest {
        recordedHistory()
        val id = places.create(test.place("Home", 1.0, -2.0))
        val named = db.placeDao().allPlaces().single()
        assertExact()

        places.delete(id)
        assertExact()

        // The whole argument for clearing the link rather than keeping the boundary: the round trip
        // has to land where it started, which an O(1) delete that kept the cluster would break.
        // Snapshotting the derivation either side would say it less: the tracks and the place are
        // identical to what stood before the delete, so exact agreement at both ends *is* the round
        // trip — and it compares radius, membership and cluster identity, which a snapshot did not.
        places.restore(named)
        assertExact()
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

        store.rebuild()
        assertExact()
    }

    @Test fun `merging then unmerging leaves rows a rebuild then makes exact`() = runTest {
        val ids = recordedHistory()

        val merged = checkNotNull(repository.mergeTracks(ids[0], ids[1]))
        assertCoherent()

        repository.unmergeTracks(merged, ids[0], ids[1])
        assertCoherent()

        store.rebuild()
        assertExact()
    }

    @Test fun `a run of every mutation in turn leaves rows a rebuild then makes exact`() = runTest {
        // The case single-mutation tests cannot reach: each repair judges a seam against what the
        // last one left, so an error that survives one step is carried into the next.
        val ids = recordedHistory()
        places.create(test.place("Home", 1.0, -2.0))
        repository.deleteTrack(ids[2])
        val merged = checkNotNull(repository.mergeTracks(ids[4], ids[5]))
        repository.setActivityType(ids[6], ActivityType.DRIVING)
        val split = checkNotNull(repository.splitTrack(ids[7], hours(84) + 15_000L))
        repository.restoreTrack(ids[2])
        repository.unsplitTracks(ids[7], split)
        repository.unmergeTracks(merged, ids[4], ids[5])

        assertCoherent()

        store.rebuild()
        assertExact()
    }
}
