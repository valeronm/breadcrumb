package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.DistanceFn
import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.domain.StayDeriver.Armed
import io.github.valeronm.breadcrumb.domain.StayDeriver.Disarmed
import io.github.valeronm.breadcrumb.domain.StayDeriver.Endpoint
import io.github.valeronm.breadcrumb.domain.StayDeriver.Gap
import io.github.valeronm.breadcrumb.domain.StayDeriver.GapReason
import io.github.valeronm.breadcrumb.domain.StayDeriver.Liveness
import io.github.valeronm.breadcrumb.domain.StayDeriver.Outage
import io.github.valeronm.breadcrumb.domain.StayDeriver.Provenance
import io.github.valeronm.breadcrumb.domain.StayDeriver.Stay
import io.github.valeronm.breadcrumb.domain.StayDeriver.TrackEnd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * Stays derive from inter-track gaps + liveness evidence. Distance is stubbed as the flat-earth
 * metric scaled so 0.001 lat ≈ 100 m — tests place endpoints by "degrees" and reason in metres.
 */
class StayDeriverTest {

    // 0.001° of latitude → 100 m; longitude treated the same (flat, fine for tests).
    private val flatDistance = DistanceFn { aLat, aLon, bLat, bLon ->
        maxOf(Math.abs(aLat - bLat), Math.abs(aLon - bLon)) * 100_000.0
    }

    private val home = Endpoint(1.0, 1.0)
    private val nearHome = Endpoint(1.0005, 1.0) // 50 m away — agrees
    private val office = Endpoint(2.0, 2.0)

    private val MIN = 60_000L
    private val NOW = 1_000 * MIN

    private fun track(id: Long, start: Long, end: Long, from: Endpoint? = home, to: Endpoint? = home) =
        TrackEnd(trackId = id, startedAt = start, endedAt = end, start = from, end = to)

    private fun derive(
        tracks: List<TrackEnd>,
        liveness: List<Liveness> = listOf(Armed(0)),
        now: Long = NOW,
        recording: Boolean = false,
    ) = StayDeriver.derive(tracks, liveness, now, recording, StayDeriver.Params(), flatDistance)

    /** Two tracks whose gap is [120, 240) min, both ending/starting near `home`. */
    private fun homePair(to: Endpoint? = home, from: Endpoint? = nearHome) = listOf(
        track(1, start = 60 * MIN, end = 120 * MIN, to = to),
        track(2, start = 240 * MIN, end = 300 * MIN, from = from),
    )

    // --- The decision table ------------------------------------------------

    @Test fun `agreeing endpoints with full liveness is an observed stay`() {
        val stays = derive(homePair()).filterIsInstance<Stay>()
        val stay = stays.first()
        assertEquals(120 * MIN, stay.start)
        assertEquals(240 * MIN, stay.end)
        assertEquals(Provenance.OBSERVED, stay.provenance)
        assertEquals(1L, stay.afterTrackId)
        // Midpoint of the two endpoints.
        assertEquals(1.00025, stay.location.lat, 1e-9)
    }

    @Test fun `an outage inside the gap downgrades to inferred`() {
        val stays = derive(
            homePair(),
            liveness = listOf(Armed(0), Outage(150 * MIN, 180 * MIN)),
        ).filterIsInstance<Stay>()
        assertEquals(Provenance.INFERRED, stays.first().provenance)
    }

    @Test fun `two outages in one gap still yield a single inferred stay`() {
        val intervals = derive(
            homePair(),
            liveness = listOf(Armed(0), Outage(130 * MIN, 140 * MIN), Outage(200 * MIN, 210 * MIN)),
        )
        assertEquals(1, intervals.count { it is Stay && it.end != null })
        assertEquals(Provenance.INFERRED, (intervals.first { it.end != null } as Stay).provenance)
    }

    @Test fun `disarm then rearm inside the gap is inferred`() {
        val stays = derive(
            homePair(),
            liveness = listOf(Armed(0), Disarmed(150 * MIN), Armed(200 * MIN)),
        ).filterIsInstance<Stay>()
        assertEquals(Provenance.INFERRED, stays.first { it.end != null }.provenance)
    }

    @Test fun `pre-liveness history derives as inferred`() {
        // First liveness evidence arrives after the gap — old data from before the feature.
        val stays = derive(homePair(), liveness = listOf(Armed(500 * MIN)))
            .filterIsInstance<Stay>()
        assertEquals(Provenance.INFERRED, stays.first().provenance)
    }

    @Test fun `no liveness rows at all derives as inferred`() {
        val stays = derive(homePair(), liveness = emptyList()).filterIsInstance<Stay>()
        assertEquals(Provenance.INFERRED, stays.first().provenance)
    }

    @Test fun `disagreeing endpoints are a moved-unrecorded gap regardless of liveness`() {
        val intervals = derive(homePair(from = office))
        val gap = intervals.first { it is Gap } as Gap
        assertEquals(GapReason.MOVED_UNRECORDED, gap.reason)
        assertEquals(120 * MIN, gap.start)
        assertEquals(240 * MIN, gap.end)
    }

    @Test fun `endpoints exactly at the agreement radius still count as a stay`() {
        // 0.001° = exactly 100 m = the radius; the rule is ≤.
        val stays = derive(homePair(from = Endpoint(1.001, 1.0))).filterIsInstance<Stay>()
        assertTrue(stays.any { it.end == 240 * MIN })
    }

    @Test fun `a missing endpoint is an unknown-endpoint gap`() {
        val intervals = derive(homePair(to = null))
        assertEquals(GapReason.UNKNOWN_ENDPOINT, (intervals.first { it is Gap } as Gap).reason)
    }

    @Test fun `a short gap emits a stay by default (no minimum)`() {
        val intervals = derive(
            listOf(
                track(1, start = 60 * MIN, end = 120 * MIN),
                track(2, start = 120 * MIN + 60_000, from = nearHome, end = 300 * MIN),
            ),
            now = 300 * MIN, recording = true,
        )
        assertEquals(1, intervals.filterIsInstance<Stay>().size)
    }

    @Test fun `a configured minimum stay still suppresses short gaps`() {
        val intervals = StayDeriver.derive(
            listOf(
                track(1, start = 60 * MIN, end = 120 * MIN),
                track(2, start = 120 * MIN + 60_000, from = nearHome, end = 300 * MIN),
            ),
            listOf(Armed(0)), 300 * MIN, true,
            StayDeriver.Params(minStayMs = 5 * MIN), flatDistance,
        )
        assertTrue(intervals.isEmpty())
    }

    @Test fun `clock stepping backwards between tracks emits nothing for that pair`() {
        val intervals = derive(
            listOf(
                track(1, start = 60 * MIN, end = 240 * MIN),
                track(2, start = 120 * MIN, end = 300 * MIN), // starts before prev ended
            ),
            now = 300 * MIN, recording = true,
        )
        assertTrue(intervals.isEmpty())
    }

    @Test fun `empty and single-track inputs derive nothing before the first track`() {
        assertTrue(derive(emptyList()).isEmpty())
        // A single track yields only the ongoing tail stay, never anything before it.
        val intervals = derive(listOf(track(1, start = 60 * MIN, end = 120 * MIN)))
        assertEquals(1, intervals.size)
        assertEquals(120 * MIN, intervals.first().start)
    }

    // --- The ongoing (tail) stay --------------------------------------------

    @Test fun `after the last track an ongoing stay is open-ended`() {
        val stay = derive(listOf(track(1, start = 60 * MIN, end = 120 * MIN)))
            .filterIsInstance<Stay>().single()
        assertNull(stay.end)
        assertEquals(Provenance.OBSERVED, stay.provenance)
        assertEquals(home, stay.location)
    }

    @Test fun `no tail stay while actively recording`() {
        assertTrue(derive(listOf(track(1, start = 60 * MIN, end = 120 * MIN)), recording = true).isEmpty())
    }

    @Test fun `a tail disarm closes the ongoing stay at the disarm time`() {
        val stay = derive(
            listOf(track(1, start = 60 * MIN, end = 120 * MIN)),
            liveness = listOf(Armed(0), Disarmed(200 * MIN)),
        ).filterIsInstance<Stay>().single()
        assertEquals(200 * MIN, stay.end)
        assertEquals(Provenance.OBSERVED, stay.provenance)
    }

    @Test fun `a tail disarm bounds the ongoing stay even when short`() {
        val stay = derive(
            listOf(track(1, start = 60 * MIN, end = 120 * MIN)),
            liveness = listOf(Armed(0), Disarmed(121 * MIN)),
        ).filterIsInstance<Stay>().single()
        assertEquals(121 * MIN, stay.end)
    }

    @Test fun `an outage in the tail makes the ongoing stay inferred`() {
        val stay = derive(
            listOf(track(1, start = 60 * MIN, end = 120 * MIN)),
            liveness = listOf(Armed(0), Outage(150 * MIN, 160 * MIN)),
        ).filterIsInstance<Stay>().single()
        assertNull(stay.end)
        assertEquals(Provenance.INFERRED, stay.provenance)
    }

    @Test fun `a track ending in the future emits no tail stay`() {
        assertTrue(derive(listOf(track(1, start = 60 * MIN, end = NOW + MIN))).isEmpty())
    }

    // --- slicePerDay ----------------------------------------------------------

    private val utc = ZoneId.of("UTC")
    private val DAY = 24 * 60 * MIN

    @Test fun `a midnight-spanning stay splits into per-day slices with clamped bounds`() {
        val stay = Stay(start = 20 * 60 * MIN, end = DAY + 9 * 60 * MIN, location = home,
            provenance = Provenance.OBSERVED, afterTrackId = 1)
        val slices = StayDeriver.slicePerDay(listOf(stay), utc, nowMs = 2 * DAY)
        assertEquals(2, slices.size)
        assertEquals(20 * 60 * MIN, slices[0].start)
        assertEquals(DAY, slices[0].end)
        assertEquals(DAY, slices[1].start)
        assertEquals(DAY + 9 * 60 * MIN, slices[1].end)
        assertTrue(slices.all { it is Stay && it.provenance == Provenance.OBSERVED })
    }

    @Test fun `an ongoing stay keeps its null end on the final slice only`() {
        val stay = Stay(start = 20 * 60 * MIN, end = null, location = home,
            provenance = Provenance.OBSERVED, afterTrackId = 1)
        val slices = StayDeriver.slicePerDay(listOf(stay), utc, nowMs = DAY + 9 * 60 * MIN)
        assertEquals(2, slices.size)
        assertEquals(DAY, slices[0].end)
        assertNull(slices[1].end)
    }

    @Test fun `an intra-day interval passes through unchanged`() {
        val gap = Gap(start = 10 * 60 * MIN, end = 11 * 60 * MIN, reason = GapReason.MOVED_UNRECORDED)
        assertEquals(listOf<StayDeriver.Interval>(gap), StayDeriver.slicePerDay(listOf(gap), utc, 2 * DAY))
    }

    @Test fun `day slicing respects the zone's DST transition`() {
        // Europe/Lisbon, 2026-03-29: 01:00 UTC the clocks jump 00:59→02:00 local... the point
        // pinned here is just that atStartOfDay on a DST day doesn't crash or mis-order slices.
        val lisbon = ZoneId.of("Europe/Lisbon")
        val start = java.time.LocalDate.of(2026, 3, 28).atStartOfDay(lisbon)
            .plusHours(20).toInstant().toEpochMilli()
        val end = java.time.LocalDate.of(2026, 3, 29).atStartOfDay(lisbon)
            .plusHours(12).toInstant().toEpochMilli()
        val slices = StayDeriver.slicePerDay(
            listOf(Stay(start, end, home, Provenance.OBSERVED, 1)), lisbon, end + DAY,
        )
        assertEquals(2, slices.size)
        assertTrue(slices[0].end!! <= slices[1].start)
        assertEquals(end, slices[1].end)
    }

    // --- interleave ------------------------------------------------------------

    @Test fun `interleave merges tracks and intervals newest-first`() {
        val summaries = listOf(
            summary(2, startedAt = 240 * MIN),
            summary(1, startedAt = 60 * MIN),
        )
        val stay = Stay(120 * MIN, 240 * MIN, home, Provenance.OBSERVED, 1)
        val items = StayDeriver.interleave(summaries, listOf(stay))
        assertEquals(
            listOf(240 * MIN, 120 * MIN, 60 * MIN),
            items.map { it.startedAt },
        )
        assertTrue(items[1] is TimelineItem.StayItem)
    }

    @Test fun `on a start-time tie the interval sorts newer than the track`() {
        val summaries = listOf(summary(1, startedAt = 60 * MIN))
        val stay = Stay(60 * MIN, null, home, Provenance.OBSERVED, 1)
        val items = StayDeriver.interleave(summaries, listOf(stay))
        assertTrue(items[0] is TimelineItem.StayItem)
        assertTrue(items[1] is TimelineItem.TrackItem)
    }

    private fun summary(id: Long, startedAt: Long) = TrackSummary(
        id = id, activityType = "WALKING", startedAt = startedAt,
        endedAt = startedAt + 10 * MIN, distanceMeters = 1000.0, pointCount = 100, ignoredCount = 0,
    )
}
