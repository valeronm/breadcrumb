package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class StopFinderTest {

    private fun pt(meters: Double, s: Int) = TrackPoint(
        trackId = 1,
        latitude = ORIGIN_LAT,
        longitude = lonAt(meters),
        altitude = null,
        accuracy = null,
        speed = null,
        bearing = null,
        timestamp = s * 1_000L,
    )

    private fun move(fromM: Double, fromS: Int, count: Int, step: Double = 10.0, every: Int = 1) =
        (0 until count).map { pt(fromM + it * step, fromS + it * every) }

    private fun stand(atM: Double, fromS: Int, count: Int, every: Int = 1) =
        (0 until count).map { pt(atM + if (it % 2 == 0) 2.0 else -2.0, fromS + it * every) }

    private fun mill(atM: Double, fromS: Int, seconds: Int) =
        (0 until seconds).map { pt(atM + 12.0 * sin(2 * PI * (fromS + it) / 120.0), fromS + it) }

    /** The settle pass flags a stop's inside and leaves its two ends. */
    private fun flagged(points: List<TrackPoint>) = points.mapIndexed { i, p ->
        if (i == 0 || i == points.lastIndex) p else p.flag(IgnoreReason.STATIONARY)
    }

    private fun TrackPoint.flag(reason: IgnoreReason) = copy(ignored = true, ignoreReason = reason.code)

    private fun find(points: List<TrackPoint>) = StopFinder.find(points, StopFinder.SHIPPED, flatDistance)

    @Test
    fun `a two-minute standstill mid-drive is one stop`() {
        val stop = find(move(0.0, 0, 120) + flagged(stand(1195.0, 120, 120)) + move(1205.0, 240, 120)).single()
        assertEquals(setOf(StopFinder.Signal.SLOW, StopFinder.Signal.STATIONARY), stop.signals)
        assertTrue(stop.startTs <= 120_000L && stop.endTs >= 239_000L)
    }

    @Test
    fun `a red light is no stop`() {
        val points = move(0.0, 0, 120) + stand(1195.0, 120, 40) + move(1205.0, 160, 120) +
            stand(2400.0, 280, 120) + move(2410.0, 400, 120)
        assertTrue(find(points).single().startTs >= 200_000L)
    }

    @Test
    fun `a long gap whose ends meet is one stop`() {
        val stop = find(move(0.0, 0, 120) + pt(1195.0, 120) + pt(1204.0, 1020) + move(1214.0, 1021, 120)).single()
        assertEquals(120_000L, stop.startTs)
        assertEquals(1_020_000L, stop.endTs)
        assertEquals(setOf(StopFinder.Signal.GAP), stop.signals)
    }

    @Test
    fun `a gap whose ends lie apart is movement unseen`() {
        val points = move(0.0, 0, 120) + move(1590.0, 300, 120) + stand(2785.0, 420, 120) + move(2795.0, 540, 120)
        assertTrue(find(points).single().startTs >= 400_000L)
    }

    @Test
    fun `milling broken by drift and a gap is one stop`() {
        val points = move(0.0, 0, 120) +
            mill(1200.0, 120, 180) +
            (300 until 310).map { pt(1250.0, it) } +
            mill(1200.0, 310, 90) +
            mill(1200.0, 448, 92) +
            move(1210.0, 540, 120)
        val stop = find(points).single()
        assertTrue(stop.startTs <= 130_000L && stop.endTs >= 530_000L)
        assertTrue(StopFinder.Signal.GAP in stop.signals && StopFinder.Signal.SLOW in stop.signals)
    }

    @Test
    fun `two stops apart in time and place stay two`() {
        val points = move(0.0, 0, 120) + stand(1195.0, 120, 120) + move(1205.0, 240, 300, step = 1.0) +
            stand(1510.0, 540, 120) + move(1520.0, 660, 120)
        assertEquals(2, find(points).size)
    }

    @Test
    fun `a stop reaching the last fix is left to the edge trim`() {
        val points = move(0.0, 0, 120) + stand(1195.0, 120, 120) + move(1205.0, 240, 120) + stand(2400.0, 360, 120)
        assertTrue(find(points).single().endTs <= 300_000L)
    }

    @Test
    fun `a crawl at walking pace is movement`() {
        val points = move(0.0, 0, 120) + move(1200.0, 120, 180, step = 0.83) + move(1350.0, 300, 60) +
            stand(1945.0, 360, 120) + move(1955.0, 480, 120)
        assertTrue(find(points).single().startTs >= 300_000L)
    }

    @Test
    fun `a crawl under two kilometers an hour is a stop`() {
        val points = move(0.0, 0, 120) + move(1200.0, 120, 240, step = 0.28) + move(1270.0, 360, 120)
        assertEquals(1, find(points).size)
    }

    @Test
    fun `a crawl of exactly the slow bar is a stop`() {
        val wholeMeters = DistanceFn { aLat, aLon, bLat, bLon ->
            Math.round(flatDistance.meters(aLat, aLon, bLat, bLon)).toDouble()
        }
        val points = move(0.0, 0, 120) + move(1200.0, 120, 240, step = 0.5) + move(1320.0, 360, 120)
        val stops = StopFinder.find(points, StopFinder.SHIPPED, wholeMeters)
        assertEquals(listOf(120_000L to 360_000L), stops.map { it.startTs to it.endTs })
    }

    @Test
    fun `a missed fix on a sparse dawdle is no gap`() {
        val dawdle = move(0.0, 0, 20, 21.0, every = 30).filterIndexed { i, _ -> i != 10 }
        val stood = stand(400.0, 600, 10, every = 30)
        val on = move(420.0, 900, 20, 21.0, every = 30)
        assertTrue(find(dawdle + stood + on).single().startTs >= 500_000L)
    }

    @Test
    fun `a stop starting at the first fix is left to the edge trim`() {
        val points = stand(0.0, 0, 120) + move(10.0, 120, 120) + stand(1210.0, 240, 120) + move(1220.0, 360, 120)
        assertEquals(listOf(237_000L), find(points).map { it.startTs })
    }

    @Test
    fun `rejected fixes are left out`() {
        val burst = (170 until 190).map { pt(1695.0, it).flag(IgnoreReason.JUMP) }
        val points = move(0.0, 0, 120) + stand(1195.0, 120, 50) + burst + stand(1195.0, 190, 50) +
            move(1205.0, 240, 120)
        val stop = find(points).single()
        assertTrue(stop.startTs <= 130_000L && stop.endTs >= 240_000L)
        assertTrue(stop.spreadM < 100.0)
    }

    @Test
    fun `two standstills a short drive apart stay two stops`() {
        val points = move(0.0, 0, 120) + stand(1195.0, 120, 70) + move(1205.0, 190, 20) +
            stand(1405.0, 210, 70) + move(1415.0, 280, 120)
        assertEquals(2, find(points).size)
    }

    @Test
    fun `duplicated timestamps do not shrink a sparse track's spacing`() {
        val dawdle = move(0.0, 0, 20, 21.0, every = 30).filterIndexed { i, _ -> i != 10 }
        val stood = stand(400.0, 600, 10, every = 30)
        val on = move(420.0, 900, 20, 21.0, every = 30)
        val doubled = (dawdle + stood + on).flatMap { listOf(it, it) }
        assertTrue(find(doubled).single().startTs >= 500_000L)
    }

    @Test
    fun `a track with no stored writer is read`() {
        assertTrue(StopFinder.runsFor(null))
        assertTrue(StopFinder.runsFor("no-such-writer"))
    }

    @Test
    fun `only recorded tracks are read`() {
        assertTrue(StopFinder.runsFor(TrackOrigin.RECORDED.code))
        assertFalse(StopFinder.runsFor(TrackOrigin.MANUAL.code))
        assertFalse(StopFinder.runsFor(TrackOrigin.IMPORTED.code))
        assertFalse(StopFinder.runsFor(TrackOrigin.GOOGLE_TIMELINE.code))
    }
}
