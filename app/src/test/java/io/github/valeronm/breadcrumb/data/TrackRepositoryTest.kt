package io.github.valeronm.breadcrumb.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.valeronm.breadcrumb.data.db.Track
import io.github.valeronm.breadcrumb.data.export.GpxParser
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.IgnoreReason
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs

/**
 * The aggregates denormalized onto the track row must never drift from the points they summarize —
 * the timeline reads them instead of counting, so a stale value is a wrong track list, and a stale
 * distance means the keep-thresholds discard a real track. Every path that changes a track's points
 * is checked here against a recount from the points themselves.
 */
@RunWith(RobolectricTestRunner::class)
class TrackRepositoryTest {

    private val test = TestDb()
    private val repository get() = test.repository
    private val dao get() = test.dao

    @After fun tearDown() = test.close()

    /** What the stored aggregates should be, recomputed from the track's points on the spot. */
    private suspend fun assertStatsMatchPoints(trackId: Long) {
        val track = dao.track(trackId)!!
        val expected = TrackStats.of(dao.allPointsFor(trackId))
        assertEquals(expected.pointCount, track.pointCount)
        assertEquals(expected.ignoredCount, track.ignoredCount)
        assertEquals(expected.distanceMeters, track.distanceMeters, 0.5)
        assertEquals(expected.startLat, track.startLat)
        assertEquals(expected.startLon, track.startLon)
        assertEquals(expected.endLat, track.endLat)
        assertEquals(expected.endLon, track.endLon)
    }

    @Test fun `finishing a track writes the aggregates the recorder never wrote`() = runTest {
        val id = repository.startTrack(ActivityType.WALKING, TEST_START)
        repository.addPoints((0..5).map { test.point(id, it) })
        // The recorder writes no distance while it records — the row is still zeroed here.
        assertEquals(0.0, dao.track(id)!!.distanceMeters, 0.0)

        repository.finishTrack(id, TEST_START + 60_000)

        val track = dao.track(id)!!
        assertNotNull(track.endedAt)
        assertNull("a real walk must not be discarded", track.discardedAt)
        assertEquals(6, track.pointCount)
        assertStatsMatchPoints(id)
    }

    @Test fun `ignored fixes are counted separately and excluded from the endpoints`() = runTest {
        val id = repository.startTrack(ActivityType.WALKING, TEST_START)
        repository.addPoints(
            listOf(
                test.point(id, 0, ignored = true, lat = 0.0), // a stray, nowhere near the walk
                test.point(id, 1),
                test.point(id, 2),
                test.point(id, 3),
                test.point(id, 4),
            ),
        )

        repository.finishTrack(id, TEST_START + 60_000)

        val track = dao.track(id)!!
        assertEquals(4, track.pointCount)
        assertEquals(1, track.ignoredCount)
        assertEquals(1.001, track.startLat!!, 1e-9) // the first *good* point, not the stray
        assertStatsMatchPoints(id)
    }

    /**
     * The crash path. A track left open by a kill has a zeroed distance on its row (the recorder
     * never wrote one), so `finalizeDangling` must recompute from the points before applying the
     * keep rule — judging it on the row would discard every recovered track as zero-length.
     */
    @Test fun `finalizeDangling recomputes a crashed track instead of discarding it`() = runTest {
        val id = repository.startTrack(ActivityType.WALKING, TEST_START)
        repository.addPoints((0..5).map { test.point(id, it) })

        repository.finalizeDangling(exceptTrackId = null)

        val track = dao.track(id)!!
        assertNotNull("the dangling track is closed", track.endedAt)
        assertNull("and kept — its distance came from the points, not the stale row", track.discardedAt)
        assertStatsMatchPoints(id)
    }

    @Test fun `the track being recorded is left alone by finalizeDangling`() = runTest {
        val id = repository.startTrack(ActivityType.WALKING, TEST_START)
        repository.addPoints((0..5).map { test.point(id, it) })

        repository.finalizeDangling(exceptTrackId = id)

        assertNull("still recording", dao.track(id)!!.endedAt)
    }

    @Test fun `a track too short to keep is discarded, with its aggregates still written`() = runTest {
        val id = repository.startTrack(ActivityType.WALKING, TEST_START)
        // Three points a meter apart over 20 s: enough points to escape the purge floor, but under
        // every keep threshold.
        repository.addPoints(
            listOf(
                test.point(id, 0, lat = 1.0),
                test.point(id, 1, lat = 1.000009),
                test.point(id, 2, lat = 1.000018),
            ),
        )

        repository.finishTrack(id, TEST_START + 20_000)

        val track = dao.track(id)!!
        assertEquals(Track.REASON_FILTERED, track.discardReason)
        assertStatsMatchPoints(id)
    }

    @Test fun `a track with two points or fewer is purged outright, not discarded`() = runTest {
        val id = repository.startTrack(ActivityType.WALKING, TEST_START)
        repository.addPoints(listOf(test.point(id, 0), test.point(id, 1)))

        repository.finishTrack(id, TEST_START + 10_000)

        assertNull("nothing to review, so no Recently deleted row", dao.track(id))
        assertEquals("its points cascade away with it", 0, dao.allPointsFor(id).size)
    }

    @Test fun `ignored fixes count against the purge floor - a noisy track is discarded instead`() = runTest {
        val id = repository.startTrack(ActivityType.WALKING, TEST_START)
        // No good points at all, but a body of rejected fixes: reviewable evidence, so it goes to
        // Recently deleted (where the map marks the noisy fixes) rather than being destroyed.
        repository.addPoints((0..4).map { test.point(id, it, ignored = true) })

        repository.finishTrack(id, TEST_START + 50_000)

        val track = dao.track(id)!!
        assertEquals(Track.REASON_FILTERED, track.discardReason)
        assertEquals(5, track.ignoredCount)
        assertStatsMatchPoints(id)
    }

    @Test fun `merging two tracks recomputes the merged track's aggregates`() = runTest {
        val first = repository.startTrack(ActivityType.WALKING, TEST_START)
        repository.addPoints((0..3).map { test.point(first, it) })
        repository.finishTrack(first, TEST_START + 60_000)
        val second = repository.startTrack(ActivityType.WALKING, TEST_START + 120_000)
        repository.addPoints((4..7).map { test.point(second, it) })
        repository.finishTrack(second, TEST_START + 180_000)

        val mergedId = repository.mergeTracks(first, second)!!

        val merged = dao.track(mergedId)!!
        assertEquals(8, merged.pointCount)
        // The join is a segment break, so the stay between the two halves isn't counted as travel.
        assertStatsMatchPoints(mergedId)
        assertEquals(Track.REASON_MERGED, dao.track(first)!!.discardReason)
        assertEquals(Track.REASON_MERGED, dao.track(second)!!.discardReason)
    }

    @Test fun `repairing a leading stray updates the counts and the distance together`() = runTest {
        val id = repository.startTrack(ActivityType.DRIVING, TEST_START)
        // A cold-start fix 20 km away, then a normal drive — the stray the repair is for.
        repository.addPoints(
            listOf(test.point(id, 0, lat = 1.2)) + (1..5).map { test.point(id, it) },
        )
        repository.finishTrack(id, TEST_START + 60_000)

        val dropped = repository.repairLeadingPoints(id)

        assertEquals(1, dropped)
        val track = dao.track(id)!!
        assertEquals(1, track.ignoredCount)
        assertEquals(5, track.pointCount)
        assertEquals(1.001, track.startLat!!, 1e-9) // the stray is no longer the start
        assertStatsMatchPoints(id)
    }

    // --- Edge stays -----------------------------------------------------------------------------

    /** A fix every 10 s advancing [stepLat] north, carrying the Doppler [speed] that pace implies. */
    private fun linePoints(
        trackId: Long,
        fromIndex: Int,
        count: Int,
        fromLat: Double,
        stepLat: Double,
        speed: Float,
    ) = (0 until count).map { i ->
        test.point(trackId, fromIndex + i, lat = fromLat + i * stepLat).copy(speed = speed)
    }

    /** 14 m per fix — walking pace. */
    private fun walkPoints(trackId: Long, fromIndex: Int, count: Int, fromLat: Double) =
        linePoints(trackId, fromIndex, count, fromLat, stepLat = 0.000126, speed = 1.4f)

    /** 90 m per fix — vehicle pace, fast enough that one fix carries its speed bin alone. */
    private fun drivePoints(trackId: Long, fromIndex: Int, count: Int, fromLat: Double) =
        linePoints(trackId, fromIndex, count, fromLat, stepLat = 0.000809, speed = 9f)

    /** A fix every 10 s jittering ±15 m around [lat] at standstill Doppler speed. */
    private fun lingerPoints(trackId: Long, fromIndex: Int, count: Int, lat: Double) =
        (0 until count).map { i ->
            test.point(trackId, fromIndex + i, lat = lat + if (i % 2 == 0) 0.000135 else -0.000135)
                .copy(speed = 0.1f)
        }

    private suspend fun discardedTracks(): List<Track> =
        dao.observeDiscardedSummaries().first().map { dao.track(it.id)!! }

    /** 10 min of walking (840 m) then 6 min lingering where the walk ended — the AR-lag tail.
     *  Returns the track's raw end time (96 fixes, one per 10 s). */
    private suspend fun addWalkThenLingerTail(id: Long): Long {
        repository.addPoints(
            walkPoints(id, 0, 60, fromLat = 1.0) +
                lingerPoints(id, 60, 36, lat = 1.0 + 60 * 0.000126),
        )
        return TEST_START + 96 * 10_000L
    }

    @Test fun `finishing a track takes its lingering tail off the path`() = runTest {
        val id = repository.startTrack(ActivityType.WALKING, TEST_START)
        val rawEnd = addWalkThenLingerTail(id)
        repository.finishTrack(id, rawEnd)

        val track = dao.track(id)!!
        assertNull("the walk itself is kept", track.discardedAt)
        // Nothing is moved or deleted: all 96 fixes are still on the track, the tail among them.
        assertEquals(96, track.pointCount + track.ignoredCount)
        assertTrue("the tail is off the path", track.ignoredCount >= 30)
        assertTrue(discardedTracks().isEmpty())
        // The clock ends at the speed collapse — the walk-to-linger transition, bin-quantized.
        val walkEndTs = TEST_START + 59 * 10_000L
        assertTrue(track.endedAt!! in walkEndTs..(walkEndTs + 60_000))
        // ...and that is where the last good fix is, so the row and the points agree.
        assertEquals(track.endedAt, dao.pointsFor(id).last().timestamp)
        assertStatsMatchPoints(id)
        assertEquals(
            repository.trackPointsFor(id).edgeStay.map { it.id },
            dao.allPointsFor(id).filter { it.timestamp > track.endedAt!! }.map { it.id },
        )
    }

    @Test fun `a track with nothing to cut keeps every fix`() = runTest {
        val id = repository.startTrack(ActivityType.WALKING, TEST_START)
        repository.addPoints(walkPoints(id, 0, 90, fromLat = 1.0))
        val endedAt = TEST_START + 90 * 10_000L
        repository.finishTrack(id, endedAt)

        val track = dao.track(id)!!
        assertEquals(90, track.pointCount)
        assertEquals(0, track.ignoredCount)
        assertEquals(endedAt, track.endedAt)
    }

    @Test fun `a track that starts lingering loses its head instead of its tail`() = runTest {
        val id = repository.startTrack(ActivityType.WALKING, TEST_START)
        // 15 min lingering before departure, then 10 min of walking. The linger must be long:
        // the departure passes *through* the corral, dragging every dwell window's exit ~40 m
        // along the walk, and only a long span dilutes that below the drift gate — a short
        // start-stay followed by walking away is deliberately not detectable (transit-shaped).
        repository.addPoints(
            lingerPoints(id, 0, 90, lat = 1.0) +
                walkPoints(id, 90, 60, fromLat = 1.0),
        )
        repository.finishTrack(id, TEST_START + 150 * 10_000L)

        val walk = dao.track(id)!!
        assertNull(walk.discardedAt)
        // startedAt moved up to the departure — the start of sustained movement, bin-quantized
        // (TEST_START is not bin-aligned, so up to one speed bin of slop either way).
        val walkStartTs = TEST_START + 90 * 10_000L
        assertTrue(walk.startedAt in (walkStartTs - 60_000)..(walkStartTs + 30_000))
        assertEquals(walk.startedAt, dao.pointsFor(id).first().timestamp)
        assertTrue(repository.trackPointsFor(id).edgeStay.all { it.timestamp < walk.startedAt })
        assertStatsMatchPoints(id)
    }

    @Test fun `the sweep hands back fixes the current rule does not flag`() = runTest {
        val id = repository.startTrack(ActivityType.WALKING, TEST_START)
        repository.addPoints(walkPoints(id, 0, 90, fromLat = 1.0))
        repository.finishTrack(id, TEST_START + 90 * 10_000L)
        // A verdict from a rule that has since moved: a tail flagged on a track that walks end to
        // end, with the clock pulled in to match, exactly as the older rule would have left it.
        val tail = dao.allPointsFor(id).takeLast(20)
        tail.forEach { dao.setIgnored(it.id, IgnoreReason.EDGE_STAY.code) }
        dao.closeTrack(id, tail.first().timestamp)

        repository.sweepEdgeStays()

        val restored = dao.track(id)!!
        assertEquals(90, restored.pointCount)
        assertEquals(0, restored.ignoredCount)
        // The raw end time is gone with the old cut, so the clock goes back to the last fix.
        assertEquals(TEST_START + 89 * 10_000L, restored.endedAt)
        assertStatsMatchPoints(id)
    }

    /** A parked vehicle's settling drift: a 30 m excursion out and back on a 60 s cycle, at the
     *  fixture's 10 s cadence, so displacement over the detector's 30 s lookback reads ~1 m/s —
     *  over the moving bar, under the floor a vehicle's own speed allows. */
    private fun parkedDriftPoints(trackId: Long, fromIndex: Int, count: Int, lat: Double) =
        (0 until count).map { i ->
            val phase = (i * 10 % 60) / 60.0
            test.point(trackId, fromIndex + i, lat = lat + 0.00027 * (1 - abs(2 * phase - 1)))
                .copy(speed = 1.0f)
        }

    @Test fun `retyping a track to a vehicle re-derives its overrun under the vehicle floor`() = runTest {
        // 5 min of driving, then 15 min parked. On foot there is no speed that rules out drift, so
        // the drift keeps voting "moving" and nothing is trimmed; a vehicle's floor calls the same
        // fixes a standstill. The verdict has to follow the retype that changed the tuning.
        val id = repository.startTrack(ActivityType.WALKING, TEST_START)
        val drive = drivePoints(id, 0, 30, fromLat = 1.0)
        repository.addPoints(drive + parkedDriftPoints(id, 30, 90, lat = drive.last().latitude))
        repository.finishTrack(id, TEST_START + 120 * 10_000L)
        assertEquals("nothing to trim under the foot rule", 0, dao.track(id)!!.ignoredCount)

        repository.setActivityType(id, ActivityType.TAXI)

        val after = dao.track(id)!!
        assertEquals("TAXI", after.activityType)
        assertTrue("the parked tail is off the path", after.ignoredCount > 60)
        // The clock ends where the drive did, and the row's aggregates followed the flags.
        assertTrue(after.endedAt!! < TEST_START + 40 * 10_000L)
        assertStatsMatchPoints(id)
    }

    @Test fun `retyping within the same tuning leaves the stored verdict alone`() = runTest {
        val id = repository.startTrack(ActivityType.WALKING, TEST_START)
        repository.finishTrack(id, addWalkThenLingerTail(id))
        val before = dao.track(id)!!

        repository.setActivityType(id, ActivityType.RUNNING)

        val after = dao.track(id)!!
        assertEquals("RUNNING", after.activityType)
        assertEquals(before.ignoredCount, after.ignoredCount)
        assertEquals(before.endedAt, after.endedAt)
    }

    @Test fun `the track screen's load splits a track's points three ways`() = runTest {
        val id = repository.startTrack(ActivityType.WALKING, TEST_START)
        val rawEnd = addWalkThenLingerTail(id)
        // A rejected fix among the good ones — a bad reading, which the map marks, as against the
        // overrun, which it grays. The two must not land in the same slice.
        repository.addPoints(
            listOf(
                test.point(id, 20, ignored = true, lat = 0.0)
                    .copy(ignoreReason = IgnoreReason.JUMP.code),
            ),
        )
        repository.finishTrack(id, rawEnd)

        val split = repository.trackPointsFor(id)
        val all = dao.allPointsFor(id)
        assertEquals("the three slices are the whole track", all.size, split.good.size + split.noisy.size + split.edgeStay.size)
        assertTrue("the path holds no ignored fix", split.good.none { it.ignored })
        assertEquals(listOf(IgnoreReason.JUMP.code), split.noisy.map { it.ignoreReason })
        assertTrue("the tail is its own slice", split.edgeStay.isNotEmpty())
        assertTrue(split.edgeStay.all { it.ignoreReason == IgnoreReason.EDGE_STAY.code })
    }

    @Test fun `merging keeps the overrun the earlier track lost in the middle`() = runTest {
        val first = repository.startTrack(ActivityType.WALKING, TEST_START)
        repository.finishTrack(first, addWalkThenLingerTail(first))
        val trimmed = dao.track(first)!!.ignoredCount
        assertTrue(trimmed > 0)
        val secondStart = TEST_START + 200 * 10_000L
        val second = repository.startTrack(ActivityType.WALKING, secondStart)
        repository.addPoints(walkPoints(second, 200, 60, fromLat = 1.01))
        repository.finishTrack(second, secondStart + 60 * 10_000L)

        val mergedId = repository.mergeTracks(first, second)!!

        // The first track's tail is now mid-track — a stop the merged track paused at, and not
        // something an edge rule would ever re-derive. It stays off the path.
        val merged = dao.track(mergedId)!!
        assertEquals(trimmed, merged.ignoredCount)
        assertStatsMatchPoints(mergedId)
    }

    @Test fun `an imported GPX track ending in a linger is trimmed via derived speed`() = runTest {
        // GPX carries no Doppler speed — the boundary must come from the displacement lookback.
        // The linger jitters ±9 m so its 30 s net displacement stays under the moving threshold.
        val walkEnd = 1.0 + 60 * 0.000126
        val points = (0 until 60).map { i ->
            GpxParser.ImportPoint(
                lat = 1.0 + i * 0.000126, lon = -2.0, ele = null,
                timeMs = TEST_START + i * 10_000L, speed = null, segmentStart = false,
            )
        } + (0 until 36).map { i ->
            GpxParser.ImportPoint(
                lat = walkEnd + if (i % 2 == 0) 0.00008 else -0.00008, lon = -2.0, ele = null,
                timeMs = TEST_START + (60 + i) * 10_000L, speed = null, segmentStart = false,
            )
        }
        val file = listOf(
            GpxParser.ImportableTrack(
                activityTypeName = "WALKING",
                startedAt = TEST_START,
                endedAt = TEST_START + 95 * 10_000L,
                points = points,
            ),
        )

        assertEquals(1, repository.importTracks(file).imported)

        val importedId = dao.allTrackIds().single()
        val head = dao.track(importedId)!!
        val walkEndTs = TEST_START + 59 * 10_000L
        assertTrue(head.endedAt!! in walkEndTs..(walkEndTs + 90_000))
        assertTrue(repository.trackPointsFor(importedId).edgeStay.isNotEmpty())
        assertStatsMatchPoints(importedId)
        // The file's own span no longer matches the track's, so the duplicate check has to work
        // off the points — or the same file would import again as a second copy.
        assertEquals(0, repository.importTracks(file).imported)
    }

    /** A file holding one straight walk: [count] fixes from [fromIndex], on the same line the
     *  recorded-track tests walk, so an imported journey and a recorded one are the same shape. */
    private fun importableWalk(fromIndex: Int, count: Int) = GpxParser.ImportableTrack(
        activityTypeName = "WALKING",
        startedAt = TEST_START + fromIndex * 10_000L,
        endedAt = TEST_START + (fromIndex + count - 1) * 10_000L,
        // A file has no track to belong to yet, hence the placeholder id.
        points = walkPoints(0, fromIndex, count, fromLat = 1.0 + fromIndex * 0.000126).map {
            GpxParser.ImportPoint(
                lat = it.latitude, lon = it.longitude, ele = it.altitude,
                timeMs = it.timestamp, speed = it.speed, segmentStart = it.segmentStart,
            )
        },
    )

    @Test fun `a file overlapping an existing track is skipped, not laid over it`() = runTest {
        assertEquals(1, repository.importTracks(listOf(importableWalk(0, 60))).imported)

        // The same journey cut differently: a span of its own, sharing 30 fixes with the first.
        val counts = repository.importTracks(listOf(importableWalk(30, 60)))

        assertEquals(0, counts.imported)
        assertEquals("not an exact duplicate — the spans differ", 0, counts.duplicates)
        assertEquals(1, counts.overlapping)
        assertEquals(1, dao.allTrackIds().size)
    }

    @Test fun `back-to-back legs touching at one instant both import`() = runTest {
        // The second leg starts at the exact timestamp the first ends. Touching is not overlapping,
        // or a file split into legs would import the first and reject every one after it.
        val counts = repository.importTracks(listOf(importableWalk(0, 60), importableWalk(59, 60)))

        assertEquals(2, counts.imported)
        assertEquals(0, counts.overlapping)
    }

    @Test fun `a span held only by a deleted track imports`() = runTest {
        val recorded = repository.startTrack(ActivityType.WALKING, TEST_START)
        repository.addPoints(walkPoints(recorded, 0, 60, fromLat = 1.0))
        repository.finishTrack(recorded, TEST_START + 60 * 10_000L)
        repository.deleteTrack(recorded)

        // Same span, so this is refused by both checks while the track is live — Recently deleted
        // holds tracks on their way out, and must not keep the period reserved against an import.
        val counts = repository.importTracks(listOf(importableWalk(0, 60)))

        assertEquals(1, counts.imported)
        assertEquals(0, counts.duplicates)
        assertEquals(0, counts.overlapping)
    }

    // --- Delete, restore, purge, unmerge --------------------------------------------------------

    /** A finished, kept 6-point walk; [fromIndex] spaces it out from other tracks in the test. */
    private suspend fun finishedWalk(fromIndex: Int): Long {
        val id = repository.startTrack(ActivityType.WALKING, TEST_START + fromIndex * 10_000L)
        repository.addPoints((fromIndex..fromIndex + 5).map { test.point(id, it) })
        repository.finishTrack(id, TEST_START + (fromIndex + 6) * 10_000L)
        return id
    }

    @Test fun `unmerging drops the copy and returns both originals to the timeline`() = runTest {
        val first = finishedWalk(0)
        val second = finishedWalk(12)
        val mergedId = repository.mergeTracks(first, second)!!

        repository.unmergeTracks(mergedId, first, second)

        assertNull("the merged copy is gone", dao.track(mergedId))
        assertEquals("its copied points cascade away with it", 0, dao.allPointsFor(mergedId).size)
        for (id in listOf(first, second)) {
            val track = dao.track(id)!!
            assertNull("back on the timeline", track.discardedAt)
            assertNull(track.discardReason)
            assertStatsMatchPoints(id)
        }
    }

    @Test fun `a user delete is soft, and restore undoes it`() = runTest {
        val id = finishedWalk(0)

        repository.deleteTrack(id)
        assertEquals(Track.REASON_DELETED, dao.track(id)!!.discardReason)

        repository.restoreTrack(id)
        val restored = dao.track(id)!!
        assertNull(restored.discardedAt)
        assertNull(restored.discardReason)
        assertStatsMatchPoints(id)
    }

    @Test fun `the retention purge deletes only tracks discarded before the window`() = runTest {
        val old = finishedWalk(0)
        val recent = finishedWalk(12)
        val now = System.currentTimeMillis()
        dao.setDiscarded(old, now - 15 * 86_400_000L, Track.REASON_DELETED)
        dao.setDiscarded(recent, now - 86_400_000L, Track.REASON_DELETED)

        repository.purgeOldDiscarded()

        assertNull("past the 14-day window", dao.track(old))
        assertEquals("its points cascade away", 0, dao.allPointsFor(old).size)
        assertNotNull("still inside the window, still restorable", dao.track(recent))
    }

    @Test fun `clear all purges every discarded track and nothing kept`() = runTest {
        val kept = finishedWalk(0)
        val deleted = finishedWalk(12)
        repository.deleteTrack(deleted)

        repository.purgeAllDiscarded()

        assertNull(dao.track(deleted))
        assertNull("the kept track is untouched", dao.track(kept)!!.discardedAt)
    }

    @Test fun `a retype with no overrun to find writes the name and leaves the row alone`() = runTest {
        // WALKING → TAXI crosses the tuning boundary, so the re-derivation runs — on a track with
        // no stay to detect, which must come back with the row untouched rather than reshaped.
        val id = finishedWalk(0)
        val before = dao.track(id)!!

        repository.setActivityType(id, ActivityType.TAXI)

        val after = dao.track(id)!!
        assertEquals("TAXI", after.activityType)
        assertEquals(before.pointCount, after.pointCount)
        assertEquals(before.distanceMeters, after.distanceMeters, 0.0)
        assertStatsMatchPoints(id)
    }

    // --- Leading-stray repair: the loop and its bound -------------------------------------------

    @Test fun `repair drops a run of leading strays one by one`() = runTest {
        val id = repository.startTrack(ActivityType.DRIVING, TEST_START)
        repository.addPoints(
            listOf(
                test.point(id, 0, lat = 3.0), // ~217 km from the next fix
                test.point(id, 1, lat = 1.05), // still ~5 km from the drive it precedes
            ) + (2..7).map { test.point(id, it) },
        )
        repository.finishTrack(id, TEST_START + 80_000)

        assertEquals(2, repository.repairLeadingPoints(id))

        val track = dao.track(id)!!
        assertEquals(2, track.ignoredCount)
        assertEquals(6, track.pointCount)
        assertEquals(1.002, track.startLat!!, 1e-9) // the first real fix is the start now
        assertStatsMatchPoints(id)
    }

    @Test fun `repair stops at its safety bound even when more strays lead the track`() = runTest {
        val id = repository.startTrack(ActivityType.DRIVING, TEST_START)
        // Six strays, each seam ~4.4x the next, so every head check fires — but the loop is
        // bounded, and only five may be repaired.
        val strayLats = listOf(21.035, 5.535, 2.035, 1.235, 1.055, 1.015)
        repository.addPoints(
            strayLats.mapIndexed { i, lat -> test.point(id, i, lat = lat) } +
                (6..11).map { test.point(id, it) },
        )
        repository.finishTrack(id, TEST_START + 120_000)

        assertEquals(5, repository.repairLeadingPoints(id))

        assertEquals(5, dao.track(id)!!.ignoredCount)
        assertStatsMatchPoints(id)
    }

    // --- Keep thresholds: the extent gate -------------------------------------------------------

    @Test fun `the extent threshold reaches the keep verdict`() = runTest {
        // A 6-point walk spreads ~550 m — clears duration and length, fails only the raised
        // extent gate.
        Settings.setMinTrackExtentM(ApplicationProvider.getApplicationContext<Context>(), 10_000)
        val id = repository.startTrack(ActivityType.WALKING, TEST_START)
        repository.addPoints((0..5).map { test.point(id, it) })

        repository.finishTrack(id, TEST_START + 60_000)

        assertEquals(Track.REASON_FILTERED, dao.track(id)!!.discardReason)
    }
}
