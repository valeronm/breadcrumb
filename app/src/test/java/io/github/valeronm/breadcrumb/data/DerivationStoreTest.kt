package io.github.valeronm.breadcrumb.data

import androidx.test.core.app.ApplicationProvider
import io.github.valeronm.breadcrumb.data.db.AppDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The stored derivation must say what the deriver says. Two implementations of one rule exist the
 * moment anything is persisted — the pure pass, and whatever wrote the rows — so the assertion that
 * matters is not "these rows look plausible" but **"these rows are what a fresh derive produces"**,
 * which is [assertDerivedMatchesFreshDerive] below and what every case here ends on.
 */
@RunWith(RobolectricTestRunner::class)
class DerivationStoreTest {

    private val test = TestDb()
    private val db: AppDatabase = test.db
    private val store = DerivationStore(ApplicationProvider.getApplicationContext(), test.db)
    private val places = PlaceRepository(ApplicationProvider.getApplicationContext(), test.db)
    private val derived = test.db.derivedDao()

    /** Long enough after the fixture's tracks that the history is settled behind the clock. */
    private val now = TEST_START + 12 * 60 * 60_000L

    @After fun tearDown() = test.close()

    /** A kept track from [fromIndex] to [toIndex] on the fixture's northbound line. */
    private suspend fun track(fromIndex: Int, toIndex: Int, startedAt: Long): Long =
        test.walk(startedAt, fromIndex, toIndex)

    /** [DerivedConsistency.assertMatchesFreshDerive] — the shared guard, which is where what this
     *  suite's cases all end on is defined. */
    private suspend fun assertDerivedMatchesFreshDerive() =
        DerivedConsistency.assertMatchesFreshDerive(db, now)

    /** Two walks from the same spot, three hours apart — a history with one interval in it. */
    private suspend fun twoTracks() {
        track(0, 5, TEST_START)
        track(0, 5, TEST_START + 3 * 60 * 60_000L)
    }

    @Test fun `a rebuild stores what the deriver derives`() = runTest {
        twoTracks()

        store.rebuild(now)

        assertTrue("the history has an interval between its two tracks", derived.intervalsOnce().isNotEmpty())
        assertDerivedMatchesFreshDerive()
    }

    @Test fun `the open stay is not among the stored rows`() = runTest {
        track(0, 5, TEST_START)

        store.rebuild(now)

        // One track has nothing after it but the stay still running, which closes at the clock.
        assertTrue(derived.intervalsOnce().isEmpty())
    }

    @Test fun `every place gets a cluster, and reconciling again does not give it two`() = runTest {
        places.create(test.place("Home", 1.0, -2.0))

        store.reconcile()

        val clusters = derived.clustersOnce()
        assertEquals(1, clusters.size)
        assertNotNull("the cluster stands for the place", clusters.single().placeId)
        assertEquals(1.0, clusters.single().anchorLat, 1e-9)
    }

    @Test fun `a named cluster keeps its id across a rebuild`() = runTest {
        // What a stay's place *is* — repointing it would silently rewrite the history's places.
        places.create(test.place("Home", 1.0, -2.0))
        val before = derived.namedClusters().single()

        twoTracks()
        store.rebuild(now)

        val after = derived.namedClusters().single()
        assertEquals(before.id, after.id)
        assertEquals(before.placeId, after.placeId)
        // Both tracks set off from the place and end ~550 m north of it, so its capture radius
        // claims the two starts and neither end — a count, not merely a non-zero.
        assertEquals(2, after.memberCount)
        assertEquals(2.0, after.sumLat, 1e-9)
    }

    // --- Repaired around a change, rather than derived again ------------------
    //
    // Every case here ends on the same assertion the rebuild cases do: what the mutation path left
    // behind must be what a derivation of the resulting history produces. No rebuild is called.
    //
    // These ask for **exact** agreement even of the paths that drop a track, which is more than
    // `StayLedger` promises in general — and they can, because of the fixture rather than the rule:
    // every track here begins at the same spot and ends at the same one, so no cluster's founder can
    // be removed while the cluster survives, which is the one way a repair is allowed to diverge.
    // `DerivedConsistencyTest` is where a history without that property is put through the same
    // paths, and where they are owed the weaker claim.

    @Test fun `a track finishing leaves what a rebuild would have left`() = runTest {
        twoTracks()

        assertTrue("the history has an interval between its two tracks", derived.intervalsOnce().isNotEmpty())
        assertDerivedMatchesFreshDerive()
    }

    @Test fun `deleting a track closes the seam its neighbours now share, and restoring reopens it`() = runTest {
        track(0, 5, TEST_START)
        val middle = track(0, 5, TEST_START + 3 * 60 * 60_000L)
        track(0, 5, TEST_START + 6 * 60 * 60_000L)

        test.repository.deleteTrack(middle)
        assertEquals("one interval spans where three tracks were two", 1, derived.intervalsOnce().size)
        assertDerivedMatchesFreshDerive()

        test.repository.restoreTrack(middle)
        assertEquals(2, derived.intervalsOnce().size)
        assertDerivedMatchesFreshDerive()
    }

    @Test fun `merging two tracks and unmerging them each leave the derivation of what stands`() = runTest {
        val earlier = track(0, 5, TEST_START)
        val later = track(0, 5, TEST_START + 3 * 60 * 60_000L)
        track(0, 5, TEST_START + 6 * 60 * 60_000L)

        val merged = checkNotNull(test.repository.mergeTracks(earlier, later))
        assertDerivedMatchesFreshDerive()

        test.repository.unmergeTracks(merged, earlier, later)
        assertDerivedMatchesFreshDerive()
    }

    @Test fun `splitting a track and undoing the split each leave the derivation of what stands`() = runTest {
        val id = track(0, 5, TEST_START)
        track(0, 5, TEST_START + 3 * 60 * 60_000L)

        val split = checkNotNull(test.repository.splitTrack(id, TEST_START + 30_000L))
        assertDerivedMatchesFreshDerive()

        test.repository.unsplitTracks(id, split)
        assertDerivedMatchesFreshDerive()
    }

    @Test fun `naming a place re-derives the history, and renaming it does not`() = runTest {
        twoTracks()
        val organic = derived.clustersOnce().map { it.id }

        // Naming moves the pin set, so the ground is re-clustered and the organic rows are replaced.
        val id = places.create(test.place("Home", 1.0, -2.0))
        assertTrue(
            "the organic clusters were derived again",
            derived.clustersOnce().none { it.id in organic },
        )
        assertDerivedMatchesFreshDerive()

        // A label reaches clustering nowhere, so nothing is re-derived and every row stands.
        val named = derived.clustersOnce().map { it.id }
        places.save(test.place("Home base", 1.0, -2.0).copy(id = id))
        assertEquals("a rename costs no derivation", named, derived.clustersOnce().map { it.id })
    }

    @Test fun `deleting a place and undoing it leave the derivation either side of the delete`() = runTest {
        twoTracks()
        val id = places.create(test.place("Home", 1.0, -2.0))
        val named = db.placeDao().allPlaces().single()
        val withPlace = derived.intervalsOnce().map { it.type to it.start }

        places.delete(id)
        assertTrue("its cluster went with it", derived.namedClusters().isEmpty())
        assertDerivedMatchesFreshDerive()

        places.restore(named)
        assertEquals("the round trip lands where it started", withPlace, derived.intervalsOnce().map { it.type to it.start })
        assertDerivedMatchesFreshDerive()
    }
}
