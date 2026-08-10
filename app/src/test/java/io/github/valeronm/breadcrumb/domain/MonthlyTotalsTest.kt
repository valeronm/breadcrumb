package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * What a month adds up to, and what a window of months looks like beside it.
 *
 * Two rules carry the file. A row is filed under the month **its own clock** puts it in, not the
 * device's — which is what keeps this page agreeing with the Timeline about a trip abroad; and a
 * window states every month it covers, including the ones nothing happened in, because a bar strip
 * with a month missing misdates every bar after it.
 *
 * Fixtures are instants, zones and invented offsets — no coordinates that place anyone.
 */
class MonthlyTotalsTest {

    private val utc = ZoneId.of("UTC")
    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val honolulu = ZoneId.of("Pacific/Honolulu")

    private val now = at(utc, LocalDate.of(2026, 8, 6), 12)

    private fun at(zone: ZoneId, date: LocalDate, hour: Int) =
        date.atStartOfDay(zone).plusHours(hour.toLong()).toInstant().toEpochMilli()

    private var nextId = 0L

    private fun track(
        activity: ActivityType,
        startedAt: Long,
        meters: Double,
        zone: ZoneId = utc,
    ) = TimelineItem.TrackItem(
        summary = trackSummary(++nextId, activity.name, startedAt, startedAt + 30 * MIN, meters),
        zone = zone,
    )

    /** A track whose stored type no build of this app can read — a legacy code from an old install. */
    private fun legacyTrack(stored: String, startedAt: Long, meters: Double) =
        track(ActivityType.WALKING, startedAt, meters).let {
            it.copy(summary = it.summary.copy(activityType = stored))
        }

    private fun stay(
        category: PlaceCategory?,
        start: Long,
        end: Long?,
        zone: ZoneId = utc,
    ) = TimelineItem.StayItem(
        stay = StayDeriver.Stay(
            start = start, end = end,
            provenance = StayDeriver.Provenance.OBSERVED, afterTrackId = ++nextId, clusterId = 0,
        ),
        place = PlaceResolver.ResolvedStay(
            place = Place(
                id = 1, label = "Somewhere", lat = 1.0, lon = -2.0, createdAt = 0L,
                radiusM = 150.0, category = category?.code,
            ),
            visitCount = 1,
            centroid = Coordinate(1.0, -2.0),
        ),
        zone = zone,
    )

    private fun derive(vararg items: TimelineItem) = MonthlyTotals.derive(items.toList(), now, utc)

    private fun month(year: Int, month: Int) = YearMonth.of(year, month)

    // A month's four figures, each read back as the plain map a case wants to assert against.
    private fun MonthTotals.meters() = totals.activities.mapValues { it.value.meters }
    private fun MonthTotals.movingMs() = totals.activities.mapValues { it.value.durationMs }
    private fun MonthTotals.durations() = totals.categories.mapValues { it.value.durationMs }
    private fun MonthTotals.visits() = totals.categories.mapValues { it.value.visits }

    @Test fun `distances of one activity in one month sum together`() {
        val months = derive(
            track(ActivityType.DRIVING, at(utc, LocalDate.of(2026, 5, 3), 9), 12_000.0),
            track(ActivityType.DRIVING, at(utc, LocalDate.of(2026, 5, 28), 9), 8_000.0),
            track(ActivityType.WALKING, at(utc, LocalDate.of(2026, 5, 12), 9), 3_000.0),
        )
        assertEquals(listOf(month(2026, 5)), months.map { it.month })
        assertEquals(
            mapOf(ActivityType.DRIVING.name to 20_000.0, ActivityType.WALKING.name to 3_000.0),
            months.single().meters(),
        )
    }

    @Test fun `an unreadable stored type keeps a bucket of its own`() {
        val months = derive(
            legacyTrack("SEGWAY", at(utc, LocalDate.of(2026, 5, 3), 9), 1_000.0),
            legacyTrack("HORSE", at(utc, LocalDate.of(2026, 5, 4), 9), 2_000.0),
        )
        assertEquals(
            mapOf("SEGWAY" to 1_000.0, "HORSE" to 2_000.0),
            months.single().meters(),
        )
    }

    @Test fun `stays sum per category, and the excluded ones count for nothing`() {
        val may = LocalDate.of(2026, 5, 10)
        val months = derive(
            stay(PlaceCategory.WORK, at(utc, may, 9), at(utc, may, 17)),
            stay(PlaceCategory.WORK, at(utc, may, 19), at(utc, may, 20)),
            stay(PlaceCategory.HOME, at(utc, may, 20), at(utc, may, 23)),
            stay(PlaceCategory.PARKING, at(utc, may, 8), at(utc, may, 9)),
            stay(null, at(utc, may, 6), at(utc, may, 7)),
        )
        assertEquals(
            mapOf(PlaceCategory.WORK to 9 * 60 * MIN),
            months.single().durations(),
        )
    }

    @Test fun `an open stay runs to now`() {
        val start = at(utc, LocalDate.of(2026, 8, 6), 10)
        val months = derive(stay(PlaceCategory.WORK, start, null))
        assertEquals(mapOf(PlaceCategory.WORK to 2 * 60 * MIN), months.single().durations())
    }

    // A month is decided the way a day is: by the row's own clock. Same instant, two zones either
    // side of a month boundary — and the device's zone decides neither of them.
    @Test fun `a row is filed on its own clock, not the device's`() {
        // Midday on the 1st in Tokyo is still the previous month's last evening in Honolulu.
        val instant = at(tokyo, LocalDate.of(2026, 6, 1), 12)
        assertEquals(
            listOf(month(2026, 6)),
            derive(track(ActivityType.DRIVING, instant, 1_000.0, zone = tokyo)).map { it.month },
        )
        assertEquals(
            listOf(month(2026, 5)),
            derive(track(ActivityType.DRIVING, instant, 1_000.0, zone = honolulu)).map { it.month },
        )
    }

    @Test fun `a row nothing placed falls back to the device's clock`() {
        val instant = at(tokyo, LocalDate.of(2026, 6, 1), 6)
        val unplaced = track(ActivityType.DRIVING, instant, 1_000.0).copy(zone = null)
        assertEquals(listOf(month(2026, 5)), MonthlyTotals.derive(listOf(unplaced), now, utc).map { it.month })
        assertEquals(listOf(month(2026, 6)), MonthlyTotals.derive(listOf(unplaced), now, tokyo).map { it.month })
    }

    @Test fun `gaps add nothing`() {
        val months = derive(
            TimelineItem.GapItem(
                gap = StayDeriver.Gap(
                    start = at(utc, LocalDate.of(2026, 5, 3), 9),
                    end = at(utc, LocalDate.of(2026, 5, 4), 9),
                    reason = StayDeriver.GapReason.MOVED_UNRECORDED,
                    afterTrackId = 1,
                ),
                departureZone = utc,
                arrivalZone = utc,
            ),
        )
        assertTrue(months.isEmpty())
    }

    // A month can hold rows and still hold no figures. Listing it would offer the reader a month to
    // step to and then tell them it was empty — and it would move where the arrows stop.
    @Test fun `a month of stays nothing can be attributed to is no month at all`() {
        val may = LocalDate.of(2026, 5, 10)
        val months = derive(
            stay(null, at(utc, may, 9), at(utc, may, 12)),
            stay(PlaceCategory.HOME, at(utc, may, 13), at(utc, may, 20)),
            stay(PlaceCategory.PARKING, at(utc, may, 21), at(utc, may, 22)),
        )
        assertTrue(months.isEmpty())
    }

    @Test fun `months come out oldest first`() {
        val months = derive(
            track(ActivityType.DRIVING, at(utc, LocalDate.of(2026, 5, 3), 9), 1.0),
            track(ActivityType.DRIVING, at(utc, LocalDate.of(2025, 12, 3), 9), 1.0),
            stay(
                PlaceCategory.WORK,
                at(utc, LocalDate.of(2026, 2, 3), 9),
                at(utc, LocalDate.of(2026, 2, 3), 17),
            ),
        )
        assertEquals(
            listOf(month(2025, 12), month(2026, 2), month(2026, 5)),
            months.map { it.month },
        )
    }

    // ---- windows ----

    @Test fun `a window states every month it covers, empty ones included`() {
        val months = derive(
            track(ActivityType.DRIVING, at(utc, LocalDate.of(2026, 3, 3), 9), 5_000.0),
            track(ActivityType.DRIVING, at(utc, LocalDate.of(2026, 8, 3), 9), 7_000.0),
        )
        val window = MonthlyTotals.window(months, month(2026, 8))
        assertEquals(12, window.size)
        assertEquals(month(2025, 9), window.first().month)
        assertEquals(month(2026, 8), window.last().month)
        assertEquals(
            List(12) { index -> index == 6 || index == 11 },
            window.map { it.meters().isNotEmpty() },
        )
    }

    @Test fun `a window ending before the history holds nothing`() {
        val months = derive(track(ActivityType.DRIVING, at(utc, LocalDate.of(2026, 8, 3), 9), 7_000.0))
        val window = MonthlyTotals.window(months, month(2020, 1))
        assertTrue(window.all { it.meters().isEmpty() && it.durations().isEmpty() })
        assertEquals(month(2020, 1), window.last().month)
    }

    // ---- series ----

    @Test fun `a series carries one value per month of the window, in its order`() {
        val months = derive(
            track(ActivityType.DRIVING, at(utc, LocalDate.of(2026, 7, 3), 9), 5_000.0),
            track(ActivityType.DRIVING, at(utc, LocalDate.of(2026, 8, 3), 9), 7_000.0),
        )
        val series = MonthlyTotals.activitySeries(MonthlyTotals.window(months, month(2026, 8))).single()
        assertEquals(ActivityType.DRIVING.name, series.key)
        assertEquals(List(10) { 0.0 } + listOf(5_000.0, 7_000.0), series.values)
        assertEquals(7_000.0, series.latest, 0.0)
        assertEquals(12_000.0, series.total, 0.0)
        assertEquals(7_000.0, series.peak, 0.0)
    }

    @Test fun `series come out biggest over the window first`() {
        val months = derive(
            track(ActivityType.WALKING, at(utc, LocalDate.of(2026, 8, 3), 9), 3_000.0),
            track(ActivityType.DRIVING, at(utc, LocalDate.of(2026, 8, 3), 9), 40_000.0),
            track(ActivityType.CYCLING, at(utc, LocalDate.of(2026, 8, 3), 9), 12_000.0),
        )
        assertEquals(
            listOf(ActivityType.DRIVING.name, ActivityType.CYCLING.name, ActivityType.WALKING.name),
            MonthlyTotals.activitySeries(MonthlyTotals.window(months, month(2026, 8))).map { it.key },
        )
    }

    // "None this month" and "never" are different answers, and a row that vanished would leave the
    // reader unable to tell them apart. So the window decides the rows, not the month being shown.
    @Test fun `a metric the shown month lacks still gets its row`() {
        val months = derive(
            track(ActivityType.FLIGHT, at(utc, LocalDate.of(2026, 2, 3), 9), 900_000.0),
            track(ActivityType.DRIVING, at(utc, LocalDate.of(2026, 8, 3), 9), 7_000.0),
        )
        val series = MonthlyTotals.activitySeries(MonthlyTotals.window(months, month(2026, 8)))
        assertEquals(listOf(ActivityType.FLIGHT.name, ActivityType.DRIVING.name), series.map { it.key })
        assertEquals(0.0, series.first().latest, 0.0)
    }

    @Test fun `a metric outside the window gets no series at all`() {
        val months = derive(track(ActivityType.FLIGHT, at(utc, LocalDate.of(2024, 2, 3), 9), 900_000.0))
        assertTrue(MonthlyTotals.activitySeries(MonthlyTotals.window(months, month(2026, 8))).isEmpty())
    }

    @Test fun `category series measure milliseconds`() {
        val day = LocalDate.of(2026, 8, 3)
        val months = derive(stay(PlaceCategory.WORK, at(utc, day, 9), at(utc, day, 17)))
        val series = MonthlyTotals.categorySeries(MonthlyTotals.window(months, month(2026, 8))).single()
        assertEquals(PlaceCategory.WORK, series.key)
        assertEquals((8 * 60 * MIN).toDouble(), series.latest, 0.0)
    }

    // ---- the second measure ----

    @Test fun `an activity states the time its distance took`() {
        val months = derive(
            track(ActivityType.DRIVING, at(utc, LocalDate.of(2026, 8, 3), 9), 12_000.0),
            track(ActivityType.DRIVING, at(utc, LocalDate.of(2026, 8, 4), 9), 8_000.0),
        )
        // Each fixture track runs half an hour.
        assertEquals(mapOf(ActivityType.DRIVING.name to 60 * MIN), months.single().movingMs())
        val series = MonthlyTotals.activitySeries(MonthlyTotals.window(months, month(2026, 8))).single()
        assertEquals(20_000.0, series.latest, 0.0)
        assertEquals((60 * MIN).toDouble(), series.secondary, 0.0)
    }

    @Test fun `a category states how many visits its hours were spread over`() {
        val day = LocalDate.of(2026, 8, 3)
        val months = derive(
            stay(PlaceCategory.GROCERIES, at(utc, day, 9), at(utc, day, 10)),
            stay(PlaceCategory.GROCERIES, at(utc, day, 17), at(utc, day, 18)),
            stay(PlaceCategory.WORK, at(utc, day, 11), at(utc, day, 16)),
        )
        assertEquals(
            mapOf(PlaceCategory.GROCERIES to 2, PlaceCategory.WORK to 1),
            months.single().visits(),
        )
    }

    /**
     * The trap this counter exists to avoid: the timeline arrives cut per day, so one long stay is
     * several rows. They share the `afterTrackId` of the interval they were cut from, which is what
     * makes them one visit.
     */
    @Test fun `a stay sliced across days is one visit, not one per day`() {
        val slicedTrackId = 77L
        val slices = (3..6).map { day ->
            val start = at(utc, LocalDate.of(2026, 8, day), 0)
            stay(PlaceCategory.TRAVEL, start, start + 24 * 60 * MIN).let {
                it.copy(stay = it.stay.copy(afterTrackId = slicedTrackId))
            }
        }
        val months = MonthlyTotals.derive(slices, now, utc)
        assertEquals(mapOf(PlaceCategory.TRAVEL to 1), months.single().visits())
        assertEquals(4 * 24 * 60 * MIN, months.single().durations()[PlaceCategory.TRAVEL])
    }

    // Cut by the month rather than by the day, the same stay is one visit on each side — which is
    // what it was, from either month's point of view.
    @Test fun `a stay spanning a month boundary is one visit in each`() {
        val slicedTrackId = 78L
        val slices = listOf(LocalDate.of(2026, 7, 31) to 0, LocalDate.of(2026, 8, 1) to 0)
            .map { (day, hour) ->
                val start = at(utc, day, hour)
                stay(PlaceCategory.TRAVEL, start, start + 24 * 60 * MIN).let {
                    it.copy(stay = it.stay.copy(afterTrackId = slicedTrackId))
                }
            }
        val months = MonthlyTotals.derive(slices, now, utc)
        assertEquals(listOf(month(2026, 7), month(2026, 8)), months.map { it.month })
        assertTrue(months.all { it.visits() == mapOf(PlaceCategory.TRAVEL to 1) })
    }
}
