package io.github.valeronm.breadcrumb.data

import androidx.test.core.app.ApplicationProvider
import io.github.valeronm.breadcrumb.data.db.AppDatabase
import io.github.valeronm.breadcrumb.data.db.DerivedInterval
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.PlaceClusterer
import io.github.valeronm.breadcrumb.domain.StayDeriver
import io.github.valeronm.breadcrumb.domain.toLiveness
import io.github.valeronm.breadcrumb.domain.toTrackEnd
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
    private suspend fun track(fromIndex: Int, toIndex: Int, startedAt: Long): Long {
        val id = test.repository.startTrack(ActivityType.WALKING, startedAt)
        test.repository.addPoints(
            (fromIndex..toIndex).map { i ->
                test.point(id, i).copy(timestamp = startedAt + (i - fromIndex) * 10_000L)
            },
        )
        test.repository.finishTrack(id, startedAt + (toIndex - fromIndex) * 10_000L)
        return id
    }

    /**
     * Every stored interval against a derivation run from scratch over the same tracks, each one's
     * cluster resolved back to the anchor it was derived at — an id alone would compare two
     * numberings rather than two answers.
     *
     * The `when` is exhaustive on purpose: a third kind of interval must fail here rather than being
     * quietly skipped by a filter, which is what the pair of `filterIsInstance` passes this replaced
     * would have done.
     */
    private suspend fun assertDerivedMatchesFreshDerive(nowMs: Long) {
        val trackEnds = db.trackDao().endpointsOnce().map { it.toTrackEnd() }
        val fresh = StayDeriver.derive(
            tracks = trackEnds,
            liveness = db.livenessDao().allEvents().mapNotNull { it.toLiveness() },
            nowMs = nowMs,
            activeTrack = null,
            distance = AndroidDistance,
            placePins = derived.namedClusters().map { it.toSeed() },
            emitTail = false,
        )
        val clusterById = derived.clustersOnce().associateBy { it.id }
        val stored = derived.intervalsOnce()
        val rowFor = stored.associateBy { it.afterTrackId }

        assertEquals("one row per derived interval", fresh.intervals.size, stored.size)
        assertEquals(fresh.intervals.map { it.afterTrackId }, stored.map { it.afterTrackId })

        fresh.intervals.forEach { interval ->
            val row = rowFor.getValue(interval.afterTrackId)
            assertEquals(interval.start, row.start)
            assertEquals(interval.end, row.endedAt)
            when (interval) {
                is StayDeriver.Stay -> {
                    assertEquals(DerivedInterval.TYPE_STAY, row.type)
                    assertEquals(interval.provenance.name, row.provenance)
                    val anchor = fresh.clusters[interval.clusterId].anchor
                    val row2 = clusterById.getValue(row.clusterId!!)
                    assertEquals(anchor.lat, row2.anchorLat, 1e-9)
                    assertEquals(anchor.lon, row2.anchorLon, 1e-9)
                }
                is StayDeriver.Gap -> {
                    assertEquals(DerivedInterval.TYPE_GAP, row.type)
                    assertEquals(interval.reason.name, row.reason)
                    assertEquals(interval.from?.lat, row.fromLat)
                    assertEquals(interval.from?.lon, row.fromLon)
                    assertEquals(interval.to?.lat, row.toLat)
                    assertEquals(interval.to?.lon, row.toLon)
                }
            }
        }
    }

    /** Two walks from the same spot, three hours apart — a history with one interval in it. */
    private suspend fun twoTracks() {
        track(0, 5, TEST_START)
        track(0, 5, TEST_START + 3 * 60 * 60_000L)
    }

    @Test fun `a rebuild stores what the deriver derives`() = runTest {
        twoTracks()

        store.rebuild(now)

        assertTrue("the history has an interval between its two tracks", derived.intervalsOnce().isNotEmpty())
        assertDerivedMatchesFreshDerive(now)
    }

    @Test fun `the open stay is not among the stored rows`() = runTest {
        track(0, 5, TEST_START)

        store.rebuild(now)

        // One track has nothing after it but the stay still running, which closes at the clock.
        assertTrue(derived.intervalsOnce().isEmpty())
    }

    @Test fun `every place gets a cluster, and asking twice does not give it two`() = runTest {
        places.create("Home", 1.0, -2.0, TEST_START, PlaceClusterer.DEFAULT_RADIUS_M)

        store.linkPlacesToClusters()
        store.linkPlacesToClusters()

        val clusters = derived.clustersOnce()
        assertEquals(1, clusters.size)
        assertNotNull("the cluster stands for the place", clusters.single().placeId)
        assertEquals(1.0, clusters.single().anchorLat, 1e-9)
    }

    @Test fun `a named cluster keeps its id across a rebuild`() = runTest {
        // What a stay's place *is* — repointing it would silently rewrite the history's places.
        places.create("Home", 1.0, -2.0, TEST_START, PlaceClusterer.DEFAULT_RADIUS_M)
        store.linkPlacesToClusters()
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
}
