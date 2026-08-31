package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackSummary
import io.github.valeronm.breadcrumb.domain.StayDeriver.Gap
import io.github.valeronm.breadcrumb.domain.StayDeriver.GapReason
import io.github.valeronm.breadcrumb.domain.StayDeriver.Stay
import io.github.valeronm.breadcrumb.domain.StayDeriver.TrackEnd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * Stays derive from inter-track gaps. Distance is stubbed as the flat-earth
 * metric scaled so 0.001 lat ≈ 100 m — tests place endpoints by "degrees" and reason in meters.
 */
class StayDeriverTest {

    private val home = Coordinate(1.0, 1.0)
    private val nearHome = Coordinate(1.0005, 1.0) // 50 m away — agrees
    private val office = Coordinate(2.0, 2.0)

    /** A named-place pin at venue scale (the default place radius is 150 m; venues get widened). */
    private fun pin(meters: Double, radiusM: Double = 350.0) = PlaceClusterer.Seed(at(meters), radiusM)

    private val NOW = 1_000 * MIN

    private fun track(id: Long, start: Long, end: Long, from: Coordinate? = home, to: Coordinate? = home) =
        TrackEnd(trackId = id, startedAt = start, endedAt = end, start = from, end = to)

    private fun derive(
        tracks: List<TrackEnd>,
        pins: List<PlaceClusterer.Seed> = emptyList(),
    ) = StayDeriver.derive(tracks, StayDeriver.Params(), flatDistance, pins).intervals

    /** Two tracks whose gap is [120, 240) min, both ending/starting near `home`. */
    private fun homePair(to: Coordinate? = home, from: Coordinate? = nearHome) = listOf(
        track(1, start = 60 * MIN, end = 120 * MIN, to = to),
        track(2, start = 240 * MIN, end = 300 * MIN, from = from),
    )

    /** A trim seam: two same-place tracks sharing the boundary instant (0 ms gap). */
    private fun seamPair(from: Coordinate? = home) = listOf(
        track(1, start = 60 * MIN, end = 120 * MIN, to = home),
        track(2, start = 120 * MIN, end = 130 * MIN, from = from),
    )

    // --- The decision table ------------------------------------------------

    @Test fun `agreeing endpoints are a stay`() {
        val stays = derive(homePair()).filterIsInstance<Stay>()
        val stay = stays.first()
        assertEquals(120 * MIN, stay.start)
        assertEquals(240 * MIN, stay.end)
        assertEquals(1L, stay.afterTrackId)
    }

    @Test fun `disagreeing endpoints are a moved-unrecorded gap`() {
        val intervals = derive(homePair(from = office))
        val gap = intervals.first { it is Gap } as Gap
        assertEquals(GapReason.MOVED_UNRECORDED, gap.reason)
        assertEquals(120 * MIN, gap.start)
        assertEquals(240 * MIN, gap.end)
    }

    @Test fun `a gap names the track it follows, like a stay`() {
        // The handle the merge offer needs: from it the UI reaches both tracks around the gap.
        assertEquals(1L, (derive(homePair(from = office)).first { it is Gap } as Gap).afterTrackId)
    }

    @Test fun `a moved-unrecorded gap indexes both sides' distinct clusters`() {
        val derivation = StayDeriver.derive(
            homePair(from = office), StayDeriver.Params(), flatDistance,
        )
        val gap = derivation.intervals.first { it is Gap } as Gap
        // The disagreement that made this a gap: the sides sit in different clusters,
        // and each id indexes a real cluster containing its endpoint.
        assertNotEquals(gap.fromClusterId, gap.toClusterId)
        assertTrue(derivation.clusters[gap.fromClusterId!!].members.contains(home))
        assertTrue(derivation.clusters[gap.toClusterId!!].members.contains(office))
        // And the coordinates themselves, beside the clusters they fell into: the gap runs from
        // where the recording stopped to where it started again, which is what a trip entered to
        // fill it runs between.
        assertEquals(home, gap.from)
        assertEquals(office, gap.to)
    }

    @Test fun `a side the recorder never fixed has no position either`() {
        // The unknown-endpoint gap: nothing to hand a form, so it must not offer a coordinate it
        // does not have — the cluster id is absent for the same reason.
        val tracks = listOf(
            TrackEnd(1, 60 * MIN, 120 * MIN, start = home, end = null),
            TrackEnd(2, 180 * MIN, 240 * MIN, start = office, end = office),
        )
        val gap = derive(tracks).filterIsInstance<Gap>().single()

        assertNull(gap.from)
        assertEquals(office, gap.to)
    }

    @Test fun `endpoints exactly at the agreement radius still count as a stay`() {
        // 0.001° = exactly 100 m = the radius; the rule is ≤.
        val stays = derive(homePair(from = Coordinate(1.001, 1.0))).filterIsInstance<Stay>()
        assertTrue(stays.any { it.end == 240 * MIN })
    }

    @Test fun `same-cluster endpoints beyond the agreement radius still form a stay`() {
        // 120 m apart — over the raw-distance radius, but both within the 150 m cluster around
        // the anchor at `home`, so clustering recognizes them as the same place.
        val stays = derive(homePair(from = at(120.0))).filterIsInstance<Stay>()
        assertTrue(stays.any { it.end == 240 * MIN })
    }

    @Test fun `nearby endpoints straddling two clusters agree via the distance fallback`() {
        // Anchors form at 0 m and 170 m (>150 m apart). prev ends at 130 m (first cluster), next
        // starts at 170 m (second cluster): different clusters but only 40 m apart — still a stay.
        val intervals = derive(
            listOf(
                track(1, start = 60 * MIN, end = 120 * MIN, from = at(0.0), to = at(130.0)),
                track(2, start = 240 * MIN, end = 300 * MIN, from = at(170.0), to = at(300.0)),
            ),
        )
        assertTrue(intervals.filterIsInstance<Stay>().any { it.end == 240 * MIN })
    }

    @Test fun `endpoints sharing a nearest named-place pin agree at venue scale`() {
        // 300 m apart — beyond both the raw radius and any shared 150 m cluster — but both
        // nearest to the same pin within 350 m (a mall-sized venue).
        val intervals = derive(homePair(from = at(300.0)), pins = listOf(pin(150.0)))
        assertTrue(intervals.filterIsInstance<Stay>().any { it.end == 240 * MIN })
    }

    @Test fun `endpoints nearest to different pins stay a gap`() {
        // Each endpoint sits by its own pin; distance (300 m) and clusters disagree too.
        val intervals = derive(homePair(from = at(300.0)), pins = listOf(pin(0.0), pin(300.0)))
        assertEquals(GapReason.MOVED_UNRECORDED, (intervals.first { it is Gap } as Gap).reason)
    }

    @Test fun `a pin near only one endpoint does not force agreement`() {
        val intervals = derive(homePair(from = at(600.0)), pins = listOf(pin(0.0)))
        assertEquals(GapReason.MOVED_UNRECORDED, (intervals.first { it is Gap } as Gap).reason)
    }

    @Test fun `agreement honors each pin's own radius`() {
        // 300 m apart with a default-radius (150 m) pin between them: neither endpoint is captured
        // (both are ~150 m out but the near one clusters organically first at 0), and no shared
        // nearest pin within radius — a gap. The same layout with a widened pin is a stay above.
        val intervals = derive(homePair(from = at(300.0)), pins = listOf(pin(150.0, radiusM = 100.0)))
        assertEquals(GapReason.MOVED_UNRECORDED, (intervals.first { it is Gap } as Gap).reason)
    }

    @Test fun `stays index into the derivation's endpoint clusters`() {
        val derivation = StayDeriver.derive(
            homePair(), StayDeriver.Params(), flatDistance,
        )
        val stay = derivation.intervals.filterIsInstance<Stay>().first()
        val anchor = derivation.clusters[stay.clusterId].anchor
        assertTrue(flatDistance.meters(anchor.lat, anchor.lon, home.lat, home.lon) <= 150.0)
    }

    @Test fun `a pinned venue's stay indexes into the pin's seeded cluster`() {
        val derivation = StayDeriver.derive(
            homePair(from = at(300.0)),
            StayDeriver.Params(), flatDistance,
            placePins = listOf(pin(150.0)),
        )
        val stay = derivation.intervals.filterIsInstance<Stay>().first { it.end == 240 * MIN }
        assertEquals(0, derivation.clusters[stay.clusterId].seedIndex)
    }

    @Test fun `a missing endpoint is an unknown-endpoint gap`() {
        val intervals = derive(homePair(to = null))
        assertEquals(GapReason.UNKNOWN_ENDPOINT, (intervals.first { it is Gap } as Gap).reason)
    }

    @Test fun `an unknown-endpoint gap still carries the known side`() {
        val gap = derive(homePair(to = null)).first { it is Gap } as Gap
        assertNull(gap.fromClusterId)
        assertNotNull(gap.toClusterId)
    }

    @Test fun `the later track's missing start reads the same way round`() {
        // The mirror of the case above, and worth pinning as its own: which side is null decides
        // which cluster id the gap can carry, so a rule written for one end is half a rule.
        val gap = derive(homePair(from = null)).first { it is Gap } as Gap
        assertEquals(GapReason.UNKNOWN_ENDPOINT, gap.reason)
        assertNotNull(gap.fromClusterId)
        assertNull(gap.toClusterId)
    }

    @Test fun `no pin near the earlier endpoint leaves the pair a gap`() {
        // The pin override answers "same *named* place", so it can only agree when both endpoints
        // reach a pin. Tested from this side too because the two are separate reads: a pin found for
        // the later endpoint and none for the earlier must not read as agreement by omission.
        val intervals = derive(homePair(to = at(600.0), from = at(0.0)), pins = listOf(pin(0.0)))
        assertEquals(GapReason.MOVED_UNRECORDED, (intervals.first { it is Gap } as Gap).reason)
    }

    @Test fun `a gap of one minute is a stay - there is no minimum`() {
        // Not a default that could be raised: a brief stop is what a place accumulates visits from,
        // so the floor lives with each reader instead. See the rule on StayDeriver.
        val intervals = derive(
            listOf(
                track(1, start = 60 * MIN, end = 120 * MIN),
                track(2, start = 120 * MIN + 60_000, from = nearHome, end = 300 * MIN),
            ),
        )
        assertEquals(1, intervals.filterIsInstance<Stay>().size)
    }

    private fun stayAt(start: Long, end: Long?) = Stay(
        start = start, end = end,
        afterTrackId = 1L, clusterId = 0,
    )

    @Test fun `a stay shorter than its bounds can measure reports no duration`() {
        // The stop is real — it still derives, still counts as a visit — but its length lives in
        // the untrimmed tail of the track before it, so the bounds are not worth printing.
        val seam = stayAt(start = 100 * MIN, end = 100 * MIN + 3_000)
        assertNull(seam.reportableDurationMs(300 * MIN))
        assertEquals(MIN, seam.copy(end = 101 * MIN).reportableDurationMs(300 * MIN))
    }

    @Test fun `an ongoing stay starts reporting once it passes the threshold`() {
        val ongoing = stayAt(start = 100 * MIN, end = null)
        assertNull(ongoing.reportableDurationMs(100 * MIN + 30_000))
        assertEquals(2 * MIN, ongoing.reportableDurationMs(102 * MIN))
    }

    @Test fun `clock stepping backwards between tracks emits nothing for that pair`() {
        val intervals = derive(
            listOf(
                track(1, start = 60 * MIN, end = 240 * MIN),
                track(2, start = 120 * MIN, end = 300 * MIN), // starts before prev ended
            ),
        )
        assertTrue(intervals.isEmpty())
    }

    @Test fun `an input with no pair in it derives nothing`() {
        // Nothing before the first track, and nothing after the last: an interval is a fact about
        // two finished tracks, so one track has none and the stay still running after it is the
        // caller's to append ([StayDeriver.tail]).
        assertTrue(derive(emptyList()).isEmpty())
        assertTrue(derive(listOf(track(1, start = 60 * MIN, end = 120 * MIN))).isEmpty())
    }

    // --- The ongoing (tail) stay --------------------------------------------
    //
    // Asked of [StayDeriver.tail] and never of [StayDeriver.derive], because that is the way round
    // production reaches it: `derive` emits intervals between finished tracks only, and the caller
    // that wants a whole timeline appends the trailing one from a stored anchor. A suite that drove
    // this through `derive` would be exercising a pass no screen runs.

    private val anchor = StayDeriver.TailAnchor(trackId = 1L, endedAt = 120 * MIN, clusterId = 3)

    private fun tail(
        disarmedSince: Long? = null,
        now: Long = NOW,
        activeStartedAt: Long? = null,
        anchor: StayDeriver.TailAnchor = this.anchor,
    ) = StayDeriver.tail(
        anchor = anchor,
        disarmedSince = disarmedSince,
        nowMs = now,
        activeStartedAt = activeStartedAt,
    )

    @Test fun `the tail answers from a bound and a cluster, with no derivation behind it`() {
        val open = tail()
        assertEquals(120 * MIN, open?.start)
        assertNull(open?.end)
        assertEquals(3, open?.clusterId)
        assertEquals(1L, open?.afterTrackId)
    }

    @Test fun `recording closes the tail stay at the active track's start`() {
        assertEquals(200 * MIN, tail(activeStartedAt = 200 * MIN)?.end)
    }

    @Test fun `an active track that started immediately leaves no tail stay`() {
        assertNull(tail(activeStartedAt = 120 * MIN))
    }

    @Test fun `a disarm closes the ongoing stay at the disarm time`() {
        assertEquals(200 * MIN, tail(disarmedSince = 200 * MIN)?.end)
    }

    @Test fun `a disarm bounds the ongoing stay even when short`() {
        assertEquals(121 * MIN, tail(disarmedSince = 121 * MIN)?.end)
    }

    @Test fun `a disarm before the anchor cannot close the stay before it begins`() {
        // A disarm older than the newest track's end (the recorder was off, then a track was
        // imported or entered by hand past it): the stay still starts at its own anchor.
        assertEquals(120 * MIN, tail(disarmedSince = 60 * MIN)?.end)
    }

    @Test fun `a track ending in the future emits no tail stay`() {
        assertNull(tail(anchor = anchor.copy(endedAt = NOW + MIN)))
    }

    // --- slicePerDay ----------------------------------------------------------

    private val utc = ZoneId.of("UTC")
    private val DAY = 24 * 60 * MIN

    /** The pieces alone: most cases below are about where the cuts land, not which clock each end
     *  of a piece ran on — the cases about stamping ask [TimelineRows.slicePerDay] for that directly. */
    private fun sliced(
        intervals: List<StayDeriver.Interval>,
        zones: (StayDeriver.Interval) -> Clocks,
        nowMs: Long,
    ): List<StayDeriver.Interval> = TimelineRows.slicePerDay(intervals, zones, nowMs).map { it.interval }

    /** Intervals as slices holding both their ends on one clock — every stay, and an absence that
     *  ended on the day it began. */
    private fun oneClock(vararg intervals: StayDeriver.Interval) =
        intervals.map { TimelineRows.Slice(it, utc, utc) }

    @Test fun `a midnight-spanning stay splits into per-day slices with clamped bounds`() {
        val stay = Stay(
            start = 20 * 60 * MIN, end = DAY + 9 * 60 * MIN,
            afterTrackId = 1, clusterId = 0,
        )
        val slices = sliced(listOf(stay), { Clocks.both(utc) }, nowMs = 2 * DAY)
        assertEquals(2, slices.size)
        assertEquals(20 * 60 * MIN, slices[0].start)
        assertEquals(DAY, slices[0].end)
        assertEquals(DAY, slices[1].start)
        assertEquals(DAY + 9 * 60 * MIN, slices[1].end)
        assertTrue(slices.all { it is Stay && it.clusterId == 0 })
    }

    /**
     * The stamp itself, at the cut — the whole claim of the seam is that the slicer says which
     * bounds are the interval's own, so nothing downstream has to work it out from a timestamp.
     * Asserted here because every other suite is handed the flags rather than deriving them: without
     * this, `daySlicesOf` could stamp every piece as holding both and each row would print a clock
     * time and a duration for a boundary that is not the stay's.
     */
    @Test fun `a stay's pieces say which of its bounds are its own`() {
        val stay = Stay(
            start = 20 * 60 * MIN, end = 2 * DAY + 9 * 60 * MIN,
            afterTrackId = 1, clusterId = 0,
        )
        val slices = TimelineRows.slicePerDay(listOf(stay), { Clocks.both(utc) }, 3 * DAY)

        assertEquals(3, slices.size)
        assertEquals(listOf(true, false, false), slices.map { it.holdsStart })
        assertEquals(listOf(false, false, true), slices.map { it.holdsEnd })
    }

    /** A gap's halves answer the same question the same way — one encoding for both kinds. */
    @Test fun `a gap's halves say which of its bounds are its own`() {
        val gap = Gap(20 * 60 * MIN, DAY + 3 * 60 * MIN, GapReason.MOVED_UNRECORDED, afterTrackId = 1)
        val halves = TimelineRows.slicePerDay(listOf(gap), { Clocks.both(utc) }, 2 * DAY)

        assertEquals(listOf(true, false), halves.map { it.holdsStart })
        assertEquals(listOf(false, true), halves.map { it.holdsEnd })
    }

    @Test fun `an ongoing stay keeps its null end on the final slice only`() {
        val stay = Stay(
            start = 20 * 60 * MIN, end = null,
            afterTrackId = 1, clusterId = 0,
        )
        val slices = sliced(listOf(stay), { Clocks.both(utc) }, nowMs = DAY + 9 * 60 * MIN)
        assertEquals(2, slices.size)
        assertEquals(DAY, slices[0].end)
        assertNull(slices[1].end)
    }

    @Test fun `an intra-day stay passes through the loop unchanged`() {
        // A stay, deliberately: a gap now returns before the loop, so asking this of one would
        // exercise the early return and leave the loop's terminating branch with no witness.
        val stay = Stay(
            start = 10 * 60 * MIN, end = 11 * 60 * MIN,
            afterTrackId = 1, clusterId = 0,
        )
        assertEquals(listOf<StayDeriver.Interval>(stay), sliced(listOf(stay), { Clocks.both(utc) }, 2 * DAY))
    }

    /**
     * The asymmetry, on one pair of bounds so the two rules are read against each other. Both are cut
     * at midnight here because both cross exactly one; what differs is the rule behind it, which the
     * multi-day cases below separate: a stay is cut at *every* midnight it crosses, a gap only ever
     * at the one opening the day it ended.
     */
    @Test fun `a gap crossing one midnight is cut there, as the same stay is`() {
        val start = 20 * 60 * MIN
        val end = DAY + 3 * 60 * MIN
        val gap = Gap(start, end, GapReason.UNKNOWN_ENDPOINT, afterTrackId = 1)
        val stay = Stay(start, end, afterTrackId = 1, clusterId = 0)

        assertEquals(2, sliced(listOf(stay), { Clocks.both(utc) }, 2 * DAY).size)

        val halves = TimelineRows.slicePerDay(listOf(gap), { Clocks.both(utc) }, 2 * DAY)
        assertEquals(listOf(start to DAY, DAY to end), halves.map { it.interval.start to it.interval.end })
        // Stamped, so each half states the end it speaks for and says nothing about the other.
        assertEquals(listOf(utc, null), halves.map { it.departureZone })
        assertEquals(listOf(null, utc), halves.map { it.arrivalZone })
    }

    @Test fun `day slicing respects the zone's DST transition`() {
        // Europe/Lisbon, 2026-03-29: 01:00 UTC the clocks jump 00:59→02:00 local... the point
        // pinned here is just that atStartOfDay on a DST day doesn't crash or mis-order slices.
        val lisbon = ZoneId.of("Europe/Lisbon")
        val start = java.time.LocalDate.of(2026, 3, 28).atStartOfDay(lisbon)
            .plusHours(20).toInstant().toEpochMilli()
        val end = java.time.LocalDate.of(2026, 3, 29).atStartOfDay(lisbon)
            .plusHours(12).toInstant().toEpochMilli()
        val slices = sliced(
            listOf(Stay(start, end, 1, clusterId = 0)), { Clocks.both(lisbon) }, end + DAY,
        )
        assertEquals(2, slices.size)
        assertTrue(slices[0].end!! <= slices[1].start)
        assertEquals(end, slices[1].end)
    }

    @Test fun `each interval is cut on its own zone, not the list's`() {
        // The same wall-clock evening in two places nine hours apart: one has already passed
        // midnight where it happened, the other has not. A single zone for the list gets one of
        // them wrong whichever it picks.
        val tokyo = ZoneId.of("Asia/Tokyo")
        val evening = java.time.LocalDate.of(2026, 7, 18).atStartOfDay(tokyo)
            .plusHours(20).toInstant().toEpochMilli()
        val abroad = Stay(evening, evening + 8 * 60 * MIN, 1, clusterId = 0)
        val athome = Stay(evening, evening + 8 * 60 * MIN, 2, clusterId = 1)
        val zones = mapOf(1L to tokyo, 2L to utc)

        val slices = sliced(
            listOf(abroad, athome),
            { Clocks.both(zones.getValue((it as Stay).afterTrackId)) },
            evening + DAY,
        )

        // 20:00–04:00 in Tokyo crosses that country's midnight and splits; the same instants are
        // 11:00–19:00 in UTC and do not.
        assertEquals(2, slices.count { (it as Stay).afterTrackId == 1L })
        assertEquals(1, slices.count { (it as Stay).afterTrackId == 2L })
    }

    @Test fun `a crossing is cut into the day it left and the day it landed`() {
        // The shape of a flight: an absence beginning on one clock and ending on another, a day
        // later. It gets one row per end, each falling on the day that end happened, so the arrival
        // sits beside whatever was recorded on getting there rather than under yesterday.
        val newYork = ZoneId.of("America/New_York")
        val lisbonZone = ZoneId.of("Europe/Lisbon")
        val lisbonEvening = java.time.LocalDate.of(2024, 10, 20).atStartOfDay(lisbonZone)
            .plusHours(21).toInstant().toEpochMilli()
        val crossing = Gap(
            start = lisbonEvening, end = lisbonEvening + 26 * 60 * MIN,
            reason = GapReason.MOVED_UNRECORDED, afterTrackId = 1,
        )

        val halves =
            TimelineRows.slicePerDay(listOf(crossing), { Clocks(lisbonZone, newYork) }, lisbonEvening + 3 * DAY)

        assertEquals(2, halves.size)
        assertEquals(crossing.start, halves[0].interval.start)
        assertEquals(crossing.end, halves[1].interval.end)
        // Each half is stamped with the end it speaks for and null for the other, at the point of
        // cutting — so nothing downstream has to work out which half it is holding.
        assertEquals(lisbonZone, halves[0].departureZone)
        assertNull(halves[0].arrivalZone)
        assertNull(halves[1].departureZone)
        assertEquals(newYork, halves[1].arrivalZone)
        // The cut is the arrival's own midnight, so the second half is that day and nothing else.
        val cut = halves[1].interval.start
        assertEquals(halves[0].interval.end, cut)
        assertEquals(
            java.time.LocalTime.MIDNIGHT,
            java.time.Instant.ofEpochMilli(cut).atZone(newYork).toLocalTime(),
        )
        assertEquals(
            java.time.Instant.ofEpochMilli(crossing.end).atZone(newYork).toLocalDate(),
            java.time.Instant.ofEpochMilli(halves[1].interval.end!!).atZone(newYork).toLocalDate(),
        )
    }

    @Test fun `a crossing that lands on the day it left stays one row`() {
        // A short hop across a border: there is no second day to give a row to, so one row states
        // both of its ends.
        val newYork = ZoneId.of("America/New_York")
        val chicago = ZoneId.of("America/Chicago")
        val noon = java.time.LocalDate.of(2024, 10, 20).atStartOfDay(newYork)
            .plusHours(12).toInstant().toEpochMilli()
        val hop = Gap(
            start = noon, end = noon + 3 * 60 * MIN,
            reason = GapReason.MOVED_UNRECORDED, afterTrackId = 1,
        )

        val slices = TimelineRows.slicePerDay(listOf(hop), { Clocks(newYork, chicago) }, noon + DAY)

        // One piece, and it speaks for both ends — the row has to state each on its own clock.
        assertEquals(listOf<StayDeriver.Interval>(hop), slices.map { it.interval })
        assertEquals(newYork, slices.single().departureZone)
        assertEquals(chicago, slices.single().arrivalZone)
    }

    @Test fun `an absence spanning whole days is still two rows, not one per day`() {
        // The days in between are folded into the departure half rather than each getting a row:
        // they hold neither end, so a row for one could say nothing but that nothing is known. Two
        // is the count whatever the span — the same shape a crossing takes, for the same reason.
        val gap = Gap(
            start = 20 * 60 * MIN, end = 3 * DAY + 3 * 60 * MIN,
            reason = GapReason.MOVED_UNRECORDED, afterTrackId = 1,
        )

        val halves = TimelineRows.slicePerDay(listOf(gap), { Clocks.both(utc) }, 4 * DAY)
        assertEquals(
            listOf(20 * 60 * MIN to 3 * DAY, 3 * DAY to 3 * DAY + 3 * 60 * MIN),
            halves.map { it.interval.start to it.interval.end },
        )
    }

    // --- interleave ------------------------------------------------------------

    @Test fun `interleave merges tracks and intervals newest-first`() {
        val summaries = listOf(
            summary(2, startedAt = 240 * MIN),
            summary(1, startedAt = 60 * MIN),
        )
        val stay = Stay(120 * MIN, 240 * MIN, 1, clusterId = 0)
        val items = TimelineRows.interleave(summaries, oneClock(stay))
        assertEquals(
            listOf(240 * MIN, 120 * MIN, 60 * MIN),
            items.map { it.startedAt },
        )
        assertTrue(items[1] is TimelineItem.StayItem)
    }

    @Test fun `on a start-time tie an ongoing interval sorts newer than the track`() {
        val summaries = listOf(summary(1, startedAt = 60 * MIN))
        val stay = Stay(60 * MIN, null, 1, clusterId = 0)
        val items = TimelineRows.interleave(summaries, oneClock(stay))
        assertTrue(items[0] is TimelineItem.StayItem)
        assertTrue(items[1] is TimelineItem.TrackItem)
    }

    @Test fun `a zero-length seam stay sorts between the two tracks it separates`() {
        // The seam ties with the departing track's start; being closed, it must render
        // below that track — between the pair — not above it.
        val summaries = listOf(summary(2, startedAt = 120 * MIN), summary(1, startedAt = 60 * MIN))
        val seam = Stay(120 * MIN, 120 * MIN, 1, clusterId = 0)
        val items = TimelineRows.interleave(summaries, oneClock(seam))
        assertTrue(items[0] is TimelineItem.TrackItem)
        assertTrue(items[1] is TimelineItem.StayItem)
        assertTrue(items[2] is TimelineItem.TrackItem)
        assertEquals(2L, (items[0] as TimelineItem.TrackItem).summary.id)
    }

    @Test fun `a seam is a row only while it carries the offer to undo the join`() {
        // Two tracks sharing an instant leave a stay of no duration between them. It says nothing
        // about where anyone was, so what it is worth is whatever it offers: with a merge plan it
        // is the way back from the join, and with none it is a row about nothing.
        val seam = Stay(120 * MIN, 120 * MIN, 1, clusterId = 0)
        val bare = TimelineItem.StayItem(seam)

        assertTrue(bare.isBareSeam)
        assertFalse(bare.copy(merge = TrackMerge.Plan(earlierId = 1, laterId = 2)).isBareSeam)
        // A stop of any length is a stop, offer or no offer.
        assertFalse(TimelineItem.StayItem(seam.copy(end = 121 * MIN)).isBareSeam)
        // Nor is the ongoing stay one: it has no end, which is not the same as ending where it began.
        assertFalse(TimelineItem.StayItem(seam.copy(end = null)).isBareSeam)
    }

    @Test fun `intervals older than every track still reach the timeline`() {
        // The merge runs two cursors and the track list is the one that empties first on any history
        // whose oldest thing is a stay. Dropping the remainder would silently truncate the bottom of
        // the timeline — the end a reader scrolls to.
        val summaries = listOf(summary(2, startedAt = 240 * MIN))
        val gap = Gap(120 * MIN, 180 * MIN, GapReason.MOVED_UNRECORDED, afterTrackId = 1)

        val items = TimelineRows.interleave(summaries, oneClock(gap))

        assertEquals(2, items.size)
        assertTrue(items[0] is TimelineItem.TrackItem)
        assertTrue("a gap renders as a gap, not a stay", items[1] is TimelineItem.GapItem)
    }

    private fun summary(id: Long, startedAt: Long) = TrackSummary(
        id = id, activityType = "WALKING", startedAt = startedAt,
        endedAt = startedAt + 10 * MIN, distanceMeters = 1000.0, pointCount = 100, ignoredCount = 0,
        source = null,
    )

    // --- Zero-length gaps (trim seams) -------------------------------------

    @Test fun `a same-activity same-place zero gap is a zero-length stay - the trim seam`() {
        val stay = derive(seamPair()).filterIsInstance<Stay>().single()
        assertEquals(120 * MIN, stay.start)
        assertEquals(120 * MIN, stay.end)
        assertEquals(1L, stay.afterTrackId)
    }

    @Test fun `a zero gap at different places emits nothing, not a gap`() {
        assertTrue(derive(seamPair(from = office)).isEmpty())
    }

    @Test fun `a negative gap still emits nothing`() {
        val overlapping = listOf(
            track(1, start = 60 * MIN, end = 120 * MIN),
            track(2, start = 119 * MIN, end = 130 * MIN),
        )
        assertTrue(derive(overlapping).isEmpty())
    }

    @Test fun `an endpoint's cluster is its own, not a later endpoint's at the same coordinate`() {
        // Anchor A at 0 m holds q at 140 m. The next track founds B at 260 m, out of A's reach, and
        // a third track starting at q joins B, the nearer anchor that reaches it. The first q is
        // still A's: two endpoints at one coordinate, two clusters.
        val q = at(140.0)
        val tracks = listOf(
            track(1, start = 60 * MIN, end = 120 * MIN, from = at(0.0), to = q),
            track(2, start = 240 * MIN, end = 300 * MIN, from = at(260.0), to = at(260.0)),
            track(3, start = 420 * MIN, end = 480 * MIN, from = q, to = q),
        )
        // A and B disagree, and 120 m is past the agreement radius: a gap, from A to B.
        val first = derive(tracks).first { it.afterTrackId == 1L }
        assertTrue(first is Gap)
        assertNotEquals((first as Gap).fromClusterId, first.toClusterId)
    }
}
