package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.Place
import io.github.valeronm.breadcrumb.domain.StayDeriver.Derivation
import io.github.valeronm.breadcrumb.domain.StayDeriver.Endpoint
import io.github.valeronm.breadcrumb.domain.StayDeriver.Gap
import io.github.valeronm.breadcrumb.domain.StayDeriver.GapReason
import io.github.valeronm.breadcrumb.domain.StayDeriver.Interval
import io.github.valeronm.breadcrumb.domain.StayDeriver.Provenance
import io.github.valeronm.breadcrumb.domain.StayDeriver.Stay
import io.github.valeronm.breadcrumb.domain.StayDeriver.TrackEnd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Travels are runs of nights not spent at home, placed off the same intervals the timeline shows.
 * Fixtures lay a night out around the sample instant rather than on it: the rule's own hour is the
 * thing under test in exactly one case here, and every other case would break for the wrong reason
 * if it were pinned to the minute.
 *
 * **What decides a night is which cluster holds it, never how far it is.** The locations therefore
 * span two orders of magnitude — `nearbyHotel` five kilometres out, `otherHome` six hundred — and a
 * suite that passed only because the far ones are far would be pinning a rule this no longer has.
 */
class TravelDeriverTest {

    private val home = at(0.0)
    private val nearbyHotel = at(5_000.0)
    private val abroad = at(200_000.0)
    private val enRoute = at(300_000.0)
    private val farAbroad = at(400_000.0)
    private val otherHome = at(600_000.0)

    /** Cluster per location, in this order — a stay's and a gap's cluster ids index into it. */
    private val known = listOf(home, nearbyHotel, abroad, enRoute, farAbroad, otherHome)
    private val clusters = PlaceClusterer.cluster(known, distance = flatDistance)

    private val offset = TravelDeriver.solarOffsetOf(home.lon)

    private fun day(n: Int): LocalDate = LocalDate.of(2024, 1, 1).plusDays(n.toLong())

    /** Epoch ms at [hour] on day [n], on home's solar clock — so `t(n, 3)` is night n's sample. */
    private fun t(n: Int, hour: Int, minute: Int = 0): Long =
        day(n).atTime(hour, minute).toInstant(offset).toEpochMilli()

    private var nextTrackId = 0L

    /** Home as [locations]' clusters, with each one's capture area around it. */
    private fun homeAt(vararg locations: Endpoint) = TravelDeriver.Home(
        clusterIds = locations.map { known.indexOf(it) }.toSet(),
        seeds = locations.map { PlaceClusterer.Seed(it, PlaceClusterer.DEFAULT_RADIUS_M) },
    )

    private fun stay(
        location: Endpoint,
        from: Long,
        until: Long?,
        provenance: Provenance = Provenance.OBSERVED,
        cluster: Int = known.indexOf(location),
    ) = Stay(
        start = from, end = until, location = location, provenance = provenance,
        afterTrackId = ++nextTrackId, clusterId = cluster,
    )

    private fun gap(from: Long, until: Long, fromCluster: Int?, toCluster: Int?) = Gap(
        start = from, end = until,
        reason = if (fromCluster == null || toCluster == null) {
            GapReason.UNKNOWN_ENDPOINT
        } else {
            GapReason.MOVED_UNRECORDED
        },
        afterTrackId = ++nextTrackId, fromClusterId = fromCluster, toClusterId = toCluster,
    )

    private fun track(from: Endpoint?, to: Endpoint?, start: Long, end: Long) =
        TrackEnd(trackId = ++nextTrackId, startedAt = start, endedAt = end, start = from, end = to)

    private fun tagged(id: Long, label: String, location: Endpoint) = Place(
        id = id, label = label, lat = location.lat, lon = location.lon,
        createdAt = 0L, radiusM = PlaceClusterer.DEFAULT_RADIUS_M, category = PlaceCategory.HOME.code,
    )

    private fun derive(
        intervals: List<Interval>,
        tracks: List<TrackEnd> = emptyList(),
        home: TravelDeriver.Home = homeAt(this.home),
        now: Long = t(15, 12),
    ) = TravelDeriver.derive(
        TravelDeriver.Timeline(Derivation(intervals, clusters), tracks),
        home, now, flatDistance,
    )

    // --- what makes a night away ---------------------------------------------

    @Test fun `a hotel a few kilometres from home is away, because it is not home`() {
        // The rule this suite exists to pin: nothing here is far, and it is still a journey. Two
        // nights in a hotel down the road is not a night at home, and no radius gets a vote.
        val travels = derive(
            listOf(
                stay(home, t(0, 12), t(1, 8)),
                stay(nearbyHotel, t(1, 12), t(3, 20)),
                stay(home, t(4, 0), t(6, 12)),
            ),
            listOf(
                track(home, nearbyHotel, t(1, 8), t(1, 12)),
                track(nearbyHotel, home, t(3, 20), t(4, 0)),
            ),
        )

        val travel = travels.single()
        assertEquals(day(2), travel.firstNight)
        assertEquals(day(3), travel.lastNight)
        assertEquals(2, travel.nightCount)
    }

    @Test fun `consecutive nights away are one travel, bounded by the home stays around them`() {
        val travels = derive(
            listOf(
                stay(home, t(0, 12), t(1, 8)),
                stay(abroad, t(1, 14), t(4, 9)),
                stay(home, t(4, 15), t(6, 12)),
            ),
            listOf(
                track(home, abroad, t(1, 8), t(1, 14)),
                track(abroad, home, t(4, 9), t(4, 15)),
            ),
        )

        val travel = travels.single()
        assertEquals(day(2), travel.firstNight)
        assertEquals(day(4), travel.lastNight)
        assertEquals(3, travel.nightCount)
        assertEquals(t(1, 8), travel.leftHomeAt)
        assertEquals(t(4, 15), travel.reachedHomeAt)
        assertEquals(Provenance.OBSERVED, travel.provenance)
        // Only what was spent away, and only stays wholly inside the leaving and arriving bounds.
        assertEquals(mapOf(known.indexOf(abroad) to t(4, 9) - t(1, 14)), travel.clusterStayMs)
    }

    @Test fun `a day trip is not a travel, however far it reaches`() {
        val travels = derive(
            listOf(
                stay(home, t(0, 12), t(2, 8)),
                stay(farAbroad, t(2, 10), t(2, 18)),
                stay(home, t(2, 20), t(4, 12)),
            ),
            listOf(
                track(home, farAbroad, t(2, 8), t(2, 10)),
                track(farAbroad, home, t(2, 18), t(2, 20)),
            ),
        )

        assertTrue(travels.isEmpty())
    }

    @Test fun `a night at home splits a travel in two`() {
        // Going home ends a journey; a second one starts when the traveller leaves again.
        val travels = derive(
            listOf(
                stay(home, t(0, 12), t(1, 8)),
                stay(abroad, t(1, 12), t(2, 20)),
                stay(home, t(3, 0), t(3, 20)),
                stay(abroad, t(4, 0), t(5, 12)),
                stay(home, t(6, 0), t(7, 12)),
            ),
        )

        assertEquals(listOf(day(2) to day(2), day(4) to day(5)), travels.map { it.firstNight to it.lastNight })
    }

    @Test fun `a journey still being travelled counts the stay it is in`() {
        // Nothing has come home yet, so the ongoing stay ends at now rather than at a bound the
        // journey does not have — without it, the journey someone is on names itself after
        // wherever they were the day before, or after nothing at all.
        val now = t(3, 11)
        val travels = derive(
            listOf(
                stay(home, t(0, 12), t(1, 8)),
                stay(abroad, t(1, 12), null),
            ),
            listOf(track(home, abroad, t(1, 8), t(1, 12))),
            now = now,
        )

        val travel = travels.single()
        assertEquals(null, travel.reachedHomeAt)
        assertEquals(mapOf(known.indexOf(abroad) to now - t(1, 12)), travel.clusterStayMs)
    }

    @Test fun `a day out before leaving does not belong to the journey`() {
        // The night before flying is spent at home, but the next track is at the far end, so that
        // interval is a gap and the last stay *at* home is the morning before — a day out near home
        // sits between them. Bounding on that stay alone would hand the journey somewhere it never
        // went; the night is what the journey is, so the window reaches back only one.
        val travels = derive(
            listOf(
                stay(home, t(0, 12), t(1, 9)),
                stay(nearbyHotel, t(1, 11), t(1, 20)), // the day out, two days before the first night
                gap(t(1, 20), t(3, 6), fromCluster = known.indexOf(home), toCluster = known.indexOf(abroad)),
                stay(abroad, t(3, 6), t(5, 9)),
                stay(home, t(6, 12), t(8, 12)),
            ),
        )

        val travel = travels.single()
        assertEquals(day(4), travel.firstNight)
        assertEquals(t(1, 9), travel.leftHomeAt)
        // Bounded by the journey's first day, not by that last stay at home two days earlier.
        assertEquals(t(3, 0), travel.windowStart)
        assertEquals(mapOf(known.indexOf(abroad) to t(5, 9) - t(3, 6)), travel.clusterStayMs)
    }

    @Test fun `a stay straddling the return counts the part inside the journey`() {
        // The stay runs past the moment home was reached; only the time before it belongs here.
        val travels = derive(
            listOf(
                stay(home, t(0, 12), t(1, 8)),
                stay(abroad, t(1, 12), t(3, 20)),
                stay(home, t(3, 6), t(5, 12)),
            ),
            listOf(track(home, abroad, t(1, 8), t(1, 12))),
        )

        val travel = travels.single()
        assertEquals(t(3, 6), travel.reachedHomeAt)
        assertEquals(mapOf(known.indexOf(abroad) to t(3, 6) - t(1, 12)), travel.clusterStayMs)
    }

    // --- placing a night no stay covers --------------------------------------

    @Test fun `a night in motion is placed from the track carrying it`() {
        // No interval covers 03:00 — a night drive does. Halfway along a track leaving home is well
        // outside home's capture area, which is the whole of the test for a night with no cluster.
        val travels = derive(
            listOf(
                stay(home, t(0, 12), t(1, 0)),
                stay(abroad, t(1, 6), t(3, 12)),
                stay(home, t(3, 20), t(5, 12)),
            ),
            listOf(track(home, abroad, t(1, 0), t(1, 6))),
        )

        val travel = travels.single()
        assertEquals(day(1), travel.firstNight)
        assertEquals(day(3), travel.lastNight)
        // Fixes attest a night in motion as much as a stay does — nothing here is inferred.
        assertEquals(Provenance.OBSERVED, travel.provenance)
    }

    @Test fun `a night driving inside home's own capture area is still a night at home`() {
        val travels = derive(
            listOf(
                stay(home, t(0, 12), t(1, 2)),
                stay(home, t(1, 4), t(3, 12)),
            ),
            // A track that starts and ends at home and never leaves its capture area.
            listOf(track(home, home, t(1, 2), t(1, 4))),
        )

        assertTrue(travels.isEmpty())
    }

    @Test fun `a night inside a gap is placed only when both sides agree, and never as observed`() {
        val travels = derive(
            listOf(
                stay(home, t(0, 12), t(1, 8)),
                stay(abroad, t(1, 12), t(1, 20)),
                gap(t(1, 20), t(2, 20), fromCluster = known.indexOf(abroad), toCluster = known.indexOf(farAbroad)),
                stay(farAbroad, t(2, 20), t(4, 0)),
                stay(home, t(4, 12), t(6, 12)),
            ),
            listOf(track(home, abroad, t(1, 8), t(1, 12))),
        )

        val travel = travels.single()
        assertEquals(day(2), travel.firstNight)
        assertEquals(day(3), travel.lastNight)
        assertEquals(Provenance.INFERRED, travel.provenance)
    }

    @Test fun `a night the two sides disagree about opens no travel`() {
        // The move happened somewhere inside the gap; which side of it the night fell on is exactly
        // what the gap says nobody knows. The travel starts at the first night that can be placed.
        val travels = derive(
            listOf(
                stay(home, t(0, 12), t(1, 20)),
                gap(t(1, 20), t(2, 20), fromCluster = known.indexOf(home), toCluster = known.indexOf(abroad)),
                stay(abroad, t(2, 20), t(4, 0)),
                stay(home, t(4, 12), t(6, 12)),
            ),
        )

        val travel = travels.single()
        assertEquals(day(3), travel.firstNight)
        assertEquals(1, travel.nightCount)
    }

    @Test fun `an unplaceable night between two away nights stays inside the travel`() {
        val travels = derive(
            listOf(
                stay(home, t(0, 12), t(1, 8)),
                stay(abroad, t(1, 12), t(2, 20)),
                gap(t(2, 20), t(3, 20), fromCluster = known.indexOf(abroad), toCluster = null),
                stay(abroad, t(3, 20), t(5, 0)),
                stay(home, t(5, 12), t(7, 12)),
            ),
            listOf(track(home, abroad, t(1, 8), t(1, 12))),
        )

        val travel = travels.single()
        assertEquals(day(2), travel.firstNight)
        assertEquals(day(4), travel.lastNight)
        assertEquals(3, travel.nightCount)
        assertEquals(Provenance.INFERRED, travel.provenance)
    }

    @Test fun `a travel does not end on an unplaceable night`() {
        val travels = derive(
            listOf(
                stay(home, t(0, 12), t(1, 8)),
                stay(abroad, t(1, 12), t(3, 20)),
                gap(t(3, 20), t(5, 20), fromCluster = known.indexOf(abroad), toCluster = null),
            ),
            listOf(track(home, abroad, t(1, 8), t(1, 12))),
            now = t(5, 20),
        )

        val travel = travels.single()
        assertEquals(day(3), travel.lastNight)
        // Nothing came back, so nothing names an arrival.
        assertEquals(null, travel.reachedHomeAt)
    }

    // --- more than one home --------------------------------------------------

    @Test fun `nights at a second home are not away`() {
        val travels = derive(
            listOf(
                stay(home, t(0, 12), t(1, 8)),
                stay(otherHome, t(1, 12), t(4, 8)),
                stay(home, t(4, 20), t(6, 12)),
            ),
            listOf(
                track(home, otherHome, t(1, 8), t(1, 12)),
                track(otherHome, home, t(4, 8), t(4, 20)),
            ),
            home = homeAt(home, otherHome),
        )

        assertTrue(travels.isEmpty())
    }

    @Test fun `a journey between two homes is a travel only for the nights that fall between them`() {
        val travels = derive(
            listOf(
                stay(home, t(0, 12), t(1, 8)),
                stay(enRoute, t(1, 18), t(3, 8)),
                stay(otherHome, t(3, 18), t(6, 12)),
            ),
            listOf(
                track(home, enRoute, t(1, 8), t(1, 18)),
                track(enRoute, otherHome, t(3, 8), t(3, 18)),
            ),
            home = homeAt(home, otherHome),
        )

        val travel = travels.single()
        assertEquals(day(2), travel.firstNight)
        assertEquals(day(3), travel.lastNight)
        // Left one home, reached the other — the bounds name different places, which is why they
        // are not called a departure and a return.
        assertEquals(t(1, 8), travel.leftHomeAt)
        assertEquals(t(3, 18), travel.reachedHomeAt)
    }

    @Test fun `an overnight hop between two homes is no travel at all`() {
        // The night's sample lands inside the gap that carried it, and both of that gap's ends are
        // home — so there is no night away to derive a travel from.
        val travels = derive(
            listOf(
                stay(home, t(0, 12), t(1, 22)),
                gap(t(1, 22), t(2, 9), fromCluster = known.indexOf(home), toCluster = known.indexOf(otherHome)),
                stay(otherHome, t(2, 9), t(4, 12)),
            ),
            home = homeAt(home, otherHome),
        )

        assertTrue(travels.isEmpty())
    }

    // --- where home is -------------------------------------------------------

    @Test fun `a tagged home outranks where the nights were actually spent`() {
        // Nearly every night here was spent abroad; the user has still said which one is home.
        val places = listOf(tagged(1, "Home", home))
        val intervals = listOf(
            stay(home, t(0, 12), t(1, 8)),
            stay(abroad, t(1, 12), t(6, 0)),
        )
        val seeded = seededClusters(places)

        assertEquals(setOf(0), TravelDeriver.homeOf(places, seeded, intervals).clusterIds)
    }

    @Test fun `every tagged home is a home, the most slept-in one first`() {
        val places = listOf(tagged(1, "Flat", home), tagged(2, "House", otherHome))
        val seeded = seededClusters(places)
        val intervals = listOf(
            stay(home, t(0, 12), t(1, 8), cluster = 0),
            stay(otherHome, t(1, 12), t(6, 0), cluster = 1),
        )

        val resolved = TravelDeriver.homeOf(places, seeded, intervals)
        assertEquals(setOf(0, 1), resolved.clusterIds)
        // The head anchors the calendar of nights, so which one leads is part of the contract.
        assertEquals(otherHome, resolved.seeds.first().anchor)
    }

    @Test fun `with nothing tagged, home is the cluster that held the most nights`() {
        val intervals = listOf(
            stay(home, t(0, 12), t(1, 8)),
            stay(abroad, t(1, 12), t(6, 0)),
        )

        val resolved = TravelDeriver.homeOf(emptyList(), clusters, intervals)
        assertEquals(setOf(known.indexOf(abroad)), resolved.clusterIds)
        assertEquals(abroad, resolved.seeds.single().anchor)
    }

    @Test fun `no home means no travels rather than a guessed one`() {
        assertFalse(TravelDeriver.homeOf(emptyList(), clusters, emptyList()).isKnown)
        assertTrue(
            derive(
                listOf(stay(abroad, t(0, 12), t(6, 0))),
                home = TravelDeriver.Home(emptySet(), emptyList()),
            ).isEmpty(),
        )
    }

    private fun seededClusters(places: List<Place>) = PlaceClusterer.cluster(
        known, distance = flatDistance,
        seeds = places.map { PlaceClusterer.Seed(Endpoint(it.lat, it.lon), it.radiusM) },
    )

    // --- the days a screen marks ---------------------------------------------

    @Test fun `a journey covers one more day than it has nights`() {
        // The invariant, and the whole of the rule: a night belongs to the evening before it as much
        // as to the morning it ends on, so three nights away is four days on a calendar — the day of
        // setting out, then one per night.
        val travels = derive(
            listOf(
                stay(home, t(0, 12), t(1, 8)),
                stay(abroad, t(1, 14), t(4, 9)),
                stay(home, t(4, 15), t(6, 12)),
            ),
            listOf(
                track(home, abroad, t(1, 8), t(1, 14)),
                track(abroad, home, t(4, 9), t(4, 15)),
            ),
        )

        val travel = travels.single()
        assertEquals(3, travel.nightCount)
        assertEquals((1..4).map(::day), TravelDeriver.daysCovered(travel, offset))
    }

    @Test fun `a stale home stay cannot stretch the days covered`() {
        // Five nights nobody could place sit between the last night at home and the journey. Running
        // the cover from that stay would report a two-night trip as a week away; the nights alone
        // decide, so it stays two plus one.
        val travels = derive(
            listOf(
                stay(home, t(0, 12), t(1, 8)),
                gap(t(1, 8), t(6, 12), fromCluster = known.indexOf(home), toCluster = null),
                stay(abroad, t(6, 12), t(8, 20)),
                stay(home, t(9, 12), t(11, 12)),
            ),
        )

        val travel = travels.single()
        assertEquals(2, travel.nightCount)
        assertEquals(t(1, 8), travel.leftHomeAt)
        assertEquals(listOf(day(6), day(7), day(8)), TravelDeriver.daysCovered(travel, offset))
    }

    @Test fun `a journey after a hole keeps the day before its first night`() {
        // Home on day 1, then five days nothing could place, then five nights away and home again.
        // The departure itself is lost inside the hole, and the cover does not need it: five nights
        // is six days whatever the history around them looks like.
        val travels = derive(
            listOf(
                stay(home, t(0, 12), t(1, 8)),
                gap(t(1, 8), t(6, 10), fromCluster = known.indexOf(home), toCluster = known.indexOf(abroad)),
                stay(abroad, t(6, 10), t(11, 9)),
                stay(home, t(11, 18), t(14, 12)),
            ),
            listOf(track(abroad, home, t(11, 9), t(11, 18))),
        )

        val travel = travels.single()
        assertEquals(5, travel.nightCount)
        assertEquals(day(7), travel.firstNight)
        assertEquals(day(11), travel.lastNight)
        assertEquals((6..11).map(::day), TravelDeriver.daysCovered(travel, offset))
    }

    @Test fun `a travel nothing bounds is covered by its nights alone`() {
        val travels = derive(
            listOf(
                stay(home, t(0, 12), t(1, 8)),
                stay(abroad, t(1, 12), t(3, 20)),
                gap(t(3, 20), t(5, 20), fromCluster = known.indexOf(abroad), toCluster = null),
            ),
            listOf(track(home, abroad, t(1, 8), t(1, 12))),
            now = t(5, 20),
        )

        // Left home on day 1, never reached one. Two nights, day 2 and day 3, so three days.
        assertEquals((1..3).map(::day), TravelDeriver.daysCovered(travels.single(), offset))
    }

    // --- the solar clock -----------------------------------------------------

    @Test fun `nights are placed on solar time, 15 degrees to the hour`() {
        assertEquals(ZoneOffset.UTC, TravelDeriver.solarOffsetOf(0.0))
        assertEquals(ZoneOffset.ofHours(-8), TravelDeriver.solarOffsetOf(-120.0))
        assertEquals(ZoneOffset.ofHours(9), TravelDeriver.solarOffsetOf(135.0))
    }

    @Test fun `a night is sampled deep enough to miss an evening out and an early start`() {
        // The one case that pins the sample hour: away all evening, home from 01:00, out again at
        // 05:00. Neither end of that night is where it was spent, and the rule takes neither.
        val travels = derive(
            listOf(
                stay(abroad, t(0, 18), t(1, 1)),
                stay(home, t(1, 1), t(1, 5)),
                stay(abroad, t(1, 5), t(2, 20)),
                stay(home, t(3, 0), t(5, 12)),
            ),
        )

        assertEquals(listOf(day(2)), travels.map { it.firstNight })
    }
}
