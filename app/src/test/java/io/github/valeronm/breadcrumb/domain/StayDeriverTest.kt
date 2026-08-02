package io.github.valeronm.breadcrumb.domain

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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * Stays derive from inter-track gaps + liveness evidence. Distance is stubbed as the flat-earth
 * metric scaled so 0.001 lat ≈ 100 m — tests place endpoints by "degrees" and reason in meters.
 */
class StayDeriverTest {

    private val home = Endpoint(1.0, 1.0)
    private val nearHome = Endpoint(1.0005, 1.0) // 50 m away — agrees
    private val office = Endpoint(2.0, 2.0)

    /** A named-place pin at venue scale (the default place radius is 150 m; venues get widened). */
    private fun pin(meters: Double, radiusM: Double = 350.0) = PlaceClusterer.Seed(at(meters), radiusM)

    private val NOW = 1_000 * MIN

    private fun track(id: Long, start: Long, end: Long, from: Endpoint? = home, to: Endpoint? = home) =
        TrackEnd(trackId = id, startedAt = start, endedAt = end, start = from, end = to)

    private fun derive(
        tracks: List<TrackEnd>,
        liveness: List<Liveness> = listOf(Armed(0)),
        now: Long = NOW,
        active: StayDeriver.ActiveTrack? = null,
        pins: List<PlaceClusterer.Seed> = emptyList(),
    ) = StayDeriver.derive(tracks, liveness, now, active, StayDeriver.Params(), flatDistance, pins)
        .intervals

    /** Two tracks whose gap is [120, 240) min, both ending/starting near `home`. */
    private fun homePair(to: Endpoint? = home, from: Endpoint? = nearHome) = listOf(
        track(1, start = 60 * MIN, end = 120 * MIN, to = to),
        track(2, start = 240 * MIN, end = 300 * MIN, from = from),
    )

    /** A trim seam: two same-place tracks sharing the boundary instant (0 ms gap). */
    private fun seamPair(from: Endpoint? = home) = listOf(
        track(1, start = 60 * MIN, end = 120 * MIN, to = home),
        track(2, start = 120 * MIN, end = 130 * MIN, from = from),
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
        // First liveness evidence arrives after the gap — history predating any liveness rows.
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

    @Test fun `a gap names the track it follows, like a stay`() {
        // The handle the merge offer needs: from it the UI reaches both tracks around the gap.
        assertEquals(1L, (derive(homePair(from = office)).first { it is Gap } as Gap).afterTrackId)
    }

    @Test fun `a tail gap into the active track names the last finished track`() {
        val gap = derive(
            listOf(track(7, start = 60 * MIN, end = 120 * MIN)),
            active = StayDeriver.ActiveTrack(startedAt = 200 * MIN, start = office),
        ).single() as Gap
        assertEquals(7L, gap.afterTrackId)
    }

    @Test fun `a moved-unrecorded gap indexes both sides' distinct clusters`() {
        val derivation = StayDeriver.derive(
            homePair(from = office), listOf(Armed(0)), NOW, null, StayDeriver.Params(), flatDistance,
        )
        val gap = derivation.intervals.first { it is Gap } as Gap
        // The disagreement that made this a gap: the sides sit in different clusters,
        // and each id indexes a real cluster containing its endpoint.
        assertNotEquals(gap.fromClusterId, gap.toClusterId)
        assertTrue(derivation.clusters[gap.fromClusterId!!].members.contains(home))
        assertTrue(derivation.clusters[gap.toClusterId!!].members.contains(office))
    }

    @Test fun `endpoints exactly at the agreement radius still count as a stay`() {
        // 0.001° = exactly 100 m = the radius; the rule is ≤.
        val stays = derive(homePair(from = Endpoint(1.001, 1.0))).filterIsInstance<Stay>()
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
            now = 300 * MIN, active = StayDeriver.ActiveTrack(startedAt = 300 * MIN),
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
            homePair(), listOf(Armed(0)), NOW, null, StayDeriver.Params(), flatDistance,
        )
        val stay = derivation.intervals.filterIsInstance<Stay>().first()
        val anchor = derivation.clusters[stay.clusterId].anchor
        assertTrue(flatDistance.meters(anchor.lat, anchor.lon, home.lat, home.lon) <= 150.0)
    }

    @Test fun `a pinned venue's stay indexes into the pin's seeded cluster`() {
        val derivation = StayDeriver.derive(
            homePair(from = at(300.0)), listOf(Armed(0)), NOW, null,
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

    @Test fun `a last track with no end coordinate derives no tail stay`() {
        // A track whose every fix was rejected has nowhere to put the stay that follows it. The
        // alternative is inventing a location, which would place a visit at a spot nothing observed.
        val intervals = derive(listOf(track(1, start = 60 * MIN, end = 120 * MIN, to = null)))
        assertTrue(intervals.isEmpty())
    }

    @Test fun `no pin near the earlier endpoint leaves the pair a gap`() {
        // The pin override answers "same *named* place", so it can only agree when both endpoints
        // reach a pin. Tested from this side too because the two are separate reads: a pin found for
        // the later endpoint and none for the earlier must not read as agreement by omission.
        val intervals = derive(homePair(to = at(600.0), from = at(0.0)), pins = listOf(pin(0.0)))
        assertEquals(GapReason.MOVED_UNRECORDED, (intervals.first { it is Gap } as Gap).reason)
    }

    @Test fun `a second disarm does not move the first`() {
        // Only the earliest disarm bounds what the app can attest to. A later one is the recorder
        // being re-armed and disarmed inside a stay it has already stopped vouching for.
        val intervals = derive(
            listOf(track(1, start = 60 * MIN, end = 120 * MIN)),
            liveness = listOf(Armed(0), Disarmed(500 * MIN), Disarmed(600 * MIN)),
        )
        assertEquals(500 * MIN, intervals.filterIsInstance<Stay>().single().end)
    }

    @Test fun `a gap of one minute is a stay - there is no minimum`() {
        // Not a default that could be raised: a brief stop is what a place accumulates visits from,
        // so the floor lives with each reader instead. See the rule on StayDeriver.
        val intervals = derive(
            listOf(
                track(1, start = 60 * MIN, end = 120 * MIN),
                track(2, start = 120 * MIN + 60_000, from = nearHome, end = 300 * MIN),
            ),
            now = 300 * MIN, active = StayDeriver.ActiveTrack(startedAt = 300 * MIN),
        )
        assertEquals(1, intervals.filterIsInstance<Stay>().size)
    }

    private fun stayAt(start: Long, end: Long?) = Stay(
        start = start, end = end, location = home,
        provenance = Provenance.OBSERVED, afterTrackId = 1L, clusterId = 0,
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
            now = 300 * MIN, active = StayDeriver.ActiveTrack(startedAt = 300 * MIN),
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

    @Test fun `recording closes the tail stay at the active track's start`() {
        val stay = derive(
            listOf(track(1, start = 60 * MIN, end = 120 * MIN)),
            active = StayDeriver.ActiveTrack(startedAt = 200 * MIN),
        ).filterIsInstance<Stay>().single()
        assertEquals(120 * MIN, stay.start)
        assertEquals(200 * MIN, stay.end)
        assertEquals(home, stay.location)
    }

    @Test fun `an active track that started immediately leaves no tail interval`() {
        assertTrue(
            derive(
                listOf(track(1, start = 60 * MIN, end = 120 * MIN)),
                active = StayDeriver.ActiveTrack(startedAt = 120 * MIN),
            ).isEmpty(),
        )
    }

    @Test fun `an active track whose first fix disagrees makes the tail a gap`() {
        val gap = derive(
            listOf(track(1, start = 60 * MIN, end = 120 * MIN)),
            active = StayDeriver.ActiveTrack(startedAt = 200 * MIN, start = office),
        ).single() as Gap
        assertEquals(GapReason.MOVED_UNRECORDED, gap.reason)
        assertEquals(120 * MIN, gap.start)
        assertEquals(200 * MIN, gap.end)
        assertNotEquals(gap.fromClusterId, gap.toClusterId)
    }

    @Test fun `an active track whose first fix agrees keeps the tail a stay`() {
        val stay = derive(
            listOf(track(1, start = 60 * MIN, end = 120 * MIN)),
            active = StayDeriver.ActiveTrack(startedAt = 200 * MIN, start = nearHome),
        ).filterIsInstance<Stay>().single()
        assertEquals(200 * MIN, stay.end)
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

    /** The pieces alone: most cases below are about where the cuts land, not which clock each end
     *  of a piece ran on — the crossing cases ask [StayDeriver.slicePerDay] for that directly. */
    private fun sliced(
        intervals: List<StayDeriver.Interval>,
        zones: (StayDeriver.Interval) -> Pair<ZoneId, ZoneId>,
        nowMs: Long,
    ): List<StayDeriver.Interval> = StayDeriver.slicePerDay(intervals, zones, nowMs).map { it.interval }

    /** Intervals as slices that happened in one place — what everything but a crossing is. */
    private fun oneClock(vararg intervals: StayDeriver.Interval) =
        intervals.map { StayDeriver.Slice(it, utc, utc) }

    @Test fun `a midnight-spanning stay splits into per-day slices with clamped bounds`() {
        val stay = Stay(
            start = 20 * 60 * MIN, end = DAY + 9 * 60 * MIN, location = home,
            provenance = Provenance.OBSERVED, afterTrackId = 1, clusterId = 0,
        )
        val slices = sliced(listOf(stay), { utc to utc }, nowMs = 2 * DAY)
        assertEquals(2, slices.size)
        assertEquals(20 * 60 * MIN, slices[0].start)
        assertEquals(DAY, slices[0].end)
        assertEquals(DAY, slices[1].start)
        assertEquals(DAY + 9 * 60 * MIN, slices[1].end)
        assertTrue(slices.all { it is Stay && it.provenance == Provenance.OBSERVED })
    }

    @Test fun `an ongoing stay keeps its null end on the final slice only`() {
        val stay = Stay(
            start = 20 * 60 * MIN, end = null, location = home,
            provenance = Provenance.OBSERVED, afterTrackId = 1, clusterId = 0,
        )
        val slices = sliced(listOf(stay), { utc to utc }, nowMs = DAY + 9 * 60 * MIN)
        assertEquals(2, slices.size)
        assertEquals(DAY, slices[0].end)
        assertNull(slices[1].end)
    }

    @Test fun `an intra-day interval passes through unchanged`() {
        val gap = Gap(
            start = 10 * 60 * MIN, end = 11 * 60 * MIN,
            reason = GapReason.MOVED_UNRECORDED, afterTrackId = 1,
        )
        assertEquals(listOf<StayDeriver.Interval>(gap), sliced(listOf(gap), { utc to utc }, 2 * DAY))
    }

    @Test fun `a midnight-spanning gap slices like a stay does`() {
        // Slicing is per interval kind, and a gap keeps its reason across the cut — a day showing
        // half of one must still say why the ground went unrecorded.
        val gap = Gap(
            start = 20 * 60 * MIN, end = DAY + 3 * 60 * MIN,
            reason = GapReason.UNKNOWN_ENDPOINT, afterTrackId = 1,
        )
        val slices = sliced(listOf(gap), { utc to utc }, 2 * DAY)
        assertEquals(2, slices.size)
        assertEquals(DAY, slices[0].end)
        assertEquals(DAY + 3 * 60 * MIN, slices[1].end)
        assertTrue(slices.all { it is Gap && it.reason == GapReason.UNKNOWN_ENDPOINT })
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
            listOf(Stay(start, end, home, Provenance.OBSERVED, 1, clusterId = 0)), { lisbon to lisbon }, end + DAY,
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
        val abroad = Stay(evening, evening + 8 * 60 * MIN, home, Provenance.OBSERVED, 1, clusterId = 0)
        val athome = Stay(evening, evening + 8 * 60 * MIN, home, Provenance.OBSERVED, 2, clusterId = 1)
        val zones = mapOf(1L to tokyo, 2L to utc)

        val slices = sliced(
            listOf(abroad, athome),
            { zones.getValue((it as Stay).afterTrackId).let { z -> z to z } },
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
            StayDeriver.slicePerDay(listOf(crossing), { lisbonZone to newYork }, lisbonEvening + 3 * DAY)

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

        val slices = StayDeriver.slicePerDay(listOf(hop), { newYork to chicago }, noon + DAY)

        // One piece, and it speaks for both ends — the row has to state each on its own clock.
        assertEquals(listOf<StayDeriver.Interval>(hop), slices.map { it.interval })
        assertEquals(newYork, slices.single().departureZone)
        assertEquals(chicago, slices.single().arrivalZone)
    }

    @Test fun `an absence that stays on one clock is cut like anything else`() {
        // The other half of the rule above: same zone at both ends is an ordinary coverage gap, and
        // a day it runs through must still be able to say so.
        val gap = Gap(
            start = 20 * 60 * MIN, end = DAY + 3 * 60 * MIN,
            reason = GapReason.MOVED_UNRECORDED, afterTrackId = 1,
        )

        assertEquals(2, sliced(listOf(gap), { utc to utc }, 2 * DAY).size)
    }

    // --- interleave ------------------------------------------------------------

    @Test fun `interleave merges tracks and intervals newest-first`() {
        val summaries = listOf(
            summary(2, startedAt = 240 * MIN),
            summary(1, startedAt = 60 * MIN),
        )
        val stay = Stay(120 * MIN, 240 * MIN, home, Provenance.OBSERVED, 1, clusterId = 0)
        val items = StayDeriver.interleave(summaries, oneClock(stay))
        assertEquals(
            listOf(240 * MIN, 120 * MIN, 60 * MIN),
            items.map { it.startedAt },
        )
        assertTrue(items[1] is TimelineItem.StayItem)
    }

    @Test fun `on a start-time tie an ongoing interval sorts newer than the track`() {
        val summaries = listOf(summary(1, startedAt = 60 * MIN))
        val stay = Stay(60 * MIN, null, home, Provenance.OBSERVED, 1, clusterId = 0)
        val items = StayDeriver.interleave(summaries, oneClock(stay))
        assertTrue(items[0] is TimelineItem.StayItem)
        assertTrue(items[1] is TimelineItem.TrackItem)
    }

    @Test fun `a zero-length seam stay sorts between the two tracks it separates`() {
        // The seam ties with the departing track's start; being closed, it must render
        // below that track — between the pair — not above it.
        val summaries = listOf(summary(2, startedAt = 120 * MIN), summary(1, startedAt = 60 * MIN))
        val seam = Stay(120 * MIN, 120 * MIN, home, Provenance.OBSERVED, 1, clusterId = 0)
        val items = StayDeriver.interleave(summaries, oneClock(seam))
        assertTrue(items[0] is TimelineItem.TrackItem)
        assertTrue(items[1] is TimelineItem.StayItem)
        assertTrue(items[2] is TimelineItem.TrackItem)
        assertEquals(2L, (items[0] as TimelineItem.TrackItem).summary.id)
    }

    @Test fun `intervals older than every track still reach the timeline`() {
        // The merge runs two cursors and the track list is the one that empties first on any history
        // whose oldest thing is a stay. Dropping the remainder would silently truncate the bottom of
        // the timeline — the end a reader scrolls to.
        val summaries = listOf(summary(2, startedAt = 240 * MIN))
        val gap = Gap(120 * MIN, 180 * MIN, GapReason.MOVED_UNRECORDED, afterTrackId = 1)

        val items = StayDeriver.interleave(summaries, oneClock(gap))

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

    /** The intervals between the two tracks — the ongoing tail stay after the last track always
     *  derives too and is not what these cases assert about. */
    private fun betweenTracks(tracks: List<TrackEnd>) =
        derive(tracks).filter { it.end != null }

    @Test fun `a same-activity same-place zero gap is a zero-length stay - the trim seam`() {
        val stay = betweenTracks(seamPair()).filterIsInstance<Stay>().single()
        assertEquals(120 * MIN, stay.start)
        assertEquals(120 * MIN, stay.end)
        assertEquals(1L, stay.afterTrackId)
    }

    @Test fun `a zero gap at different places emits nothing, not a gap`() {
        assertTrue(betweenTracks(seamPair(from = office)).isEmpty())
    }

    @Test fun `a negative gap still emits nothing`() {
        val overlapping = listOf(
            track(1, start = 60 * MIN, end = 120 * MIN),
            track(2, start = 119 * MIN, end = 130 * MIN),
        )
        assertTrue(betweenTracks(overlapping).isEmpty())
    }
}
