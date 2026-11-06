package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.db.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SpeedSmootherTest {

    private fun pt(second: Int) = TrackPoint(
        trackId = 1,
        latitude = 1.0,
        longitude = 0.0,
        altitude = null,
        accuracy = null,
        speed = null,
        bearing = null,
        timestamp = second * 1_000L,
    )

    private fun smooth(kmh: List<Float>, errorKmh: List<Float> = List(kmh.size) { 0.7f }, breaks: Set<Int> = emptySet()) =
        SpeedSmoother.smoothedKmh(
            List(kmh.size) { pt(it) },
            kmh.toFloatArray(),
            errorKmh.toFloatArray(),
            BooleanArray(kmh.size) { it in breaks },
        )

    /** A drive at 50 km/h braking at 3 m/s² to a 10 s stop, then pulling away at 2 m/s², a fix a second. */
    private fun stopAtALight(): List<Float> {
        val mps = ArrayList<Float>()
        repeat(30) { mps += 50 / 3.6f }
        while (mps.last() > 0f) mps += maxOf(0f, mps.last() - 3f)
        repeat(10) { mps += 0f }
        while (mps.last() < 50 / 3.6f) mps += minOf(50 / 3.6f, mps.last() + 2f)
        repeat(30) { mps += 50 / 3.6f }
        return mps.map { it * 3.6f }
    }

    @Test
    fun `a short stop at a light is drawn down to a standstill`() {
        val kmh = stopAtALight()
        val stop = kmh.indices.filter { kmh[it] == 0f }

        val smoothed = smooth(kmh)

        assertTrue(stop.minOf { smoothed[it] } < 3f)
    }

    @Test
    fun `wobble at a steady pace is evened out`() {
        val kmh = List(120) { if (it % 2 == 0) 52f else 48f }

        val smoothed = smooth(kmh)

        for (i in 10 until 110) assertEquals(50f, smoothed[i], 0.5f)
    }

    @Test
    fun `a reading the receiver reports as poor pulls the line less than a sound one`() {
        val kmh = List(61) { if (it == 30) 80f else 50f }

        val sound = smooth(kmh)[30]
        val poor = smooth(kmh, List(61) { if (it == 30) 36f else 0.7f })[30]

        assertTrue("$poor against $sound", poor - 50f < (sound - 50f) / 4)
    }

    @Test
    fun `a reading with no accuracy weighs as a sound one`() {
        val kmh = List(61) { if (it == 30) 80f else 50f }

        val unknown = smooth(kmh, List(61) { if (it == 30) Float.NaN else 0.7f })[30]

        assertEquals(smooth(kmh)[30], unknown, 0.01f)
    }

    @Test
    fun `a fix with nothing measured leaves the line where its neighbors put it`() {
        val kmh = List(61) { if (it == 30) 0f else 50f }

        val smoothed = smooth(kmh, List(61) { if (it == 30) Float.POSITIVE_INFINITY else 0.7f })

        assertEquals(50f, smoothed[30], 0.01f)
    }

    @Test
    fun `no reading is averaged across a break`() {
        val kmh = List(60) { if (it < 30) 20f else 80f }

        val smoothed = smooth(kmh, breaks = setOf(30))

        for (i in 0 until 30) assertEquals(20f, smoothed[i], 0.01f)
        for (i in 30 until 60) assertEquals(80f, smoothed[i], 0.01f)
    }

    @Test
    fun `a standstill never reads below zero`() {
        val kmh = List(60) { if (it % 3 == 0) 1f else 0f }

        assertTrue(smooth(kmh).all { it >= 0f })
    }

    @Test
    fun `pulling away is not spread over the half minute around it`() {
        val kmh = stopAtALight()
        val firstMoving = kmh.indices.first { it > 0 && kmh[it - 1] == 0f && kmh[it] > 0f }

        val smoothed = smooth(kmh)

        assertTrue(abs(smoothed[firstMoving + 10] - kmh[firstMoving + 10]) < 3f)
    }
}
