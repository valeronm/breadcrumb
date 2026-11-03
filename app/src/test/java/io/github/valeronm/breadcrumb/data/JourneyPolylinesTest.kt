package io.github.valeronm.breadcrumb.data

import io.github.valeronm.breadcrumb.data.db.Track
import io.github.valeronm.breadcrumb.domain.ActivityType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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

        val line = JourneyPolylines(test.repository).lineFor(test.dao.track(id)!!)

        assertEquals(test.dao.allPointsFor(id).count { !it.ignored }, line.fixCount)
    }

    @Test fun `reopening a journey of many short tracks reads none of them again`() = runTest {
        val polylines = JourneyPolylines(test.repository)
        val tracks = (0 until 400).map { trackWith(fixes = 2) }

        val opened = tracks.map { polylines.lineFor(it) }
        val reopened = tracks.map { polylines.lineFor(it) }

        opened.zip(reopened).forEach { (line, again) -> assertSame(line, again) }
    }

    @Test fun `lines past the budget make room by dropping the one drawn longest ago`() = runTest {
        val polylines = JourneyPolylines(test.repository)
        val long = trackWith(fixes = 600_000)
        val longer = trackWith(fixes = 600_000)

        val drawn = polylines.lineFor(long)
        polylines.lineFor(longer)

        assertNotSame(drawn, polylines.lineFor(long))
    }

    private suspend fun trackWith(fixes: Int): Track {
        val id = test.repository.startTrack(ActivityType.WALKING, TEST_START)
        test.repository.addPoints((0 until fixes).map { test.point(id, it) })
        return test.dao.track(id)!!
    }
}
