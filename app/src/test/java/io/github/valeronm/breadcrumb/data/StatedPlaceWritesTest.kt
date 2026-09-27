package io.github.valeronm.breadcrumb.data

import io.github.valeronm.breadcrumb.domain.ActivityType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The repository's writes that hand a track's stated places on to the rows they create. */
@RunWith(RobolectricTestRunner::class)
class StatedPlaceWritesTest {

    private val test = TestDb()
    private val repository get() = test.repository
    private val dao get() = test.dao

    @After fun tearDown() = test.close()

    private suspend fun placeId(label: String) = test.db.placeDao().insert(test.place(label, 1.0, -2.0))

    private fun state(trackId: Long, startPlaceId: Long?, endPlaceId: Long?) =
        test.db.openHelper.writableDatabase.execSQL(
            "UPDATE tracks SET startPlaceId = ?, endPlaceId = ? WHERE id = ?",
            arrayOf(startPlaceId, endPlaceId, trackId),
        )

    /** A finished, kept 6-point walk; [fromIndex] spaces it out from other tracks in the test. */
    private suspend fun finishedWalk(fromIndex: Int): Long {
        val id = repository.startTrack(ActivityType.WALKING, TEST_START + fromIndex * 10_000L)
        repository.addPoints((fromIndex..fromIndex + 5).map { test.point(id, it) })
        repository.finishTrack(id, TEST_START + (fromIndex + 6) * 10_000L)
        return id
    }

    /** A finished walk of 40 fixes at walking pace, which leaves the overrun rule no stay to flag. */
    private suspend fun finishedPacedWalk(): Long {
        val id = repository.startTrack(ActivityType.WALKING, TEST_START)
        repository.addPoints(
            (0 until 40).map { test.point(id, it, lat = 1.0 + it * 0.000126).copy(speed = 1.4f) },
        )
        repository.finishTrack(id, TEST_START + 40 * 10_000L)
        return id
    }

    @Test fun `a merge keeps the earlier track's start place and the later one's end place`() = runTest {
        val first = finishedWalk(0)
        val second = finishedWalk(12)
        val (a, b, c) = listOf(placeId("A"), placeId("B"), placeId("C"))
        state(first, a, b)
        state(second, b, c)

        val merged = dao.track(repository.mergeTracks(first, second)!!)!!

        assertEquals(a, merged.startPlaceId)
        assertEquals(c, merged.endPlaceId)
    }

    @Test fun `a split leaves the cut unstated and hands the end place to the second half`() = runTest {
        val id = finishedPacedWalk()
        val (a, b) = listOf(placeId("A"), placeId("B"))
        state(id, a, b)

        val split = repository.splitTrack(id, TEST_START + 20 * 10_000L)!!

        val first = dao.track(id)!!
        val second = dao.track(split.secondId)!!
        assertEquals(a, first.startPlaceId)
        assertNull(first.endPlaceId)
        assertNull(second.startPlaceId)
        assertEquals(b, second.endPlaceId)
    }

    @Test fun `an unsplit gives the original its end place back`() = runTest {
        val id = finishedPacedWalk()
        val (a, b) = listOf(placeId("A"), placeId("B"))
        state(id, a, b)
        val split = repository.splitTrack(id, TEST_START + 20 * 10_000L)!!

        repository.unsplitTracks(id, split)

        val reunited = dao.track(id)!!
        assertEquals(a, reunited.startPlaceId)
        assertEquals(b, reunited.endPlaceId)
    }
}
