package io.github.valeronm.breadcrumb.data

import androidx.test.core.app.ApplicationProvider
import io.github.valeronm.breadcrumb.data.db.AppDatabase
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

    /** The derivation as the app used to compute it, for the same history. */
    private suspend fun fresh(activeStartedAt: Long? = null): StayDeriver.Derivation =
        StayDeriver.derive(
            tracks = db.trackDao().endpointsOnce().map { it.toTrackEnd() },
            liveness = db.livenessDao().allEvents().mapNotNull { it.toLiveness() },
            nowMs = now,
            activeTrack = activeStartedAt?.let { StayDeriver.ActiveTrack(it) },
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
            places = db.placeDao().allPlaces(),
            liveness = db.livenessDao().allEvents(),
            nowMs = now,
            activeStartedAt = activeStartedAt,
        )

    @Test fun `rows read back are the derivation, intervals and clusters alike`() = runTest {
        places.create("Home", 1.0, -2.0, TEST_START, PlaceClusterer.DEFAULT_RADIUS_M)
        track(TEST_START)
        track(TEST_START + 3 * 60 * 60_000L)
        track(TEST_START + 6 * 60 * 60_000L)
        store.rebuild(now)

        val fresh = fresh()
        val read = read()

        assertEquals(fresh.intervals, read.intervals)
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
        assertEquals(fresh().intervals, read.intervals)
        assertEquals(derived.intervalsOnce().size + 1, read.intervals.size)
        assertNull("the trailing stay is open", read.intervals.last().end)
    }

    @Test fun `a recording track closes the trailing stay where it began`() = runTest {
        track(TEST_START)
        store.rebuild(now)

        val active = TEST_START + 60 * 60_000L
        val read = read(active)
        assertEquals(fresh(active).intervals, read.intervals)
        assertEquals(active, read.intervals.single().end)
    }

    @Test fun `a named place keeps its position, so its cluster still resolves to it`() = runTest {
        // PlaceResolver maps seedIndex positionally, so a cluster read back must land at the index
        // of its place in the places list — not merely somewhere in the list.
        places.create("Home", 1.0, -2.0, TEST_START, PlaceClusterer.DEFAULT_RADIUS_M)
        places.create("Far", 9.0, -9.0, TEST_START, PlaceClusterer.DEFAULT_RADIUS_M)
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
