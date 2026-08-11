package io.github.valeronm.breadcrumb.data

import androidx.test.core.app.ApplicationProvider
import io.github.valeronm.breadcrumb.data.db.AppDatabase
import io.github.valeronm.breadcrumb.domain.Coordinate
import io.github.valeronm.breadcrumb.domain.PlaceClusterer
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.toLiveness
import io.github.valeronm.breadcrumb.domain.toTrackEnd
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
 * **The whole claim of persisting the derivation**: rows read back say what deriving from scratch
 * says. Everything downstream — place resolution, the day slicing, the totals, the journeys — is
 * unchanged by the move, and can only stay unchanged if this holds.
 *
 * The stored side deliberately runs through `DerivationStore.rebuild`, not through hand-built rows:
 * a fixture agreeing with the reader proves the pair of them consistent with each other and nothing
 * about what is written on a phone.
 */
@RunWith(RobolectricTestRunner::class)
class DerivedReadModelTest {

    private val test = TestDb()
    private val db: AppDatabase = test.db
    private val store = DerivationStore(ApplicationProvider.getApplicationContext(), test.db)
    private val places = PlaceRepository(ApplicationProvider.getApplicationContext(), test.db)
    private val derived = test.db.derivedDao()
    private val now = TEST_START + 12 * 60 * 60_000L

    @After fun tearDown() = test.close()

    private suspend fun track(startedAt: Long): Long = test.walk(startedAt, 0, 5)

    /**
     * The intervals **between finished tracks** as a fresh pass over the same history gives them —
     * which is all of them, the trailing stay being no row's and no pass's ([StayDeriver.tail]).
     * The cases below that reach it therefore compare the read-back rows against this and the tail
     * against `tail`, rather than against one reference holding both.
     */
    private suspend fun fresh(): StayDeriver.Derivation =
        StayDeriver.derive(
            tracks = db.trackDao().endpointsOnce().map { it.toTrackEnd() },
            liveness = db.livenessDao().allEvents().mapNotNull { it.toLiveness() },
            nowMs = now,
            distance = AndroidDistance,
            placePins = PlaceClusterer.seedsOf(db.placeDao().allPlaces()),
        )

    /**
     * The derivation as it is now read — through [DerivationStore.observeStored], the query the
     * screens are fed by, so a snapshot the app never takes cannot pass here.
     */
    private suspend fun read(activeStartedAt: Long? = null): StayDeriver.Derivation =
        DerivedReadModel.derivationOf(
            stored = store.observeStored().first(),
            liveness = db.livenessDao().allEvents(),
            nowMs = now,
            activeStartedAt = activeStartedAt,
        )

    @Test fun `rows read back are the derivation, intervals and clusters alike`() = runTest {
        places.create(test.place("Home", 1.0, -2.0))
        track(TEST_START)
        track(TEST_START + 3 * 60 * 60_000L)
        track(TEST_START + 6 * 60 * 60_000L)
        store.rebuild(now)

        val fresh = fresh()
        val read = read()

        // Bar the trailing stay, which the reader appends and no pass over the pairs produces.
        assertEquals(fresh.intervals, read.intervals.dropLast(1))
        assertEquals(fresh.clusters.map { it.anchor }, read.clusters.map { it.anchor })
        assertEquals(fresh.clusters.map { it.seedIndex }, read.clusters.map { it.seedIndex })
        assertEquals(fresh.clusters.map { it.visitCount }, read.clusters.map { it.visitCount })
        assertEquals(fresh.clusters.map { it.radiusM }, read.clusters.map { it.radiusM })
        fresh.clusters.forEachIndexed { i, cluster ->
            assertEquals(cluster.centroid.lat, read.clusters[i].centroid.lat, 1e-9)
            assertEquals(cluster.centroid.lon, read.clusters[i].centroid.lon, 1e-9)
            assertEquals(cluster.members.toSet(), read.clusters[i].members.toSet())
        }
    }

    @Test fun `the trailing stay is synthesized, not stored`() = runTest {
        track(TEST_START)
        track(TEST_START + 3 * 60 * 60_000L)
        store.rebuild(now)

        // Stored rows stop at the last track; the reader adds the stay still running after it.
        val read = read()
        assertEquals("the pairs are what a fresh pass gives", fresh().intervals, read.intervals.dropLast(1))
        assertEquals(derived.intervalsOnce().size + 1, read.intervals.size)
        assertNull("the trailing stay is open", read.intervals.last().end)
    }

    @Test fun `a recording track closes the trailing stay where it began`() = runTest {
        track(TEST_START)
        store.rebuild(now)

        val active = TEST_START + 60 * 60_000L
        val read = read(active)
        // One track, so there is no pair to derive an interval between — everything on screen here
        // is the trailing stay, which is exactly the reading no stored row can hold.
        assertTrue("a lone track leaves no pair", fresh().intervals.isEmpty())
        assertEquals(active, read.intervals.single().end)
    }

    /**
     * **Where the trailing stay says the reader is** — the cluster the newest track ended in, which
     * is the whole of what that row places on a map or a timeline.
     *
     * Asked against a history holding more than one cluster, deliberately: with a single cluster
     * every id is 0 and the claim passes on any implementation, including one that took the *first*
     * endpoint or the wrong track's. The rule now lives in `DerivedReadModel.tailAnchor`, which picks
     * the last non-start membership and rests on `DerivedDao.membersOnce` ordering by `atMs` — an
     * ordering nothing else here would notice changing.
     */
    @Test fun `the trailing stay belongs to the cluster the newest track ended in`() = runTest {
        test.walk(TEST_START, 0, 5)
        // A second walk ending a neighbourhood away, so the history holds two clusters and the
        // newest track's end is not also the oldest's.
        val newest = test.walk(TEST_START + 3 * 60 * 60_000L, 0, 5, lonOffset = 0.01)
        store.rebuild(now)

        val read = read()
        val tail = read.intervals.last()
        val endedIn = db.derivedDao().membersForTracks(listOf(newest)).single { !it.isStart }

        assertNull("the trailing stay is the open one", tail.end)
        assertEquals(
            "it sits where that track ended",
            Coordinate(endedIn.lat, endedIn.lon),
            read.clusters[(tail as StayDeriver.Stay).clusterId].members.last(),
        )
    }

    @Test fun `a named place keeps its position, so its cluster still resolves to it`() = runTest {
        // PlaceResolver maps seedIndex positionally, so a cluster read back must land at the index
        // of its place in the places list — not merely somewhere in the list.
        places.create(test.place("Home", 1.0, -2.0))
        places.create(test.place("Far", 9.0, -9.0))
        track(TEST_START)
        store.rebuild(now)

        val read = read()
        val all = db.placeDao().allPlaces()
        all.indices.forEach { index ->
            assertEquals("a cluster stands at each place's position", index, read.clusters[index].seedIndex)
        }
        assertTrue("unnamed clusters follow the named ones", read.clusters.drop(all.size).all { it.seedIndex == null })
    }
}
