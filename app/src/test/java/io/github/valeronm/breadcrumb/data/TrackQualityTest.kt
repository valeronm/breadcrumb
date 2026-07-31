package io.github.valeronm.breadcrumb.data

import io.github.valeronm.breadcrumb.data.db.TrackPoint
import io.github.valeronm.breadcrumb.domain.ActivityType
import io.github.valeronm.breadcrumb.domain.DistanceFn
import io.github.valeronm.breadcrumb.domain.IgnoreReason
import io.github.valeronm.breadcrumb.domain.Motion
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The bad-fix rule, tested purely on how it *handles* a distance — not on how that distance is
 * computed. Each test injects a [DistanceFn] that returns exactly the gap it wants to assert about,
 * so the cases read as a distance/time → verdict table with no coordinates or geodesy involved.
 */
class TrackQualityTest {

    private val WALKING = ActivityType.WALKING
    private val DRIVING = ActivityType.DRIVING

    /** A fixed gap in meters, regardless of the coordinates passed. */
    private fun gap(meters: Double) = DistanceFn { _, _, _, _ -> meters }

    /** The gates as configured, 50 m accuracy and the GNSS cross-check off, unless a case varies one. */
    private fun gates(
        maxAccuracyM: Float = 50f,
        gnssBacked: Boolean? = null,
        motion: Motion = Motion.Unknown,
    ) = TrackQuality.Gates(maxAccuracyM, gnssBacked, motion)

    private fun point(
        timestamp: Long,
        accuracy: Float? = 5f,
        lat: Double = 0.0,
        lon: Double = 0.0,
        speed: Float? = null,
    ) = TrackPoint(
        trackId = 1,
        latitude = lat,
        longitude = lon,
        altitude = null,
        accuracy = accuracy,
        speed = speed,
        bearing = null,
        timestamp = timestamp,
    )

    // --- Accuracy gate (independent of distance) -------------------------

    @Test fun `a fix at or past the accuracy radius is bad`() {
        val bad = point(timestamp = 1_000, accuracy = 50f)
        assertNotNull(TrackQuality.badFixReason(point(0), bad, WALKING, gates(), distance = gap(1.0)))
    }

    @Test fun `a fix inside the accuracy radius is not gated on accuracy`() {
        val ok = point(timestamp = 1_000, accuracy = 49f)
        assertNull(TrackQuality.badFixReason(point(0), ok, WALKING, gates(), distance = gap(1.0)))
    }

    @Test fun `a fix with unknown accuracy skips the accuracy gate`() {
        val ok = point(timestamp = 5_000, accuracy = null)
        assertNull(TrackQuality.badFixReason(point(0), ok, WALKING, gates(), distance = gap(5.0)))
    }

    // --- First point of a track / segment -------------------------------

    @Test fun `the first point of a segment is never a bad fix`() {
        assertNull(TrackQuality.badFixReason(null, point(0), WALKING, gates(), distance = gap(9_999.0)))
    }

    // --- Implausible-speed teleport (per activity) ----------------------

    @Test fun `a walking teleport beyond plausible speed is bad`() {
        // 100 m in 1 s = 360 km/h, well past walking's 12 km/h ceiling.
        assertNotNull(TrackQuality.badFixReason(point(0), point(1_000), WALKING, gates(), distance = gap(100.0)))
    }

    @Test fun `a normal walking step is kept`() {
        // 8 m in 5 s ≈ 5.8 km/h, under 12 km/h.
        assertNull(TrackQuality.badFixReason(point(0), point(5_000), WALKING, gates(), distance = gap(8.0)))
    }

    @Test fun `the speed ceiling is per activity`() {
        // 300 m in 5 s = 216 km/h: implausible on foot, fine in a vehicle (ceiling 220).
        val prev = point(0)
        val next = point(5_000)
        assertNotNull(TrackQuality.badFixReason(prev, next, WALKING, gates(), distance = gap(300.0)))
        assertNull(TrackQuality.badFixReason(prev, next, DRIVING, gates(), distance = gap(300.0)))
    }

    // --- Zero / negative time gap ---------------------------------------

    @Test fun `a large jump over a non-positive time gap is bad`() {
        // Same timestamp, > MIN_JUMP_M apart → treated as an infinite-speed teleport.
        assertNotNull(TrackQuality.badFixReason(point(1_000), point(1_000), DRIVING, gates(), distance = gap(11.0)))
    }

    @Test fun `a tiny jump over a non-positive time gap is not bad`() {
        // Same timestamp but within MIN_JUMP_M → jitter, not a teleport.
        assertNull(TrackQuality.badFixReason(point(1_000), point(1_000), WALKING, gates(), distance = gap(9.0)))
    }

    // --- GNSS cross-check, and its precedence over the other two reasons ---
    // The rule, not its caller, decides the cross-check and how it ranks against the other
    // reasons — which is what makes these rows pinnable from a host test.

    @Test fun `an unbacked fix is NO_GNSS`() {
        assertEquals(
            IgnoreReason.NO_GNSS,
            TrackQuality.badFixReason(point(0), point(1_000), WALKING, gates(gnssBacked = false), gap(1.0)),
        )
    }

    @Test fun `a backed fix is judged on its own merits`() {
        assertNull(TrackQuality.badFixReason(point(0), point(1_000), WALKING, gates(gnssBacked = true), gap(1.0)))
        assertEquals(
            IgnoreReason.JUMP,
            TrackQuality.badFixReason(point(0), point(1_000), WALKING, gates(gnssBacked = true), gap(300.0)),
        )
    }

    @Test fun `the cross-check switched off is not the same as unbacked`() {
        // null = don't ask. A fix that would be NO_GNSS with the setting on must come back good.
        assertNull(TrackQuality.badFixReason(point(0), point(1_000), WALKING, gates(gnssBacked = null), gap(1.0)))
    }

    @Test fun `unbacked outranks a bad accuracy radius and a teleport`() {
        // A fabricated position's other numbers say nothing, so the reason reported is the one that
        // explains the fix — not whichever gate happens to be checked first.
        val fabricated = point(1_000, accuracy = 999f)
        assertEquals(
            IgnoreReason.NO_GNSS,
            TrackQuality.badFixReason(point(0), fabricated, WALKING, gates(gnssBacked = false), gap(9_999.0)),
        )
    }

    @Test fun `accuracy still outranks a teleport when the fix is backed`() {
        val imprecise = point(1_000, accuracy = 999f)
        assertEquals(
            IgnoreReason.ACCURACY,
            TrackQuality.badFixReason(point(0), imprecise, WALKING, gates(gnssBacked = true), gap(9_999.0)),
        )
    }

    // --- The ceiling under a measured-motion verdict ---------------------
    // The cases above all run with `Motion.Unknown` — what the recorder passes when the motion
    // cross-check is off — and stay unedited on purpose: their green run is what pins that the
    // off state changes nothing.

    /** A carried journey at roughly 20 km/h — the case the cross-check exists for. */
    private val CARRIED = Motion.Moving(5.6)

    /** A brisk walk, ~5.8 km/h — a pace the walking label explains perfectly well. */
    private val BRISK_WALK = Motion.Moving(1.6)

    @Test fun `measured ground speed lifts a carrier's fixes over the pedestrian ceiling`() {
        // 11.2 m in 2 s = 20.2 km/h — the deck's own cruise, and rejected as a teleport on the
        // label alone, though the whole crossing is made of steps like it.
        val prev = point(0)
        val next = point(2_000)
        assertEquals(
            IgnoreReason.JUMP,
            TrackQuality.badFixReason(prev, next, WALKING, gates(), gap(11.2)),
        )
        assertNull(TrackQuality.badFixReason(prev, next, WALKING, gates(motion = CARRIED), gap(11.2)))
    }

    @Test fun `abstaining leaves the label's ceiling exactly as it was`() {
        val prev = point(0)
        val next = point(2_000)
        assertEquals(
            IgnoreReason.JUMP,
            TrackQuality.badFixReason(prev, next, WALKING, gates(motion = Motion.Unknown), gap(11.2)),
        )
        assertEquals(
            IgnoreReason.JUMP,
            TrackQuality.badFixReason(prev, next, WALKING, gates(motion = Motion.Stopped), gap(11.2)),
        )
    }

    @Test fun `a step far beyond the measured pace is still a teleport`() {
        // The margin is generous, not unlimited: 40 m in 2 s is 72 km/h against a window that
        // measured 20 — a verdict buys a fix the benefit of the doubt, not an exemption.
        assertEquals(
            IgnoreReason.JUMP,
            TrackQuality.badFixReason(point(0), point(2_000), WALKING, gates(motion = CARRIED), gap(40.0)),
        )
    }

    @Test fun `a verdict never lowers a ceiling the label already granted`() {
        // Crawling ground under a drive label must not turn the drive's own fixes into teleports.
        val crawling = Motion.Moving(1.0)
        assertEquals(
            TrackQuality.jumpCeilingKmh(DRIVING),
            TrackQuality.jumpCeilingKmh(DRIVING, crawling),
            0.0,
        )
        assertNull(TrackQuality.badFixReason(point(0), point(5_000), DRIVING, gates(motion = crawling), gap(300.0)))
    }

    @Test fun `the ceiling keeps a margin over the window average it was derived from`() {
        // The verdict's speed is a window *average*; a carrier accelerating is instantaneously well
        // above it, and a ceiling drawn tight to the average would reject exactly those fixes.
        val ceiling = TrackQuality.jumpCeilingKmh(WALKING, CARRIED)
        assertTrue("$ceiling should clear the observed 20.2 km/h with room", ceiling > 40.0)
    }

    @Test fun `motion overrules a label only when it outruns that label's ceiling`() {
        // The one predicate behind the live "Moving" display: a carried pace overrules a walking
        // label but not a driving one, and anything short of Moving overrules nothing.
        assertTrue(TrackQuality.motionOverrules(WALKING, CARRIED))
        assertFalse(TrackQuality.motionOverrules(DRIVING, CARRIED))
        assertFalse(TrackQuality.motionOverrules(WALKING, Motion.Unknown))
        assertFalse(TrackQuality.motionOverrules(WALKING, Motion.Stopped))
    }

    @Test fun `a walking pace does not overrule the walking label`() {
        // The margin the ceiling keeps is not this predicate's to spend: at 2.5x a brisk walk clears
        // WALKING's 12 km/h on the margin alone, and the recorder announced "Moving" on ordinary
        // walks. The ceiling still keeps its margin — being generous there only ever keeps a fix.
        assertFalse(TrackQuality.motionOverrules(WALKING, BRISK_WALK))
        assertTrue(TrackQuality.jumpCeilingKmh(WALKING, BRISK_WALK) > TrackQuality.jumpCeilingKmh(WALKING))
    }

    @Test fun `the group ceiling is the most permissive of the group's labels`() {
        // The carrier-evidence speed channel measures against this: a run inside a walking-labelled
        // track sustains speeds above WALKING's own ceiling, so the group's is the honest bar.
        assertEquals(
            TrackQuality.jumpCeilingKmh(ActivityType.RUNNING),
            TrackQuality.groupCeilingKmh(WALKING),
            0.0,
        )
        assertEquals(
            TrackQuality.jumpCeilingKmh(DRIVING),
            TrackQuality.groupCeilingKmh(DRIVING),
            0.0,
        )
    }

    @Test fun `no verdict can lift a fix above what some label already allows`() {
        // A lone teleport is fed to the confirmer like any other fix, so one can carry a window to
        // Moving on its own. The clamp is what bounds the cost: at worst the fix is judged as
        // though the track were a drive.
        val absurd = Motion.Moving(1_000.0)
        assertEquals(
            TrackQuality.jumpCeilingKmh(DRIVING),
            TrackQuality.jumpCeilingKmh(WALKING, absurd),
            0.0,
        )
    }

    // --- distanceMeters just delegates to the DistanceFn ----------------

    @Test fun `distanceMeters returns the supplied function's value`() {
        assertEquals(42.0, TrackQuality.distanceMeters(point(0), point(1_000), gap(42.0)), 0.0)
    }

    // --- pointSpeedsKmh (map speed coloring) ---------------------------
    // Derived-speed cases inject a fixed gap() so the derivation runs on the host without the
    // Android Location distance (there's no unitTests.returnDefaultValues, so the default would throw).

    @Test fun `a reported speed is converted m per s to km per h`() {
        // 2 m/s -> 7.2 km/h. First point uses its reported speed; no distance involved.
        assertArrayEquals(floatArrayOf(7.2f), TrackQuality.pointSpeedsKmh(listOf(point(0, speed = 2f))), 1e-3f)
    }

    @Test fun `a reported speed wins over the derivable one`() {
        // Second point reports 3 m/s (10.8 km/h); the gap is never consulted for it.
        val pts = listOf(point(0, speed = 1f), point(5_000, speed = 3f))
        assertArrayEquals(floatArrayOf(3.6f, 10.8f), TrackQuality.pointSpeedsKmh(pts, gap(999.0)), 1e-3f)
    }

    @Test fun `a missing or negative reported speed falls back to the derived one`() {
        // 10 m over 5 s = 2 m/s = 7.2 km/h, from the injected gap.
        val absent = listOf(point(0), point(5_000))
        assertArrayEquals(floatArrayOf(0f, 7.2f), TrackQuality.pointSpeedsKmh(absent, gap(10.0)), 1e-3f)
        val negative = listOf(point(0), point(5_000, speed = -1f))
        assertArrayEquals(floatArrayOf(0f, 7.2f), TrackQuality.pointSpeedsKmh(negative, gap(10.0)), 1e-3f)
    }

    @Test fun `a non-positive time gap carries the previous speed instead of reporting a stop`() {
        // 50 m over 5 s = 36 km/h, then a fix at the same instant: unmeasurable, not stopped.
        val pts = listOf(point(0), point(5_000), point(5_000), point(10_000))
        assertArrayEquals(
            floatArrayOf(0f, 36f, 36f, 36f),
            TrackQuality.pointSpeedsKmh(pts, gap(50.0)),
            1e-3f,
        )
    }

    @Test fun `a zero gap on the second point has nothing to carry`() {
        val pts = listOf(point(1_000), point(1_000))
        assertArrayEquals(floatArrayOf(0f, 0f), TrackQuality.pointSpeedsKmh(pts, gap(50.0)), 1e-3f)
    }

    @Test fun `the first point with no reported speed is zero`() {
        assertArrayEquals(floatArrayOf(0f), TrackQuality.pointSpeedsKmh(listOf(point(0)), gap(50.0)), 1e-3f)
    }

    @Test fun `an empty track yields an empty array`() {
        assertArrayEquals(floatArrayOf(), TrackQuality.pointSpeedsKmh(emptyList(), gap(1.0)), 1e-3f)
    }

    // --- Seams (the one walk the track screen's series share) ---------------
    // The cases above all inject a *constant* gap, which is exactly what cannot catch a seam array
    // read one index off. These vary the distance per seam so a shift shows up.

    /** Distance = the metres between the two longitudes, so every seam differs. */
    private val perSeam = DistanceFn { _, aLon, _, bLon -> bLon - aLon }

    @Test fun `a seam's distance sits at the index of the point it arrives at`() {
        val pts = listOf(point(0, lon = 0.0), point(1_000, lon = 10.0), point(2_000, lon = 110.0))
        // Nothing precedes the first fix, then 0→10 m and 10→110 m.
        assertArrayEquals(doubleArrayOf(0.0, 10.0, 100.0), TrackQuality.seams(pts, perSeam).meters, 1e-9)
    }

    @Test fun `speeds off a shared walk match the ones off a walk of their own`() {
        // 10 m in 1 s = 36 km/h, then 100 m in 1 s = 360 km/h — distinct per seam, so reading the
        // wrong index changes the answer.
        val pts = listOf(point(0, lon = 0.0), point(1_000, lon = 10.0), point(2_000, lon = 110.0))
        assertArrayEquals(
            floatArrayOf(0f, 36f, 360f),
            TrackQuality.pointSpeedsKmh(TrackQuality.seams(pts, perSeam)),
            1e-3f,
        )
        assertArrayEquals(
            TrackQuality.pointSpeedsKmh(pts, perSeam),
            TrackQuality.pointSpeedsKmh(TrackQuality.seams(pts, perSeam)),
            1e-3f,
        )
    }

    @Test fun `an empty track has no seams`() {
        assertArrayEquals(doubleArrayOf(), TrackQuality.seams(emptyList(), perSeam).meters, 1e-9)
    }

    // --- Stray leading point (drive-start cold-start artifact in imports) -----
    // The rule is relative, not an absolute ceiling: the first seam is a stray when it's much faster
    // than the real pace that follows (a car pulling out does a few km/h, not 180 in the opening
    // second). Per-seam distance is keyed by the from-point's latitude, and every point is one second
    // apart unless a test says otherwise, so seam speed (km/h) = gap-meters × 3.6.

    /** Per-seam gaps keyed by the from-point's latitude: lat n -> gaps[n] meters to the next. */
    private fun seamGaps(vararg gaps: Double) = DistanceFn { la1, _, la2, _ ->
        gaps[minOf(la1, la2).toInt()]
    }

    /** Five points, lat 0..4, one second apart — four seams driven by [seamGaps]. */
    private fun fivePoints() = List(5) { i -> point(timestamp = i * 1_000L, lat = i.toDouble()) }

    @Test fun `a stray first point is detected when the rest of the track is a slow start`() {
        // First seam 180 km/h; the car then crawls out at ~14 km/h — an impossible launch.
        val pts = fivePoints()
        assertTrue(TrackQuality.leadingPointIsJump(pts, seamGaps(50.0, 4.0, 4.0, 4.0)))
    }

    @Test fun `a fast start that stays fast is not a stray`() {
        // Already on the motorway: every seam ~100 km/h, first seam no outlier.
        val pts = fivePoints()
        assertFalse(TrackQuality.leadingPointIsJump(pts, seamGaps(28.0, 28.0, 28.0, 28.0)))
    }

    @Test fun `a genuine acceleration from a stop is not a stray`() {
        // First seam slow (~4 km/h), speeding up after — the opposite shape from a stray.
        val pts = fivePoints()
        assertFalse(TrackQuality.leadingPointIsJump(pts, seamGaps(1.0, 17.0, 17.0, 17.0)))
    }

    @Test fun `a sub-ceiling stray is still caught`() {
        // 100 km/h first seam is under the driving jump ceiling, yet impossible one second after
        // setting off when the real pace is ~14 km/h — the stray an absolute ceiling cannot catch.
        val pts = fivePoints()
        assertTrue(TrackQuality.leadingPointIsJump(pts, seamGaps(28.0, 4.0, 4.0, 4.0)))
    }

    @Test fun `a modest first seam below the floor is not a stray`() {
        // ~29 km/h first seam: faster than the follow pace, but a plausible launch — not flagged.
        val pts = fivePoints()
        assertFalse(TrackQuality.leadingPointIsJump(pts, seamGaps(8.0, 0.5, 0.5, 0.5)))
    }

    @Test fun `a first seam not far enough above the follow pace is not a stray`() {
        // First 180, follow 72 km/h — fast but only 2.5x, within a plausible ramp-up.
        val pts = fivePoints()
        assertFalse(TrackQuality.leadingPointIsJump(pts, seamGaps(50.0, 20.0, 20.0, 20.0)))
    }

    @Test fun `a degenerate second seam is skipped when judging the follow pace`() {
        // Points 1 and 2 share a timestamp (the real data's shape); the follow pace comes from
        // the later valid seams, and the stray is still caught.
        val pts = listOf(
            point(timestamp = 0, lat = 0.0),
            point(timestamp = 1_000, lat = 1.0),
            point(timestamp = 1_000, lat = 2.0),
            point(timestamp = 2_000, lat = 3.0),
            point(timestamp = 3_000, lat = 4.0),
        )
        assertTrue(TrackQuality.leadingPointIsJump(pts, seamGaps(50.0, 5.0, 4.0, 4.0)))
    }

    @Test fun `tracks with fewer than three points are never flagged`() {
        assertFalse(TrackQuality.leadingPointIsJump(fivePoints().take(2), seamGaps(50.0)))
        assertFalse(TrackQuality.leadingPointIsJump(emptyList(), seamGaps()))
    }

    // --- Withdrawing jump flags when a track is retyped ---------------------
    //
    // A point's longitude is its position along the path in metres and its index is the second it
    // was recorded at, so the speed of any step is (Δlon / Δt) × 3.6 km/h — including the steps
    // that skip a rejected fix, which is what these cases are mostly about.

    /** Distance = the metres between the two longitudes, whichever way the step runs. */
    private val alongPath = DistanceFn { _, aLon, _, bLon -> abs(bLon - aLon) }

    /** A good fix [meters] along the path, recorded at second [second]. */
    private fun onPath(second: Int, meters: Double) = point(timestamp = second * 1_000L, lon = meters)

    /** …and the same fix as the recorder rejected it. */
    private fun flagged(second: Int, meters: Double, reason: IgnoreReason) =
        onPath(second, meters).copy(ignored = true, ignoreReason = reason.code)

    @Test fun `a jump the corrected ceiling accepts is handed back`() {
        // 72 km/h steps: teleports on foot, an ordinary road pace in a car.
        val pts = listOf(
            onPath(0, 0.0),
            flagged(1, 20.0, IgnoreReason.JUMP),
            flagged(2, 40.0, IgnoreReason.JUMP),
        )
        assertEquals(setOf(1, 2), TrackQuality.jumpRestores(pts, DRIVING, alongPath))
    }

    @Test fun `the ceiling the track already had withdraws nothing`() {
        val pts = listOf(
            onPath(0, 0.0),
            flagged(1, 20.0, IgnoreReason.JUMP),
            flagged(2, 40.0, IgnoreReason.JUMP),
        )
        assertTrue(TrackQuality.jumpRestores(pts, WALKING, alongPath).isEmpty())
    }

    @Test fun `a fix the corrected ceiling still rejects keeps its flag`() {
        // 360 km/h — past even the vehicle ceiling, so no retype speaks for it.
        val pts = listOf(onPath(0, 0.0), flagged(1, 100.0, IgnoreReason.JUMP))
        assertTrue(TrackQuality.jumpRestores(pts, DRIVING, alongPath).isEmpty())
    }

    @Test fun `a fix that stays rejected is no baseline for the one after it`() {
        // The teleport keeps its flag, so the fix behind it is measured from the last *good* one:
        // 5 m over 2 s, which is a walking pace and comes back to the path.
        val pts = listOf(
            onPath(0, 0.0),
            flagged(1, 100.0, IgnoreReason.JUMP),
            flagged(2, 5.0, IgnoreReason.JUMP),
        )
        assertEquals(setOf(2), TrackQuality.jumpRestores(pts, DRIVING, alongPath))
    }

    @Test fun `a restored fix becomes the next one's baseline`() {
        // 15 m in the first second (54 km/h) is fine for a bicycle, so that fix returns — and the
        // 23 m step off it (82.8 km/h) is not, so the next one stays flagged. Measured from the
        // fix before instead, it would read 68.4 km/h and be handed back with it.
        val pts = listOf(
            onPath(0, 0.0),
            flagged(1, 15.0, IgnoreReason.JUMP),
            flagged(2, 38.0, IgnoreReason.JUMP),
        )
        assertEquals(setOf(1), TrackQuality.jumpRestores(pts, ActivityType.CYCLING, alongPath))
    }

    @Test fun `a flag with no good fix before it stands`() {
        // The imported leading stray: its verdict came from the track's own following pace, which
        // no ceiling is entitled to overturn — and there is no step to re-measure anyway.
        val pts = listOf(
            flagged(0, 0.0, IgnoreReason.JUMP),
            onPath(1, 10.0),
            onPath(2, 20.0),
        )
        assertTrue(TrackQuality.jumpRestores(pts, DRIVING, alongPath).isEmpty())
    }

    @Test fun `only jump flags are withdrawn`() {
        // Neither an accuracy radius nor a missing satellite backing depends on the activity, and a
        // legacy ignored point (no reason stored) could be either.
        val pts = listOf(
            onPath(0, 0.0),
            flagged(1, 10.0, IgnoreReason.ACCURACY),
            flagged(2, 20.0, IgnoreReason.NO_GNSS),
            onPath(3, 30.0).copy(ignored = true),
            flagged(4, 40.0, IgnoreReason.JUMP),
        )
        assertEquals(setOf(4), TrackQuality.jumpRestores(pts, DRIVING, alongPath))
    }

    @Test fun `an edge-stay fix carries the baseline like the good fix it is`() {
        // The 25 m step off the arrival fix reads 90 km/h, past the bicycle ceiling. Stretching the
        // gap across the stay instead would read 54 km/h and hand a real teleport back.
        val pts = listOf(
            onPath(0, 0.0),
            flagged(1, 5.0, IgnoreReason.EDGE_STAY),
            flagged(2, 30.0, IgnoreReason.JUMP),
        )
        assertTrue(TrackQuality.jumpRestores(pts, ActivityType.CYCLING, alongPath).isEmpty())
    }

    @Test fun `a teleport over a non-positive time gap is never handed back`() {
        // No time to have travelled it in — infinitely fast under any ceiling.
        val pts = listOf(onPath(0, 0.0), flagged(0, 11.0, IgnoreReason.JUMP))
        assertTrue(TrackQuality.jumpRestores(pts, DRIVING, alongPath).isEmpty())
    }
}
