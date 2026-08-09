package io.github.valeronm.breadcrumb.domain

import io.github.valeronm.breadcrumb.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The slot the arrival pause is decided by. The floor it waits is the resume window clamped up to
 * [ArrivalWatch.STANDSTILL_FLOOR_MS], and the window case pins the clamp at both ends: above it
 * the window is the wait, below it — zero included — the clamp is.
 *
 * The invariant the two silence cases pin is the consultation contract: [Motion.Unknown] moves
 * nothing in either direction. It neither opens a standstill (a witness silent throughout must
 * leave the recorder as it was without the witness) nor closes one (silence is what a parked phone
 * produces — fixes thin out and the window drains — so treating it as contrary evidence would
 * reset the slot at exactly the stops it exists to notice).
 */
class ArrivalWatchTest {

    private val watch = ArrivalWatch()
    private val clamp = ArrivalWatch.STANDSTILL_FLOOR_MS

    /** The shipped default window sits under the clamp, so the clamp is the wait. */
    private fun judge(
        motion: Motion,
        atMs: Long,
        windowMs: Long = Settings.DEFAULT_STITCH_RESUME_WINDOW_SEC * 1000L,
    ) = watch.onMotion(motion, atMs, windowMs)

    @Test fun `a standstill fires once it has stood the floor, backdated to its start`() {
        assertNull(judge(Motion.Stopped, T0))
        assertNull(judge(Motion.Stopped, T0 + clamp - 1))
        assertEquals(T0, judge(Motion.Stopped, T0 + clamp))
    }

    @Test fun `the standstill survives the witness draining to silence`() {
        assertNull(judge(Motion.Stopped, T0))
        assertNull(judge(Motion.Unknown, T0 + MINUTE))
        assertEquals(T0, judge(Motion.Unknown, T0 + clamp))
    }

    @Test fun `moving ground restarts the count`() {
        judge(Motion.Stopped, T0)
        judge(Motion.Moving(Speed.kmh(30.0)), T0 + MINUTE)
        judge(Motion.Stopped, T0 + 2 * MINUTE)
        assertNull(judge(Motion.Unknown, T0 + clamp + MINUTE))
        assertEquals(T0 + 2 * MINUTE, judge(Motion.Unknown, T0 + 2 * MINUTE + clamp))
    }

    @Test fun `a fire spends the standstill, so the next pause takes a full fresh floor`() {
        judge(Motion.Stopped, T0)
        assertEquals(T0, judge(Motion.Unknown, T0 + clamp))
        assertNull(judge(Motion.Unknown, T0 + 2 * clamp))
        judge(Motion.Stopped, T0 + 2 * clamp)
        assertEquals(T0 + 2 * clamp, judge(Motion.Stopped, T0 + 3 * clamp))
    }

    @Test fun `reset forgets a standstill still counting`() {
        judge(Motion.Stopped, T0)
        watch.reset()
        assertNull(judge(Motion.Unknown, T0 + clamp))
    }

    @Test fun `a witness silent throughout never fires`() {
        assertNull(judge(Motion.Unknown, T0))
        assertNull(judge(Motion.Unknown, T0 + 10 * clamp))
    }

    @Test fun `the resume window stretches the wait upward but can never shrink it`() {
        val windowMs = 10 * MINUTE
        assertNull(judge(Motion.Stopped, T0, windowMs))
        assertNull(judge(Motion.Stopped, T0 + clamp, windowMs))
        assertEquals(T0, judge(Motion.Stopped, T0 + windowMs, windowMs))
        // That fire spent the standstill; below the clamp — zero included — the clamp is the wait.
        val t1 = T0 + windowMs + MINUTE
        assertNull(judge(Motion.Stopped, t1, windowMs = 0L))
        assertNull(judge(Motion.Stopped, t1 + clamp - 1, windowMs = 0L))
        assertEquals(t1, judge(Motion.Stopped, t1 + clamp, windowMs = 0L))
    }

    private companion object {
        const val T0 = 1_700_000_000_000L
        const val MINUTE = 60_000L
    }
}
