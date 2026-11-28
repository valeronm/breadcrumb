package io.github.valeronm.breadcrumb.data

import io.github.valeronm.breadcrumb.data.db.Track
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.timeline.TimelineItem
import io.github.valeronm.breadcrumb.domain.trackSummary
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class JourneyPolylinesTest {

    private val test = TestDb()

    @After fun tearDown() = test.close()

    @Test fun `a line keeps every fix of the track, the map thinning it per zoom`() = runTest {
        val id = test.repository.startTrack(ActivityType.WALKING, TEST_START)
        test.repository.addPoints(test.walkPoints(id, 0, 40, fromLat = 1.0))
        test.repository.finishTrack(id, TEST_START + 40 * 10_000L)

        val line = JourneyPolylines(test.repository).lineFor(test.dao.track(id)!!, smoothed = true)

        assertEquals(test.dao.allPointsFor(id).count { !it.ignored }, line.fixCount)
    }

    @Test fun `a recording's line, read as its fixes are stored, is the line they draw once cached`() = runTest {
        val id = test.repository.startTrack(ActivityType.WALKING, TEST_START)
        val walk = test.walkPoints(id, 0, 40, fromLat = 1.0)
        test.repository.addPoints(walk.take(20))
        val held = test.repository.recordingFixes(id, held = null)
        val stray = test.point(id, 20, ignored = true, segmentStart = true).copy(timestamp = walk[20].timestamp - 1)
        test.repository.addPoints(listOf(stray) + walk.drop(20))
        val recording = TimelineItem.RecordingItem(id, ActivityType.WALKING, TEST_START)

        val live = recordingLineOf(recording, test.repository.recordingFixes(id, held).good, smoothed = true)
        val cached = JourneyPolylines(test.repository).lineFor(test.dao.track(id)!!, smoothed = true)

        assertEquals(40, live.fixCount)
        assertEquals(cached.stretchFixes, live.stretchFixes)
        assertEquals(cached.lonLat.toList(), live.lonLat.toList())
    }

    @Test fun `a track whose start moved is loaded again`() {
        val track = trackSummary(id = 1, activityType = ActivityType.WALKING.name, startedAt = TEST_START, endedAt = TEST_START + 60_000)

        assertNotEquals(lineKeys(listOf(track), smoothed = true), lineKeys(listOf(track.copy(startedAt = TEST_START + 10_000)), smoothed = true))
    }

    @Test fun `a retyped track is loaded again`() {
        val track = trackSummary(id = 1, activityType = ActivityType.WALKING.name, startedAt = TEST_START, endedAt = TEST_START + 60_000)

        assertNotEquals(lineKeys(listOf(track), smoothed = true), lineKeys(listOf(track.copy(activityType = ActivityType.CYCLING.name)), smoothed = true))
    }

    @Test fun `a retyped track's line takes its new type`() = runTest {
        val polylines = JourneyPolylines(test.repository)
        val track = trackWith(fixes = 2)
        polylines.lineFor(track, smoothed = true)

        assertEquals(ActivityType.CYCLING.name, polylines.lineFor(track.copy(activityType = ActivityType.CYCLING.name), smoothed = true).activityType)
    }

    @Test fun `reopening a journey of many short tracks reads none of them again`() = runTest {
        val polylines = JourneyPolylines(test.repository)
        val tracks = (0 until 400).map { trackWith(fixes = 2) }

        val opened = tracks.map { polylines.lineFor(it, smoothed = true) }
        val reopened = tracks.map { polylines.lineFor(it, smoothed = true) }

        opened.zip(reopened).forEach { (line, again) -> assertSame(line, again) }
    }

    @Test fun `lines past the budget make room by dropping the one drawn longest ago`() = runTest {
        val polylines = JourneyPolylines(test.repository, maxFixes = 10)
        val long = trackWith(fixes = 6)
        val longer = trackWith(fixes = 6)

        val drawn = polylines.lineFor(long, smoothed = true)
        polylines.lineFor(longer, smoothed = true)

        assertNotSame(drawn, polylines.lineFor(long, smoothed = true))
    }

    @Test fun `a line loaded unsmoothed is the stored fixes, cached apart from the smoothed one`() = runTest {
        val polylines = JourneyPolylines(test.repository)
        val id = test.repository.startTrack(ActivityType.WALKING, TEST_START)
        val stored = (0 until 20).map {
            test.point(id, it, lat = 1.0 + it * 0.0000126)
                .copy(timestamp = TEST_START + it * 1_000L, longitude = -2.0 + if (it % 2 == 0) 0.00002 else -0.00002)
        }
        test.repository.addPoints(stored)
        val track = test.dao.track(id)!!

        val smoothed = polylines.lineFor(track, smoothed = true)
        val raw = polylines.lineFor(track, smoothed = false)

        assertEquals(stored.flatMap { listOf(it.longitude, it.latitude) }, raw.lonLat.toList())
        assertNotEquals(raw.lonLat.toList(), smoothed.lonLat.toList())
    }

    private suspend fun trackWith(fixes: Int): Track {
        val id = test.repository.startTrack(ActivityType.WALKING, TEST_START)
        test.repository.addPoints((0 until fixes).map { test.point(id, it) })
        return test.dao.track(id)!!
    }
}
