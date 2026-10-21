package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class TrackMergeTest {

    private fun track(id: Long, activity: String, startedAt: Long, source: String? = null) =
        TrackSummary(id, activity, startedAt, endedAt = startedAt + 1000, distanceMeters = 100.0, pointCount = 10, ignoredCount = 0, source = source, startPlaceId = null, endPlaceId = null)

    private val before = track(1, "WALKING", 0)
    private val after = track(2, "WALKING", 300_000)

    private fun stay(start: Long, end: Long?) = StayDeriver.Stay(
        start = start,
        end = end,
        afterTrackId = before.id,
        clusterId = 0,
    )

    private fun gap(start: Long, end: Long) = StayDeriver.Gap(
        start = start,
        end = end,
        reason = StayDeriver.GapReason.MOVED_UNRECORDED,
        afterTrackId = before.id,
    )

    @Test fun `short same-activity stay yields a merge into the earlier track`() {
        assertEquals(
            TrackMerge.Plan(earlierId = 1, laterId = 2),
            TrackMerge.plan(before, after, stay(0, 60_000)),
        )
    }

    @Test fun `a 5 minute stay is still eligible (boundary)`() {
        assertEquals(
            TrackMerge.Plan(earlierId = 1, laterId = 2),
            TrackMerge.plan(before, after, stay(0, TrackMerge.MAX_GAP_MS)),
        )
    }

    @Test fun `a long stay between same-activity tracks is mergeable`() {
        assertEquals(
            TrackMerge.Plan(earlierId = 1, laterId = 2),
            TrackMerge.plan(before, after, stay(0, 20 * 60_000L)),
        )
    }

    @Test fun `a gap longer than 5 minutes is not mergeable`() {
        assertNull(
            TrackMerge.plan(before, after, gap(0, TrackMerge.MAX_GAP_MS + 1)),
        )
    }

    @Test fun `different activities are not mergeable`() {
        val running = track(2, "RUNNING", 300_000)
        assertNull(TrackMerge.plan(before, running, stay(0, 60_000)))
    }

    @Test fun `tracks written by different sources are not mergeable`() {
        val recorded = track(1, "WALKING", 0, TrackOrigin.RECORDED.code)
        val imported = track(2, "WALKING", 300_000, TrackOrigin.IMPORTED.code)
        assertNull(TrackMerge.plan(recorded, imported, stay(0, 60_000)))
    }

    @Test fun `a manual trip merges with neither a recording nor an import`() {
        // Two typed endpoints absorbed into measured fixes would leave a row no writer describes.
        val manual = track(1, "WALKING", 0, TrackOrigin.MANUAL.code)
        val recorded = track(2, "WALKING", 300_000, TrackOrigin.RECORDED.code)
        val imported = track(2, "WALKING", 300_000, TrackOrigin.IMPORTED.code)
        assertNull(TrackMerge.plan(manual, recorded, stay(0, 60_000)))
        assertNull(TrackMerge.plan(manual, imported, stay(0, 60_000)))
    }

    @Test fun `same source on both sides merges as usual`() {
        val first = track(1, "WALKING", 0, TrackOrigin.IMPORTED.code)
        val second = track(2, "WALKING", 300_000, TrackOrigin.IMPORTED.code)
        assertEquals(
            TrackMerge.Plan(earlierId = 1, laterId = 2),
            TrackMerge.plan(first, second, stay(0, 60_000)),
        )
    }

    @Test fun `an ongoing stay is not mergeable`() {
        assertNull(TrackMerge.plan(before, after, stay(0, end = null)))
    }

    // --- plansByAnchor: what the timeline actually asks -------------------------------

    private val neighbors = mapOf(before.id to TrackMerge.Neighbors(before, after))

    @Test fun `a short interval's plan is keyed by the track it follows`() {
        val plans = TrackMerge.plansByAnchor(listOf(stay(0, 60_000)), neighbors)
        assertEquals(TrackMerge.Plan(earlierId = 1, laterId = 2), plans[before.id])
    }

    @Test fun `a stay across midnight is judged whole, not by the short slice it renders as`() {
        // 23:58 to 00:30 — half an hour at one place, drawn as a two-minute row and a
        // thirty-minute one. Merging on the short row would fuse the tracks across the whole stop.
        val start = DAY - 2 * MINUTE
        val longStay = stay(start, start + 32 * MINUTE)
        val slices = TimelineRows.slicePerDay(listOf(longStay), { Clocks.both(ZoneId.of("UTC")) }, nowMs = 2 * DAY)
            .map { it.interval }
        assertEquals(2, slices.size)
        assertTrue(slices.any { it.end!! - it.start <= TrackMerge.MAX_GAP_MS })

        val plans = TrackMerge.plansByAnchor(listOf(longStay), neighbors)
        val expected = TrackMerge.Plan(earlierId = 1, laterId = 2)
        assertEquals(expected, plans[before.id])
        assertTrue(slices.all { plans[it.afterTrackId] == expected })
    }

    @Test fun `an interval whose later side is missing yields no plan`() {
        // The tail interval, running into a track that is still recording.
        assertNull(TrackMerge.plansByAnchor(listOf(stay(0, 60_000)), emptyMap())[before.id])
    }

    private companion object {
        const val MINUTE = 60_000L
        const val DAY = 24 * 60 * MINUTE
    }
}
