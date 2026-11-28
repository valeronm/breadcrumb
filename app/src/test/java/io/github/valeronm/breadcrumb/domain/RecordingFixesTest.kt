package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class RecordingFixesTest {

    private fun fix(id: Long, ignored: Boolean = false, segmentStart: Boolean = false) = TrackPoint(
        id = id,
        trackId = 1,
        latitude = 1.0 + id * 0.001,
        longitude = -2.0,
        altitude = null,
        accuracy = 5f,
        speed = null,
        bearing = null,
        timestamp = 1_700_000_000_000L + id * 1_000L,
        ignored = ignored,
        segmentStart = segmentStart,
    )

    private val track = listOf(
        fix(1), fix(2), fix(3),
        fix(4, ignored = true, segmentStart = true),
        fix(5), fix(6, ignored = true), fix(7),
    )

    @Test fun `a recording read in batches holds what one read of the whole track holds`() {
        val batched = RecordingFixes.of(track.take(4)).plus(track.drop(4))

        assertEquals(SegmentBreaks.goodWithCarriedBreaks(track), batched?.good)
    }

    @Test fun `a batch of ignored fixes leaves the held fixes as they were`() {
        val held = RecordingFixes.of(track.take(5))
        val past = held.plus(listOf(fix(6, ignored = true)))!!

        assertSame(held.good, past.good)
        assertEquals(SegmentBreaks.goodWithCarriedBreaks(track), past.plus(listOf(fix(7)))?.good)
    }

    @Test fun `a batch reaching back before the held fixes asks for a full read`() {
        val held = RecordingFixes.of(track.take(5))

        assertNull(held.plus(listOf(fix(3).copy(id = 8))))
    }
}
